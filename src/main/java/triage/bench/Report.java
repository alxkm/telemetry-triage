package triage.bench;

import java.util.Locale;
import triage.core.Cause;

/** Plain-text and CSV rendering of a {@link Tally}. */
public final class Report {
    private Report() {
    }

    public static String perTypeTable(Tally t, boolean attributes) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-18s %6s %6s %8s %8s %8s %6s", "fault", "events", "det%", "delayMed", "delayP90", "latMed", "extra"));
        if (attributes) {
            sb.append(String.format(Locale.ROOT, " %6s %6s %6s %6s %7s", "PROC", "INST", "DATA", "UNDET", "correct"));
        }
        sb.append('\n');
        for (FaultType f : FaultType.values()) {
            Tally.PerType p = t.perType.get(f);
            if (p.events == 0) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%-18s %6d %6.1f %8.0f %8.0f %8.0f %6d", f.name(), p.events,
                    100.0 * p.detected / p.events, Tally.quantile(p.delays, 0.5), Tally.quantile(p.delays, 0.9),
                    Tally.quantile(p.latencies, 0.5), p.extra));
            if (attributes) {
                sb.append(String.format(Locale.ROOT, " %6d %6d %6d %6d %6.1f%%", p.predicted.get(Cause.PROCESS),
                        p.predicted.get(Cause.INSTRUMENT), p.predicted.get(Cause.DATA_PATH), p.predicted.get(Cause.UNDETERMINED),
                        p.detected == 0 ? 0.0 : 100.0 * p.predicted.get(f.cause()) / p.detected));
            }
            sb.append('\n');
        }
        sb.append(String.format(Locale.ROOT, "false alarms: %d over %.1f h monitored = %.3f /h%n", t.falseAlarms,
                t.monitoredSamples / 3600.0, t.falseAlarmsPerHour()));
        if (attributes) {
            sb.append("false alarms by attributed cause: ").append(t.falseAlarmByCause).append('\n');
            sb.append(confusion(t));
        }
        return sb.toString();
    }

    public static String confusion(Tally t) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-12s %7s %7s %7s %7s %7s %7s %9s%n", "truth\\pred", "events", "missed", "PROC", "INST", "DATA", "UNDET", "acc(det)"));
        Cause[] truths = {Cause.PROCESS, Cause.INSTRUMENT, Cause.DATA_PATH};
        for (Cause c : truths) {
            int det = t.detected(c);
            sb.append(String.format(Locale.ROOT, "%-12s %7d %7d %7d %7d %7d %7d %8.1f%%%n", c, t.events(c), t.events(c) - det,
                    t.confusion(c, Cause.PROCESS), t.confusion(c, Cause.INSTRUMENT), t.confusion(c, Cause.DATA_PATH),
                    t.confusion(c, Cause.UNDETERMINED), det == 0 ? 0.0 : 100.0 * t.confusion(c, c) / det));
        }
        return sb.toString();
    }
}
