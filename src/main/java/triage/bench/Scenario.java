package triage.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import triage.core.Cause;

/**
 * One synthetic run: the transmitted level y, an independent rate measurement, and the ground-truth event list.
 *
 * <p>Generation follows the observation model of the paper, y_t = D_t(h(x_t) + b_t + n_t): the process x (with a
 * slow Ornstein-Uhlenbeck wander) is advanced first and process events act on its setpoint; instrument events act
 * on the bias b, the noise n or the reported value; data-path events act on the transmitted value only. The rate
 * channel measures the true change of x and is affected neither by instrument nor by data-path events.
 */
public final class Scenario {
    public static final int FIRST_EVENT = 900;

    public final ProcessModel model;
    public final long seed;
    public final double[] y;
    public final double[] rate;
    public final double[] truth;
    public final List<Event> events;

    private Scenario(ProcessModel model, long seed, double[] y, double[] rate, double[] truth, List<Event> events) {
        this.model = model;
        this.seed = seed;
        this.y = y;
        this.rate = rate;
        this.truth = truth;
        this.events = List.copyOf(events);
    }

    /** A run with every fault type in shuffled rounds, amplitudes drawn from the catalogue ranges. */
    public static Scenario generate(ProcessModel model, long seed, int length, int gap) {
        return generate(model, seed, length, gap, null, Double.NaN);
    }

    /**
     * A semi-synthetic run: the events of the same catalogue and schedule injected into a recorded series. The
     * recorded series supplies the noise and the background process; amplitudes are in units of {@code sigma}, the
     * channel's noise as the method itself estimates it. Process changes are set-point moves limited to
     * {@link #SEMI_MAX_RATE} σ per sample added to the recording. There is no rate channel.
     *
     * @param boundary the declared boundary value to inject for ZERO_OR_BOUNDARY (0.0 unless the recording contains it)
     */
    public static Scenario semiSynthetic(double[] base, double sigma, double boundary, long seed, int gap) {
        SplittableRandom rnd = new SplittableRandom(seed);
        int length = base.length;
        List<Event> plan = schedule(rnd, length, gap, null, Double.NaN);
        double[] y = new double[length];
        double[] truth = new double[length];
        double[] rate = new double[length];
        java.util.Arrays.fill(rate, Double.NaN);
        List<Event> events = new ArrayList<>();
        double target = 0.0;
        double d = 0.0;
        double bias = 0.0;
        double lastTransmitted = Double.NaN;
        double regimePeriod = 150.0;
        double regimeAmp = 0.0;
        double cap = Double.NaN;
        boolean shiftPending = false;
        int shiftStart = 0;
        double shiftAmp = 0.0;
        Event active = null;
        int k = 0;
        for (int t = 0; t < length; t++) {
            if (k < plan.size() && plan.get(k).start() == t) {
                active = plan.get(k++);
                switch (active.type()) {
                    case LEVEL_SHIFT -> {
                        target += active.amplitude();
                        shiftPending = true;
                        shiftStart = t;
                        shiftAmp = active.amplitude();
                    }
                    case BIAS_STEP -> bias += active.amplitude();
                    case REGIME_CHANGE -> {
                        target += active.amplitude();
                        regimePeriod = uniform(rnd, 100, 200);
                        regimeAmp = uniform(rnd, 1.0, 2.0);
                    }
                    case SATURATION -> cap = (Double.isFinite(lastTransmitted) ? lastTransmitted : base[t]) + sigma * active.amplitude();
                    default -> {
                    }
                }
                if (active.type() != FaultType.LEVEL_SHIFT) {
                    events.add(active);
                }
            }
            boolean inside = active != null && t >= active.start() && t <= active.end();
            FaultType type = inside ? active.type() : null;
            if (type == FaultType.RAMP) {
                target += active.amplitude();
            }
            double osc = type == FaultType.REGIME_CHANGE ? regimeAmp * Math.sin(2 * Math.PI * (t - active.start()) / regimePeriod) : 0.0;
            d += Math.max(-SEMI_MAX_RATE, Math.min(SEMI_MAX_RATE, target + osc - d));
            truth[t] = base[t] + sigma * d;
            if (shiftPending && Math.abs(target - d) < 0.05 * Math.abs(shiftAmp)) {
                events.add(new Event(FaultType.LEVEL_SHIFT, shiftStart, t, shiftAmp));
                shiftPending = false;
            }
            if (type == FaultType.LINEAR_DRIFT) {
                bias += active.amplitude();
            }
            double out = truth[t] + sigma * bias;
            if (type == FaultType.NOISE_GROWTH) {
                // the recording already carries noise of about sigma; add the rest to reach factor x sigma
                out += sigma * Math.sqrt(active.amplitude() * active.amplitude() - 1.0) * rnd.nextGaussian();
            }
            if (type == FaultType.SATURATION) {
                out = Math.min(out, cap);
            }
            if (type == FaultType.STUCK_AT_LAST && Double.isFinite(lastTransmitted)) {
                out = lastTransmitted;
            }
            if (type != null) {
                switch (type) {
                    case SPIKE, SUBSTITUTION -> out = truth[t] + sigma * (bias + active.amplitude() + 0.5 * rnd.nextGaussian());
                    case REPLAY -> out = y[t - (int) active.amplitude()];
                    case ZERO_OR_BOUNDARY -> out = boundary;
                    case DROPOUT -> out = Double.NaN;
                    default -> {
                    }
                }
            }
            y[t] = out;
            if (Double.isFinite(out) && (type == null || type.cause() != Cause.DATA_PATH)) {
                lastTransmitted = out;
            }
        }
        events.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return new Scenario(null, seed, y, rate, truth, events);
    }

