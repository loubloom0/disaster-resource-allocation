package com.disaster.allocation.scheduler;

/**
 * Immutable description of one request as the scheduler sees it.
 *
 * Time here is SIMULATED time in abstract "work units", not wall-clock time.
 * Resource requests are not CPU bursts, so we model each request as needing
 * {@code workUnits} units of processing (verification, dispatch, handover...).
 * This lets us compute waiting / turnaround / response times the same way an
 * Operating Systems textbook does for CPU scheduling.
 *
 * @param id          unique request id
 * @param priority    1 (low) .. 5 (life-threatening)
 * @param arrivalSeq  strictly increasing arrival number; breaks ties deterministically
 * @param arrivalTime simulated arrival time (>= 0)
 * @param workUnits   simulated processing time needed (> 0)
 */
public record JobSpec(long id, int priority, long arrivalSeq, int arrivalTime, int workUnits) {

    public JobSpec {
        if (priority < 1 || priority > 5) {
            throw new IllegalArgumentException("priority must be between 1 and 5, got " + priority);
        }
        if (arrivalTime < 0) {
            throw new IllegalArgumentException("arrivalTime must be >= 0, got " + arrivalTime);
        }
        if (workUnits <= 0) {
            throw new IllegalArgumentException("workUnits must be > 0, got " + workUnits);
        }
    }
}
