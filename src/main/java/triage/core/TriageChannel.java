package triage.core;

import com.thealgorithms.datastructures.buffers.SlidingWindowAggregator;
import com.thealgorithms.streaming.Adwin;
import com.thealgorithms.streaming.ComplementaryFilter;
import com.thealgorithms.streaming.CusumDetector;
import com.thealgorithms.streaming.EwmaChangeDetector;
import com.thealgorithms.streaming.GeneralizedEsd;
import com.thealgorithms.streaming.HampelFilter;
import com.thealgorithms.streaming.KalmanFilter;
import com.thealgorithms.streaming.P2QuantileEstimator;
import com.thealgorithms.streaming.WelfordAlgorithm;
import java.util.ArrayList;
import java.util.List;

/**
 * One telemetry channel: a bank of online detectors and the attribution rule of the paper (Table 2).
 *
 * <p>Pipeline per sample: range guard → replay / stuck / saturation checks on the raw value → Hampel filter
 * (outliers confirmed by GESD) → scalar Kalman tracker on the cleaned value → CUSUM and EWMA on the standardized
 * innovation → Welford variance and ADWIN on the standardized innovation. The first detector to fire opens a
 * decision window of Δ samples; the rule then attributes the event to a cause, deferring while the level is still
 * moving, up to the drift horizon. An optional second channel (a rate) drives a complementary filter whose
 * divergence from the level separates sensor drift from a process trend.
 *
 * <p>Memory per channel is bounded by the configured windows and independent of stream length.
 */
public final class TriageChannel {
    private static final int RANGE = 1;
    private static final int REPLAY = 2;
    private static final int SATURATION = 4;
    private static final int STUCK = 8;
    private static final int OUTLIER = 16;
    private static final int NOISE = 32;
    private static final int CHANGE = 64;
    /** E|X| / median|X| for a centred normal X: converts the ADWIN mean of |Δy| to the median scale. */
    private static final double MEAN_ABS_OVER_MEDIAN_ABS = Math.sqrt(2.0 / Math.PI) / 0.6744897501960817;
    private static final double LN2_SQRT = 0.6744897501960817 * Math.sqrt(2.0);

    private final ChannelSpec spec;
    private final TriageConfig cfg;

    // calibration
    private long index = -1;
    private final P2QuantileEstimator absDiffMedian = new P2QuantileEstimator(0.5);
    private final WelfordAlgorithm calibrationMoments = new WelfordAlgorithm();
    private long zeroDiffs;
    private long diffs;
    private double previousRaw = Double.NaN;
    private double sigma = Double.NaN;
    private boolean quantized;
    private P2QuantileEstimator varianceQuantile;
    private P2QuantileEstimator outlierQuantile;
    private double outlierThreshold = Double.NaN;
    private long calibrationRepeats;
    private double varianceThreshold = Double.NaN;

    // detectors
    private HampelFilter hampel;
    private final GeneralizedEsd gesd;
    private KalmanFilter kalman;
    private CusumDetector cusum;
    private EwmaChangeDetector ewma;
    private final DoubleRing absDiffs;
    private double noiseRatio = 1.0;
    private Adwin adwin;
    private final SlidingWindowAggregator<Double, Double> windowMax;
    private final SlidingWindowAggregator<Double, Double> windowMin;
    private ComplementaryFilter complementary;

    // history
    private final DoubleRing raw;
    private final DoubleRing cleaned;
    private final DoubleRing divergence;
    private final double[] gesdBuffer;
    private int stuckRunLength;
    private int replayRunLength;

    // decision state
    private boolean deciding;
    private long triggerIndex;
    private long holdoffUntil = -1;
    private double preBaseline;
    private double jumpMax;
    private int rangeHits;
    private int replayHits;
    private int saturationHits;
    private int stuckHits;
    private int outlierHits;
    private int noiseHits;
    private int changeHits;
    private boolean dismissed;
    private int windowKinds;
    private long carryUntil = -1;
    private long carryTrigger;
    private double carryBaseline;
    private double carryJump;
    private int latchedKinds;
    private final List<String> lastTriggers = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();

