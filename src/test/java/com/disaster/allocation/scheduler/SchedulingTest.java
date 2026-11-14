package com.disaster.allocation.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.disaster.allocation.scheduler.SchedulingResult.JobResult;
import com.disaster.allocation.scheduler.SchedulingResult.Slice;

class SchedulingTest {

    private final SchedulingSimulator simulator = new SchedulingSimulator();

    /** Textbook workload: bursts 24, 3, 3 all arriving at time 0. */
    private static List<JobSpec> textbook() {
        return List.of(
                new JobSpec(1, 3, 1, 0, 24),
                new JobSpec(2, 3, 2, 0, 3),
                new JobSpec(3, 3, 3, 0, 3));
    }

    private static JobResult job(SchedulingResult r, long id) {
        return r.jobs().stream().filter(j -> j.id() == id).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------- FCFS

    @Test
    void fcfsServesInArrivalOrderAndMatchesTextbookWaitingTimes() {
        SchedulingResult r = simulator.run(textbook(), new FcfsScheduler());
        assertEquals(List.of(1L, 2L, 3L), r.completionOrder());
        assertEquals(0, job(r, 1).waiting());
        assertEquals(24, job(r, 2).waiting());
        assertEquals(27, job(r, 3).waiting());
        assertEquals(17.0, r.averageWaiting(), 1e-9);
    }

    @Test
    void fcfsBreaksArrivalTimeTiesByArrivalSequence() {
        // Same arrival time, listed in the "wrong" order: arrivalSeq decides.
        List<JobSpec> workload = List.of(
                new JobSpec(20, 1, 2, 0, 1),
                new JobSpec(10, 1, 1, 0, 1));
        assertEquals(List.of(10L, 20L), simulator.run(workload, new FcfsScheduler()).completionOrder());
    }

    // --------------------------------------------------------- Priority

    @Test
    void priorityRunsHighestPriorityFirstAndBreaksTiesByArrival() {
        List<JobSpec> workload = List.of(
                new JobSpec(1, 2, 1, 0, 5),
                new JobSpec(2, 5, 2, 0, 3),
                new JobSpec(3, 5, 3, 0, 2),   // same priority as job 2, arrived later
                new JobSpec(4, 1, 4, 0, 1));
        SchedulingResult r = simulator.run(workload, new PriorityScheduler());
        assertEquals(List.of(2L, 3L, 1L, 4L), r.completionOrder());
        assertEquals(0, job(r, 2).response());
        assertEquals(3, job(r, 3).response());
        assertEquals(5, job(r, 1).response());
        assertEquals(10, job(r, 4).response());
    }

    @Test
    void priorityGivesLifeThreateningRequestsFasterResponseThanFcfs() {
        List<JobSpec> workload = List.of(
                new JobSpec(1, 1, 1, 0, 2),
                new JobSpec(2, 1, 2, 0, 2),
                new JobSpec(3, 5, 3, 0, 2));
        double fcfs = simulator.run(workload, new FcfsScheduler()).averageResponseForPriority(5);
        double prio = simulator.run(workload, new PriorityScheduler()).averageResponseForPriority(5);
        assertEquals(4.0, fcfs, 1e-9);
        assertEquals(0.0, prio, 1e-9);
    }

    // ------------------------------------------------------ Round Robin

    @Test
    void roundRobinMatchesTextbookExampleWithQuantum4() {
        SchedulingResult r = simulator.run(textbook(), new RoundRobinScheduler(4));
        assertEquals(List.of(2L, 3L, 1L), r.completionOrder());
        assertEquals(6, job(r, 1).waiting());
        assertEquals(4, job(r, 2).waiting());
        assertEquals(7, job(r, 3).waiting());
        assertEquals(17.0 / 3.0, r.averageWaiting(), 1e-9);
        assertEquals(30, r.makespan());
    }

    @Test
    void roundRobinAdmitsJobsArrivingDuringASliceBeforeRequeueingThePreemptedJob() {
        List<JobSpec> workload = List.of(
                new JobSpec(1, 3, 1, 0, 4),
                new JobSpec(2, 3, 2, 2, 2));
        SchedulingResult r = simulator.run(workload, new RoundRobinScheduler(2));
        assertEquals(List.of(new Slice(1, 0, 2), new Slice(2, 2, 4), new Slice(1, 4, 6)), r.timeline());
        assertEquals(List.of(2L, 1L), r.completionOrder());
    }

    @Test
    void roundRobinWithHugeQuantumBehavesLikeFcfs() {
        SchedulingResult rr = simulator.run(textbook(), new RoundRobinScheduler(1_000));
        SchedulingResult fcfs = simulator.run(textbook(), new FcfsScheduler());
        assertEquals(fcfs.jobs(), rr.jobs());
    }

    @Test
    void roundRobinRejectsNonPositiveQuantum() {
        assertThrows(IllegalArgumentException.class, () -> new RoundRobinScheduler(0));
        assertThrows(IllegalArgumentException.class, () -> new RoundRobinScheduler(-3));
    }

    // ------------------------------------------------------ Simulation

    @Test
    void clockJumpsOverIdleTimeBetweenArrivals() {
        List<JobSpec> workload = List.of(
                new JobSpec(1, 3, 1, 0, 2),
                new JobSpec(2, 3, 2, 10, 3));
        SchedulingResult r = simulator.run(workload, new FcfsScheduler());
        assertEquals(0, job(r, 2).response());
        assertEquals(0, job(r, 2).waiting());
        assertEquals(13, r.makespan());
    }

    @Test
    void emptyWorkloadGivesEmptyResult() {
        SchedulingResult r = simulator.run(List.of(), new FcfsScheduler());
        assertTrue(r.jobs().isEmpty());
        assertEquals(0.0, r.averageWaiting(), 1e-9);
        assertEquals(0, r.makespan());
    }

    @Test
    void sameInputGivesIdenticalResultEveryTime() {
        List<JobSpec> workload = randomWorkload(42, 100);
        for (SchedulingAlgorithm algorithm : algorithms()) {
            assertEquals(simulator.run(workload, algorithm), simulator.run(workload, algorithm),
                    algorithm.name() + " must be deterministic");
        }
    }

    @Test
    void invariantsHoldForEveryAlgorithmOnALargeRandomWorkload() {
        List<JobSpec> workload = randomWorkload(7, 500);
        int totalWork = workload.stream().mapToInt(JobSpec::workUnits).sum();

        for (SchedulingAlgorithm algorithm : algorithms()) {
            SchedulingResult r = simulator.run(workload, algorithm);
            String who = algorithm.name();

            assertEquals(workload.size(), r.jobs().size(), who + ": every request completes");

            // Processor never runs two things at once, never goes backwards.
            int lastEnd = 0;
            int busy = 0;
            for (Slice s : r.timeline()) {
                assertTrue(s.start() >= lastEnd, who + ": slices must not overlap");
                assertTrue(s.end() > s.start(), who + ": slices must have positive length");
                busy += s.end() - s.start();
                lastEnd = s.end();
            }
            assertEquals(totalWork, busy, who + ": total processing equals total work");

            for (JobResult j : r.jobs()) {
                assertTrue(j.firstStart() >= j.arrivalTime(), who + ": cannot start before arrival");
                assertTrue(j.response() >= 0 && j.waiting() >= 0, who + ": no negative times");
                assertEquals(j.turnaround(), j.waiting() + j.workUnits(), who + ": turnaround = waiting + work");
            }
        }
    }

    @Test
    void invalidJobSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JobSpec(1, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JobSpec(1, 6, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JobSpec(1, 3, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new JobSpec(1, 3, 1, 0, 0));
    }

    // ---------------------------------------------------------- helpers

    private static List<SchedulingAlgorithm> algorithms() {
        return List.of(new FcfsScheduler(), new PriorityScheduler(), new RoundRobinScheduler(3));
    }

    /** Repeatable workload: the same seed always produces the same requests. */
    private static List<JobSpec> randomWorkload(long seed, int size) {
        Random random = new Random(seed);
        List<JobSpec> list = new ArrayList<>();
        int arrival = 0;
        for (int i = 1; i <= size; i++) {
            arrival += random.nextInt(4);                // 0..3 units between arrivals
            list.add(new JobSpec(i, 1 + random.nextInt(5), i, arrival, 1 + random.nextInt(8)));
        }
        return list;
    }
}
