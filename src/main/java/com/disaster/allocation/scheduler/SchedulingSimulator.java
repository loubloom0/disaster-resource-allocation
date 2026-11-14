package com.disaster.allocation.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.disaster.allocation.scheduler.SchedulingResult.JobResult;
import com.disaster.allocation.scheduler.SchedulingResult.Slice;

/**
 * Replays a workload on a single simulated processor under a chosen algorithm.
 *
 * Deterministic: the same workload and algorithm always give the same result
 * (no randomness, no wall-clock time, no threads).
 *
 * Rules of the simulated clock:
 *  - jobs enter the ready queue when their arrivalTime is reached;
 *  - if the queue is empty the clock jumps to the next arrival (idle time);
 *  - when a time slice ends, jobs that arrived during/at the end of the slice
 *    join the queue BEFORE the preempted job is put back (standard convention).
 */
public final class SchedulingSimulator {

    public SchedulingResult run(List<JobSpec> workload, SchedulingAlgorithm algorithm) {
        List<Job> notArrived = new ArrayList<>();
        for (JobSpec spec : workload) {
            notArrived.add(new Job(spec)); // fresh state per run
        }
        notArrived.sort(Comparator.comparingInt(Job::arrivalTime).thenComparingLong(Job::arrivalSeq));

        List<Job> ready = new ArrayList<>();
        List<Job> finished = new ArrayList<>();
        List<Slice> timeline = new ArrayList<>();
        int next = 0;   // index of the next job that has not arrived yet
        int clock = 0;

        while (finished.size() < notArrived.size()) {
            next = admitArrivals(notArrived, next, clock, ready);
            if (ready.isEmpty()) {
                clock = notArrived.get(next).arrivalTime(); // idle: jump to next arrival
                continue;
            }

            Job job = algorithm.selectNext(ready);
            ready.remove(job);

            int slice = Math.min(algorithm.timeSlice(job), job.remaining());
            job.runFor(clock, slice);
            timeline.add(new Slice(job.id(), clock, clock + slice));
            clock += slice;

            next = admitArrivals(notArrived, next, clock, ready);
            if (job.remaining() > 0) {
                ready.add(job);       // back of the queue
            } else {
                finished.add(job);
            }
        }

        List<JobResult> results = new ArrayList<>();
        for (Job j : notArrived) {
            int turnaround = j.completion() - j.arrivalTime();
            results.add(new JobResult(j.id(), j.priority(), j.arrivalTime(), j.spec().workUnits(),
                    j.firstStart(), j.completion(),
                    j.firstStart() - j.arrivalTime(),
                    turnaround - j.spec().workUnits(),
                    turnaround));
        }
        return new SchedulingResult(algorithm.name(), List.copyOf(timeline), List.copyOf(results));
    }

    private static int admitArrivals(List<Job> notArrived, int next, int clock, List<Job> ready) {
        while (next < notArrived.size() && notArrived.get(next).arrivalTime() <= clock) {
            ready.add(notArrived.get(next++));
        }
        return next;
    }
}
