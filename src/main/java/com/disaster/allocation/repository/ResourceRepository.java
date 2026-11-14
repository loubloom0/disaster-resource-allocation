package com.disaster.allocation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.disaster.allocation.model.Dto.History;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceInput;

@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Resource> MAP = (rs, i) -> new Resource(
            rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getInt("total_quantity"),
            rs.getInt("available_quantity"), rs.getInt("allocated_quantity"), rs.getBoolean("reusable"),
            rs.getString("location"), rs.getBoolean("active"),
            rs.getObject("last_updated", java.time.OffsetDateTime.class));

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Resource> findAll() {
        return jdbc.query("SELECT * FROM resources ORDER BY id", MAP);
    }

    public Optional<Resource> findById(long id) {
        return jdbc.query("SELECT * FROM resources WHERE id = ?", MAP, id).stream().findFirst();
    }

    /**
     * DBMS: pessimistic row lock. Any other transaction that runs SELECT ... FOR UPDATE on the
     * same row blocks here until this transaction commits or rolls back. MUST be called inside
     * a @Transactional method, otherwise the lock is released immediately.
     */
    public Optional<Resource> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM resources WHERE id = ? FOR UPDATE", MAP, id).stream().findFirst();
    }

    /**
     * Locks EVERY reusable resource row in ascending id order. Banker's safety looks at all reusable
     * resource types at once, so a concurrent allocation of a different reusable type could invalidate
     * the snapshot. A fixed lock order (ORDER BY id) means two transactions can never wait on each other
     * in a cycle, i.e. the locking itself cannot deadlock.
     */
    public List<Resource> findAllReusableForUpdate() {
        return jdbc.query("SELECT * FROM resources WHERE reusable = TRUE ORDER BY id FOR UPDATE", MAP);
    }

    public Resource insert(ResourceInput in) {
        int total = in.totalQuantity();
        return jdbc.queryForObject(
                "INSERT INTO resources (name, category, total_quantity, available_quantity, allocated_quantity, "
                        + "reusable, location, active) VALUES (?,?,?,?,0,?,?,?) RETURNING *",
                MAP, in.name(), in.category(), total, total,
                in.reusable() == null || in.reusable(), in.location(), in.active() == null || in.active());
    }

    /** Changes descriptive fields and total; available is recomputed as total - allocated. */
    public Resource update(long id, ResourceInput in, int newAvailable) {
        return jdbc.queryForObject(
                "UPDATE resources SET name=?, category=?, total_quantity=?, available_quantity=?, "
                        + "reusable=COALESCE(?, reusable), location=?, active=COALESCE(?, active), "
                        + "last_updated=now() WHERE id=? RETURNING *",
                MAP, in.name(), in.category(), in.totalQuantity(), newAvailable, in.reusable(), in.location(),
                in.active(), id);
    }

    /** Moves units between available and allocated; the CHECK constraint guards the invariant. */
    public void applyDelta(long id, int availableDelta) {
        jdbc.update("UPDATE resources SET available_quantity = available_quantity + ?, "
                + "allocated_quantity = allocated_quantity - ?, last_updated = now() WHERE id = ?",
                availableDelta, availableDelta, id);
    }

    /** History of a never-used resource (CREATE entry only) is removed together with it. */
    public int deleteHistory(long resourceId) {
        return jdbc.update("DELETE FROM resource_history WHERE resource_id = ?", resourceId);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM resources WHERE id = ?", id);
    }

    public boolean isReferenced(long id) {
        Integer n = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM resource_requests WHERE resource_id = ?) "
                        + "+ (SELECT count(*) FROM organization_claims WHERE resource_id = ?)",
                Integer.class, id, id);
        return n != null && n > 0;
    }

    // ---- append-only audit trail ----
    public void addHistory(long resourceId, Long requestId, String action, int change, int before, int after,
                           String note) {
        jdbc.update("INSERT INTO resource_history (resource_id, request_id, action, quantity_change, "
                + "available_before, available_after, note) VALUES (?,?,?,?,?,?,?)",
                resourceId, requestId, action, change, before, after, note);
    }

    public List<History> history(Long resourceId, int limit) {
        String sql = "SELECT h.*, r.name AS resource_name FROM resource_history h JOIN resources r ON r.id=h.resource_id "
                + (resourceId != null ? "WHERE h.resource_id = ? " : "")
                + "ORDER BY h.created_at DESC, h.id DESC LIMIT ?";
        RowMapper<History> m = (rs, i) -> new History(rs.getLong("id"), rs.getLong("resource_id"),
                rs.getString("resource_name"), (Long) rs.getObject("request_id"), rs.getString("action"),
                rs.getInt("quantity_change"), rs.getInt("available_before"), rs.getInt("available_after"),
                rs.getString("note"), rs.getObject("created_at", java.time.OffsetDateTime.class));
        return resourceId != null ? jdbc.query(sql, m, resourceId, limit) : jdbc.query(sql, m, limit);
    }
}
