package com.disaster.allocation.safety;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.disaster.allocation.safety.RequestDecision.Outcome;

/**
 * Uses the classic textbook example (Silberschatz et al.): 5 processes, 3 resource
 * types A, B, C with 10, 5 and 7 units in total.
 */
class BankersAlgorithmTest {

    private static BankersAlgorithm textbook() {
        return new BankersAlgorithm(
                new int[] {3, 3, 2},
                new int[][] {{7, 5, 3}, {3, 2, 2}, {9, 0, 2}, {2, 2, 2}, {4, 3, 3}},
                new int[][] {{0, 1, 0}, {2, 0, 0}, {3, 0, 2}, {2, 1, 1}, {0, 0, 2}});
    }

    @Test
    void needMatrixIsMaxMinusAllocation() {
        int[][] need = textbook().need();
        assertArrayEquals(new int[] {7, 4, 3}, need[0]);
        assertArrayEquals(new int[] {1, 2, 2}, need[1]);
        assertArrayEquals(new int[] {6, 0, 0}, need[2]);
        assertArrayEquals(new int[] {0, 1, 1}, need[3]);
        assertArrayEquals(new int[] {4, 3, 1}, need[4]);
    }

    @Test
    void textbookInitialStateIsSafeWithAValidSequence() {
        BankersAlgorithm banker = textbook();
        SafetyResult result = banker.checkSafety();
        assertTrue(result.safe());
        assertEquals(List.of(1, 3, 0, 2, 4), result.safeSequence()); // deterministic: lowest index first
        assertTrue(sequenceReallyWorks(banker, result.safeSequence()),
                "replaying the sequence must let every process finish");
    }

    @Test
    void requestWithinNeedAndSafeIsGrantedAndChangesState() {
        BankersAlgorithm banker = textbook();
        RequestDecision d = banker.requestAndGrant(1, new int[] {1, 0, 2});
        assertEquals(Outcome.GRANTED, d.outcome());
        assertArrayEquals(new int[] {2, 3, 0}, banker.available());
        assertArrayEquals(new int[] {3, 0, 2}, banker.allocation()[1]);
        assertTrue(banker.checkSafety().safe());
    }

    @Test
    void requestExceedingAvailableMustWait() {
        BankersAlgorithm banker = textbook();
        banker.requestAndGrant(1, new int[] {1, 0, 2});
        RequestDecision d = banker.requestAndGrant(4, new int[] {3, 3, 0});
        assertEquals(Outcome.INSUFFICIENT_AVAILABLE, d.outcome());
        assertArrayEquals(new int[] {2, 3, 0}, banker.available(), "a refused request changes nothing");
    }

    @Test
    void requestThatWouldLeaveAnUnsafeStateIsRejectedEvenThoughUnitsAreFree() {
        BankersAlgorithm banker = textbook();
        banker.requestAndGrant(1, new int[] {1, 0, 2});
        int[] availableBefore = banker.available();

        RequestDecision d = banker.requestAndGrant(0, new int[] {0, 2, 0});

        assertEquals(Outcome.UNSAFE, d.outcome()); // units are free (available B = 3) but it is unsafe
        assertTrue(d.explanation().contains("UNSAFE"));
        assertArrayEquals(availableBefore, banker.available(), "an unsafe request must not be applied");
        assertTrue(banker.checkSafety().safe(), "the real state is still safe");
    }

    @Test
    void requestAboveDeclaredMaximumClaimIsAnError() {
        BankersAlgorithm banker = textbook();
        RequestDecision d = banker.requestAndGrant(3, new int[] {3, 0, 0}); // P3 need is (0,1,1)
        assertEquals(Outcome.EXCEEDS_MAX_CLAIM, d.outcome());
    }

    @Test
    void evaluateRequestNeverChangesState() {
        BankersAlgorithm banker = textbook();
        RequestDecision d = banker.evaluateRequest(1, new int[] {1, 0, 2});
        assertTrue(d.granted());
        assertArrayEquals(new int[] {3, 3, 2}, banker.available());
        assertArrayEquals(new int[] {2, 0, 0}, banker.allocation()[1]);
    }

    @Test
    void anUnsafeInitialStateIsDetected() {
        // 1 resource type, nothing free, two processes each still needing 1: nobody can finish.
        BankersAlgorithm banker = new BankersAlgorithm(
                new int[] {0}, new int[][] {{2}, {2}}, new int[][] {{1}, {1}});
        SafetyResult result = banker.checkSafety();
        assertFalse(result.safe());
        assertTrue(result.safeSequence().isEmpty());
    }

    @Test
    void releaseReturnsUnitsToAvailable() {
        BankersAlgorithm banker = textbook();
        banker.release(3, new int[] {2, 1, 1});
        assertArrayEquals(new int[] {5, 4, 3}, banker.available());
        assertArrayEquals(new int[] {0, 0, 0}, banker.allocation()[3]);
        assertThrows(IllegalArgumentException.class, () -> banker.release(3, new int[] {1, 0, 0}));
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BankersAlgorithm(
                new int[] {1}, new int[][] {{1}}, new int[][] {{2}}));          // holds more than max
        assertThrows(IllegalArgumentException.class, () -> new BankersAlgorithm(
                new int[] {-1}, new int[][] {{1}}, new int[][] {{0}}));         // negative available
        assertThrows(IllegalArgumentException.class, () -> new BankersAlgorithm(
                new int[] {1, 1}, new int[][] {{1}}, new int[][] {{0}}));       // wrong width
        BankersAlgorithm banker = textbook();
        assertThrows(IllegalArgumentException.class, () -> banker.requestAndGrant(9, new int[] {0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> banker.requestAndGrant(0, new int[] {-1, 0, 0}));
    }

    /** Many threads race for the last units: the grant count must never exceed what exists. */
    @Test
    void concurrentRequestsNeverGrantMoreThanAvailable() throws Exception {
        final int processes = 50;
        final int units = 10;
        int[][] max = new int[processes][1];
        int[][] alloc = new int[processes][1];
        for (int i = 0; i < processes; i++) {
            max[i][0] = 1;
        }
        BankersAlgorithm banker = new BankersAlgorithm(new int[] {units}, max, alloc);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < processes; i++) {
            final int process = i;
            results.add(pool.submit(() -> {
                startGun.await();
                return banker.requestAndGrant(process, new int[] {1}).granted();
            }));
        }
        startGun.countDown();
        int granted = 0;
        for (Future<Boolean> f : results) {
            if (f.get(10, TimeUnit.SECONDS)) {
                granted++;
            }
        }
        pool.shutdown();

        assertEquals(units, granted, "exactly the available units are granted, no more, no less");
        assertArrayEquals(new int[] {0}, banker.available());
        int held = Arrays.stream(banker.allocation()).mapToInt(row -> row[0]).sum();
        assertEquals(units, held, "units held + units free must equal the total");
    }

    // ---------------------------------------------------------- helpers

    /** Independent check: replay the sequence by hand and see that each process can finish in turn. */
    private static boolean sequenceReallyWorks(BankersAlgorithm banker, List<Integer> sequence) {
        int[] work = banker.available();
        int[][] need = banker.need();
        int[][] allocation = banker.allocation();
        for (int p : sequence) {
            for (int j = 0; j < work.length; j++) {
                if (need[p][j] > work[j]) {
                    return false;
                }
            }
            for (int j = 0; j < work.length; j++) {
                work[j] += allocation[p][j];
            }
        }
        return sequence.size() == need.length;
    }
}
