package com.disaster.allocation.allocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.disaster.allocation.model.Dto.Claim;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.safety.BankersAlgorithm;

/**
 * Builds the Banker's matrices from REAL database data (not from inventory counts alone):
 *   processes  = active organizations that declared a claim on at least one reusable resource
 *   resources  = active REUSABLE resources (units come back, so the classic model applies)
 *   Available  = resources.available_quantity           (read under row lock during allocation)
 *   Max        = organization_claims.max_claim
 *   Allocation = SUM(remaining quantity) of ACTIVE allocations per organization and resource
 *   Need       = Max - Allocation   (computed by BankersAlgorithm)
 * If an organization already holds more than its declared claim (claim lowered later), Max is raised
 * to the holding so the algorithm's input stays valid; the snapshot records a warning.
 */
public final class BankersSnapshot {

    public final List<Long> orgIds = new ArrayList<>();
    public final List<String> orgNames = new ArrayList<>();
    public final List<Long> resourceIds = new ArrayList<>();
    public final List<String> resourceNames = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public int[] available;
    public int[][] max;
    public int[][] allocation;

    public static BankersSnapshot build(List<Resource> resources, List<Organization> orgs, List<Claim> claims,
                                        List<long[]> holdings, Long mustIncludeOrg) {
        BankersSnapshot s = new BankersSnapshot();
        for (Resource r : resources) {
            if (r.reusable() && r.active()) {
                s.resourceIds.add(r.id());
                s.resourceNames.add(r.name());
            }
        }
        Map<Long, Integer> col = new HashMap<>();
        for (int j = 0; j < s.resourceIds.size(); j++) {
            col.put(s.resourceIds.get(j), j);
        }
        Map<Long, Resource> byId = new HashMap<>();
        resources.forEach(r -> byId.put(r.id(), r));

        for (Organization o : orgs) {
            boolean hasClaim = claims.stream().anyMatch(c -> c.organizationId().equals(o.id())
                    && col.containsKey(c.resourceId()));
            boolean required = mustIncludeOrg != null && mustIncludeOrg.equals(o.id());
            if ((o.active() && hasClaim) || required) {
                s.orgIds.add(o.id());
                s.orgNames.add(o.name());
            }
        }
        int n = s.orgIds.size();
        int m = s.resourceIds.size();
        s.available = new int[m];
        s.max = new int[n][m];
        s.allocation = new int[n][m];
        for (int j = 0; j < m; j++) {
            s.available[j] = byId.get(s.resourceIds.get(j)).availableQuantity();
        }
        Map<Long, Integer> row = new HashMap<>();
        for (int i = 0; i < n; i++) {
            row.put(s.orgIds.get(i), i);
        }
        for (Claim c : claims) {
            Integer i = row.get(c.organizationId());
            Integer j = col.get(c.resourceId());
            if (i != null && j != null) {
                s.max[i][j] = c.maxClaim();
            }
        }
        for (long[] h : holdings) {
            Integer i = row.get((Long) h[0]);
            Integer j = col.get((Long) h[1]);
            if (i != null && j != null) {
                s.allocation[i][j] = (int) h[2];
                if (s.allocation[i][j] > s.max[i][j]) {
                    s.warnings.add(s.orgNames.get(i) + " holds more " + s.resourceNames.get(j)
                            + " than its declared claim; claim raised to " + s.allocation[i][j]);
                    s.max[i][j] = s.allocation[i][j];
                }
            }
        }
        return s;
    }

    public BankersAlgorithm toAlgorithm() {
        return new BankersAlgorithm(available, max, allocation);
    }

    public int orgIndex(long orgId) {
        return orgIds.indexOf(orgId);
    }

    public int resourceIndex(long resourceId) {
        return resourceIds.indexOf(resourceId);
    }
}
