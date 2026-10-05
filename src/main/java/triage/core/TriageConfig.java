package triage.core;

/**
 * Every parameter of the detector bank and the attribution rule. All thresholds on the signal are in units of the
 * channel's own noise standard deviation σ, estimated online during calibration. Defaults were fixed on development
 * seeds only and are not tuned per dataset.
 */
public record TriageConfig(
        int calibrationA,          // samples used to estimate σ and the starting level
        int calibrationB,          // samples during which the filters run but do not trigger; feeds the P² thresholds
        double kalmanProcessRatio, // q = ratio · σ²  (random-walk process noise of the tracker)
        int hampelWindow,
        double hampelThreshold,    // in σ: distance from the window median that flags a sample
        int gesdWindow,
        int gesdMaxOutliers,
        double gesdAlpha,
        double cusumAllowance,     // k, on the standardized innovation
        double cusumThreshold,     // h
        double ewmaAlpha,
        double ewmaWidth,
        int innovationWindow,      // window of the Welford variance of standardized innovations
        double varianceFloor,      // lower bound of the noise-growth threshold
        double adwinDelta,
        int decisionWindow,        // Δ: samples observed after the first trigger before the rule decides
        int driftHorizon,          // longest deferral while the level is still moving
        int slopeWindow,           // samples in the regression that decides whether the level is still moving
        double slopeThreshold,     // σ per sample; |slope| above this means "still moving"
        double shiftThreshold,     // σ; net level change that counts as a level change
        double jumpMargin,         // σ; noise margin added to 5·plausibleRate in the bias-step test
        int isolatedMax,           // at most this many confirmed outliers count as "isolated"
        int stuckRun,              // identical consecutive values that count as stuck
        int replayWindow,          // history searched for exactly repeated values
        int replayRun,             // consecutive replayed values that count as a replayed segment
        int saturationCount,       // repeats of the window extremum that count as saturation
        int extremumWindow,        // window of the running extremum; repeats are counted over 4× this window
        double quantizedShare,     // share of zero first differences in calibration above which the channel is quantized
        int holdoff,               // samples after a decision during which no new trigger is accepted
        double complementaryCoefficient,
        double divergenceThreshold // σ; two-channel test
) {
    /** The configuration used for every result reported in the paper. */
    public static TriageConfig defaults() {
        return DEFAULTS;
    }

    private static final TriageConfig DEFAULTS = create();

    private static TriageConfig create() {
        return new TriageConfig(
                300, 300,
                0.002,
                11, 4.5,
                31, 5, 0.01,
                0.5, 8.0,
                0.1, 4.0,
                40, 1.8,
                0.002,
                40, 240, 60, 0.03, 2.0, 2.4,
                4, 6, 256, 4, 3, 16, 0.2,
                40,
                0.995, 2.5);
    }
}
