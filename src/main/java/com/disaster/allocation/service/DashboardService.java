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

    /** Tenant dashboard uses the same response fields while scoping request data to the authenticated organization. */
    public Map<String, Object> organizationStats(long organizationId, Long userId, boolean manager) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizations", 1L);
        m.put("activeOrganizations", num("SELECT count(*) FROM organizations WHERE id=? AND active", organizationId));
        String owner = manager ? "" : " AND q.created_by_user_id=?";
        Object[] requestArgs = manager ? new Object[]{organizationId} : new Object[]{organizationId, userId};
        String scope = " FROM resource_requests q WHERE q.organization_id=?" + owner;
        m.put("pendingRequests", num("SELECT count(*)" + scope + " AND q.status IN ('PENDING','WAITING')", requestArgs));
        m.put("completedAllocations", num("SELECT count(*) FROM allocations a JOIN resource_requests q ON q.id=a.request_id WHERE q.organization_id=?" + owner,
                requestArgs));
        m.put("activeAllocations", num("SELECT count(*) FROM allocations a JOIN resource_requests q ON q.id=a.request_id WHERE q.organization_id=?" + owner + " AND a.status='ACTIVE'",
                requestArgs));
        m.put("requestsByStatus", jdbc.queryForList("SELECT q.status,count(*) AS count" + scope + " GROUP BY q.status ORDER BY q.status", requestArgs));
        m.put("resources", jdbc.queryForList("SELECT id,name,category,available_quantity AS \"availableQuantity\",reusable "
                + "FROM resources WHERE active=true ORDER BY name,id"));
        String eventScope = "q.organization_id=?" + owner;
        m.put("recentActivity", jdbc.queryForList("SELECT h.id,h.resource_id AS \"resourceId\",r.name AS \"resourceName\",h.request_id AS \"requestId\","
                + "h.action,h.quantity_change AS \"quantityChange\",h.available_before AS \"availableBefore\",h.available_after AS \"availableAfter\","
                + "h.note,h.created_at AS \"createdAt\" FROM resource_history h JOIN resources r ON r.id=h.resource_id "
                + "JOIN resource_requests q ON q.id=h.request_id WHERE " + eventScope + " ORDER BY h.created_at DESC LIMIT 15", requestArgs));
        return m;
    }

    public Map<String, Object> organizationMetrics(long organizationId, Long userId, boolean manager) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        String owner = manager ? "" : " AND created_by_user_id=?";
        Object[] args = manager ? new Object[]{organizationId} : new Object[]{organizationId, userId};
        metrics.put("runs", List.of());
        metrics.put("failureLog", List.of());
        metrics.put("requestOutcomes", jdbc.queryForList("SELECT status,count(*) AS count FROM resource_requests "
                + "WHERE organization_id=?" + owner + " GROUP BY status ORDER BY status", args));
        metrics.put("startLatencyByPriority", jdbc.queryForList("SELECT priority,count(*) AS requests,"
                + "round(avg(EXTRACT(EPOCH FROM (processing_started_at-arrival_time)))::numeric,3) AS avg_seconds_to_start "
                + "FROM resource_requests WHERE organization_id=?" + owner + " AND processing_started_at IS NOT NULL "
                + "GROUP BY priority ORDER BY priority DESC", args));
        metrics.put("scope", "ORGANIZATION");
        return metrics;
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

    private long num(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
