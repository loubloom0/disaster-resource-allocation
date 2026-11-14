package com.disaster.allocation.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.model.Dto.Allocation;
import com.disaster.allocation.model.Dto.QueueRunResult;
import com.disaster.allocation.repository.AllocationRepository;
import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.service.SchedulingService;

@RestController
@RequestMapping("/api/allocations")
public class AllocationController {

    private final AllocationRepository repo;
    private final AllocationEngine engine;
    private final SchedulingService scheduling;

    public AllocationController(AllocationRepository repo, AllocationEngine engine, SchedulingService scheduling) {
        this.repo = repo;
        this.engine = engine;
        this.scheduling = scheduling;
    }

    @GetMapping
    public List<Allocation> list(@RequestParam(required = false) String status) { return repo.findAll(status); }

    @GetMapping("/{id}")
    public Allocation get(@PathVariable long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Allocation", id));
    }

    @PostMapping("/{id}/release")
    public Allocation release(@PathVariable long id) { return engine.release(id); }

    /**
     * Process the whole queue with the chosen scheduler. injectFailure only has an effect when the server
     * was started with DRAS_FAILURE_INJECTION=true (demo of rollback).
     */
    @PostMapping("/process-queue")
    public QueueRunResult processQueue(@RequestBody Map<String, Object> body) {
        String algorithm = String.valueOf(body.getOrDefault("algorithm", "FCFS"));
        Integer quantum = body.get("quantum") == null ? null : Integer.valueOf(String.valueOf(body.get("quantum")));
        boolean inject = Boolean.parseBoolean(String.valueOf(body.getOrDefault("injectFailure", false)));
        return scheduling.runQueue(algorithm, quantum, inject);
    }
}
