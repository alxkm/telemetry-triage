package triage.bench;

import triage.core.TriageConfig;

/** Development-seed run (seeds 1000–1019). Used to fix the defaults once; never run on test seeds. */
public final class Dev {
    private Dev() {
    }

    public static void main(String[] args) {
        TriageConfig cfg = TriageConfig.defaults();
        for (ProcessModel m : ProcessModel.values()) {
            for (boolean rate : new boolean[] {false, true}) {
                Tally t = new Tally();
                for (long seed = 1000; seed < 1020; seed++) {
                    Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP);
                    t.run(s, Detector.triage(Bench.spec(m), cfg, rate), Bench.WARMUP, Bench.TAIL);
                }
                System.out.println("== " + m + (rate ? " level+rate" : " level only"));
                System.out.println(Report.perTypeTable(t, true));
            }
        }
    }

}
