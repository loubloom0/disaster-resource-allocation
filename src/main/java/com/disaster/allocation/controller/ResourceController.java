package com.disaster.allocation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.model.Dto.*;
import com.disaster.allocation.service.ResourceService;
import com.disaster.allocation.security.AccessControl;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService service;
    private final AccessControl access;

    public ResourceController(ResourceService service, AccessControl access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public List<?> list() {
        List<Resource> resources = service.list();
        if (access.isGlobalOperator()) return resources;
        return resources.stream().filter(Resource::active)
                .map(r -> new ResourceAvailability(r.id(), r.name(), r.category(), r.availableQuantity(), r.reusable())).toList();
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable long id) {
        Resource resource = service.get(id);
        if (access.isGlobalOperator()) return resource;
        if (!resource.active()) throw com.disaster.allocation.exception.ApiException.notFound("Resource", id);
        return new ResourceAvailability(resource.id(), resource.name(), resource.category(),
                resource.availableQuantity(), resource.reusable());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Resource create(@Valid @RequestBody ResourceInput in) { access.requireGlobalOperator(); return service.create(in); }

    @PutMapping("/{id}")
    public Resource update(@PathVariable long id, @Valid @RequestBody ResourceInput in) {
        access.requireGlobalOperator();
        return service.update(id, in);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { access.requireGlobalOperator(); service.delete(id); }

    @GetMapping("/history")
    public List<History> history(@RequestParam(required = false) Long resourceId,
                                 @RequestParam(defaultValue = "50") int limit) {
        access.requireGlobalOperator();
        return service.history(resourceId, limit);
    }
}
