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

    public OrganizationService(OrganizationRepository orgs, ResourceRepository resources) {
        this.orgs = orgs;
        this.resources = resources;
    }

    public List<Organization> list() {
        return orgs.findAll();
    }

    public Organization get(long id) {
        return orgs.findById(id).orElseThrow(() -> ApiException.notFound("Organization", id));
    }

    public Organization create(OrganizationInput in) {
        return orgs.insert(in); // UNIQUE(name) violations become 409 via GlobalExceptionHandler
    }

    public Organization update(long id, OrganizationInput in) {
        return orgs.update(id, in).orElseThrow(() -> ApiException.notFound("Organization", id));
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
        return new Claim(orgId, in.resourceId(), in.maxClaim());
    }
}
