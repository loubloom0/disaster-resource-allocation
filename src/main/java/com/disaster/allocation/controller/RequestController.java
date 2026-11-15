package com.disaster.allocation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.model.Dto.*;
import com.disaster.allocation.service.RequestService;
import com.disaster.allocation.security.AccessControl;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService service;
    private final AllocationEngine engine;
    private final AccessControl access;

    public RequestController(RequestService service, AllocationEngine engine, AccessControl access) {
        this.service = service;
        this.engine = engine;
        this.access = access;
    }

    @GetMapping
    public List<ResourceRequest> list(@RequestParam(required = false) String status) {
        if (access.isGlobalOperator()) return service.list(status);
        Long own = access.organizationId();
        if (own == null) return List.of();
        return access.hasRole("ORGANIZATION_MANAGER")
                ? service.listForOrganization(own, status)
                : service.listForUser(own, access.userId(), status);
    }

    @GetMapping("/{id}")
    public ResourceRequest get(@PathVariable long id) {
        ResourceRequest request = service.get(id);
        access.requireOrganization(request.organizationId());
        if (!access.isGlobalOperator() && !access.hasRole("ORGANIZATION_MANAGER")
                && !service.ownedBy(id, access.userId())) {
            throw com.disaster.allocation.exception.ApiException.forbidden("Request access is not permitted");
        }
        return request;
    }

    @GetMapping("/{id}/timeline")
    public List<java.util.Map<String, Object>> timeline(@PathVariable long id) {
        get(id);
        return service.timeline(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceRequest submit(@Valid @RequestBody RequestInput in) {
        if (access.isGlobalOperator()) return service.submit(in);
        Long own = access.organizationId();
        if (own == null) throw com.disaster.allocation.exception.ApiException.forbidden("An organization membership is required");
        return service.submit(new RequestInput(own, in.resourceId(), in.quantity(), in.priority(), in.workUnits()), access.userId());
    }

    @PutMapping("/{id}/status")
    public ResourceRequest status(@PathVariable long id, @Valid @RequestBody StatusInput in) {
        if (!access.isGlobalOperator()) {
            ResourceRequest current = service.get(id);
            access.requireOrganizationManager(current.organizationId());
        }
        return service.updateStatus(id, in, access.userId());
    }

    /** Allocate ONE request right now (manual FCFS-style allocation from the Allocation Center). */
    @PostMapping("/{id}/allocate")
    public AllocationOutcome allocate(@PathVariable long id) {
        access.requireGlobalOperator();
        return engine.allocate(id, false, "MANUAL");
    }
}
