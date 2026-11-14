package com.disaster.allocation.scheduler;

import java.util.Comparator;
import java.util.List;

/**
 * Priority scheduling (non-preemptive): the highest priority (5 = life-threatening)
 * goes first. TIE-BREAK: among equal priorities the earlier arrival wins
 * (arrivalTime, then arrivalSeq), so the order is fully deterministic.
 *
 * Note: low-priority requests can starve if high-priority ones keep arriving.
 * That is a known property of priority scheduling and is visible in the metrics.
 */
public final class PriorityScheduler implements SchedulingAlgorithm {

    private static final Comparator<Job> HIGHEST_PRIORITY_FIRST =
            Comparator.comparingInt(Job::priority).reversed()
                    .thenComparingInt(Job::arrivalTime)
                    .thenComparingLong(Job::arrivalSeq);

    @Override
    public String name() {
        return "PRIORITY";
    }

    @Override
    public Job selectNext(List<Job> ready) {
        return ready.stream().min(HIGHEST_PRIORITY_FIRST).orElseThrow();
    }

    @Override
    public int timeSlice(Job job) {
        return job.remaining(); // non-preemptive
    }
}
