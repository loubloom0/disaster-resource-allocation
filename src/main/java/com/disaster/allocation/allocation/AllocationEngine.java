package com.disaster.allocation.allocation;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.Allocation;
import com.disaster.allocation.model.Dto.AllocationOutcome;
import com.disaster.allocation.model.Dto.Resource;
import com.disaster.allocation.model.Dto.ResourceRequest;
import com.disaster.allocation.repository.AllocationRepository;
import com.disaster.allocation.repository.OrganizationRepository;
import com.disaster.allocation.repository.RequestRepository;
import com.disaster.allocation.repository.ResourceRepository;
import com.disaster.allocation.service.AuditService;
import com.disaster.allocation.safety.RequestDecision;

/**
 * THE ALLOCATION ENGINE:
 * validate -> lock -> recheck inventory -> Banker's safety -> write.
 *
 * Everything happens inside one PostgreSQL transaction.
 *
 * ACID:
 * Atomicity   : All database writes commit together or roll back.
 * Consistency : PostgreSQL constraints protect inventory consistency.
 * Isolation   : Pessimistic row locking prevents conflicting allocations.
 * Durability  : Committed PostgreSQL records survive application restarts.
 */
@Service
public class AllocationEngine {

    private final RequestRepository requests;
    private final ResourceRepository resources;
    private final AllocationRepository allocations;
    private final OrganizationRepository organizations;
    private final boolean failureInjectionEnabled;
    private final AuditService audit;

    public AllocationEngine(
            RequestRepository requests,
            ResourceRepository resources,
            AllocationRepository allocations,
            OrganizationRepository organizations,
            AuditService audit,
            @Value("${dras.failure-injection.enabled:false}")
            boolean failureInjectionEnabled) {

        this.requests = requests;
        this.resources = resources;
        this.allocations = allocations;
        this.organizations = organizations;
        this.audit = audit;
        this.failureInjectionEnabled = failureInjectionEnabled;
    }

    public boolean failureInjectionEnabled() {
        return failureInjectionEnabled;
    }

    /**
     * Used to deliberately test transaction rollback.
     */
    public static final class InjectedFailure extends RuntimeException {

        public InjectedFailure(String message) {
            super(message);
        }
    }

