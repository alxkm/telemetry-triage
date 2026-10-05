package triage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TriageChannelTest {
    private static final ChannelSpec SPEC = new ChannelSpec("level", 0, 400, 0.075, new double[] {0.0});

    private static double[] noise(long seed, int n) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = 200 + 0.5 * rnd.nextGaussian();
        }
        return y;
    }

    private static List<Decision> run(double[] y) {
        TriageChannel ch = new TriageChannel(SPEC, TriageConfig.defaults());
        for (double v : y) {
            ch.update(v);
        }
        return ch.decisions();
    }

    @Test
    void calibratesNoiseFromFirstDifferences() {
        TriageChannel ch = new TriageChannel(SPEC, TriageConfig.defaults());
        for (double v : noise(1, 700)) {
            ch.update(v);
        }
        assertEquals(0.5, ch.sigma(), 0.06);
    }

    @Test
    void cleanNoiseRaisesAtMostOneDecisionPerHour() {
        double[] y = noise(2, 3600 * 3 + 600);
        assertTrue(run(y).size() <= 3, "decisions on clean noise: " + run(y));
    }

    @Test
    void missingValuesAreAttributedToTheDataPath() {
        double[] y = noise(3, 2000);
        for (int i = 1000; i < 1010; i++) {
            y[i] = Double.NaN;
        }
        Decision d = run(y).get(0);
        assertEquals(Cause.DATA_PATH, d.cause());
        assertEquals(1000, d.triggerIndex());
    }

    @Test
    void stuckValueIsAttributedToTheInstrument() {
        double[] y = noise(4, 2000);
        for (int i = 1000; i < 1100; i++) {
            y[i] = y[999];
        }
        Decision d = run(y).get(0);
        assertEquals(Cause.INSTRUMENT, d.cause());
    }

    @Test
    void instantaneousBiasStepIsAttributedToTheInstrument() {
        double[] y = noise(5, 2000);
        for (int i = 1000; i < y.length; i++) {
            y[i] += 8 * 0.5;
        }
        Decision d = run(y).get(0);
        assertEquals(Cause.INSTRUMENT, d.cause());
    }

    @Test
    void rateLimitedLevelShiftIsAttributedToTheProcess() {
        double[] y = noise(6, 2000);
        double level = 0;
        for (int i = 1000; i < y.length; i++) {
            level = Math.min(8 * 0.5, level + 0.075);
            y[i] += level;
        }
        Decision d = run(y).get(0);
        assertEquals(Cause.PROCESS, d.cause());
    }

    @Test
    void replayedSegmentIsAttributedToTheDataPath() {
        double[] y = noise(7, 2000);
        for (int i = 1000; i < 1020; i++) {
            y[i] = y[i - 100];
        }
        Decision d = run(y).get(0);
        assertEquals(Cause.DATA_PATH, d.cause());
    }

    @Test
    void stateSizeDoesNotDependOnStreamLength() {
        TriageChannel ch = new TriageChannel(SPEC, TriageConfig.defaults());
        int before = ch.fixedStateDoubles();
        for (double v : noise(8, 50_000)) {
            ch.update(v);
        }
        assertEquals(before, ch.fixedStateDoubles());
        assertTrue(ch.adwinBuckets() < 200);
    }
}
