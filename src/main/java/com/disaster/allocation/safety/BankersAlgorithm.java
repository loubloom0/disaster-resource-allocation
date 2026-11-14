package com.disaster.allocation.safety;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Banker's Algorithm for deadlock AVOIDANCE with several resource types.
 *
 * Data (n processes, m resource types):
 *   available[m]      units free right now
 *   max[n][m]         the most each process declared it could ever need
 *   allocation[n][m]  units each process holds now
 *   need[n][m]        max - allocation  (what each process may still ask for)
 *
 * HOW THIS APPLIES TO THE SIMULATOR
 *  - A "process" is an organization; its declared maximum claims are stored in
 *    the organization_claims table; allocation comes from ACTIVE allocations.
 *  - Only REUSABLE resources (ambulances, beds, staff) belong in this model:
 *    their units come back, so "a process finishes and returns its units"
 *    makes sense. Consumable stock (food, water, medicine) is checked only
 *    against inventory, because consumed units never return and are not a
 *    circular-wait risk in the classic sense.
 *
 * THREAD SAFETY: the shared matrices are guarded by one ReentrantLock (a mutex),
 * so two threads can never test-and-grant at the same time. This protects the
 * in-memory model only; stored data is protected by PostgreSQL transactions.
 */
public final class BankersAlgorithm {

    private final int[] available;
    private final int[][] max;
    private final int[][] allocation;
    private final int processes;
    private final int resourceTypes;
    private final ReentrantLock lock = new ReentrantLock();

    public BankersAlgorithm(int[] available, int[][] max, int[][] allocation) {
        this.processes = max.length;
        this.resourceTypes = available.length;
        if (allocation.length != processes) {
            throw new IllegalArgumentException("max and allocation must have the same number of processes");
        }
        this.available = available.clone();
        this.max = deepCopy(max);
        this.allocation = deepCopy(allocation);
        for (int i = 0; i < processes; i++) {
            if (max[i].length != resourceTypes || allocation[i].length != resourceTypes) {
                throw new IllegalArgumentException("row " + i + " must have " + resourceTypes + " columns");
            }
            for (int j = 0; j < resourceTypes; j++) {
                if (max[i][j] < 0 || allocation[i][j] < 0) {
                    throw new IllegalArgumentException("negative value at process " + i + ", resource " + j);
                }
                if (allocation[i][j] > max[i][j]) {
                    throw new IllegalArgumentException(
                            "process " + i + " holds more of resource " + j + " than its declared maximum");
                }
            }
        }
        for (int j = 0; j < resourceTypes; j++) {
            if (available[j] < 0) {
                throw new IllegalArgumentException("negative available units for resource " + j);
            }
        }
    }

    // ---------------------------------------------------------------- safety

    /** Safety algorithm on the CURRENT state. */
    public SafetyResult checkSafety() {
        lock.lock();
        try {
            return safety(available, need(), allocation);
        } finally {
            lock.unlock();
        }
    }

