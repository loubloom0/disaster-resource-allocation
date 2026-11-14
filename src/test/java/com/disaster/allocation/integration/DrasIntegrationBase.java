package com.disaster.allocation.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.OrganizationInput;
import com.disaster.allocation.model.Dto.RequestInput;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceInput;
import com.disaster.allocation.model.Dto.ResourceRequest;
import com.disaster.allocation.service.OrganizationService;
import com.disaster.allocation.service.RequestService;
import com.disaster.allocation.service.ResourceService;
import com.disaster.allocation.model.Dto.ClaimInput;

/**
 * Shared helpers. Integration tests run against the REAL PostgreSQL database named by the DB_* environment
 * variables. Each test creates its own uniquely named organizations/resources and removes only those rows
 * afterwards, so the seed data is untouched.
 */
abstract class DrasIntegrationBase {

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected OrganizationService orgService;
    @Autowired protected ResourceService resService;
    @Autowired protected RequestService reqService;

    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> resIds = new ArrayList<>();

    protected Organization newOrg() {
        Organization o = orgService.create(new OrganizationInput("T-org-" + UUID.randomUUID(), "HOSPITAL", "Test", null, true));
        orgIds.add(o.id());
        return o;
    }

    protected Resource newResource(int total, boolean reusable) {
        Resource r = resService.create(new ResourceInput("T-res-" + UUID.randomUUID(),
                reusable ? "AMBULANCE" : "FOOD", total, reusable, "Test", true));
        resIds.add(r.id());
        return r;
    }

    protected void claim(Organization o, Resource r, int max) {
        orgService.setClaim(o.id(), new ClaimInput(r.id(), max));
    }

    protected ResourceRequest request(Organization o, Resource r, int qty, int priority) {
        return reqService.submit(new RequestInput(o.id(), r.id(), qty, priority, 1));
    }

    protected Resource reload(Resource r) {
        return resService.get(r.id());
    }

    protected long activeUnits(Resource r) {
        Long n = jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM allocations WHERE resource_id=? AND status='ACTIVE'",
                Long.class, r.id());
        return n;
    }

    @AfterEach
    void cleanup() {
        for (long rid : resIds) {
            jdbc.update("DELETE FROM failure_recovery_logs WHERE request_id IN (SELECT id FROM resource_requests WHERE resource_id=?)", rid);
            jdbc.update("DELETE FROM allocations WHERE resource_id=?", rid);
            jdbc.update("DELETE FROM resource_history WHERE resource_id=?", rid);
            jdbc.update("DELETE FROM resource_requests WHERE resource_id=?", rid);
            jdbc.update("DELETE FROM organization_claims WHERE resource_id=?", rid);
            jdbc.update("DELETE FROM resources WHERE id=?", rid);
        }
        for (long oid : orgIds) {
            jdbc.update("DELETE FROM failure_recovery_logs WHERE request_id IN (SELECT id FROM resource_requests WHERE organization_id=?)", oid);
            jdbc.update("DELETE FROM allocations WHERE request_id IN (SELECT id FROM resource_requests WHERE organization_id=?)", oid);
            jdbc.update("DELETE FROM resource_requests WHERE organization_id=?", oid);
            jdbc.update("DELETE FROM organization_claims WHERE organization_id=?", oid);
            jdbc.update("DELETE FROM organizations WHERE id=?", oid);
        }
        resIds.clear();
        orgIds.clear();
    }
}
