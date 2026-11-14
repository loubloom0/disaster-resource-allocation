package com.disaster.allocation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.allocation.AllocationEngine;
import com.disaster.allocation.model.Dto.*;
import com.disaster.allocation.service.RequestService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService service;
    private final AllocationEngine engine;

    public RequestController(RequestService service, AllocationEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    @GetMapping
    public List<ResourceRequest> list(@RequestParam(required = false) String status) { return service.list(status); }

    @GetMapping("/{id}")
    public ResourceRequest get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceRequest submit(@Valid @RequestBody RequestInput in) { return service.submit(in); }

    @PutMapping("/{id}/status")
    public ResourceRequest status(@PathVariable long id, @Valid @RequestBody StatusInput in) {
        return service.updateStatus(id, in);
    }

    /** Allocate ONE request right now (manual FCFS-style allocation from the Allocation Center). */
    @PostMapping("/{id}/allocate")
    public AllocationOutcome allocate(@PathVariable long id) {
        return engine.allocate(id, false, "MANUAL");
    }
}
