package com.disaster.allocation.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.QueueRunResult;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceRequest;
import com.disaster.allocation.service.SchedulingService;

/** Atomicity: a failure AFTER all writes but BEFORE commit must leave no trace. */
@SpringBootTest(properties = "dras.failure-injection.enabled=true")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class RollbackIT extends DrasIntegrationBase {

    @Autowired AllocationEngine engine;
    @Autowired SchedulingService scheduling;

    @Test
    void injectedFailureRollsBackInventoryAllocationStatusAndHistory() {
        Organization o = newOrg();
        Resource r = newResource(10, true);
        claim(o, r, 6);
        ResourceRequest q = request(o, r, 4, 3);

        assertThrows(AllocationEngine.InjectedFailure.class, () -> engine.allocate(q.id(), true, "TEST"));

        Resource after = reload(r);
        assertEquals(10, after.availableQuantity());
        assertEquals(0, after.allocatedQuantity());
        assertEquals(0, activeUnits(r));
        assertEquals("PENDING", reqService.get(q.id()).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resource_history WHERE request_id=?", Integer.class, q.id()));
    }

    @Test
    void queueRunLogsTheRollbackInASeparateTransactionAndTheRequestStaysRetryable() {
        Organization o = newOrg();
        Resource r = newResource(10, false);
        claim(o, r, 5);
        ResourceRequest q = request(o, r, 5, 3);

        QueueRunResult run = scheduling.runQueue("FCFS", null, true);

        assertEquals("ROLLED_BACK", run.outcomes().stream().filter(x -> x.requestId() == q.id()).findFirst().orElseThrow().outcome());
        assertEquals(10, reload(r).availableQuantity());
        // The log row was written outside the rolled-back transaction, so it survives.
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM failure_recovery_logs WHERE request_id=?", Integer.class, q.id()));
        // Retrying without injection succeeds.
        assertEquals("ALLOCATED", engine.allocate(q.id(), false, "RETRY").outcome());
    }
}
