package com.disaster.allocation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.model.Dto.*;
import com.disaster.allocation.service.OrganizationService;
import com.disaster.allocation.security.AccessControl;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationService service;
    private final AccessControl access;

    public OrganizationController(OrganizationService service, AccessControl access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public List<?> list() {
        if (access.isAdministrator()) return service.list();
        if (access.hasRole("DISPATCHER")) return service.list().stream().map(OrganizationController::summary).toList();
        Long own = access.organizationId();
        if (own == null) return List.of();
        Organization organization = service.get(own);
        return access.isOrganizationManager() ? List.of(organization) : List.of(summary(organization));
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable long id) {
        access.requireOrganization(id);
        Organization organization = service.get(id);
        return access.isAdministrator() || access.isOrganizationManager() ? organization : summary(organization);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Organization create(@Valid @RequestBody OrganizationInput in) { access.requireAdministrator(); return service.create(in); }

    @PutMapping("/{id}")
    public Organization update(@PathVariable long id, @Valid @RequestBody OrganizationInput in) {
        access.requireOrganizationManager(id);
        if (!access.isGlobalOperator() && in.active() != null) {
            throw com.disaster.allocation.exception.ApiException.forbidden("Only a system administrator or dispatcher can change organization activation");
        }
        return service.update(id, in);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { access.requireAdministrator(); service.delete(id); }

    @GetMapping("/{id}/claims")
    public List<Claim> claims(@PathVariable long id) { access.requireOrganizationManager(id); return service.claims(id); }

    @PutMapping("/{id}/claims")
    public Claim setClaim(@PathVariable long id, @Valid @RequestBody ClaimInput in) {
        access.requireOrganizationManager(id);
        return service.setClaim(id, in);
    }

    private static OrganizationSummary summary(Organization o) {
        return new OrganizationSummary(o.id(), o.name(), o.orgType(), o.location(), o.active());
    }
}
