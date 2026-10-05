package triage.bench;

import java.util.ArrayList;
import java.util.List;
import triage.core.Decision;
import triage.core.TriageChannel;
import triage.core.TriageConfig;

/** Development aid: prints the decisions around events of one type on development seeds. */
public final class Probe {
    private Probe() {
    }

    public static void main(String[] args) {
        if (args[0].equals("FA")) {
            falseAlarms(ProcessModel.valueOf(args.length > 1 ? args[1] : "TANK"));
            return;
        }
        FaultType type = FaultType.valueOf(args[0]);
        ProcessModel m = ProcessModel.valueOf(args.length > 1 ? args[1] : "TANK");
        int shown = 0;
        for (long seed = Bench.DEV_SEED_FROM; seed < Bench.DEV_SEED_TO && shown < 12; seed++) {
            Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP);
            TriageChannel ch = new TriageChannel(Bench.spec(m), TriageConfig.defaults());
            List<Decision> ds = new ArrayList<>();
            for (int t = 0; t < s.length(); t++) {
                Decision d = ch.update(s.y[t]);
                if (d != null) {
                    ds.add(d);
                }
            }
            for (Event e : s.events) {
                if (e.type() != type) {
                    continue;
                }
                Decision hit = null;
                for (Decision d : ds) {
                    if (d.triggerIndex() >= e.start() - 300 && d.triggerIndex() <= e.end() + Bench.TAIL) {
                        if (hit == null || d.triggerIndex() >= e.start()) {
                            hit = d;
                            if (d.triggerIndex() >= e.start()) {
                                break;
                            }
                        }
                    }
                }
                boolean wrong = hit == null || hit.triggerIndex() < e.start() || hit.cause() != type.cause();
                if (wrong) {
                    shown++;
                    System.out.printf("seed %d %s start=%d end=%d amp=%.3f -> %s%n", seed, type, e.start(), e.end(), e.amplitude(), hit);
                }
            }
        }
    }

    private static void falseAlarms(ProcessModel m) {
        for (long seed = Bench.DEV_SEED_FROM; seed < Bench.DEV_SEED_TO; seed++) {
            Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP);
            TriageChannel ch = new TriageChannel(Bench.spec(m), TriageConfig.defaults());
            for (int t = 0; t < s.length(); t++) {
                Decision d = ch.update(s.y[t]);
                if (d == null) {
                    continue;
                }
                boolean inside = false;
                Event prev = null;
                for (Event e : s.events) {
                    if (d.triggerIndex() >= e.start() && d.triggerIndex() <= e.end() + Bench.TAIL) {
                        inside = true;
                    }
                    if (e.start() <= d.triggerIndex()) {
                        prev = e;
                    }
                }
                if (!inside && d.triggerIndex() >= Bench.WARMUP) {
                    System.out.printf("seed %d FA %s after %s -> %s%n", seed, d.triggerIndex(), prev, d);
                }
            }
        }
    }
}
