package com.disaster.allocation.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import com.disaster.allocation.model.Dto.SimInput;
import com.disaster.allocation.service.SchedulingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/scheduling")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','DISPATCHER')")
public class SchedulingController {

    private final SchedulingService service;

    public SchedulingController(SchedulingService service) {
        this.service = service;
    }

    /** What-if run on jobs typed by the user. */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@Valid @RequestBody SimInput in) { return service.simulate(in); }

    /** Order the real queue would be served in (no allocation happens). */
    @GetMapping("/queue-preview")
    public Map<String, Object> preview(@RequestParam(defaultValue = "FCFS") String algorithm,
                                       @RequestParam(required = false) Integer quantum) {
        return service.previewQueue(algorithm, quantum);
    }
}
