package triage.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import triage.core.Cause;
import triage.core.Decision;

/**
 * Accumulates detection and attribution outcomes over runs.
 *
 * <p>An event is detected when a decision's trigger index falls in [start, end + tail]. The first such decision is
 * matched to the event; further decisions in the same interval are counted as extra decisions, not false alarms.
 * A decision whose trigger falls outside every event interval and after the warm-up is a false alarm. One sample is
 * taken to be one second.
 */
public final class Tally {
    public static final class PerType {
        public int events;
        public int detected;
        public int extra;
        public final List<Integer> delays = new ArrayList<>();
        public final List<Integer> latencies = new ArrayList<>();
        public final Map<Cause, Integer> predicted = new EnumMap<>(Cause.class);

        PerType() {
            for (Cause c : Cause.values()) {
                predicted.put(c, 0);
            }
        }
    }

    public final Map<FaultType, PerType> perType = new EnumMap<>(FaultType.class);
    public long falseAlarms;
    public long monitoredSamples;
    public final List<Long> falseAlarmCauses = new ArrayList<>();
    public final Map<Cause, Integer> falseAlarmByCause = new EnumMap<>(Cause.class);

    public Tally() {
        for (FaultType f : FaultType.values()) {
            perType.put(f, new PerType());
        }
        for (Cause c : Cause.values()) {
            falseAlarmByCause.put(c, 0);
        }
    }

    /** Runs {@code detector} over {@code s} and records the outcome. */
    public void run(Scenario s, Detector detector, int warmup, int tail) {
        List<Decision> decisions = new ArrayList<>();
        for (int t = 0; t < s.length(); t++) {
            Decision d = detector.step(s.y[t], s.rate[t]);
            if (d != null) {
                decisions.add(d);
            }
        }
        record(s, decisions, warmup, tail);
    }

    void record(Scenario s, List<Decision> decisions, int warmup, int tail) {
        boolean[] inWindow = new boolean[s.length()];
        for (Event e : s.events) {
            for (int t = e.start(); t <= Math.min(s.length() - 1, e.end() + tail); t++) {
                inWindow[t] = true;
            }
        }
        for (int t = warmup; t < s.length(); t++) {
            if (!inWindow[t]) {
                monitoredSamples++;
            }
        }
        boolean[] used = new boolean[decisions.size()];
        for (Event e : s.events) {
            PerType p = perType.get(e.type());
            p.events++;
            boolean matched = false;
            for (int i = 0; i < decisions.size(); i++) {
                Decision d = decisions.get(i);
                if (d.triggerIndex() >= e.start() && d.triggerIndex() <= e.end() + tail) {
                    used[i] = true;
                    if (!matched) {
                        matched = true;
                        p.detected++;
                        p.delays.add((int) (d.triggerIndex() - e.start()));
                        p.latencies.add((int) (d.decisionIndex() - e.start()));
                        p.predicted.merge(d.cause(), 1, Integer::sum);
                    } else {
                        p.extra++;
                    }
                }
            }
        }
        for (int i = 0; i < decisions.size(); i++) {
            Decision d = decisions.get(i);
            if (!used[i] && d.triggerIndex() >= warmup && !inWindow[(int) d.triggerIndex()]) {
                falseAlarms++;
                falseAlarmByCause.merge(d.cause(), 1, Integer::sum);
            }
        }
    }

    public double falseAlarmsPerHour() {
        return monitoredSamples == 0 ? 0.0 : falseAlarms / (monitoredSamples / 3600.0);
    }

    /** Events of {@code cause} (ground truth) detected and attributed to {@code predicted}. */
    public int confusion(Cause truth, Cause predicted) {
        int n = 0;
        for (FaultType f : FaultType.values()) {
            if (f.cause() == truth) {
                n += perType.get(f).predicted.get(predicted);
            }
        }
        return n;
    }

    public int events(Cause truth) {
        int n = 0;
        for (FaultType f : FaultType.values()) {
            if (f.cause() == truth) {
                n += perType.get(f).events;
            }
        }
        return n;
    }

    public int detected(Cause truth) {
        int n = 0;
        for (FaultType f : FaultType.values()) {
            if (f.cause() == truth) {
                n += perType.get(f).detected;
            }
        }
        return n;
    }

    public List<Integer> delays(Cause truth) {
        List<Integer> all = new ArrayList<>();
        for (FaultType f : FaultType.values()) {
            if (truth == null || f.cause() == truth) {
                all.addAll(perType.get(f).delays);
            }
        }
        return all;
    }

    public static double quantile(List<Integer> values, double p) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (pos - lo) * (sorted.get(hi) - sorted.get(lo));
    }
}
