package com.disaster.allocation.model;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Plain data carriers (records) for requests and responses. */
public final class Dto {
    private Dto() { }

    public record Organization(Long id, String name, String orgType, String location, String contactInfo,
                               boolean active, OffsetDateTime createdAt) { }

    public record OrganizationSummary(Long id, String name, String orgType, String location, boolean active) { }

    public record OrganizationInput(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "HOSPITAL|SHELTER|RESCUE_TEAM",
                    message = "must be HOSPITAL, SHELTER or RESCUE_TEAM") String orgType,
            @NotBlank @Size(max = 120) String location,
            @Size(max = 200) String contactInfo,
            Boolean active) { }

    public record Resource(Long id, String name, String category, int totalQuantity, int availableQuantity,
                           int allocatedQuantity, boolean reusable, String location, boolean active,
                           OffsetDateTime lastUpdated) { }

    public record ResourceAvailability(Long id, String name, String category, int availableQuantity, boolean reusable) { }

    public record ResourceInput(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @jakarta.validation.constraints.Pattern(
                    regexp = "AMBULANCE|MEDICINE|FOOD|WATER|HOSPITAL_BED|MEDICAL_STAFF",
                    message = "invalid category") String category,
            @NotNull @Min(0) Integer totalQuantity,
            Boolean reusable,
            @NotBlank @Size(max = 120) String location,
            Boolean active) { }

    public record Claim(Long organizationId, Long resourceId, int maxClaim) { }

    public record ClaimInput(@NotNull Long resourceId, @NotNull @Min(0) Integer maxClaim) { }

    public record RequestInput(
            @NotNull Long organizationId,
            @NotNull Long resourceId,
            @NotNull @Min(1) Integer quantity,
            @NotNull @Min(1) @Max(5) Integer priority,
            @Min(1) Integer workUnits) { }

    public record StatusInput(
            @NotBlank @jakarta.validation.constraints.Pattern(
                    regexp = "PENDING|WAITING|PROCESSING|COMPLETED|REJECTED",
                    message = "status must be PENDING, WAITING, PROCESSING, COMPLETED or REJECTED") String status,
            @Size(max = 300) String reason) { }

    public record ResourceRequest(Long id, Long organizationId, String organizationName, Long resourceId,
                                  String resourceName, int requestedQuantity, int allocatedQuantity, int priority,
                                  int maxClaim, int workUnits, long arrivalSeq, OffsetDateTime arrivalTime,
                                  String status, String statusReason, OffsetDateTime completedAt) { }

    public record Allocation(Long id, Long requestId, Long organizationId, String organizationName, Long resourceId,
                             String resourceName, int quantity, String status, OffsetDateTime allocatedAt,
                             OffsetDateTime releasedAt, int releasedQuantity, int remainingQuantity,
                             boolean reusable) { }

    public record AllocationReleaseInput(Integer quantity) { }

    public record History(Long id, Long resourceId, String resourceName, Long requestId, String action,
                          int quantityChange, int availableBefore, int availableAfter, String note,
                          OffsetDateTime createdAt) { }

    /** Outcome of trying to allocate one request. */
    public record AllocationOutcome(long requestId, String outcome, String message, int allocatedNow,
                                    Long allocationId, String algorithm, String bankersNote) { }

    /** One scheduler-chosen step of a queue run. */
    public record QueueRunResult(String algorithm, Integer quantum, List<Long> processingOrder,
                                 List<AllocationOutcome> outcomes) { }

    public record SimJob(@NotNull Long id, @NotNull @Min(1) @Max(5) Integer priority, @NotNull @Min(0) Integer arrivalTime,
                         @NotNull @Min(1) Integer workUnits) { }

    public record SimInput(@NotBlank String algorithm, @Min(1) Integer quantum, @NotNull List<SimJob> jobs,
                           Boolean save, String name) { }

    public record BankersInput(@NotNull int[] available, @NotNull int[][] max, @NotNull int[][] allocation,
                               Integer process, int[] request) { }

    public record AnalyticsDashboard(String scope, int trendDays, ResourceSummary resources,
                                     List<ResourceCategoryAnalytics> resourceCategories,
                                     RequestSummary requests, List<RequestStatusAnalytics> requestStatuses,
                                     List<RequestCategoryAnalytics> requestCategories,
                                     List<AllocationTrend> allocationTrends) { }

    public record ResourceSummary(long resourceCount, long totalUnits, long availableUnits, long allocatedUnits,
                                  long shortageCount, double utilizationPercentage) { }

    public record ResourceCategoryAnalytics(String category, long resourceCount, long totalUnits,
                                            long availableUnits, long allocatedUnits, long shortageCount,
                                            double utilizationPercentage) { }

    public record RequestSummary(long total, long pending, long allocated, long processing,
                                 long rejected, long completed, long critical) { }

    public record RequestStatusAnalytics(String status, long count) { }

    public record RequestCategoryAnalytics(String category, long requestedUnits, long allocatedUnits) { }

    public record AllocationTrend(java.time.LocalDate date, long allocatedUnits, long releasedUnits,
                                  long allocationCount) { }
}
