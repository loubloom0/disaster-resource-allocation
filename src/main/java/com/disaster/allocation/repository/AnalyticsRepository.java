package com.disaster.allocation.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.disaster.allocation.model.Dto.AllocationTrend;
import com.disaster.allocation.model.Dto.RequestCategoryAnalytics;
import com.disaster.allocation.model.Dto.RequestStatusAnalytics;
import com.disaster.allocation.model.Dto.ResourceCategoryAnalytics;

/** Focused, read-only aggregates for the analytics dashboard. All tenant filters are SQL-side. */
@Repository
public class AnalyticsRepository {
    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ResourceCategoryAnalytics> resourceCategories() {
        return jdbc.query("SELECT category, count(*) AS resource_count, "
                        + "COALESCE(sum(total_quantity),0) AS total_units, "
                        + "COALESCE(sum(available_quantity),0) AS available_units, "
                        + "COALESCE(sum(allocated_quantity),0) AS allocated_units, "
                        + "count(*) FILTER (WHERE available_quantity=0) AS shortage_count "
                        + "FROM resources WHERE active=true GROUP BY category ORDER BY category",
                (rs, row) -> new ResourceCategoryAnalytics(rs.getString("category"), rs.getLong("resource_count"),
                        rs.getLong("total_units"), rs.getLong("available_units"), rs.getLong("allocated_units"),
                        rs.getLong("shortage_count"), percent(rs.getLong("allocated_units"), rs.getLong("total_units"))));
    }

    public List<RequestStatusAnalytics> requestStatuses(Long organizationId, Long userId) {
        Scope scope = requestScope(organizationId, userId);
        return jdbc.query("SELECT q.status, count(*) AS count FROM resource_requests q " + scope.where
                        + " GROUP BY q.status ORDER BY q.status",
                (rs, row) -> new RequestStatusAnalytics(rs.getString("status"), rs.getLong("count")), scope.args);
    }

    public long countCriticalRequests(Long organizationId, Long userId) {
        Scope scope = requestScope(organizationId, userId);
        String where = scope.where.isEmpty() ? "WHERE q.priority=5" : scope.where + " AND q.priority=5";
        Long count = jdbc.queryForObject("SELECT count(*) FROM resource_requests q " + where,
                Long.class, scope.args);
        return count == null ? 0 : count;
    }

    public List<RequestCategoryAnalytics> requestCategories(Long organizationId, Long userId) {
        Scope scope = requestScope(organizationId, userId);
        return jdbc.query("SELECT r.category, COALESCE(sum(q.requested_quantity),0) AS requested_units, "
                        + "COALESCE(sum(q.allocated_quantity),0) AS allocated_units "
                        + "FROM resource_requests q JOIN resources r ON r.id=q.resource_id " + scope.where
                        + " GROUP BY r.category ORDER BY r.category",
                (rs, row) -> new RequestCategoryAnalytics(rs.getString("category"), rs.getLong("requested_units"),
                        rs.getLong("allocated_units")), scope.args);
    }

    /** Historic ledger rows are retained when allocations are released, so both series are real events. */
    public List<AllocationTrend> allocationTrends(LocalDate from, LocalDate untilExclusive,
                                                  Long organizationId, Long userId) {
        Scope scope = requestScope(organizationId, userId);
        String filter = scope.where.isEmpty() ? "" : scope.where.replaceFirst("WHERE", "AND");
        return jdbc.query("SELECT activity_date, sum(allocated_units) AS allocated_units, "
                        + "sum(released_units) AS released_units, sum(allocation_count) AS allocation_count FROM ("
                        + "SELECT a.allocated_at::date AS activity_date, sum(a.quantity) AS allocated_units, "
                        + "0::bigint AS released_units, count(*) AS allocation_count FROM allocations a "
                        + "JOIN resource_requests q ON q.id=a.request_id WHERE a.allocated_at >= ? AND a.allocated_at < ? "
                        + filter + " GROUP BY a.allocated_at::date UNION ALL "
                        + "SELECT ar.released_at::date AS activity_date, 0::bigint AS allocated_units, "
                        + "sum(ar.released_quantity) AS released_units, 0::bigint AS allocation_count "
                        + "FROM allocation_releases ar JOIN allocations a ON a.id=ar.allocation_id "
                        + "JOIN resource_requests q ON q.id=a.request_id "
                        + "WHERE ar.released_at >= ? AND ar.released_at < ? "
                        + filter + " GROUP BY ar.released_at::date) events GROUP BY activity_date ORDER BY activity_date",
                (rs, row) -> new AllocationTrend(rs.getObject("activity_date", Date.class).toLocalDate(),
                        rs.getLong("allocated_units"), rs.getLong("released_units"), rs.getLong("allocation_count")),
                trendArgs(from, untilExclusive, scope.args));
    }

    private static Object[] trendArgs(LocalDate from, LocalDate untilExclusive, Object[] scopeArgs) {
        Object[] args = new Object[4 + scopeArgs.length * 2];
        args[0] = from;
        args[1] = untilExclusive;
        System.arraycopy(scopeArgs, 0, args, 2, scopeArgs.length);
        int second = 2 + scopeArgs.length;
        args[second] = from;
        args[second + 1] = untilExclusive;
        System.arraycopy(scopeArgs, 0, args, second + 2, scopeArgs.length);
        return args;
    }

    private static Scope requestScope(Long organizationId, Long userId) {
        if (organizationId == null) return new Scope("", new Object[0]);
        if (userId == null) return new Scope("WHERE q.organization_id=?", new Object[]{organizationId});
        return new Scope("WHERE q.organization_id=? AND q.created_by_user_id=?", new Object[]{organizationId, userId});
    }

    private static double percent(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round((numerator * 10000.0) / denominator) / 100.0;
    }

    private record Scope(String where, Object[] args) { }
}
