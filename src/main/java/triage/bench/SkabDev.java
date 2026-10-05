package triage.bench;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import triage.core.ChannelSpec;
import triage.core.Decision;
import triage.core.TriageChannel;
import triage.core.TriageConfig;

/** Development aid on SKAB's anomaly-free (training) file only: false alarms by channel and evidence. */
public final class SkabDev {
    private SkabDev() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("datasets", "skab");
        SkabLoader.SkabFile f = SkabLoader.read(root.resolve("anomaly-free/anomaly-free.csv"), root);
        TriageConfig cfg = Configs.with(Configs.with(TriageConfig.defaults(), "calibrationA", 200), "calibrationB", 200);
        for (int c = 0; c < 8; c++) {
            double[] x = f.channel(c);
            TriageChannel tc = new TriageChannel(ChannelSpec.unconstrained(f.channels()[c]), cfg);
            Map<String, Integer> reasons = new TreeMap<>();
            int n = 0;
            for (double v : x) {
                Decision d = tc.update(v);
                if (d != null) {
                    n++;
                    String key = d.cause() + ": " + d.evidence().get(0).replaceAll("[-0-9.]+", "#");
                    reasons.merge(key, 1, Integer::sum);
                }
            }
            System.out.printf("%-22s sigma=%.5f quantized=%s decisions=%d%n", f.channels()[c], tc.sigma(), tc.quantized(), n);
            reasons.forEach((k, v) -> System.out.printf("    %4d  %s%n", v, k));
        }
    }
}