    public TriageChannel(ChannelSpec spec, TriageConfig cfg) {
        this.spec = spec;
        this.cfg = cfg;
        this.gesd = new GeneralizedEsd(cfg.gesdAlpha());
        this.absDiffs = new DoubleRing(cfg.innovationWindow());
        int history = Math.max(Math.max(cfg.replayWindow(), cfg.slopeWindow() + 16), cfg.gesdWindow() + 1);
        this.raw = new DoubleRing(history);
        this.cleaned = new DoubleRing(history);
        this.divergence = new DoubleRing(64);
        this.gesdBuffer = new double[cfg.gesdWindow()];
        this.windowMax = SlidingWindowAggregator.maximum(cfg.extremumWindow(), Double::compare);
        this.windowMin = SlidingWindowAggregator.minimum(cfg.extremumWindow(), Double::compare);
    }

    /** Feeds one level sample. Returns a decision when one is reached on this sample, otherwise {@code null}. */
    public Decision update(double value) {
        return update(value, Double.NaN);
    }

    /**
     * Feeds one level sample together with an independent rate measurement of the same quantity (second channel).
     * Pass {@link Double#NaN} as the rate when there is none.
     */
    public Decision update(double value, double rate) {
        index++;
        if (index < cfg.calibrationA()) {
            calibrateA(value);
            return null;
        }
        if (index == cfg.calibrationA()) {
            finishCalibrationA();
        }
        boolean live = index >= (long) cfg.calibrationA() + cfg.calibrationB();
        if (live && varianceThreshold != varianceThreshold) {
            double q = varianceQuantile.isEmpty() ? cfg.varianceFloor() : varianceQuantile.quantile();
            varianceThreshold = Math.max(cfg.varianceFloor(), 1.25 * q);
            double o = outlierQuantile.isEmpty() ? 0.0 : outlierQuantile.quantile();
            outlierThreshold = Math.max(cfg.hampelThreshold(), 1.25 * o);
        }

        lastTriggers.clear();

        // 1. range guard: missing, out-of-range or boundary values never reach the filters
        if (!Double.isFinite(value) || value < spec.rangeMin() || value > spec.rangeMax() || isBoundary(value)) {
            if (live) {
                trigger("value outside declared range, missing, or at a boundary value", RANGE);
                rangeHits++;
            }
            return live ? advanceDecision() : null;
        }

        // 2. exact-value checks on the raw stream
        boolean noise = false;
        boolean stuck = false;
        boolean replay = false;
        boolean saturation = false;
        if (!quantized) {
            stuckRunLength = (raw.size() > 0 && Double.compare(raw.get(0), value) == 0) ? stuckRunLength + 1 : 0;
            stuck = stuckRunLength + 1 >= cfg.stuckRun();
            boolean replayedNow = stuckRunLength == 0 && raw.size() > 2 && raw.containsExactly(value, 1);
            replayRunLength = replayedNow ? replayRunLength + 1 : 0;
            replay = replayRunLength >= cfg.replayRun();
        }
        raw.add(value);
        windowMax.add(value);
        windowMin.add(value);
        if (!quantized && stuckRunLength == 0 && windowMax.size() >= cfg.extremumWindow() / 2) {
            boolean atExtremum = Double.compare(value, windowMax.aggregate()) == 0 || Double.compare(value, windowMin.aggregate()) == 0;
            saturation = atExtremum && raw.countExactly(value, 4 * cfg.extremumWindow()) >= cfg.saturationCount();
        }

        // 3. Hampel filter with GESD confirmation
        hampel.accept(value);
        double median = hampel.median();
        // The window median comes from the Hampel filter; the scale is the calibrated σ rather than the MAD of an
        // 11-sample window, whose sampling error lets outliers of 5–6σ through (development seeds).
        double deviation = Math.abs(value - median) / sigma;
        if (!live) {
            outlierQuantile.add(deviation);
        }
        // the threshold is the larger of the configured one and 1.25 × the 99.9th percentile seen in calibration B,
        // so a channel whose normal noise is heavy-tailed does not report its own tail as corruption
        boolean hampelFlag = deviation > (live ? outlierThreshold : cfg.hampelThreshold());
        boolean confirmedOutlier = false;
        if (hampelFlag && raw.size() >= cfg.gesdWindow()) {
            int n = raw.copyRecent(cfg.gesdWindow(), gesdBuffer);
            int[] outliers = gesd.findOutliers(gesdBuffer, Math.min(cfg.gesdMaxOutliers(), n - 2));
            for (int o : outliers) {
                if (o == n - 1) {
                    confirmedOutlier = true;
                    break;
                }
            }
        }
        double clean = hampelFlag ? median : value;
        cleaned.add(clean);

        // 4. Kalman tracker and innovation statistics
        double prior = kalman.predict();
        double innovationVariance = kalman.errorCovariance() + kalman.measurementNoise();
        double z = (clean - prior) / Math.sqrt(innovationVariance);
        kalman.update(clean);
        boolean change = cusum.accept(z).isAlarm() | ewma.accept(z).isAlarm();

        // noise statistic: the robust spread of first differences of the raw value, relative to calibration. A
        // smooth process movement barely changes it; one outlier does not move the median; a noisier instrument
        // scales it directly. ADWIN watches the mean of the same (clipped) quantity for a sustained change.
        noiseRatio = 1.0;
        if (raw.size() >= 2) {
            double d = Math.abs(raw.get(0) - raw.get(1)) / (sigma * LN2_SQRT);
            absDiffs.add(d);
            if (absDiffs.size() >= cfg.innovationWindow()) {
                noiseRatio = absDiffs.median(cfg.innovationWindow(), 0);
            }
            boolean adwinCut = adwin.accept(Math.min(d, 6.0));
            double adwinRatio = adwin.estimate() / MEAN_ABS_OVER_MEDIAN_ABS;
            boolean noiseNow = noiseRatio > varianceThreshold || (adwinCut && adwinRatio > varianceThreshold);
            noise = live && noiseNow;
            if (!live && absDiffs.size() >= cfg.innovationWindow()) {
                varianceQuantile.add(noiseRatio);
            }
        }

        // 5. optional second channel
        if (Double.isFinite(rate)) {
            if (complementary == null) {
                complementary = new ComplementaryFilter(cfg.complementaryCoefficient());
                complementary.reset(clean);
            }
            double estimate = complementary.accept(rate, clean);
            divergence.add((clean - estimate) / sigma);
        }

        if (!live) {
            return null;
        }

        if (stuck) {
            trigger("identical consecutive values", STUCK);
            stuckHits++;
        }
        if (replay) {
            trigger("values exactly repeating earlier values", REPLAY);
            replayHits++;
        }
        if (saturation) {
            trigger("repeated value at the window extremum", SATURATION);
            saturationHits++;
        }
        if (confirmedOutlier) {
            trigger("Hampel outlier confirmed by GESD", OUTLIER);
            outlierHits++;
        }
        if (noise) {
            trigger("noise level above threshold", NOISE);
            noiseHits++;
        }
        if (change) {
            trigger("CUSUM/EWMA alarm on the innovation", CHANGE);
            changeHits++;
        }
        return advanceDecision();
    }

