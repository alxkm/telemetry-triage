package triage.bench;

import triage.core.ChannelSpec;

/** Fixed protocol constants of the synthetic benchmark. */
public final class Bench {
    public static final int LENGTH = 20_000;
    /** Δ = 40; the gap between events is at least 3Δ plus a margin for the process to settle. */
    public static final int GAP = 420;
    public static final int TAIL = 120;
    public static final int WARMUP = 600;
    public static final long DEV_SEED_FROM = 1000;
    public static final long DEV_SEED_TO = 1020;
    public static final long TEST_SEED_FROM = 2000;
    public static final long TEST_SEED_TO = 2050;

    private Bench() {
    }

    /** What an engineer would declare for the channel: instrument range, process rate bound, zero as boundary. */
    public static ChannelSpec spec(ProcessModel m) {
        return new ChannelSpec(m.name().toLowerCase(), m.rangeMin(), m.rangeMax(), m.plausibleRate(), new double[] {0.0});
    }
}
