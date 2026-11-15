package com.disaster.allocation.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.Claim;
import com.disaster.allocation.model.Dto.Organization;
import com.disaster.allocation.model.Dto.RequestInput;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceRequest;
import com.disaster.allocation.model.Dto.StatusInput;
import com.disaster.allocation.repository.AllocationRepository;
import com.disaster.allocation.repository.OrganizationRepository;
import com.disaster.allocation.repository.RequestRepository;
import com.disaster.allocation.repository.ResourceRepository;

@Service
public class RequestService {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "REJECTED");

    private final RequestRepository requests;
    private final OrganizationRepository orgs;
    private final ResourceRepository resources;
    private final AllocationRepository allocations;
    private final AuditService audit;

    public RequestService(
            RequestRepository requests,
            OrganizationRepository orgs,
            ResourceRepository resources,
            AllocationRepository allocations,
            AuditService audit) {

        this.requests = requests;
        this.orgs = orgs;
        this.resources = resources;
        this.allocations = allocations;
        this.audit = audit;
    }

    public List<ResourceRequest> list(String status) {
        return requests.findAll(status);
    }

    public List<ResourceRequest> listForOrganization(long organizationId, String status) {
        return requests.findForOrganization(organizationId, status);
    }

    public List<ResourceRequest> listForUser(long organizationId, long userId, String status) {
        return requests.findForUser(organizationId, userId, status);
    }

    public boolean ownedBy(long requestId, long userId) { return requests.isOwnedBy(requestId, userId); }
    public List<java.util.Map<String, Object>> timeline(long requestId) {
        get(requestId);
        return requests.events(requestId);
    }

    public ResourceRequest get(long id) {
        return requests.findById(id)
                .orElseThrow(() -> ApiException.notFound("Request", id));
    }

    /**
     * Step 1-3 of the workflow:
     * Submit, validate and enter the queue as PENDING.
     */
    @Transactional
    public ResourceRequest submit(RequestInput in) {
        return submit(in, null);
    }

    @Transactional
    public ResourceRequest submit(RequestInput in, Long createdBy) {

        Organization org = orgs.findById(in.organizationId())
                .orElseThrow(() -> ApiException.notFound(
                        "Organization", in.organizationId()));

        Resource res = resources.findById(in.resourceId())
                .orElseThrow(() -> ApiException.notFound(
                        "Resource", in.resourceId()));

        // Validate organization
        if (!org.active()) {
            throw ApiException.badRequest(
                    "Organization " + org.name() + " is inactive");
        }

        // Validate resource
        if (!res.active()) {
            throw ApiException.badRequest(
                    "Resource " + res.name() + " is inactive");
        }

        // Retrieve maximum claim
        Claim claim = orgs.claims(org.id())
                .stream()
                .filter(c -> c.resourceId().equals(res.id()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        org.name() + " has not declared a maximum claim for "
                                + res.name()
                                + "; declare one first (needed by Banker's Algorithm)"
                ));

        // Validate declared maximum claim
        if (in.quantity() > claim.maxClaim()) {
            throw ApiException.badRequest(
                    "Requested " + in.quantity()
                            + " exceeds the declared maximum claim of "
                            + claim.maxClaim()
            );
        }

        /*
         * Do not reject requests based on total inventory here.
         * The AllocationEngine handles resource availability
         * and determines whether the request should be allocated
         * or placed in the WAITING queue.
         */

        int work = in.workUnits() == null ? 1 : in.workUnits();

        // Insert request into database
        ResourceRequest created = requests.insert(
                org.id(),
                res.id(),
                in.quantity(),
                in.priority(),
                claim.maxClaim(),
                work,
                createdBy
        );
        requests.addEvent(created.id(), createdBy, "REQUEST_SUBMITTED", null, "PENDING", "{}");
        audit.record("REQUEST_CREATED", "REQUEST", created.id(),
                java.util.Map.of("organizationId", org.id(), "resourceId", res.id(), "quantity", in.quantity()));
        return created;
    }

    /**
     * Manual status change (cancel, mark processing, ...).
     * Allocation and release have their own endpoints
     * because they move inventory.
     * ALLOCATED cannot be set by hand.
     */
    @Transactional
    public ResourceRequest updateStatus(long id, StatusInput in) {
        return updateStatus(id, in, null);
    }

    @Transactional
    public ResourceRequest updateStatus(long id, StatusInput in, Long actor) {

        ResourceRequest cur = requests.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Request", id));

        if (TERMINAL.contains(cur.status())) {
            throw ApiException.conflict(
                    "Request is already " + cur.status());
        }

        if ("ALLOCATED".equals(cur.status())
                && !"COMPLETED".equals(in.status())) {

            throw ApiException.conflict(
                    "Request holds an allocation; release it instead");
        }

        if ("COMPLETED".equals(in.status())
                && allocations.activeCountForRequest(id) > 0) {

            throw ApiException.conflict(
                    "Request still has ACTIVE allocations; release them to complete it");
        }

        requests.setStatus(id, in.status(), in.reason());
        requests.addEvent(id, actor, "STATUS_CHANGED", cur.status(), in.status(), "{}");
        audit.record("REQUEST_STATUS_CHANGED", "REQUEST", id,
                java.util.Map.of("from", cur.status(), "to", in.status()));

        return get(id);
    }
}
