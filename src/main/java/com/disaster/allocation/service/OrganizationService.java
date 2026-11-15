package com.disaster.allocation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.Claim;
import com.disaster.allocation.model.Dto.ClaimInput;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.OrganizationInput;
import com.disaster.allocation.repository.OrganizationRepository;
import com.disaster.allocation.repository.ResourceRepository;

@Service
public class OrganizationService {

    private final OrganizationRepository orgs;
    private final ResourceRepository resources;
    private final AuditService audit;

    public OrganizationService(OrganizationRepository orgs, ResourceRepository resources, AuditService audit) {
        this.orgs = orgs;
        this.resources = resources;
        this.audit = audit;
    }

    public List<Organization> list() {
        return orgs.findAll();
    }

    public Organization get(long id) {
        return orgs.findById(id).orElseThrow(() -> ApiException.notFound("Organization", id));
    }

    public Organization create(OrganizationInput in) {
        Organization created = orgs.insert(in); // UNIQUE(name) violations become 409 via GlobalExceptionHandler
        audit.record("ORGANIZATION_CREATED", "ORGANIZATION", created.id(), java.util.Map.of("name", created.name()));
        return created;
    }

    public Organization update(long id, OrganizationInput in) {
        Organization updated = orgs.update(id, in).orElseThrow(() -> ApiException.notFound("Organization", id));
        audit.record("ORGANIZATION_UPDATED", "ORGANIZATION", id, java.util.Map.of("name", updated.name()));
        return updated;
    }

    @Transactional
    public void delete(long id) {
        get(id);
        if (orgs.hasRequests(id)) {
            throw ApiException.conflict("Organization has resource requests and cannot be deleted; deactivate it instead");
        }
        // Claims have no meaning without the organization: remove them in the same transaction.
        orgs.deleteClaims(id);
        orgs.delete(id);
        audit.record("ORGANIZATION_DELETED", "ORGANIZATION", id, java.util.Map.of());
    }

    public List<Claim> claims(long id) {
        get(id);
        return orgs.claims(id);
    }

    /** Declares / changes the Banker's maximum claim of one organization for one resource. */
    public Claim setClaim(long orgId, ClaimInput in) {
        get(orgId);
        resources.findById(in.resourceId()).orElseThrow(() -> ApiException.notFound("Resource", in.resourceId()));
        orgs.upsertClaim(orgId, in.resourceId(), in.maxClaim());
        audit.record("CLAIM_CHANGED", "ORGANIZATION", orgId,
                java.util.Map.of("resourceId", in.resourceId(), "maxClaim", in.maxClaim()));
        return new Claim(orgId, in.resourceId(), in.maxClaim());
    }
}
