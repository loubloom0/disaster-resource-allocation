package com.disaster.allocation.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.AllocationOutcome;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.QueueRunResult;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceRequest;
import com.disaster.allocation.service.SchedulingService;

/**
 * End-to-end allocation workflow against PostgreSQL.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(
        named = "DB_PASSWORD",
        matches = ".+"
)
class AllocationEngineIT extends DrasIntegrationBase {

    @Autowired
    AllocationEngine engine;

    @Autowired
    SchedulingService scheduling;

    @Test
    void successfulAllocationUpdatesInventoryRequestAllocationAndHistory() {

        Organization o = newOrg();
        Resource r = newResource(10, true);

        claim(o, r, 6);

        ResourceRequest q = request(o, r, 4, 3);

        AllocationOutcome out =
                engine.allocate(q.id(), false, "TEST");

        assertEquals("ALLOCATED", out.outcome());

        Resource after = reload(r);

        assertEquals(6, after.availableQuantity());
        assertEquals(4, after.allocatedQuantity());

        assertEquals(
                "ALLOCATED",
                reqService.get(q.id()).status()
        );

        assertEquals(4, activeUnits(r));

        Integer hist = jdbc.queryForObject(
                "SELECT count(*) FROM resource_history "
                        + "WHERE request_id=? AND action='ALLOCATE'",
                Integer.class,
                q.id()
        );

        assertEquals(1, hist);
    }

    @Test
    void insufficientInventoryMakesRequestWaitAndChangesNothing() {

        Organization o = newOrg();
        Resource r = newResource(3, false);

        claim(o, r, 5);

        ResourceRequest q = request(o, r, 5, 3);

        AllocationOutcome out =
                engine.allocate(q.id(), false, "TEST");

        assertEquals("WAITING", out.outcome());

        assertEquals(3, reload(r).availableQuantity());
        assertEquals(0, activeUnits(r));

        assertEquals(
                "WAITING",
                reqService.get(q.id()).status()
        );
    }

    @Test
    void requestBeyondRemainingClaimIsRejectedByBankers() {

        Organization o = newOrg();
        Resource r = newResource(20, true);

        claim(o, r, 8);

        assertEquals(
                "ALLOCATED",
                engine.allocate(
                        request(o, r, 5, 3).id(),
                        false,
                        "TEST"
                ).outcome()
        );

        ResourceRequest second = request(o, r, 5, 3);

        AllocationOutcome out =
                engine.allocate(second.id(), false, "TEST");

        assertEquals("REJECTED", out.outcome());

        assertTrue(
                out.bankersNote().contains("EXCEEDS_MAX_CLAIM")
        );

        assertEquals(15, reload(r).availableQuantity());
    }

    @Test
    void unsafeRequestWaitsEvenThoughUnitsAreFree() {

        Organization a = newOrg();
        Organization b = newOrg();

        Resource r = newResource(10, true);

        claim(a, r, 10);
        claim(b, r, 10);

        assertEquals(
                "ALLOCATED",
                engine.allocate(
                        request(a, r, 5, 3).id(),
                        false,
                        "TEST"
                ).outcome()
        );

        AllocationOutcome out =
                engine.allocate(
                        request(b, r, 5, 3).id(),
                        false,
                        "TEST"
                );

        assertEquals("WAITING", out.outcome());

        assertTrue(
                out.bankersNote().contains("UNSAFE"),
                out.bankersNote()
        );

        assertEquals(
                5,
                reload(r).availableQuantity(),
                "Unsafe request must not touch inventory"
        );
    }

    @Test
    void releaseReturnsUnitsAndCompletesTheRequest() {

        Organization o = newOrg();
        Resource r = newResource(10, true);

        claim(o, r, 10);

        ResourceRequest q = request(o, r, 6, 3);

        long allocationId =
                engine.allocate(q.id(), false, "TEST")
                        .allocationId();

        engine.release(allocationId);

        assertEquals(10, reload(r).availableQuantity());

        assertEquals(
                "COMPLETED",
                reqService.get(q.id()).status()
        );

        assertEquals(0, activeUnits(r));
    }

    /**
     * Regression test:
     * Consumable resources must never be returned to inventory.
     */
    @Test
    void releasingConsumableAllocationIsRejectedWithoutChangingInventory() {

        Organization o = newOrg();

        // false = consumable resource
        Resource r = newResource(10, false);

        claim(o, r, 10);

        ResourceRequest q = request(o, r, 6, 3);

        // First allocate six consumable units.
        AllocationOutcome out =
                engine.allocate(q.id(), false, "TEST");

        assertEquals("ALLOCATED", out.outcome());
        assertNotNull(out.allocationId());

        long allocationId = out.allocationId();

        // Attempting to release must throw HTTP 409 ApiException.
        ApiException error = assertThrows(
                ApiException.class,
                () -> engine.release(allocationId)
        );

        assertTrue(
                error.getMessage().contains("consumable"),
                error.getMessage()
        );

        // Inventory must remain unchanged.
        Resource after = reload(r);

        assertEquals(4, after.availableQuantity());
        assertEquals(6, after.allocatedQuantity());

        // Allocation must remain ACTIVE.
        assertEquals(6, activeUnits(r));

        String allocationStatus = jdbc.queryForObject(
                "SELECT status FROM allocations WHERE id = ?",
                String.class,
                allocationId
        );

        assertEquals("ACTIVE", allocationStatus);

        // Request must remain ALLOCATED.
        assertEquals(
                "ALLOCATED",
                reqService.get(q.id()).status()
        );

        // No RELEASE history must be recorded.
        Integer releaseHistory = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_history "
                        + "WHERE request_id = ? AND action = 'RELEASE'",
                Integer.class,
                q.id()
        );

        assertEquals(0, releaseHistory);
    }

    @Test
    void priorityQueueRunServesHighPriorityRequestFirst() {

        Organization lo = newOrg();
        Organization hi = newOrg();

        Resource r = newResource(10, false);

        claim(lo, r, 8);
        claim(hi, r, 8);

        ResourceRequest low = request(lo, r, 8, 1);
        ResourceRequest high = request(hi, r, 8, 5);

        QueueRunResult run =
                scheduling.runQueue("PRIORITY", null, false);

        assertTrue(
                run.processingOrder().indexOf(high.id())
                        < run.processingOrder().indexOf(low.id())
        );

        assertEquals(
                "ALLOCATED",
                reqService.get(high.id()).status()
        );

        assertEquals(
                "WAITING",
                reqService.get(low.id()).status()
        );

        assertNotNull(run.algorithm());
    }

}
