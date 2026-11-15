package com.disaster.allocation.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import com.disaster.allocation.service.DashboardService;
import com.disaster.allocation.security.AccessControl;

/** System health, dashboard statistics and simulation metrics (all read live from PostgreSQL). */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final DashboardService dashboard;
    private final boolean failureInjection;
    private final AccessControl access;

    public HealthController(JdbcTemplate jdbc, DashboardService dashboard,
                            @Value("${dras.failure-injection.enabled:false}") boolean failureInjection,
                            AccessControl access) {
        this.jdbc = jdbc;
        this.dashboard = dashboard;
        this.failureInjection = failureInjection;
        this.access = access;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        if (access.isGlobalOperator()) {
            m.put("organizations", jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class));
            m.put("resources", jdbc.queryForObject("SELECT count(*) FROM resources", Integer.class));
        }
        return m;
    }

    @GetMapping("/api/system/status")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','DISPATCHER')")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        m.put("database", jdbc.queryForObject("SELECT version()", String.class));
        m.put("dbLatencyMs", (System.nanoTime() - t0) / 1_000_000.0);
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public' "
                        + "AND table_type='BASE TABLE' AND table_name <> 'schema_migrations' ORDER BY table_name",
                String.class);
        m.put("tables", tables);
        m.put("indexes", jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname LIKE 'idx_%' ORDER BY 1",
                String.class));
        // The database's own invariant: total = available + allocated. Count violations (must be 0).
        m.put("inventoryInvariantViolations", jdbc.queryForObject(
                "SELECT count(*) FROM resources WHERE total_quantity <> available_quantity + allocated_quantity",
                Integer.class));
        // Cross-check: allocated_quantity must equal the sum of ACTIVE allocation rows.
        m.put("allocationLedgerMismatches", jdbc.queryForObject(
                "SELECT count(*) FROM resources r WHERE r.allocated_quantity <> "
                        + "COALESCE((SELECT SUM(a.quantity - a.released_quantity) FROM allocations a "
                        + "WHERE a.resource_id=r.id AND a.status='ACTIVE'),0)",
                Integer.class));
        m.put("failureInjectionEnabled", failureInjection);
        m.put("status", "UP");
        return m;
    }

    @GetMapping("/api/dashboard/stats")
    public Map<String, Object> stats() {
        if (access.isGlobalOperator()) return dashboard.stats();
        Long org = access.organizationId();
        if (org == null) throw com.disaster.allocation.exception.ApiException.forbidden("Organization membership is required");
        return dashboard.organizationStats(org, access.userId(), access.hasRole("ORGANIZATION_MANAGER"));
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        if (access.isGlobalOperator()) return dashboard.metrics();
        if (!access.isOrganizationManager()) throw com.disaster.allocation.exception.ApiException.forbidden("Organization manager role required");
        Long organizationId = access.organizationId();
        if (organizationId == null) throw com.disaster.allocation.exception.ApiException.forbidden("Organization membership is required");
        return dashboard.organizationMetrics(organizationId, access.userId(), true);
    }
}