    /**
     * The safety algorithm itself.
     *  1. work = available; finish[i] = false for all i
     *  2. find an unfinished i with need[i] <= work (lowest index first, so the answer is deterministic)
     *  3. pretend i finishes and returns what it holds: work += allocation[i]; finish[i] = true; repeat
     *  4. safe  <=>  every process could finish
     */
    static SafetyResult safety(int[] available, int[][] need, int[][] allocation) {
        int n = need.length;
        int[] work = available.clone();
        boolean[] finish = new boolean[n];
        List<Integer> sequence = new ArrayList<>();

        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < n; i++) {
                if (!finish[i] && lessOrEqual(need[i], work)) {
                    for (int j = 0; j < work.length; j++) {
                        work[j] += allocation[i][j];
                    }
                    finish[i] = true;
                    sequence.add(i);
                    progress = true;
                    break; // restart the scan from process 0
                }
            }
        }

        if (sequence.size() == n) {
            return new SafetyResult(true, List.copyOf(sequence),
                    "Safe: every process can finish in the order " + sequence);
        }
        List<Integer> stuck = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!finish[i]) {
                stuck.add(i);
            }
        }
        return new SafetyResult(false, List.of(),
                "Unsafe: no process in " + stuck + " can be guaranteed to finish with the units that could be freed");
    }

    // --------------------------------------------------------------- request

    /** Evaluate a request WITHOUT changing any state. */
    public RequestDecision evaluateRequest(int process, int[] request) {
        lock.lock();
        try {
            return evaluate(process, request);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evaluate a request and, only if it is GRANTED, apply it. Test + grant happen
     * under one lock, so two threads cannot both pass the check for the last units.
     */
    public RequestDecision requestAndGrant(int process, int[] request) {
        lock.lock();
        try {
            RequestDecision decision = evaluate(process, request);
            if (decision.granted()) {
                for (int j = 0; j < resourceTypes; j++) {
                    available[j] -= request[j];
                    allocation[process][j] += request[j];
                }
            }
            return decision;
        } finally {
            lock.unlock();
        }
    }

    /** Resource-request algorithm (must be called while holding the lock). */
    private RequestDecision evaluate(int process, int[] request) {
        if (process < 0 || process >= processes) {
            throw new IllegalArgumentException("unknown process " + process);
        }
        if (request.length != resourceTypes) {
            throw new IllegalArgumentException("request must have " + resourceTypes + " entries");
        }
        for (int j = 0; j < resourceTypes; j++) {
            if (request[j] < 0) {
                throw new IllegalArgumentException("request cannot be negative (resource " + j + ")");
            }
        }

        int[][] need = need();

        // Step 1: request must not exceed what the process still may need.
        for (int j = 0; j < resourceTypes; j++) {
            if (request[j] > need[process][j]) {
                return new RequestDecision(RequestDecision.Outcome.EXCEEDS_MAX_CLAIM,
                        "Process " + process + " asked for " + request[j] + " of resource " + j
                                + " but its remaining declared need is only " + need[process][j], List.of());
            }
        }
        // Step 2: enough units must be free right now.
        for (int j = 0; j < resourceTypes; j++) {
            if (request[j] > available[j]) {
                return new RequestDecision(RequestDecision.Outcome.INSUFFICIENT_AVAILABLE,
                        "Only " + available[j] + " of resource " + j + " available, " + request[j]
                                + " requested: request must wait", List.of());
            }
        }
        // Step 3: pretend to allocate on COPIES, then run the safety algorithm.
        int[] newAvailable = available.clone();
        int[][] newAllocation = deepCopy(allocation);
        int[][] newNeed = deepCopy(need);
        for (int j = 0; j < resourceTypes; j++) {
            newAvailable[j] -= request[j];
            newAllocation[process][j] += request[j];
            newNeed[process][j] -= request[j];
        }
        SafetyResult after = safety(newAvailable, newNeed, newAllocation);
        if (!after.safe()) {
            return new RequestDecision(RequestDecision.Outcome.UNSAFE,
                    "Units are free, but granting them would leave the system in an UNSAFE state ("
                            + after.explanation() + "): request must wait", List.of());
        }
        return new RequestDecision(RequestDecision.Outcome.GRANTED,
                "Granted: the system stays safe, e.g. order " + after.safeSequence(), after.safeSequence());
    }

    // ------------------------------------------------------------- release

    /** A process returns units (e.g. when its request completes). */
    public void release(int process, int[] units) {
        lock.lock();
        try {
            for (int j = 0; j < resourceTypes; j++) {
                if (units[j] < 0 || units[j] > allocation[process][j]) {
                    throw new IllegalArgumentException(
                            "process " + process + " cannot release " + units[j] + " of resource " + j);
                }
            }
            for (int j = 0; j < resourceTypes; j++) {
                allocation[process][j] -= units[j];
                available[j] += units[j];
            }
        } finally {
            lock.unlock();
        }
    }

    // --------------------------------------------------------------- views

    /** Need = Max - Allocation (a fresh copy). */
    public int[][] need() {
        lock.lock(); // reentrant: safe to call from methods that already hold the lock
        try {
            int[][] need = new int[processes][resourceTypes];
            for (int i = 0; i < processes; i++) {
                for (int j = 0; j < resourceTypes; j++) {
                    need[i][j] = max[i][j] - allocation[i][j];
                }
            }
            return need;
        } finally {
            lock.unlock();
        }
    }

    public int[] available() {
        lock.lock();
        try {
            return available.clone();
        } finally {
            lock.unlock();
        }
    }

    public int[][] allocation() {
        lock.lock();
        try {
            return deepCopy(allocation);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------- helpers

    private static boolean lessOrEqual(int[] a, int[] b) {
        for (int j = 0; j < a.length; j++) {
            if (a[j] > b[j]) {
                return false;
            }
        }
        return true;
    }

    private static int[][] deepCopy(int[][] m) {
        return Arrays.stream(m).map(int[]::clone).toArray(int[][]::new);
    }
}
