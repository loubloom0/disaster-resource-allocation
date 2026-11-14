package com.disaster.allocation.scheduler;

/**
 * A request while it is being scheduled: the immutable {@link JobSpec} plus the
 * running state of one simulation. A fresh Job is created for every run, so the
 * same workload can be replayed under each algorithm without interference.
 */
public final class Job {

    private final JobSpec spec;
    private int remaining;
    private int firstStart = -1;
    private int completion = -1;

    Job(JobSpec spec) {
        this.spec = spec;
        this.remaining = spec.workUnits();
    }

    public JobSpec spec()        { return spec; }
    public long id()             { return spec.id(); }
    public int priority()        { return spec.priority(); }
    public long arrivalSeq()     { return spec.arrivalSeq(); }
    public int arrivalTime()     { return spec.arrivalTime(); }
    public int remaining()       { return remaining; }
    public int firstStart()      { return firstStart; }
    public int completion()      { return completion; }

    // Only the simulator (same package) changes a job's progress.
    void runFor(int startTime, int units) {
        if (firstStart < 0) {
            firstStart = startTime;
        }
        remaining -= units;
        if (remaining == 0) {
            completion = startTime + units;
        }
    }
}
