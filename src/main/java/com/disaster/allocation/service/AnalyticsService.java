package com.disaster.allocation.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.Dto.AnalyticsDashboard;
import com.disaster.allocation.model.Dto.RequestCategoryAnalytics;
import com.disaster.allocation.model.Dto.RequestStatusAnalytics;
import com.disaster.allocation.model.Dto.RequestSummary;
import com.disaster.allocation.model.Dto.ResourceCategoryAnalytics;
import com.disaster.allocation.model.Dto.ResourceSummary;
import com.disaster.allocation.repository.AnalyticsRepository;
import com.disaster.allocation.security.AccessControl;

@Service
public class AnalyticsService {
    private final AnalyticsRepository repository;
    private final AccessControl access;

    public AnalyticsService(AnalyticsRepository repository, AccessControl access) {
        this.repository = repository;
        this.access = access;
    }

    public AnalyticsDashboard dashboard(int days) {
        if (days < 1 || days > 365) throw ApiException.badRequest("days must be between 1 and 365");

        boolean global = access.isGlobalOperator();
        boolean manager = access.isOrganizationManager();
        boolean member = access.hasRole("ORGANIZATION_MEMBER");
        if (!global && !manager && !member) throw ApiException.forbidden("Analytics access is not permitted");

        Long organizationId = null;
        Long userId = null;
        String scope;
        if (global) {
            scope = "SYSTEM";
        } else {
            organizationId = access.organizationId();
            if (organizationId == null) throw ApiException.forbidden("Organization membership is required");
            if (!manager) {
                userId = access.userId();
                if (userId == null) throw ApiException.forbidden("User identity is required");
                scope = "REQUESTER";
            } else {
                scope = "ORGANIZATION";
            }
        }

        List<ResourceCategoryAnalytics> resourceCategories = global ? repository.resourceCategories() : List.of();
        ResourceSummary resources = global ? summarizeResources(resourceCategories) : null;
        List<RequestStatusAnalytics> statuses = repository.requestStatuses(organizationId, userId);
        Map<String, Long> counts = statuses.stream().collect(Collectors.toMap(
                RequestStatusAnalytics::status, RequestStatusAnalytics::count, Long::sum));
        RequestSummary requests = new RequestSummary(
                counts.values().stream().mapToLong(Long::longValue).sum(),
                count(counts, "PENDING") + count(counts, "WAITING"),
                count(counts, "ALLOCATED"),
                count(counts, "PROCESSING") + count(counts, "PARTIALLY_ALLOCATED"),
                count(counts, "REJECTED"), count(counts, "COMPLETED"),
                repository.countCriticalRequests(organizationId, userId));
        List<RequestCategoryAnalytics> requestCategories = repository.requestCategories(organizationId, userId);
        LocalDate until = LocalDate.now().plusDays(1);
        List<com.disaster.allocation.model.Dto.AllocationTrend> trends = repository.allocationTrends(
                until.minusDays(days), until, organizationId, userId);
        return new AnalyticsDashboard(scope, days, resources, resourceCategories, requests, statuses,
                requestCategories, trends);
    }

    private static ResourceSummary summarizeResources(List<ResourceCategoryAnalytics> categories) {
        long resourceCount = categories.stream().mapToLong(ResourceCategoryAnalytics::resourceCount).sum();
        long total = categories.stream().mapToLong(ResourceCategoryAnalytics::totalUnits).sum();
        long available = categories.stream().mapToLong(ResourceCategoryAnalytics::availableUnits).sum();
        long allocated = categories.stream().mapToLong(ResourceCategoryAnalytics::allocatedUnits).sum();
        long shortage = categories.stream().mapToLong(ResourceCategoryAnalytics::shortageCount).sum();
        double utilization = total == 0 ? 0.0 : Math.round(allocated * 10000.0 / total) / 100.0;
        return new ResourceSummary(resourceCount, total, available, allocated, shortage, utilization);
    }

    private static long count(Map<String, Long> counts, String status) {
        return counts.getOrDefault(status, 0L);
    }
}
