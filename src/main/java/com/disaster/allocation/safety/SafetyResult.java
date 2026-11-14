package com.disaster.allocation.safety;

import java.util.List;

/**
 * Result of the safety algorithm.
 *
 * @param safe         true if every process can finish in some order
 * @param safeSequence one valid finishing order (process indexes); empty when unsafe
 * @param explanation  plain-language reason
 */
public record SafetyResult(boolean safe, List<Integer> safeSequence, String explanation) { }
