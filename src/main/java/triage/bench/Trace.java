package triage.bench;

import triage.core.Decision;
import triage.core.TriageChannel;
import triage.core.TriageConfig;

/** Development aid: prints the per-sample state of one channel between two indices of a development run. */
public final class Trace {
    private Trace() {
    }

    public static void main(String[] args) {
        ProcessModel m = ProcessModel.valueOf(args[0]);
        long seed = Long.parseLong(args[1]);
        int from = Integer.parseInt(args[2]);
        int to = Integer.parseInt(args[3]);
        Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP);
        TriageChannel ch = new TriageChannel(Bench.spec(m), TriageConfig.defaults());
        for (int t = 0; t <= to; t++) {
            Decision d = ch.update(s.y[t]);
            if (t >= from) {
                System.out.printf("%d y=%.4f truth=%.4f deciding=%s %s %s%n", t, s.y[t], s.truth[t], ch.deciding(), ch.lastTriggers(), d == null ? "" : d);
            }
        }
    }
}