    private void calibrateA(double value) {
        if (!Double.isFinite(value)) {
            return;
        }
        calibrationMoments.add(value);
        if (raw.containsExactly(value, 0)) {
            calibrationRepeats++; // the value already occurred within the replay window
        }
        if (previousRaw == previousRaw) {
            double d = Math.abs(value - previousRaw);
            absDiffMedian.add(d);
            diffs++;
            if (d == 0.0) {
                zeroDiffs++;
            }
        }
        previousRaw = value;
        raw.add(value);
        cleaned.add(value);
        windowMax.add(value);
        windowMin.add(value);
    }

    private void finishCalibrationA() {
        // quantized: many repeated consecutive values, or more than 5% of values repeating an earlier value within
        // the replay window; exact repeats are then expected on normal data and
        // the stuck / replay / saturation checks are disabled
        quantized = diffs > 0 && ((double) zeroDiffs / diffs > cfg.quantizedShare()
                || calibrationRepeats > 0.05 * calibrationMoments.count());
        double robust = absDiffMedian.isEmpty() ? 0.0 : absDiffMedian.quantile() / LN2_SQRT;
        double fallback = calibrationMoments.count() > 1 ? calibrationMoments.sampleStandardDeviation() : 0.0;
        sigma = robust > 0 ? robust : fallback;
        if (quantized) {
            sigma = Math.max(sigma, fallback);
        }
        if (!(sigma > 0)) {
            sigma = Math.max(1e-9, Math.abs(calibrationMoments.mean()) * 1e-6);
        }
        double start = cleaned.median(9, 0);
        hampel = new HampelFilter(cfg.hampelWindow(), cfg.hampelThreshold());
        for (int i = Math.min(cfg.hampelWindow(), raw.size()) - 1; i >= 0; i--) {
            hampel.accept(raw.get(i));
        }
        kalman = new KalmanFilter(start, sigma * sigma, cfg.kalmanProcessRatio() * sigma * sigma, sigma * sigma);
        cusum = new CusumDetector(0.0, 1.0, cfg.cusumAllowance(), cfg.cusumThreshold());
        ewma = new EwmaChangeDetector(0.0, 1.0, cfg.ewmaAlpha(), cfg.ewmaWidth());
        adwin = new Adwin(cfg.adwinDelta());
        varianceQuantile = new P2QuantileEstimator(0.999);
        outlierQuantile = new P2QuantileEstimator(0.999);
    }

