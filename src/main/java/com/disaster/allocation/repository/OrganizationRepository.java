package com.disaster.allocation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.disaster.allocation.model.Dto.Claim;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.OrganizationInput;

/** Parameterised SQL only (no string concatenation of user input). */
@Repository
public class OrganizationRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Organization> MAP = (rs, i) -> new Organization(
            rs.getLong("id"), rs.getString("name"), rs.getString("org_type"), rs.getString("location"),
            rs.getString("contact_info"), rs.getBoolean("active"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    public OrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Organization> findAll() {
        return jdbc.query("SELECT * FROM organizations ORDER BY id", MAP);
    }

    public Optional<Organization> findById(long id) {
        return jdbc.query("SELECT * FROM organizations WHERE id = ?", MAP, id).stream().findFirst();
    }

    public Organization insert(OrganizationInput in) {
        return jdbc.queryForObject(
                "INSERT INTO organizations (name, org_type, location, contact_info, active) "
                        + "VALUES (?,?,?,?,?) RETURNING *",
                MAP, in.name(), in.orgType(), in.location(), in.contactInfo(),
                in.active() == null || in.active());
    }

    public Optional<Organization> update(long id, OrganizationInput in) {
        return jdbc.query(
                "UPDATE organizations SET name=?, org_type=?, location=?, contact_info=?, "
                        + "active = COALESCE(?, active) WHERE id=? RETURNING *",
                MAP, in.name(), in.orgType(), in.location(), in.contactInfo(), in.active(), id)
                .stream().findFirst();
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM organizations WHERE id = ?", id);
    }

    public boolean hasRequests(long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM resource_requests WHERE organization_id = ?",
                Integer.class, id);
        return n != null && n > 0;
    }

    public int deleteClaims(long orgId) {
        return jdbc.update("DELETE FROM organization_claims WHERE organization_id = ?", orgId);
    }

    // ---- Banker's "Max" matrix rows ----
    public List<Claim> claims(long orgId) {
        return jdbc.query("SELECT * FROM organization_claims WHERE organization_id = ? ORDER BY resource_id",
                (rs, i) -> new Claim(rs.getLong("organization_id"), rs.getLong("resource_id"), rs.getInt("max_claim")),
                orgId);
    }

    public List<Claim> allClaims() {
        return jdbc.query("SELECT * FROM organization_claims ORDER BY organization_id, resource_id",
                (rs, i) -> new Claim(rs.getLong("organization_id"), rs.getLong("resource_id"), rs.getInt("max_claim")));
    }

    /** Upsert: declares or changes the maximum claim of one organization for one resource. */
    public void upsertClaim(long orgId, long resourceId, int maxClaim) {
        jdbc.update("INSERT INTO organization_claims (organization_id, resource_id, max_claim) VALUES (?,?,?) "
                + "ON CONFLICT (organization_id, resource_id) DO UPDATE SET max_claim = EXCLUDED.max_claim",
                orgId, resourceId, maxClaim);
    }
}
