package com.disaster.allocation.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.disaster.allocation.service.AlertService;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertService alerts;
    public AlertController(AlertService alerts) { this.alerts = alerts; }

    @GetMapping
    public List<Map<String, Object>> list() { return alerts.list(); }

    @PostMapping("/{id}/acknowledge")
    public Map<String, String> acknowledge(@PathVariable long id) {
        alerts.acknowledge(id);
        return Map.of("status", "ACKNOWLEDGED");
    }
}