    private boolean isBoundary(double value) {
        for (double b : spec.boundaryValues()) {
            if (Double.compare(b, value) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Registers a detector firing. After a decision, the kinds of evidence that led to it are latched: while they
     * keep firing within the hold-off they extend it instead of opening a new decision, so one persistent condition
     * yields one decision. A different kind of evidence opens a new decision at once.
     */
    private void trigger(String reason, int kind) {
        lastTriggers.add(reason);
        if (deciding) {
            windowKinds |= kind;
            return;
        }
        if (index < holdoffUntil && (latchedKinds & kind) != 0) {
            holdoffUntil = index + cfg.holdoff();
            return;
        }
        {
            windowKinds = kind;
            deciding = true;
            if (kind == CHANGE && index < carryUntil) {
                // a change alarm shortly after an unconfirmed window continues that window's accumulation
                triggerIndex = carryTrigger;
                preBaseline = carryBaseline;
                jumpMax = carryJump;
            } else {
                triggerIndex = index;
                preBaseline = cleaned.median(9, Math.min(4, Math.max(0, cleaned.size() - 9)));
                jumpMax = 0.0;
            }
            carryUntil = -1;
            rangeHits = replayHits = saturationHits = stuckHits = outlierHits = noiseHits = changeHits = 0;
        }
    }

    private Decision advanceDecision() {
        if (!deciding) {
            return null;
        }
        if (cleaned.size() >= 10) {
            double jump = Math.abs(cleaned.median(5, 0) - cleaned.median(5, 5)) / sigma;
            jumpMax = Math.max(jumpMax, jump);
        }
        long elapsed = index - triggerIndex;
        if (elapsed < cfg.decisionWindow()) {
            return null;
        }
        List<String> evidence = new ArrayList<>();
        Cause cause = attribute(evidence, elapsed);
        if (cause == null && !dismissed) {
            return null; // deferred: the level is still moving
        }
        deciding = false;
        if (dismissed) {
            // no event confirmed: nothing is latched and the detectors keep their state, so a slow change that
            // was not yet confirmed keeps accumulating evidence and can open a new decision at once
            dismissed = false;
            carryUntil = triggerIndex + cfg.driftHorizon();
            carryTrigger = triggerIndex;
            carryBaseline = preBaseline;
            carryJump = jumpMax;
            return null;
        }
        holdoffUntil = index + cfg.holdoff();
        latchedKinds = windowKinds;
        cusum.reset();
        ewma.reset();
        adwin.reset();
        Decision d = new Decision(cause, triggerIndex, index, evidence);
        decisions.add(d);
        return d;
    }

    /** The rule of Table 2. Returns {@code null} to defer; sets {@link #dismissed} when no event is confirmed. */
    private Cause attribute(List<String> evidence, long elapsed) {
        double netShift = Math.abs(cleaned.median(9, 0) - preBaseline) / sigma;
        double slope = cleaned.slope(cfg.slopeWindow()) / sigma;
        boolean moving = Math.abs(slope) > cfg.slopeThreshold();
        double jumpBound = (Double.isFinite(spec.plausibleRate()) ? 5.0 * spec.plausibleRate() / sigma : Double.POSITIVE_INFINITY) + cfg.jumpMargin();

        if (rangeHits > 0) {
            evidence.add("values outside the declared range, missing, or at a boundary value: " + rangeHits);
            return Cause.DATA_PATH;
        }
        if (replayHits > 0) {
            evidence.add("segment of values exactly repeating earlier values");
            return Cause.DATA_PATH;
        }
        if (saturationHits > 0) {
            evidence.add("value pinned at the window extremum, repeated " + saturationHits + " times");
            return Cause.INSTRUMENT;
        }
        if (stuckHits > 0) {
            evidence.add("stuck at the last valid value for at least " + cfg.stuckRun() + " samples");
            return Cause.INSTRUMENT;
        }
        double noiseScale = Math.max(1.0, noiseRatio);
        // noise growth must persist through the decision window; a transient excursion of the statistic is dismissed
        if (noiseHits > 0 && noiseRatio > varianceThreshold && netShift < cfg.shiftThreshold() * noiseScale) {
            evidence.add(String.format("noise %.2f× calibration (threshold %.2f×), net level change %.2fσ", noiseRatio, varianceThreshold, netShift));
            return Cause.INSTRUMENT;
        }
        if (outlierHits > 0 && outlierHits <= cfg.isolatedMax() && netShift < cfg.shiftThreshold()) {
            evidence.add("isolated outliers confirmed by GESD: " + outlierHits + "; level unchanged");
            return Cause.DATA_PATH;
        }
        boolean levelChange = netShift >= cfg.shiftThreshold() || (changeHits > 0 && moving);
        if (levelChange) {
            if (jumpMax > jumpBound) {
                evidence.add(String.format("level moved %.2fσ within 5 samples, above the plausible-rate bound %.2fσ", jumpMax, jumpBound));
                return Cause.INSTRUMENT;
            }
            if (divergence.size() > 15) {
                double div = Math.abs(divergence.median(15, 0));
                if (div > cfg.divergenceThreshold()) {
                    evidence.add(String.format("level diverges %.2fσ from the rate-integrated estimate", div));
                    return Cause.INSTRUMENT;
                }
                if (!moving || elapsed >= cfg.driftHorizon()) {
                    evidence.add(String.format("level change explained by the rate channel (divergence %.2fσ)", div));
                    return Cause.PROCESS;
                }
                return null;
            }
            if (!moving) {
                evidence.add(String.format("level settled %.2fσ from baseline within the plausible rate", netShift));
                return Cause.PROCESS;
            }
            if (elapsed < cfg.driftHorizon()) {
                return null;
            }
            evidence.add(String.format("level still moving at %.3fσ/sample after %d samples: process trend and sensor drift are not separable from one channel", slope, elapsed));
            return Cause.UNDETERMINED;
        }
        if (outlierHits > 0) {
            evidence.add("outliers confirmed by GESD: " + outlierHits);
            return Cause.DATA_PATH;
        }
        dismissed = true;
        return null;
    }

    public List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    /** Reasons the detectors fired on the last sample (empty when none fired). */
    public List<String> lastTriggers() {
        return List.copyOf(lastTriggers);
    }

    /** Number of ADWIN buckets currently held: the only part of the state that grows, logarithmically. */
    public int adwinBuckets() {
        return adwin == null ? 0 : adwin.bucketCount();
    }

    /**
     * Doubles held in fixed-size arrays: the raw, cleaned, divergence and |Δy| rings with their scratch arrays, the
     * GESD buffer and the Hampel window with its two work arrays. Independent of the stream length.
     */
    public int fixedStateDoubles() {
        int history = Math.max(Math.max(cfg.replayWindow(), cfg.slopeWindow() + 16), cfg.gesdWindow() + 1);
        return 2 * (2 * history + 64 + cfg.innovationWindow()) + cfg.gesdWindow() + 3 * cfg.hampelWindow();
    }

    public double sigma() {
        return sigma;
    }

    /** True when calibration saw no variation at all; such a channel carries no information and is not monitored. */
    public boolean degenerate() {
        return calibrationMoments.count() > 1 && calibrationMoments.sampleVariance() == 0.0;
    }

    public boolean quantized() {
        return quantized;
    }

    public boolean deciding() {
        return deciding;
    }

    public long index() {
        return index;
    }
}
