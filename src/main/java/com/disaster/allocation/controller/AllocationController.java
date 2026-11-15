package com.disaster.allocation.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.model.Dto.Allocation;
import com.disaster.allocation.model.Dto.QueueRunResult;
import com.disaster.allocation.model.Dto.AllocationReleaseInput;
import com.disaster.allocation.repository.AllocationRepository;
import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.service.SchedulingService;
import com.disaster.allocation.security.AccessControl;

@RestController
@RequestMapping("/api/allocations")
public class AllocationController {

    private final AllocationRepository repo;
    private final AllocationEngine engine;
    private final SchedulingService scheduling;
    private final AccessControl access;

    public AllocationController(AllocationRepository repo, AllocationEngine engine, SchedulingService scheduling, AccessControl access) {
        this.repo = repo;
        this.engine = engine;
        this.scheduling = scheduling;
        this.access = access;
    }

    @GetMapping
    public List<Allocation> list(@RequestParam(required = false) String status) {
        if (access.isGlobalOperator()) return repo.findAll(status);
        Long organizationId = access.organizationId();
        if (organizationId == null) throw ApiException.forbidden("An organization membership is required");
        return access.isOrganizationManager() ? repo.findForOrganization(organizationId, status)
                : repo.findForUser(organizationId, access.userId(), status);
    }

    @GetMapping("/{id}")
    public Allocation get(@PathVariable long id) {
        Allocation allocation = repo.findById(id).orElseThrow(() -> ApiException.notFound("Allocation", id));
        access.requireOrganization(allocation.organizationId());
        if (!access.isGlobalOperator() && !access.isOrganizationManager() && !repo.requestOwnedBy(id, access.userId())) {
            throw ApiException.forbidden("Allocation access is not permitted");
        }
        return allocation;
    }

    @PostMapping("/{id}/release")
    @ResponseStatus(HttpStatus.OK)
    public Allocation release(@PathVariable long id,
                              @RequestBody(required = false) AllocationReleaseInput input) {
        Allocation allocation = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Allocation", id));
        if (!access.isGlobalOperator()) {
            // Organization is taken from the stored allocation/request relation, never from the request body.
            access.requireOrganizationManager(allocation.organizationId());
        }
        return engine.release(id, input == null ? null : input.quantity());
    }

    /**
     * Process the whole queue with the chosen scheduler. injectFailure only has an effect when the server
     * was started with DRAS_FAILURE_INJECTION=true (demo of rollback).
     */
    @PostMapping("/process-queue")
    public QueueRunResult processQueue(@RequestBody Map<String, Object> body) {
        access.requireGlobalOperator();
        String algorithm = String.valueOf(body.getOrDefault("algorithm", "FCFS"));
        Integer quantum = body.get("quantum") == null ? null : Integer.valueOf(String.valueOf(body.get("quantum")));
        boolean inject = Boolean.parseBoolean(String.valueOf(body.getOrDefault("injectFailure", false)));
        return scheduling.runQueue(algorithm, quantum, inject);
    }
}
