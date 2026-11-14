package com.disaster.allocation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.model.Dto.*;
import com.disaster.allocation.service.OrganizationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Organization> list() { return service.list(); }

    @GetMapping("/{id}")
    public Organization get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Organization create(@Valid @RequestBody OrganizationInput in) { return service.create(in); }

    @PutMapping("/{id}")
    public Organization update(@PathVariable long id, @Valid @RequestBody OrganizationInput in) {
        return service.update(id, in);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.delete(id); }

    @GetMapping("/{id}/claims")
    public List<Claim> claims(@PathVariable long id) { return service.claims(id); }

    @PutMapping("/{id}/claims")
    public Claim setClaim(@PathVariable long id, @Valid @RequestBody ClaimInput in) {
        return service.setClaim(id, in);
    }
}
