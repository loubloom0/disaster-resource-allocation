package com.disaster.allocation.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceRequest;

/**
 * The required scenario: 10 units available, two organizations request 7 each AT THE SAME TIME.
 * The database row lock (SELECT ... FOR UPDATE) must make one wait for the other, so at most 7 are ever
 * allocated. No Java synchronisation is used anywhere in the engine.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ConcurrencyIT extends DrasIntegrationBase {

    @Autowired AllocationEngine engine;

    @RepeatedTest(5)
    void twoOrganizationsRequestingSevenOfTenNeverOverAllocateReusable() throws Exception {
        runRace(true, 2, 7, 10);
    }

    @RepeatedTest(5)
    void twoOrganizationsRequestingSevenOfTenNeverOverAllocateConsumable() throws Exception {
        runRace(false, 2, 7, 10);
    }

    @Test
    void manyThreadsNeverExceedTotalStock() throws Exception {
        runRace(false, 8, 3, 10);    // 8 organizations x 3 units against 10 units: exactly 3 can win (8 < Hikari pool of 10)
    }

    private void runRace(boolean reusable, int orgs, int each, int stock) throws Exception {
        Resource r = newResource(stock, reusable);
        List<ResourceRequest> reqs = new ArrayList<>();
        for (int i = 0; i < orgs; i++) {
            Organization o = newOrg();
            claim(o, r, each);
            reqs.add(request(o, r, each, 3));
        }
        ExecutorService pool = Executors.newFixedThreadPool(orgs);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (ResourceRequest q : reqs) {
            futures.add(pool.submit(() -> {
                go.await();                       // release all threads at the same instant
                return engine.allocate(q.id(), false, "RACE").outcome();
            }));
        }
        go.countDown();
        int allocated = 0;
        for (Future<String> f : futures) {
            if ("ALLOCATED".equals(f.get(30, TimeUnit.SECONDS))) {
                allocated++;
            }
        }
        pool.shutdown();

        Resource after = reload(r);
        assertEquals(stock / each, allocated, "only floor(stock/each) requests can be satisfied");
        assertEquals(allocated * each, after.allocatedQuantity());
        assertEquals(stock - allocated * each, after.availableQuantity());
        assertEquals(allocated * each, activeUnits(r), "allocation rows must match the inventory");
        assertTrue(after.allocatedQuantity() <= stock, "never more than the total stock");
        assertTrue(after.availableQuantity() >= 0);
    }
}