    /**
     * Allocates one resource request.
     *
     * Possible outcomes:
     * ALLOCATED
     * WAITING
     * REJECTED
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class
    )
    public AllocationOutcome allocate(
            long requestId,
            boolean injectFailure,
            String algorithm) {

        // 1. Lock request.
        ResourceRequest req = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("Request", requestId));

        if (!"PENDING".equals(req.status())
                && !"WAITING".equals(req.status())) {

            throw ApiException.conflict(
                    "Request " + requestId + " is "
                            + req.status() + " and cannot be allocated"
            );
        }

        // 2. Lock resource inventory.
        Optional<Resource> targetOpt =
                resources.findById(req.resourceId());

        if (targetOpt.isEmpty()) {
            return reject(
                    req,
                    algorithm,
                    "Resource no longer exists",
                    null
            );
        }

        List<Resource> lockedReusable = List.of();
        Resource target;

        if (targetOpt.get().reusable()) {

            lockedReusable =
                    resources.findAllReusableForUpdate();

            target = lockedReusable.stream()
                    .filter(r -> java.util.Objects.equals(r.id(), req.resourceId()))
                    .findFirst()
                    .orElseThrow();

        } else {

            target = resources.findByIdForUpdate(req.resourceId())
                    .orElseThrow();
        }

        // 3. Validate organization.
        var org = organizations.findById(req.organizationId())
                .orElse(null);

        if (org == null || !org.active()) {
            return reject(
                    req,
                    algorithm,
                    "Organization is inactive or missing",
                    null
            );
        }

        if (!target.active()) {
            return reject(
                    req,
                    algorithm,
                    "Resource is inactive",
                    null
            );
        }

        int qty = req.requestedQuantity();

        // 4. Validate available inventory.
        if (qty > target.availableQuantity()) {

            return wait(
                    req,
                    algorithm,
                    "Insufficient inventory: "
                            + target.availableQuantity()
                            + " " + target.name()
                            + " available, "
                            + qty + " requested",
                    null
            );
        }

        // 5. Banker's Algorithm for reusable resources.
        String bankersNote =
                "Consumable resource: inventory check only "
                        + "(Banker's not applicable)";

        if (target.reusable()) {

            BankersSnapshot snap = BankersSnapshot.build(
                    lockedReusable,
                    organizations.findAll(),
                    organizations.allClaims(),
                    allocations.activeHoldings(),
                    req.organizationId()
            );

            int i = snap.orgIndex(req.organizationId());
            int j = snap.resourceIndex(req.resourceId());

            int[] vector = new int[snap.resourceIds.size()];
            vector[j] = qty;

            RequestDecision decision =
                    snap.toAlgorithm().evaluateRequest(i, vector);

            bankersNote =
                    "Banker's: " + decision.outcome()
                            + " - " + decision.explanation();

            switch (decision.outcome()) {

                case EXCEEDS_MAX_CLAIM:
                    return reject(
                            req,
                            algorithm,
                            "Exceeds remaining declared claim",
                            bankersNote
                    );

                case INSUFFICIENT_AVAILABLE:
                    return wait(
                            req,
                            algorithm,
                            "Insufficient inventory (Banker's check)",
                            bankersNote
                    );

                case UNSAFE:
                    return wait(
                            req,
                            algorithm,
                            "Allocation would leave an UNSAFE state; waiting",
                            bankersNote
                    );

                case GRANTED:
                    // Continue with allocation.
                    break;
            }
        }

        // 6. Execute all allocation writes in one transaction.
        int before = target.availableQuantity();

        resources.applyDelta(target.id(), -qty);

        long allocationId =
                allocations.insert(req.id(), target.id(), qty);

        requests.markAllocated(
                req.id(),
                qty,
                "Allocated via " + algorithm
        );
        requests.addEvent(req.id(), audit.actorId(), "ALLOCATION_APPROVED", req.status(), "ALLOCATED",
                "{\"allocationId\":" + allocationId + ",\"algorithm\":\"" + algorithm + "\"}");
        audit.record("ALLOCATION_COMPLETED", "ALLOCATION", allocationId,
                java.util.Map.of("requestId", req.id(), "resourceId", target.id(), "quantity", qty, "algorithm", algorithm));

        resources.addHistory(
                target.id(),
                req.id(),
                "ALLOCATE",
                -qty,
                before,
                before - qty,
                "Request " + req.id()
                        + " by " + req.organizationName()
        );

        // 7. Deliberate failure injection for rollback testing.
        if (injectFailure && failureInjectionEnabled) {

            throw new InjectedFailure(
                    "Injected failure after writes for request "
                            + req.id()
            );
        }

        return new AllocationOutcome(
                req.id(),
                "ALLOCATED",
                "Allocated " + qty + " x " + target.name(),
                qty,
                allocationId,
                algorithm,
                bankersNote
        );
    }

    private AllocationOutcome wait(
            ResourceRequest req,
            String algorithm,
            String reason,
            String note) {

        requests.setStatus(
                req.id(),
                "WAITING",
                trunc(reason)
        );
        requests.addEvent(req.id(), audit.actorId(), "REQUEST_WAITING", req.status(), "WAITING", "{}");

        return new AllocationOutcome(
                req.id(),
                "WAITING",
                reason,
                0,
                null,
                algorithm,
                note
        );
    }

    private AllocationOutcome reject(
            ResourceRequest req,
            String algorithm,
            String reason,
            String note) {

        requests.setStatus(
                req.id(),
                "REJECTED",
                trunc(reason)
        );
        requests.addEvent(req.id(), audit.actorId(), "REQUEST_REJECTED", req.status(), "REJECTED", "{}");

        return new AllocationOutcome(
                req.id(),
                "REJECTED",
                reason,
                0,
                null,
                algorithm,
                note
        );
    }

    private static String trunc(String s) {

        return s == null
                ? null
                : s.length() > 300
                        ? s.substring(0, 300)
                        : s;
    }

    /**
     * Releases an ACTIVE allocation.
     *
     * Reusable resources can return to inventory.
     * Consumable resources cannot be returned because
     * allocation represents consumption in this model.
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class
    )
    public Allocation release(long allocationId) {
        return release(allocationId, null);
    }

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class
    )
    public Allocation release(long allocationId, Integer requestedQuantity) {

        // 1. Lock allocation.
        Allocation a = allocations.findByIdForUpdate(allocationId)
                .orElseThrow(
                        () -> ApiException.notFound(
                                "Allocation",
                                allocationId
                        )
                );

        if (!"ACTIVE".equals(a.status())) {

            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Allocation " + allocationId
                            + " is already released"
            );
        }

        // 2. Lock request.
        requests.findByIdForUpdate(a.requestId());

        // 3. Lock resource.
        Resource r = resources.findByIdForUpdate(a.resourceId())
                .orElseThrow();

        // 4. IMPORTANT: Prevent consumable resource release.
        if (!r.reusable()) {

            throw ApiException.conflict(
                    "Cannot release consumable resource '"
                            + r.name()
                            + "'. Consumed stock cannot be "
                            + "returned to inventory."
            );
        }

        int quantity = requestedQuantity == null ? a.remainingQuantity() : requestedQuantity;
        if (quantity < 1) {
            throw ApiException.badRequest("Release quantity must be at least 1");
        }
        if (quantity > a.remainingQuantity()) {
            throw ApiException.badRequest("Release quantity cannot exceed the remaining allocation of "
                    + a.remainingQuantity());
        }

        // 5. Restore only the returned units. All release records and inventory changes commit together.
        int availableBefore = r.availableQuantity();
        resources.applyDelta(
                r.id(),
                quantity
        );

        // 6. Update allocation totals and append immutable release/audit history.
        allocations.addReleasedQuantity(allocationId, quantity);
        allocations.insertRelease(allocationId, r.id(), a.organizationId(), quantity, audit.actorId());
        int remaining = a.remainingQuantity() - quantity;
        requests.addEvent(a.requestId(), audit.actorId(), "ALLOCATION_RELEASED", null, null,
                "{\"allocationId\":" + allocationId + ",\"quantity\":" + quantity
                        + ",\"remainingQuantity\":" + remaining + "}");
        audit.record("ALLOCATION_RELEASED", "ALLOCATION", allocationId,
                java.util.Map.of("requestId", a.requestId(), "resourceId", a.resourceId(), "organizationId",
                        a.organizationId(), "quantity", quantity, "remainingQuantity", remaining));

        resources.addHistory(
                r.id(),
                a.requestId(),
                "RELEASE",
                quantity,
                availableBefore,
                availableBefore + quantity,
                "Allocation " + allocationId + " returned " + quantity + " unit(s)"
        );

        // 7. Complete request if no active allocations remain.
        if (allocations.activeCountForRequest(a.requestId()) == 0) {

            requests.setStatus(
                    a.requestId(),
                    "COMPLETED",
                    "All allocations released"
            );
            requests.addEvent(a.requestId(), audit.actorId(), "REQUEST_COMPLETED", "ALLOCATED", "COMPLETED", "{}");
        }

        return allocations.findById(allocationId)
                .orElseThrow();
    }
}
