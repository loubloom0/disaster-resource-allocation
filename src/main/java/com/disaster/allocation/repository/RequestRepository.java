package com.disaster.allocation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.disaster.allocation.model.Dto.ResourceRequest;

@Repository
public class RequestRepository {

    private final JdbcTemplate jdbc;

    private static final String SELECT =
            "SELECT q.*, o.name AS org_name, r.name AS res_name FROM resource_requests q "
                    + "JOIN organizations o ON o.id = q.organization_id "
                    + "JOIN resources r ON r.id = q.resource_id ";

    private static final RowMapper<ResourceRequest> MAP = (rs, i) -> new ResourceRequest(
            rs.getLong("id"), rs.getLong("organization_id"), rs.getString("org_name"), rs.getLong("resource_id"),
            rs.getString("res_name"), rs.getInt("requested_quantity"), rs.getInt("allocated_quantity"),
            rs.getInt("priority"), rs.getInt("max_claim"), rs.getInt("work_units"), rs.getLong("arrival_seq"),
            rs.getObject("arrival_time", java.time.OffsetDateTime.class), rs.getString("status"),
            rs.getString("status_reason"), rs.getObject("completed_at", java.time.OffsetDateTime.class));

    public RequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ResourceRequest insert(long orgId, long resId, int qty, int priority, int maxClaim, int workUnits) {
        Long id = jdbc.queryForObject(
                "INSERT INTO resource_requests (organization_id, resource_id, requested_quantity, priority, "
                        + "max_claim, work_units) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, orgId, resId, qty, priority, maxClaim, workUnits);
        return findById(id).orElseThrow();
    }

    public List<ResourceRequest> findAll(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query(SELECT + "ORDER BY q.arrival_seq DESC", MAP);
        }
        return jdbc.query(SELECT + "WHERE q.status = ? ORDER BY q.arrival_seq DESC", MAP, status);
    }

    public Optional<ResourceRequest> findById(long id) {
        return jdbc.query(SELECT + "WHERE q.id = ?", MAP, id).stream().findFirst();
    }

    /** Row lock on the request itself so the same request cannot be allocated twice concurrently. */
    public Optional<ResourceRequest> findByIdForUpdate(long id) {
        // FOR UPDATE OF q: lock only the request row, not the joined organization/resource rows.
        return jdbc.query(SELECT + "WHERE q.id = ? FOR UPDATE OF q", MAP, id).stream().findFirst();
    }

    /** The scheduling queue: requests still waiting to be served (served by idx_requests_queue). */
    public List<ResourceRequest> queue() {
        return jdbc.query(SELECT + "WHERE q.status IN ('PENDING','WAITING') ORDER BY q.arrival_seq", MAP);
    }

    public void setStatus(long id, String status, String reason) {
        jdbc.update("UPDATE resource_requests SET status = ?, status_reason = ?, "
                + "completed_at = CASE WHEN ? IN ('COMPLETED','REJECTED') THEN now() ELSE completed_at END, "
                + "processing_started_at = CASE WHEN ? = 'PROCESSING' THEN now() ELSE processing_started_at END "
                + "WHERE id = ?", status, reason, status, status, id);
    }

    public void markAllocated(long id, int qty, String reason) {
        jdbc.update("UPDATE resource_requests SET status='ALLOCATED', allocated_quantity = ?, status_reason = ?, "
                + "processing_started_at = COALESCE(processing_started_at, now()) WHERE id = ?", qty, reason, id);
    }
}
