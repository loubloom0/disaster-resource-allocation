package com.disaster.allocation.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.disaster.allocation.service.DashboardService;

/** System health, dashboard statistics and simulation metrics (all read live from PostgreSQL). */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final DashboardService dashboard;
    private final boolean failureInjection;

    public HealthController(JdbcTemplate jdbc, DashboardService dashboard,
                            @Value("${dras.failure-injection.enabled:false}") boolean failureInjection) {
        this.jdbc = jdbc;
        this.dashboard = dashboard;
        this.failureInjection = failureInjection;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("organizations", jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class));
        m.put("resources", jdbc.queryForObject("SELECT count(*) FROM resources", Integer.class));
        return m;
    }

    @GetMapping("/api/system/status")
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
                        + "COALESCE((SELECT SUM(a.quantity) FROM allocations a WHERE a.resource_id=r.id AND a.status='ACTIVE'),0)",
                Integer.class));
        m.put("failureInjectionEnabled", failureInjection);
        m.put("status", "UP");
        return m;
    }

    @GetMapping("/api/dashboard/stats")
    public Map<String, Object> stats() { return dashboard.stats(); }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() { return dashboard.metrics(); }
}
