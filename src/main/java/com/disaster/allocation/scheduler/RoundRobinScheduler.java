package com.disaster.allocation.scheduler;

import java.util.List;

/**
 * Round Robin with a configurable time quantum.
 *
 * HOW A QUANTUM APPLIES TO RESOURCE REQUESTS: a request is not a CPU burst, so
 * each request carries {@code workUnits} of simulated processing. Every turn a
 * request may use at most {@code quantum} units; if it still has work left it
 * goes to the back of the queue and the next request gets a turn. This rotates
 * attention fairly instead of letting one big request block everybody.
 */
public final class RoundRobinScheduler implements SchedulingAlgorithm {

    private final int quantum;

    public RoundRobinScheduler(int quantum) {
        if (quantum <= 0) {
            throw new IllegalArgumentException("time quantum must be > 0, got " + quantum);
        }
        this.quantum = quantum;
    }

    public int quantum() {
        return quantum;
    }

    @Override
    public String name() {
        return "ROUND_ROBIN";
    }

    @Override
    public Job selectNext(List<Job> ready) {
        return ready.get(0); // the simulator keeps the list in queue order
    }

    @Override
    public int timeSlice(Job job) {
        return Math.min(quantum, job.remaining());
    }
}
