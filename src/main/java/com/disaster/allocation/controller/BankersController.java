package com.disaster.allocation.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.model.Dto.BankersInput;
import com.disaster.allocation.service.BankersService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/bankers")
public class BankersController {

    private final BankersService service;

    public BankersController(BankersService service) {
        this.service = service;
    }

    /** Simulator on user-supplied matrices. */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@Valid @RequestBody BankersInput in) { return service.evaluate(in); }

    /** Matrices built from the live database; optional what-if request (never changes data). */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam(required = false) Long organizationId,
                                     @RequestParam(required = false) Long resourceId,
                                     @RequestParam(required = false) Integer quantity) {
        return service.liveState(organizationId, resourceId, quantity);
    }
}
