package com.disaster.allocation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.disaster.allocation.model.Dto.Allocation;

@Repository
public class AllocationRepository {

    private final JdbcTemplate jdbc;

    private static final String SELECT =
            "SELECT a.*, q.organization_id, o.name AS org_name, r.name AS res_name FROM allocations a "
                    + "JOIN resource_requests q ON q.id = a.request_id "
                    + "JOIN organizations o ON o.id = q.organization_id "
                    + "JOIN resources r ON r.id = a.resource_id ";

    private static final RowMapper<Allocation> MAP = (rs, i) -> new Allocation(
            rs.getLong("id"), rs.getLong("request_id"), rs.getLong("organization_id"), rs.getString("org_name"),
            rs.getLong("resource_id"), rs.getString("res_name"), rs.getInt("quantity"), rs.getString("status"),
            rs.getObject("allocated_at", java.time.OffsetDateTime.class),
            rs.getObject("released_at", java.time.OffsetDateTime.class));

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long requestId, long resourceId, int qty) {
        Long id = jdbc.queryForObject(
                "INSERT INTO allocations (request_id, resource_id, quantity) VALUES (?,?,?) RETURNING id",
                Long.class, requestId, resourceId, qty);
        return id;
    }

    public List<Allocation> findAll(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query(SELECT + "ORDER BY a.id DESC", MAP);
        }
        return jdbc.query(SELECT + "WHERE a.status = ? ORDER BY a.id DESC", MAP, status);
    }

    public Optional<Allocation> findById(long id) {
        return jdbc.query(SELECT + "WHERE a.id = ?", MAP, id).stream().findFirst();
    }

    public Optional<Allocation> findByIdForUpdate(long id) {
        return jdbc.query(SELECT + "WHERE a.id = ? FOR UPDATE OF a", MAP, id).stream().findFirst();
    }

    public void markReleased(long id) {
        jdbc.update("UPDATE allocations SET status='RELEASED', released_at = now() WHERE id = ?", id);
    }

    public int activeCountForRequest(long requestId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM allocations WHERE request_id = ? AND status = 'ACTIVE'", Integer.class,
                requestId);
        return n == null ? 0 : n;
    }

    /**
     * Banker's ALLOCATION matrix source: units currently held (ACTIVE allocations) per
     * (organization, resource). Rows: {organizationId, resourceId, quantity}.
     */
    public List<long[]> activeHoldings() {
        return jdbc.query(
                "SELECT q.organization_id, a.resource_id, SUM(a.quantity) AS qty FROM allocations a "
                        + "JOIN resource_requests q ON q.id = a.request_id WHERE a.status = 'ACTIVE' "
                        + "GROUP BY q.organization_id, a.resource_id",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
    }

    public long totalActiveUnits(long resourceId) {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM allocations WHERE resource_id = ? AND status='ACTIVE'",
                Long.class, resourceId);
        return n == null ? 0 : n;
    }
}