    /** Largest injected process change per sample in the semi-synthetic runs, in σ; declared as the plausible rate. */
    public static final double SEMI_MAX_RATE = 0.15;

    /** A run restricted to {@code only} (when not null), with a fixed step amplitude in σ (when not NaN). */
    public static Scenario generate(ProcessModel model, long seed, int length, int gap, List<FaultType> only, double fixedAmplitude) {
        SplittableRandom rnd = new SplittableRandom(seed);
        List<Event> plan = schedule(rnd, length, gap, only, fixedAmplitude);
        return synthesize(model, seed, length, plan, rnd);
    }

    private static List<Event> schedule(SplittableRandom rnd, int length, int gap, List<FaultType> only, double fixedAmplitude) {
        List<FaultType> pool = new ArrayList<>(only == null ? List.of(FaultType.values()) : only);
        List<FaultType> bag = new ArrayList<>();
        List<Event> plan = new ArrayList<>();
        int t = FIRST_EVENT + rnd.nextInt(200);
        while (true) {
            if (bag.isEmpty()) {
                bag.addAll(pool);
                Collections.shuffle(bag, new Random(rnd.nextLong()));
            }
            FaultType type = bag.remove(bag.size() - 1);
            double sign = rnd.nextBoolean() ? 1.0 : -1.0;
            double amp;
            int duration;
            switch (type) {
                case LEVEL_SHIFT, BIAS_STEP -> {
                    amp = sign * (Double.isNaN(fixedAmplitude) ? uniform(rnd, 4, 10) : fixedAmplitude);
                    duration = 0;
                }
                case REGIME_CHANGE -> {
                    amp = sign * uniform(rnd, 3, 6);
                    duration = (int) uniform(rnd, 300, 500);
                }
                case RAMP, LINEAR_DRIFT -> {
                    amp = sign * uniform(rnd, 0.02, 0.08);
                    duration = (int) uniform(rnd, 100, 300);
                }
                case NOISE_GROWTH -> {
                    amp = uniform(rnd, 2.5, 5.0);
                    duration = (int) uniform(rnd, 200, 400);
                }
                case STUCK_AT_LAST -> {
                    amp = 0;
                    duration = (int) uniform(rnd, 30, 200);
                }
                case SATURATION -> {
                    amp = uniform(rnd, 0.3, 0.8);
                    duration = (int) uniform(rnd, 200, 400);
                }
                case SPIKE -> {
                    amp = sign * uniform(rnd, 6, 15);
                    duration = 0;
                }
                case SUBSTITUTION -> {
                    amp = sign * uniform(rnd, 6, 15);
                    duration = 2;
                }
                case REPLAY -> {
                    amp = (int) uniform(rnd, 50, 200); // lag of the replayed segment
                    duration = (int) uniform(rnd, 10, 40) - 1;
                }
                case ZERO_OR_BOUNDARY -> {
                    amp = 0;
                    duration = rnd.nextInt(3);
                }
                case DROPOUT -> {
                    amp = 0;
                    duration = (int) uniform(rnd, 5, 20) - 1;
                }
                default -> throw new IllegalStateException(type.name());
            }
            int end = t + duration;
            if (end + gap >= length) {
                break;
            }
            plan.add(new Event(type, t, end, amp));
            t = end + gap + rnd.nextInt(Math.max(1, gap / 2));
        }
        return plan;
    }

