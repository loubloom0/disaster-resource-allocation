package com.disaster.allocation.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.disaster.allocation.repository.ResourceRepository;
import com.disaster.allocation.repository.SimulationRepository;

/** Every number on the dashboard is a live SQL aggregate; nothing is cached or hardcoded. */
@Service
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final ResourceRepository resources;
    private final SimulationRepository simulations;

    public DashboardService(JdbcTemplate jdbc, ResourceRepository resources, SimulationRepository simulations) {
        this.jdbc = jdbc;
        this.resources = resources;
        this.simulations = simulations;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalUnits", num("SELECT COALESCE(SUM(total_quantity),0) FROM resources WHERE active"));
        m.put("availableUnits", num("SELECT COALESCE(SUM(available_quantity),0) FROM resources WHERE active"));
        m.put("allocatedUnits", num("SELECT COALESCE(SUM(allocated_quantity),0) FROM resources WHERE active"));
        m.put("resourceTypes", num("SELECT count(*) FROM resources"));
        m.put("organizations", num("SELECT count(*) FROM organizations"));
        m.put("activeOrganizations", num("SELECT count(*) FROM organizations WHERE active"));
        m.put("pendingRequests", num("SELECT count(*) FROM resource_requests WHERE status IN ('PENDING','WAITING')"));
        m.put("completedAllocations", num("SELECT count(*) FROM allocations"));
        m.put("activeAllocations", num("SELECT count(*) FROM allocations WHERE status='ACTIVE'"));
        m.put("requestsByStatus", jdbc.queryForList(
                "SELECT status, count(*) AS count FROM resource_requests GROUP BY status ORDER BY status"));
        m.put("resources", resources.findAll());
        m.put("recentActivity", resources.history(null, 15));
        return m;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", simulations.recentRuns(25));
        m.put("failureLog", simulations.recentFailures(20));
        m.put("requestOutcomes", jdbc.queryForList(
                "SELECT status, count(*) AS count FROM resource_requests GROUP BY status ORDER BY status"));
        List<Map<String, Object>> latency = jdbc.queryForList(
                "SELECT priority, count(*) AS requests, "
                        + "round(avg(EXTRACT(EPOCH FROM (processing_started_at - arrival_time)))::numeric, 3) AS avg_seconds_to_start "
                        + "FROM resource_requests WHERE processing_started_at IS NOT NULL GROUP BY priority ORDER BY priority DESC");
        m.put("startLatencyByPriority", latency);
        return m;
    }

    private long num(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
