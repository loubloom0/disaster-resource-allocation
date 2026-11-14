package com.disaster.allocation.scheduler;

import java.util.Comparator;
import java.util.List;

/**
 * Outcome of replaying one workload under one algorithm.
 *
 * Formulas (all in simulated work units):
 *   response   = first time the request got the processor - arrival
 *   turnaround = completion - arrival
 *   waiting    = turnaround - workUnits   (time spent NOT being processed)
 *
 * This describes SCHEDULING quality only. It says nothing about whether real
 * inventory was available; that is the allocation engine's job.
 */
public record SchedulingResult(String algorithm, List<Slice> timeline, List<JobResult> jobs) {

    /** One continuous stretch of processing (one Gantt-chart bar). */
    public record Slice(long jobId, int start, int end) { }

    public record JobResult(long id, int priority, int arrivalTime, int workUnits,
                            int firstStart, int completion,
                            int response, int waiting, int turnaround) { }

    /** Request ids in the order they finished. */
    public List<Long> completionOrder() {
        return jobs.stream()
                .sorted(Comparator.comparingInt(JobResult::completion).thenComparingLong(JobResult::id))
                .map(JobResult::id)
                .toList();
    }

    public double averageWaiting()    { return average(jobs.stream().mapToInt(JobResult::waiting).sum()); }
    public double averageTurnaround() { return average(jobs.stream().mapToInt(JobResult::turnaround).sum()); }
    public double averageResponse()   { return average(jobs.stream().mapToInt(JobResult::response).sum()); }

    /** Time at which the last request finished (0 for an empty workload). */
    public int makespan() {
        return jobs.stream().mapToInt(JobResult::completion).max().orElse(0);
    }

    /** Average response time of requests with the given priority; NaN if there are none. */
    public double averageResponseForPriority(int priority) {
        List<JobResult> subset = jobs.stream().filter(j -> j.priority() == priority).toList();
        if (subset.isEmpty()) {
            return Double.NaN;
        }
        return subset.stream().mapToInt(JobResult::response).sum() / (double) subset.size();
    }

    private double average(int sum) {
        return jobs.isEmpty() ? 0.0 : sum / (double) jobs.size();
    }
}
