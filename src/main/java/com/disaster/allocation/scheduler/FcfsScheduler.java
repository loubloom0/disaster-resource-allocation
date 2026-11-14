package com.disaster.allocation.scheduler;

import java.util.Comparator;
import java.util.List;

/** First Come First Served: earliest arrival first; runs each request to completion (non-preemptive). */
public final class FcfsScheduler implements SchedulingAlgorithm {

    private static final Comparator<Job> BY_ARRIVAL =
            Comparator.comparingInt(Job::arrivalTime).thenComparingLong(Job::arrivalSeq);

    @Override
    public String name() {
        return "FCFS";
    }

    @Override
    public Job selectNext(List<Job> ready) {
        return ready.stream().min(BY_ARRIVAL).orElseThrow();
    }

    @Override
    public int timeSlice(Job job) {
        return job.remaining(); // non-preemptive
    }
}
