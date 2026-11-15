package com.disaster.allocation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.disaster.allocation.model.Dto.AnalyticsDashboard;
import com.disaster.allocation.service.AnalyticsService;

/** One authenticated aggregate endpoint keeps the dashboard refresh to a single analytics request. */
@RestController
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/api/analytics/dashboard")
    public AnalyticsDashboard dashboard(@RequestParam(defaultValue = "30") int days) {
        return analytics.dashboard(days);
    }
}
