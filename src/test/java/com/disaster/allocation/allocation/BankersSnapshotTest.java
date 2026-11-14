package com.disaster.allocation.allocation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.disaster.allocation.model.Dto.Claim;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.safety.RequestDecision.Outcome;

/** Pure-Java tests (no database): the matrices must be built from claims + holdings + inventory. */
class BankersSnapshotTest {

    private static Resource res(long id, String name, boolean reusable, boolean active, int total, int available) {
        return new Resource(id, name, "AMBULANCE", total, available, total - available, reusable, "x", active, null);
    }

    private static Organization org(long id, boolean active) {
        return new Organization(id, "Org" + id, "HOSPITAL", "x", null, active, null);
    }

    @Test
    void buildsAvailableMaxAllocationAndNeedFromRealData() {
        List<Resource> resources = List.of(res(1, "Ambulances", true, true, 10, 6), res(2, "Food", false, true, 100, 100));
        List<Organization> orgs = List.of(org(10, true), org(11, true), org(12, false));
        List<Claim> claims = List.of(new Claim(10L, 1L, 8), new Claim(11L, 1L, 5), new Claim(12L, 1L, 9), new Claim(10L, 2L, 50));
        List<long[]> holdings = List.<long[]>of(new long[] {10, 1, 4});

        BankersSnapshot s = BankersSnapshot.build(resources, orgs, claims, holdings, null);

        assertEquals(List.of(1L), s.resourceIds, "consumable resources are not part of the Banker's model");
        assertEquals(List.of(10L, 11L), s.orgIds, "inactive organizations are excluded");
        assertArrayEquals(new int[] {6}, s.available);
        assertArrayEquals(new int[] {8}, s.max[0]);
        assertArrayEquals(new int[] {4}, s.allocation[0]);
        assertArrayEquals(new int[] {4}, s.toAlgorithm().need()[0]);   // 8 - 4
        assertArrayEquals(new int[] {5}, s.toAlgorithm().need()[1]);   // 5 - 0
    }

    @Test
    void requestingOrganizationIsAlwaysIncluded() {
        BankersSnapshot s = BankersSnapshot.build(List.of(res(1, "A", true, true, 5, 5)), List.of(org(10, true)),
                List.of(), List.of(), 10L);
        assertEquals(List.of(10L), s.orgIds);
    }

    @Test
    void claimIsRaisedWhenOrganizationAlreadyHoldsMore() {
        BankersSnapshot s = BankersSnapshot.build(List.of(res(1, "A", true, true, 10, 2)), List.of(org(10, true)),
                List.of(new Claim(10L, 1L, 3)), List.<long[]>of(new long[] {10, 1, 8}), null);
        assertEquals(8, s.max[0][0]);
        assertFalse(s.warnings.isEmpty());
        s.toAlgorithm(); // must not throw
    }

    @Test
    void twoOrganizationsEachClaimingTheWholeStockProduceTheClassicUnsafeCase() {
        // 10 units; both organizations may need all 10. A already holds 5, B asks for the other 5.
        List<Resource> resources = List.of(res(1, "Ambulances", true, true, 10, 5));
        List<Organization> orgs = List.of(org(10, true), org(11, true));
        List<Claim> claims = List.of(new Claim(10L, 1L, 10), new Claim(11L, 1L, 10));
        BankersSnapshot s = BankersSnapshot.build(resources, orgs, claims, List.<long[]>of(new long[] {10, 1, 5}), 11L);

        var decision = s.toAlgorithm().evaluateRequest(s.orgIndex(11), new int[] {5});

        assertEquals(Outcome.UNSAFE, decision.outcome(), "free units exist but granting them risks deadlock");
        assertTrue(s.toAlgorithm().checkSafety().safe(), "the current state itself is still safe");
    }

    @Test
    void sevenAndSevenAgainstTenIsSafeToGrantOnlyOnce() {
        List<Resource> resources = List.of(res(1, "Ambulances", true, true, 10, 10));
        List<Organization> orgs = List.of(org(10, true), org(11, true));
        List<Claim> claims = List.of(new Claim(10L, 1L, 7), new Claim(11L, 1L, 7));
        BankersSnapshot first = BankersSnapshot.build(resources, orgs, claims, List.of(), 10L);
        assertTrue(first.toAlgorithm().evaluateRequest(0, new int[] {7}).granted());

        // After A holds 7 only 3 remain, so B's 7 cannot be granted.
        List<Resource> after = List.of(res(1, "Ambulances", true, true, 10, 3));
        BankersSnapshot second = BankersSnapshot.build(after, orgs, claims, List.<long[]>of(new long[] {10, 1, 7}), 11L);
        assertEquals(Outcome.INSUFFICIENT_AVAILABLE, second.toAlgorithm().evaluateRequest(1, new int[] {7}).outcome());
    }
}
