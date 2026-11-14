package com.disaster.allocation.scheduler;

import java.util.List;

/**
 * Strategy interface: a scheduling algorithm only decides ORDER and TIME SLICE.
 *
 * It never looks at inventory and never allocates anything. Whether a request
 * can really be given units is decided later by the allocation engine and the
 * Banker's safety check, so no scheduler can override a resource constraint.
 *
 * New strategies can be added by implementing this interface; the simulator and
 * allocation engine do not change.
 */
public interface SchedulingAlgorithm {

    String name();

    /**
     * Pick the job that runs next.
     *
     * @param ready non-empty list of jobs that have arrived and are not finished,
     *              in queue order (a job that was preempted sits at the back)
     */
    Job selectNext(List<Job> ready);

    /** How many work units the chosen job may run before the scheduler looks again. */
    int timeSlice(Job job);
}
