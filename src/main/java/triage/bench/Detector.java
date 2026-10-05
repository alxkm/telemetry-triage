package triage.bench;

import com.thealgorithms.streaming.CusumDetector;
import com.thealgorithms.streaming.WelfordAlgorithm;
import triage.core.Cause;
import triage.core.ChannelSpec;
import triage.core.Decision;
import triage.core.TriageChannel;
import triage.core.TriageConfig;

/** A streaming detector under evaluation. Baselines detect only; they report {@link Cause#UNDETERMINED}. */
public interface Detector {
    /** Feeds one sample; returns a decision when one is reached on this sample. */
    Decision step(double value, double rate);

    /** Whether the detector attributes causes (baselines do not). */
    boolean attributes();

    String name();

    /** The method of the paper on one channel, or with the rate channel when {@code useRate}. */
    static Detector triage(ChannelSpec spec, TriageConfig cfg, boolean useRate) {
        TriageChannel channel = new TriageChannel(spec, cfg);
        return new Detector() {
            @Override
            public Decision step(double value, double rate) {
                return useRate ? channel.update(value, rate) : channel.update(value);
            }

            @Override
            public boolean attributes() {
                return true;
            }

            @Override
            public String name() {
                return useRate ? "triage (level + rate)" : "triage (level only)";
            }
        };
    }

    /** Rolling z-score: |y − mean| / sd over the trailing window, alarm above the threshold, then a hold-off. */
    static Detector rollingZ(int calibration, int window, double threshold, int holdoff) {
        return new Detector() {
            final double[] ring = new double[window];
            final WelfordAlgorithm moments = new WelfordAlgorithm();
            int head;
            int filled;
            long t = -1;
            long quietUntil = -1;

            @Override
            public Decision step(double value, double rate) {
                t++;
                if (!Double.isFinite(value)) {
                    return null;
                }
                Decision d = null;
                if (t >= calibration && filled == window && t >= quietUntil) {
                    double sd = moments.sampleStandardDeviation();
                    if (sd > 0 && Math.abs(value - moments.mean()) / sd > threshold) {
                        d = new Decision(Cause.UNDETERMINED, t, t, java.util.List.of("z above threshold"));
                        quietUntil = t + holdoff;
                    }
                }
                if (filled == window) {
                    moments.remove(ring[head]);
                } else {
                    filled++;
                }
                ring[head] = value;
                head = (head + 1) % window;
                moments.add(value);
                return d;
            }

            @Override
            public boolean attributes() {
                return false;
            }

            @Override
            public String name() {
                return "rolling z-score";
            }
        };
    }

    /**
     * Two-sided CUSUM on the raw value, standardized by the calibration σ; after each alarm the reference is
     * re-learned from the mean of the next {@code holdoff} samples.
     */
    static Detector restartedCusum(int calibration, double allowance, double threshold, int holdoff) {
        return new Detector() {
            final WelfordAlgorithm calibrationMoments = new WelfordAlgorithm();
            final WelfordAlgorithm relearn = new WelfordAlgorithm();
            CusumDetector cusum;
            double sd;
            long t = -1;
            long relearnUntil = -1;

            @Override
            public Decision step(double value, double rate) {
                t++;
                if (!Double.isFinite(value)) {
                    return null;
                }
                if (t < calibration) {
                    calibrationMoments.add(value);
                    return null;
                }
                if (cusum == null) {
                    sd = Math.max(calibrationMoments.sampleStandardDeviation(), 1e-12);
                    cusum = new CusumDetector(calibrationMoments.mean(), sd, allowance, threshold);
                }
                if (t < relearnUntil) {
                    relearn.add(value);
                    if (t == relearnUntil - 1) {
                        cusum = new CusumDetector(relearn.mean(), sd, allowance, threshold);
                    }
                    return null;
                }
                if (cusum.accept(value).isAlarm()) {
                    relearn.clear();
                    relearnUntil = t + 1 + holdoff;
                    return new Decision(Cause.UNDETERMINED, t, t, java.util.List.of("CUSUM alarm"));
                }
                return null;
            }

            @Override
            public boolean attributes() {
                return false;
            }

            @Override
            public String name() {
                return "restarted CUSUM";
            }
        };
    }
}
