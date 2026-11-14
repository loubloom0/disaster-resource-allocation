package com.disaster.allocation.safety;

import java.util.List;

/**
 * Answer of the Banker's resource-request algorithm.
 *
 * @param outcome      what should happen to the request
 * @param explanation  plain-language reason (shown to the user)
 * @param safeSequence safe order that proves the state stays safe (only when GRANTED)
 */
public record RequestDecision(Outcome outcome, String explanation, List<Integer> safeSequence) {

    public enum Outcome {
        /** Request is within the claim, available, and leaves the system safe. */
        GRANTED,
        /** Process asked for more than it declared it would ever need (an error, not a wait). */
        EXCEEDS_MAX_CLAIM,
        /** Not enough units free right now: the request must wait. */
        INSUFFICIENT_AVAILABLE,
        /** Units are free, but granting them could lead to deadlock: the request must wait. */
        UNSAFE
    }

    public boolean granted() {
        return outcome == Outcome.GRANTED;
    }
}
