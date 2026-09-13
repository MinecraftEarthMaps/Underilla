package fr.formiko.mc.underilla.paper.impl;

/** Exact inverse of Paper's float conversion of Minecraft's quantized climate coordinates. */
final class ClimateCoordinates {
    static final long UNSUPPORTED = Long.MIN_VALUE;
    private ClimateCoordinates() {}

    static long recover(double value) {
        // Within this bound, integer -> float is exact and division error is < 0.04
        // quantization units. Rounding recovers the original integer, unlike truncation.
        // Reject other representations and larger/custom values instead of approximating.
        if (!Double.isFinite(value) || Math.abs(value) > 100.0) return UNSUPPORTED;
        long quantized = Math.round(value * 10000.0);
        return (double) ((float) quantized / 10000.0F) == value ? quantized : UNSUPPORTED;
    }
}