    private static Scenario synthesize(ProcessModel m, long seed, int length, List<Event> plan, SplittableRandom rnd) {
        double[] y = new double[length];
        double[] rate = new double[length];
        double[] truth = new double[length];
        List<Event> events = new ArrayList<>();
        final double ouTheta = 1.0 / 500.0;
        final double ouSd = 0.5 * Math.sqrt(2.0 * ouTheta);
        double target = 0.0;
        double x = 0.0;
        double wander = 0.0;
        double bias = 0.0;
        double prevTrue = 0.0;
        double lastTransmitted = Double.NaN;
        double regimePeriod = 150.0;
        double regimeAmp = 0.0;
        double cap = Double.NaN;
        boolean shiftPending = false;
        int shiftStart = 0;
        double shiftAmp = 0.0;
        Event active = null;
        int k = 0;

        for (int t = 0; t < length; t++) {
            if (k < plan.size() && plan.get(k).start() == t) {
                active = plan.get(k++);
                switch (active.type()) {
                    case LEVEL_SHIFT -> {
                        target += active.amplitude();
                        shiftPending = true;
                        shiftStart = t;
                        shiftAmp = active.amplitude();
                    }
                    case BIAS_STEP -> bias += active.amplitude();
                    case REGIME_CHANGE -> {
                        target += active.amplitude();
                        regimePeriod = uniform(rnd, 100, 200);
                        regimeAmp = uniform(rnd, 1.0, 2.0);
                    }
                    case SATURATION -> cap = x + wander + bias + active.amplitude();
                    default -> {
                    }
                }
                if (active.type() != FaultType.LEVEL_SHIFT) {
                    events.add(active);
                }
            }
            boolean inside = active != null && t >= active.start() && t <= active.end();
            FaultType type = inside ? active.type() : null;

            // process
            if (type == FaultType.RAMP) {
                target += active.amplitude();
            }
            // the oscillation of a new regime acts on the setpoint, so the process still respects its rate limit
            double osc = type == FaultType.REGIME_CHANGE
                    ? regimeAmp * Math.sin(2 * Math.PI * (t - active.start()) / regimePeriod)
                    : 0.0;
            x = m.step(x, target + osc);
            wander += -ouTheta * wander + ouSd * rnd.nextGaussian();
            double trueLevel = x + wander;
            truth[t] = m.offset + m.sigma * trueLevel;
            rate[t] = m.sigma * ((t == 0 ? 0.0 : trueLevel - prevTrue) + 0.05 * rnd.nextGaussian());
            prevTrue = trueLevel;
            if (shiftPending && Math.abs(target - x) < 0.05 * Math.abs(shiftAmp)) {
                events.add(new Event(FaultType.LEVEL_SHIFT, shiftStart, t, shiftAmp));
                shiftPending = false;
            }

            // instrument
            if (type == FaultType.LINEAR_DRIFT) {
                bias += active.amplitude();
            }
            double noiseFactor = type == FaultType.NOISE_GROWTH ? active.amplitude() : 1.0;
            double reported = trueLevel + bias + noiseFactor * rnd.nextGaussian();
            if (type == FaultType.SATURATION) {
                reported = Math.min(reported, cap);
            }
            double out = m.offset + m.sigma * reported;
            if (type == FaultType.STUCK_AT_LAST && Double.isFinite(lastTransmitted)) {
                out = lastTransmitted;
            }

            // data path
            if (type != null) {
                switch (type) {
                    case SPIKE, SUBSTITUTION -> out = m.offset + m.sigma * (trueLevel + bias + active.amplitude() + 0.5 * rnd.nextGaussian());
                    case REPLAY -> out = y[t - (int) active.amplitude()];
                    case ZERO_OR_BOUNDARY -> out = 0.0;
                    case DROPOUT -> out = Double.NaN;
                    default -> {
                    }
                }
            }
            y[t] = out;
            if (Double.isFinite(out) && (type == null || type.cause() != Cause.DATA_PATH)) {
                lastTransmitted = out;
            }
        }
        events.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return new Scenario(m, seed, y, rate, truth, events);
    }

    private static double uniform(SplittableRandom rnd, double lo, double hi) {
        return lo + (hi - lo) * rnd.nextDouble();
    }

    public int length() {
        return y.length;
    }
}
