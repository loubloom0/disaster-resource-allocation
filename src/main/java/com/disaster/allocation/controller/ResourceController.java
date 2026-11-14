package com.disaster.allocation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.disaster.allocation.model.Dto.*;
import com.disaster.allocation.service.ResourceService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<Resource> list() { return service.list(); }

    @GetMapping("/{id}")
    public Resource get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Resource create(@Valid @RequestBody ResourceInput in) { return service.create(in); }

    @PutMapping("/{id}")
    public Resource update(@PathVariable long id, @Valid @RequestBody ResourceInput in) {
        return service.update(id, in);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.delete(id); }

    @GetMapping("/history")
    public List<History> history(@RequestParam(required = false) Long resourceId,
                                 @RequestParam(defaultValue = "50") int limit) {
        return service.history(resourceId, limit);
    }
}
