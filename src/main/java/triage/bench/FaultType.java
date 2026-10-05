package triage.bench;

import triage.core.Cause;

/** The injection catalogue (Table 4). Every type carries its ground-truth cause. */
public enum FaultType {
    LEVEL_SHIFT(Cause.PROCESS, "process level shift (rate-limited)"),
    RAMP(Cause.PROCESS, "process ramp"),
    REGIME_CHANGE(Cause.PROCESS, "process regime change (new operating point, oscillation)"),
    BIAS_STEP(Cause.INSTRUMENT, "sensor bias step"),
    LINEAR_DRIFT(Cause.INSTRUMENT, "sensor linear drift"),
    NOISE_GROWTH(Cause.INSTRUMENT, "sensor noise growth"),
    STUCK_AT_LAST(Cause.INSTRUMENT, "sensor stuck at last value"),
    SATURATION(Cause.INSTRUMENT, "sensor saturation"),
    SPIKE(Cause.DATA_PATH, "single corrupted sample"),
    SUBSTITUTION(Cause.DATA_PATH, "burst of 3 substituted samples"),
    REPLAY(Cause.DATA_PATH, "replayed segment of earlier values"),
    ZERO_OR_BOUNDARY(Cause.DATA_PATH, "zero / boundary value"),
    DROPOUT(Cause.DATA_PATH, "missing samples (NaN)");

    private final Cause cause;
    private final String label;

    FaultType(Cause cause, String label) {
        this.cause = cause;
        this.label = label;
    }

    public Cause cause() {
        return cause;
    }

    public String label() {
        return label;
    }
}
