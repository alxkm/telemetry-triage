package triage.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import triage.core.Cause;
import triage.core.ChannelSpec;
import triage.core.Decision;
import triage.core.TriageChannel;
import triage.core.TriageConfig;

/**
 * Experiments E1–E8 of the paper. Every number in {@code results/} is written by this class from the frozen
 * defaults on the test seeds (2000–2049) or on SKAB; nothing is edited by hand.
 *
 * <pre>
 * E1  detection and attribution, level channel only, synthetic, test seeds
 * E2  the same with the second (rate) channel
 * E3  detection against two baselines (rolling z-score, restarted CUSUM)
 * E4  attribution of bias steps and process level shifts versus step amplitude
 * E5  sensitivity to the decision window Δ (not used for tuning)
 * E6  cost per sample and state size
 * E7  SKAB: detection delay and false alarms; attribution reported descriptively
 * E8  ablations: no plausible-rate test, no deferral
 * </pre>
 */
public final class Experiments {
    private static final Path OUT = Path.of("results");
    private static final StringBuilder SUMMARY = new StringBuilder();

    private Experiments() {
    }

    public static void main(String[] args) throws IOException {
        List<String> which = args.length == 0 ? List.of("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "FIG") : Arrays.asList(args);
        Files.createDirectories(OUT.resolve("figures"));
        SUMMARY.append("# Results summary\n\n");
        SUMMARY.append("Generated ").append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append(" by `triage.bench.Experiments` on ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append(", ").append(System.getProperty("os.name")).append(".\n\n");
        SUMMARY.append("Synthetic runs: ").append(Bench.LENGTH).append(" samples each, test seeds ").append(Bench.TEST_SEED_FROM)
                .append("–").append(Bench.TEST_SEED_TO - 1).append(" (").append(Bench.TEST_SEED_TO - Bench.TEST_SEED_FROM)
                .append(" per process model). One sample = 1 s.\n\n");
        write("config.txt", Configs.describe(TriageConfig.defaults()));
        for (String e : which) {
            switch (e) {
                case "E1" -> synthetic(false, "E1");
                case "E2" -> synthetic(true, "E2");
                case "E3" -> baselines();
                case "E4" -> amplitude();
                case "E5" -> decisionWindow();
                case "E6" -> cost();
                case "E7" -> skab();
                case "E8" -> ablation();
                case "FIG" -> exampleFigure();
                default -> throw new IllegalArgumentException(e);
            }
        }
        if (which.size() >= 9) {
            write("SUMMARY.md", SUMMARY.toString());
        } else {
            System.out.println(SUMMARY);
        }
    }

    // ---------------------------------------------------------------- E1, E2

    private static void synthetic(boolean rate, String id) throws IOException {
        StringBuilder txt = new StringBuilder();
        StringBuilder csv = new StringBuilder("model,channels,fault,true_cause,events,detected,delay_median,delay_p90,decision_latency_median,extra,pred_process,pred_instrument,pred_data_path,pred_undetermined\n");
        Tally pooled = new Tally();
        for (ProcessModel m : ProcessModel.values()) {
            Tally t = new Tally();
            for (long seed = Bench.TEST_SEED_FROM; seed < Bench.TEST_SEED_TO; seed++) {
                Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP);
                t.run(s, Detector.triage(Bench.spec(m), TriageConfig.defaults(), rate), Bench.WARMUP, Bench.TAIL);
                pooled.run(s, Detector.triage(Bench.spec(m), TriageConfig.defaults(), rate), Bench.WARMUP, Bench.TAIL);
            }
            txt.append("== ").append(m).append(rate ? ", level + rate channel" : ", level channel only").append('\n');
            txt.append(Report.perTypeTable(t, true)).append('\n');
            csvRows(csv, m.name(), rate ? "level+rate" : "level", t);
        }
        txt.append("== both models pooled\n").append(Report.perTypeTable(pooled, true));
        csvRows(csv, "POOLED", rate ? "level+rate" : "level", pooled);
        write(id + "_" + (rate ? "level_rate" : "level") + ".txt", txt.toString());
        write(id + "_" + (rate ? "level_rate" : "level") + ".csv", csv.toString());
        SUMMARY.append("## ").append(id).append(rate ? " — level + rate channel" : " — level channel only").append(" (both models pooled)\n\n");
        SUMMARY.append(summaryBlock(pooled)).append('\n');
    }

    private static void csvRows(StringBuilder csv, String model, String channels, Tally t) {
        for (FaultType f : FaultType.values()) {
            Tally.PerType p = t.perType.get(f);
            csv.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%d,%.1f,%.1f,%.1f,%d,%d,%d,%d,%d%n", model, channels, f, f.cause(), p.events, p.detected,
                    Tally.quantile(p.delays, 0.5), Tally.quantile(p.delays, 0.9), Tally.quantile(p.latencies, 0.5), p.extra,
                    p.predicted.get(Cause.PROCESS), p.predicted.get(Cause.INSTRUMENT), p.predicted.get(Cause.DATA_PATH), p.predicted.get(Cause.UNDETERMINED)));
        }
        csv.append(String.format(Locale.ROOT, "%s,%s,FALSE_ALARMS,,%d,,,,,,,,,%n", model, channels, t.falseAlarms));
        csv.append(String.format(Locale.ROOT, "%s,%s,MONITORED_HOURS,,%.3f,,,,,,,,,%n", model, channels, t.monitoredSamples / 3600.0));
    }

    private static String summaryBlock(Tally t) {
        StringBuilder sb = new StringBuilder();
        int events = 0;
        int detected = 0;
        int correct = 0;
        int undetermined = 0;
        for (Cause c : new Cause[] {Cause.PROCESS, Cause.INSTRUMENT, Cause.DATA_PATH}) {
            events += t.events(c);
            detected += t.detected(c);
            correct += t.confusion(c, c);
            undetermined += t.confusion(c, Cause.UNDETERMINED);
        }
        sb.append("```\n").append(Report.confusion(t)).append("```\n\n");
        sb.append(String.format(Locale.ROOT, "- events %d, detected %d (%.1f%%); of detected: correct %d (%.1f%%), UNDETERMINED %d (%.1f%%), wrong cause %d (%.1f%%)%n",
                events, detected, pct(detected, events), correct, pct(correct, detected), undetermined, pct(undetermined, detected),
                detected - correct - undetermined, pct(detected - correct - undetermined, detected)));
        sb.append("| true cause | events | detected | precision | recall | F1 | UNDETERMINED, %% of detected |%n|---|---|---|---|---|---|---|%n".formatted());
        for (Cause c : new Cause[] {Cause.PROCESS, Cause.INSTRUMENT, Cause.DATA_PATH}) {
            int tp = t.confusion(c, c);
            int predicted = t.confusion(Cause.PROCESS, c) + t.confusion(Cause.INSTRUMENT, c) + t.confusion(Cause.DATA_PATH, c);
            double p = predicted == 0 ? Double.NaN : (double) tp / predicted;
            double r = t.events(c) == 0 ? Double.NaN : (double) tp / t.events(c);
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% |%n", c, t.events(c), pct(t.detected(c), t.events(c)),
                    100 * p, 100 * r, 100 * 2 * p * r / (p + r), pct(t.confusion(c, Cause.UNDETERMINED), t.detected(c))));
        }
        sb.append("%nPrecision is over decisions matched to an event (false alarms are reported separately); recall is over all events, missed ones included.%n%n".formatted());
        sb.append(String.format(Locale.ROOT, "- detection delay (first trigger − onset), all detected events: median %.0f s, P90 %.0f s%n",
                Tally.quantile(t.delays(null), 0.5), Tally.quantile(t.delays(null), 0.9)));
        for (Cause c : new Cause[] {Cause.PROCESS, Cause.INSTRUMENT, Cause.DATA_PATH}) {
            sb.append(String.format(Locale.ROOT, "- delay, %s events: median %.0f s, P90 %.0f s%n", c, Tally.quantile(t.delays(c), 0.5), Tally.quantile(t.delays(c), 0.9)));
        }
        sb.append(String.format(Locale.ROOT, "- false alarms: %d in %.1f h monitored outside event windows = %.3f per hour%n",
                t.falseAlarms, t.monitoredSamples / 3600.0, t.falseAlarmsPerHour()));
        return sb.toString();
    }

    // ---------------------------------------------------------------- E3

    private static void baselines() throws IOException {
        StringBuilder txt = new StringBuilder();
        StringBuilder csv = new StringBuilder("model,detector,fault,events,detected,delay_median,delay_p90\n");
        Map<String, Tally> pooled = new java.util.LinkedHashMap<>();
        for (ProcessModel m : ProcessModel.values()) {
            List<Supplier<Detector>> detectors = List.of(
                    () -> Detector.triage(Bench.spec(m), TriageConfig.defaults(), false),
                    () -> Detector.rollingZ(Bench.WARMUP, 300, 4.0, 40),
                    () -> Detector.restartedCusum(Bench.WARMUP, 0.5, 8.0, 40));
            for (Supplier<Detector> sup : detectors) {
                Tally t = new Tally();
                String name = sup.get().name();
                Tally pool = pooled.computeIfAbsent(name, k -> new Tally());
                for (long seed = Bench.TEST_SEED_FROM; seed < Bench.TEST_SEED_TO; seed++) {
                    Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP);
                    t.run(s, sup.get(), Bench.WARMUP, Bench.TAIL);
                    pool.run(s, sup.get(), Bench.WARMUP, Bench.TAIL);
                }
                txt.append("== ").append(m).append(", ").append(name).append('\n').append(Report.perTypeTable(t, false)).append('\n');
                for (FaultType f : FaultType.values()) {
                    Tally.PerType p = t.perType.get(f);
                    csv.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%.1f,%.1f%n", m, name, f, p.events, p.detected, Tally.quantile(p.delays, 0.5), Tally.quantile(p.delays, 0.9)));
                }
                csv.append(String.format(Locale.ROOT, "%s,%s,FALSE_ALARMS_PER_HOUR,%.4f,,,%n", m, name, t.falseAlarmsPerHour()));
            }
        }
        write("E3_baselines.txt", txt.toString());
        write("E3_baselines.csv", csv.toString());
        SUMMARY.append("## E3 — detection against baselines (both models pooled; baselines do not attribute)\n\n");
        SUMMARY.append("| detector | detected / events | PROCESS | INSTRUMENT | DATA_PATH | delay median / P90 (s) | false alarms per hour |\n|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Tally> e : pooled.entrySet()) {
            Tally t = e.getValue();
            int ev = 0;
            int det = 0;
            for (Cause c : new Cause[] {Cause.PROCESS, Cause.INSTRUMENT, Cause.DATA_PATH}) {
                ev += t.events(c);
                det += t.detected(c);
            }
            SUMMARY.append(String.format(Locale.ROOT, "| %s | %d / %d (%.1f%%) | %.1f%% | %.1f%% | %.1f%% | %.0f / %.0f | %.3f |%n", e.getKey(), det, ev, pct(det, ev),
                    pct(t.detected(Cause.PROCESS), t.events(Cause.PROCESS)), pct(t.detected(Cause.INSTRUMENT), t.events(Cause.INSTRUMENT)),
                    pct(t.detected(Cause.DATA_PATH), t.events(Cause.DATA_PATH)), Tally.quantile(t.delays(null), 0.5), Tally.quantile(t.delays(null), 0.9), t.falseAlarmsPerHour()));
        }
        SUMMARY.append("\nPer fault type: `E3_baselines.txt`.\n\n");
    }

    // ---------------------------------------------------------------- E4

    private static void amplitude() throws IOException {
        double[] amps = {2, 3, 4, 5, 6, 8, 10};
        StringBuilder csv = new StringBuilder("model,amplitude_sigma,fault,events,detected,correct,pred_process,pred_instrument,pred_data_path,pred_undetermined\n");
        List<Svg.Series> series = new ArrayList<>();
        String[] colors = {"#1f77b4", "#d62728", "#2ca02c", "#ff7f0e"};
        int ci = 0;
        StringBuilder md = new StringBuilder("## E4 — attribution versus step amplitude (level channel only; share of detected events attributed correctly)\n\n| model | fault |");
        for (double a : amps) {
            md.append(String.format(Locale.ROOT, " %.0fσ |", a));
        }
        md.append("\n|---|---|").append("---|".repeat(amps.length)).append('\n');
        for (ProcessModel m : ProcessModel.values()) {
            Map<FaultType, double[]> acc = new EnumMap<>(FaultType.class);
            acc.put(FaultType.LEVEL_SHIFT, new double[amps.length]);
            acc.put(FaultType.BIAS_STEP, new double[amps.length]);
            for (int i = 0; i < amps.length; i++) {
                Tally t = new Tally();
                for (long seed = Bench.TEST_SEED_FROM; seed < Bench.TEST_SEED_TO; seed++) {
                    Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP, List.of(FaultType.LEVEL_SHIFT, FaultType.BIAS_STEP), amps[i]);
                    t.run(s, Detector.triage(Bench.spec(m), TriageConfig.defaults(), false), Bench.WARMUP, Bench.TAIL);
                }
                for (FaultType f : List.of(FaultType.LEVEL_SHIFT, FaultType.BIAS_STEP)) {
                    Tally.PerType p = t.perType.get(f);
                    int correct = p.predicted.get(f.cause());
                    acc.get(f)[i] = pct(correct, p.detected);
                    csv.append(String.format(Locale.ROOT, "%s,%.0f,%s,%d,%d,%d,%d,%d,%d,%d%n", m, amps[i], f, p.events, p.detected, correct,
                            p.predicted.get(Cause.PROCESS), p.predicted.get(Cause.INSTRUMENT), p.predicted.get(Cause.DATA_PATH), p.predicted.get(Cause.UNDETERMINED)));
                }
            }
            for (FaultType f : List.of(FaultType.LEVEL_SHIFT, FaultType.BIAS_STEP)) {
                series.add(new Svg.Series(m.name().toLowerCase() + " " + f.name().toLowerCase().replace('_', ' '), amps, acc.get(f), colors[ci++], f == FaultType.LEVEL_SHIFT));
                md.append("| ").append(m).append(" | ").append(f).append(" |");
                for (double v : acc.get(f)) {
                    md.append(String.format(Locale.ROOT, " %.1f%% |", v));
                }
                md.append('\n');
            }
        }
        write("E4_amplitude.csv", csv.toString());
        Svg.write(OUT.resolve("figures/E4_amplitude.svg"), "Attribution of steps versus amplitude (level channel only)",
                List.of(new Svg.Panel("correctly attributed, % of detected", series, List.of(), new double[] {2, 10}, new double[] {0, 100}, "step amplitude (σ)", "%", true)));
        SUMMARY.append(md).append("\nPlausible-rate bound in the jump test: 5·rate + 2.4σ = 3.15σ (tank), 3.40σ (thermal).\n\n");
    }

    // ---------------------------------------------------------------- E5

    private static void decisionWindow() throws IOException {
        int[] deltas = {20, 40, 80};
        StringBuilder md = new StringBuilder("## E5 — sensitivity to the decision window Δ (both models, level channel only; Δ = 40 is the frozen default)\n\n");
        md.append("| Δ | detected | correct of detected | UNDETERMINED | decision latency median (s) | false alarms per hour |\n|---|---|---|---|---|---|\n");
        StringBuilder csv = new StringBuilder("delta,events,detected,correct,undetermined,latency_median,false_alarms_per_hour\n");
        for (int d : deltas) {
            TriageConfig cfg = Configs.with(TriageConfig.defaults(), "decisionWindow", d);
            Tally t = new Tally();
            for (ProcessModel m : ProcessModel.values()) {
                for (long seed = Bench.TEST_SEED_FROM; seed < Bench.TEST_SEED_TO; seed++) {
                    t.run(Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP), Detector.triage(Bench.spec(m), cfg, false), Bench.WARMUP, Bench.TAIL);
                }
            }
            int ev = 0;
            int det = 0;
            int cor = 0;
            int und = 0;
            List<Integer> lat = new ArrayList<>();
            for (FaultType f : FaultType.values()) {
                Tally.PerType p = t.perType.get(f);
                ev += p.events;
                det += p.detected;
                cor += p.predicted.get(f.cause());
                und += p.predicted.get(Cause.UNDETERMINED);
                lat.addAll(p.latencies);
            }
            md.append(String.format(Locale.ROOT, "| %d | %.1f%% | %.1f%% | %.1f%% | %.0f | %.3f |%n", d, pct(det, ev), pct(cor, det), pct(und, det), Tally.quantile(lat, 0.5), t.falseAlarmsPerHour()));
            csv.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.1f,%.4f%n", d, ev, det, cor, und, Tally.quantile(lat, 0.5), t.falseAlarmsPerHour()));
        }
        write("E5_decision_window.csv", csv.toString());
        SUMMARY.append(md).append('\n');
    }

    // ---------------------------------------------------------------- E6

    private static void cost() throws IOException {
        int n = 1_000_000;
        Scenario s = Scenario.generate(ProcessModel.TANK, 1, n, Bench.GAP);
        StringBuilder md = new StringBuilder("## E6 — cost per sample (one stream of " + n + " samples, tank model, all fault types injected)\n\n");
        md.append("| configuration | median ns per sample (5 timed passes after 3 warm-up passes) | min | max |\n|---|---|---|---|\n");
        StringBuilder csv = new StringBuilder("configuration,pass,ns_per_sample\n");
        long buckets = 0;
        for (boolean rate : new boolean[] {false, true}) {
            double[] timings = new double[5];
            for (int pass = 0; pass < 8; pass++) {
                TriageChannel ch = new TriageChannel(Bench.spec(ProcessModel.TANK), TriageConfig.defaults());
                long t0 = System.nanoTime();
                long sink = 0;
                for (int i = 0; i < n; i++) {
                    Decision d = rate ? ch.update(s.y[i], s.rate[i]) : ch.update(s.y[i]);
                    if (d != null) {
                        sink++;
                    }
                }
                long t1 = System.nanoTime();
                if (pass >= 3) {
                    timings[pass - 3] = (t1 - t0) / (double) n;
                    csv.append(String.format(Locale.ROOT, "%s,%d,%.1f%n", rate ? "level+rate" : "level", pass - 3, timings[pass - 3]));
                }
                if (sink < 0) {
                    System.out.println(sink);
                }
                buckets = Math.max(buckets, ch.adwinBuckets());
            }
            double[] sorted = timings.clone();
            Arrays.sort(sorted);
            md.append(String.format(Locale.ROOT, "| %s | %.0f | %.0f | %.0f |%n", rate ? "level + rate" : "level only", sorted[2], sorted[0], sorted[4]));
        }
        TriageChannel probe = new TriageChannel(Bench.spec(ProcessModel.TANK), TriageConfig.defaults());
        md.append(String.format(Locale.ROOT, "%nFixed-size state per channel: %d doubles in arrays sized by the configuration (independent of stream length), "
                + "plus %d boxed values in the two extremum aggregators. ADWIN keeps O(log W) buckets; the most seen over these passes: %d.%n%n",
                probe.fixedStateDoubles(), 2 * TriageConfig.defaults().extremumWindow(), buckets));
        md.append("Machine: ").append(System.getProperty("os.name")).append(", ").append(Runtime.getRuntime().availableProcessors())
                .append(" logical processors, ").append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.version"))
                .append(". Wall-clock timing with System.nanoTime, single thread; not a JMH benchmark.\n\n");
        write("E6_cost.csv", csv.toString());
        SUMMARY.append(md);
    }

    // ---------------------------------------------------------------- E7

    private static void skab() throws IOException {
        Path root = Path.of("datasets", "skab");
        if (!Files.isDirectory(root)) {
            SUMMARY.append("## E7 — SKAB\n\nDataset not found; run `datasets/download_skab.sh`.\n\n");
            return;
        }
        List<SkabLoader.SkabFile> files = SkabLoader.readAll(root);
        TriageConfig cfg = Configs.with(Configs.with(TriageConfig.defaults(), "calibrationA", 200), "calibrationB", 200);
        int warmup = 400;
        String[] names = {"triage (level only)", "rolling z-score", "restarted CUSUM"};
        int[] detected = new int[3];
        List<List<Integer>> delays = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        long[] falseAlarms = new long[3];
        long monitored = 0;
        long[] freeFalseAlarms = new long[3];
        long freeMonitored = 0;
        Map<Cause, Integer> attributed = new EnumMap<>(Cause.class);
        Map<Cause, Integer> faCauses = new EnumMap<>(Cause.class);
        Map<Cause, Integer> freeFaCauses = new EnumMap<>(Cause.class);
        for (Cause c : Cause.values()) {
            attributed.put(c, 0);
            faCauses.put(c, 0);
            freeFaCauses.put(c, 0);
        }
        int evaluated = 0;
        List<String> excluded = new ArrayList<>();
        StringBuilder csv = new StringBuilder("file,rows,first_anomaly,last_anomaly,monitored_channels,detector,detected,delay_s,false_alarms,first_cause,first_channel\n");
        for (SkabLoader.SkabFile f : files) {
            int first = f.firstAnomaly();
            boolean free = first < 0;
            if (!free && first < warmup + 1) {
                excluded.add(f.name() + " (first anomaly at row " + first + ", before the end of the " + warmup + "-row warm-up)");
                continue;
            }
            int last = f.lastAnomaly();
            if (!free) {
                evaluated++;
            }
            long mon = 0;
            for (int i = warmup; i < f.rows(); i++) {
                if (free || i < first || i > last + Bench.TAIL) {
                    mon++;
                }
            }
            if (free) {
                freeMonitored += mon;
            } else {
                monitored += mon;
            }
            for (int k = 0; k < 3; k++) {
                long fa = 0;
                long firstTrigger = Long.MAX_VALUE;
                Cause firstCause = null;
                String firstChannel = "";
                int channels = 0;
                for (int c = 0; c < f.channels().length; c++) {
                    double[] x = f.channel(c);
                    ChannelSpec spec = ChannelSpec.unconstrained(f.channels()[c]);
                    TriageChannel tc = k == 0 ? new TriageChannel(spec, cfg) : null;
                    Detector d = k == 1 ? Detector.rollingZ(warmup, 300, 4.0, 40) : k == 2 ? Detector.restartedCusum(warmup, 0.5, 8.0, 40) : null;
                    List<Decision> ds = new ArrayList<>();
                    boolean degenerate = false;
                    for (int i = 0; i < x.length; i++) {
                        Decision dec = k == 0 ? tc.update(x[i]) : d.step(x[i], Double.NaN);
                        if (k == 0 && i == cfg.calibrationA() && tc.degenerate()) {
                            degenerate = true;
                            break;
                        }
                        if (dec != null) {
                            ds.add(dec);
                        }
                    }
                    if (degenerate || (k > 0 && constant(x, warmup))) {
                        continue;
                    }
                    channels++;
                    for (Decision dec : ds) {
                        int ti = (int) dec.triggerIndex();
                        if (ti < warmup) {
                            continue;
                        }
                        if (!free && ti >= first && ti <= last) {
                            if (ti < firstTrigger) {
                                firstTrigger = ti;
                                firstCause = dec.cause();
                                firstChannel = f.channels()[c];
                            }
                        } else if (free || ti < first || ti > last + Bench.TAIL) {
                            fa++;
                            if (k == 0) {
                                (free ? freeFaCauses : faCauses).merge(dec.cause(), 1, Integer::sum);
                            }
                        }
                    }
                }
                if (free) {
                    freeFalseAlarms[k] += fa;
                } else {
                    falseAlarms[k] += fa;
                    if (firstTrigger != Long.MAX_VALUE) {
                        detected[k]++;
                        delays.get(k).add((int) (firstTrigger - first));
                        if (k == 0) {
                            attributed.merge(firstCause, 1, Integer::sum);
                        }
                    }
                }
                csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%s,%s,%s,%d,%s,%s%n", f.name(), f.rows(), first, last, channels, names[k],
                        free ? "" : String.valueOf(firstTrigger != Long.MAX_VALUE), firstTrigger != Long.MAX_VALUE ? String.valueOf(firstTrigger - first) : "",
                        fa, firstCause == null ? "" : firstCause.name(), firstChannel));
            }
        }
        write("E7_skab.csv", csv.toString());
        StringBuilder md = new StringBuilder("## E7 — SKAB (Skoltech Anomaly Benchmark)\n\n");
        md.append(String.format(Locale.ROOT, "%d labelled files evaluated; warm-up %d rows (calibration A = 200, B = 200 — the only change from the synthetic configuration, "
                + "because SKAB anomalies begin near row 570); a channel constant during calibration is not monitored. A file counts as detected when any channel triggers on a "
                + "labelled anomaly row; delay is from the first labelled row. False alarms: triggers on unlabelled rows after the warm-up, excluding %d rows after the last "
                + "labelled row.%n%n", evaluated, warmup, Bench.TAIL));
        if (!excluded.isEmpty()) {
            md.append("Excluded: ").append(String.join("; ", excluded)).append(".\n\n");
        }
        md.append(String.format(Locale.ROOT, "| detector | detected files | delay median / P90 (s) | false alarms per hour, labelled files (%.2f h) | false alarms per hour, anomaly-free file (%.2f h) |%n|---|---|---|---|---|%n",
                monitored / 3600.0, freeMonitored / 3600.0));
        for (int k = 0; k < 3; k++) {
            md.append(String.format(Locale.ROOT, "| %s | %d / %d | %.0f / %.0f | %.2f | %.2f |%n", names[k], detected[k], evaluated, Tally.quantile(delays.get(k), 0.5),
                    Tally.quantile(delays.get(k), 0.9), falseAlarms[k] / (monitored / 3600.0), freeFalseAlarms[k] / (freeMonitored / 3600.0)));
        }
        md.append("\nCause attributed by the first in-anomaly decision of the method (descriptive only; SKAB labels anomalies, not causes): ").append(attributed).append(".\n\n");
        md.append("False alarms of the method by attributed cause: labelled files ").append(faCauses).append("; anomaly-free file ").append(freeFaCauses).append(".\n\n");
        SUMMARY.append(md);
    }

    private static boolean constant(double[] x, int n) {
        for (int i = 1; i < Math.min(n, x.length); i++) {
            if (x[i] != x[0]) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- E8

    private static void ablation() throws IOException {
        Map<String, TriageConfig> variants = new java.util.LinkedHashMap<>();
        variants.put("full method", TriageConfig.defaults());
        variants.put("no plausible-rate test", Configs.with(TriageConfig.defaults(), "jumpMargin", Double.POSITIVE_INFINITY));
        variants.put("no deferral (horizon = Δ)", Configs.with(TriageConfig.defaults(), "driftHorizon", TriageConfig.defaults().decisionWindow()));
        StringBuilder md = new StringBuilder("## E8 — ablations (both models, level channel only)\n\n");
        StringBuilder txt = new StringBuilder();
        md.append("| variant | correct of detected | BIAS_STEP correct | LEVEL_SHIFT correct | RAMP correct | UNDETERMINED |\n|---|---|---|---|---|---|\n");
        for (Map.Entry<String, TriageConfig> v : variants.entrySet()) {
            Tally t = new Tally();
            for (ProcessModel m : ProcessModel.values()) {
                for (long seed = Bench.TEST_SEED_FROM; seed < Bench.TEST_SEED_TO; seed++) {
                    t.run(Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP), Detector.triage(Bench.spec(m), v.getValue(), false), Bench.WARMUP, Bench.TAIL);
                }
            }
            int det = 0;
            int cor = 0;
            int und = 0;
            for (FaultType f : FaultType.values()) {
                Tally.PerType p = t.perType.get(f);
                det += p.detected;
                cor += p.predicted.get(f.cause());
                und += p.predicted.get(Cause.UNDETERMINED);
            }
            md.append(String.format(Locale.ROOT, "| %s | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% |%n", v.getKey(), pct(cor, det), typeAcc(t, FaultType.BIAS_STEP),
                    typeAcc(t, FaultType.LEVEL_SHIFT), typeAcc(t, FaultType.RAMP), pct(und, det)));
            txt.append("== ").append(v.getKey()).append('\n').append(Report.perTypeTable(t, true)).append('\n');
        }
        write("E8_ablation.txt", txt.toString());
        SUMMARY.append(md).append('\n');
    }

    private static double typeAcc(Tally t, FaultType f) {
        Tally.PerType p = t.perType.get(f);
        return pct(p.predicted.get(f.cause()), p.detected);
    }

    // ---------------------------------------------------------------- figure

    private static void exampleFigure() throws IOException {
        ProcessModel m = ProcessModel.TANK;
        long seed = Bench.TEST_SEED_FROM;
        Scenario s = Scenario.generate(m, seed, Bench.LENGTH, Bench.GAP);
        List<Decision> level = run(s, false);
        List<Decision> both = run(s, true);
        List<Svg.Panel> panels = new ArrayList<>();
        for (FaultType f : List.of(FaultType.LEVEL_SHIFT, FaultType.BIAS_STEP, FaultType.LINEAR_DRIFT, FaultType.REPLAY)) {
            Event e = s.events.stream().filter(x -> x.type() == f).findFirst().orElseThrow();
            int from = Math.max(0, e.start() - 150);
            int to = Math.min(s.length(), e.end() + 260);
            double[] xs = Svg.indices(from, to);
            double[] ys = Arrays.copyOfRange(s.y, from, to);
            // the sensor bias accumulated from earlier injected events is removed for display: the true level is
            // drawn shifted by the median offset between transmitted and true values before the onset
            double[] offsets = new double[Math.min(100, e.start() - from)];
            for (int i = 0; i < offsets.length; i++) {
                offsets[i] = s.y[e.start() - 1 - i] - s.truth[e.start() - 1 - i];
            }
            Arrays.sort(offsets);
            double offset = offsets[offsets.length / 2];
            double[] tr = Arrays.copyOfRange(s.truth, from, to);
            for (int i = 0; i < tr.length; i++) {
                tr[i] += offset;
            }
            List<Svg.Marker> markers = new ArrayList<>();
            markers.add(new Svg.Marker(e.start(), "#555", "onset"));
            addMarkers(markers, level, from, to, "#d62728", "");
            if (f == FaultType.LINEAR_DRIFT) {
                addMarkers(markers, both, from, to, "#2ca02c", " (with rate)");
            }
            panels.add(new Svg.Panel(f.label() + " — test seed " + seed + ", tank model", Svg.list(
                    new Svg.Series("transmitted value", xs, ys, "#1f77b4", false),
                    new Svg.Series("true level (aligned before onset)", xs, tr, "#444", true)), markers, null, null, "sample (s)", "level (cm)", false));
        }
        Svg.write(OUT.resolve("figures/example_events.svg"), "Detector bank and attribution on four injected events", panels);
    }

    private static void addMarkers(List<Svg.Marker> markers, List<Decision> ds, int from, int to, String color, String suffix) {
        for (Decision d : ds) {
            if (d.decisionIndex() >= from && d.triggerIndex() < to) {
                markers.add(new Svg.Marker(d.triggerIndex(), "#ff7f0e", "trigger"));
                markers.add(new Svg.Marker(d.decisionIndex(), color, d.cause().name() + suffix));
            }
        }
    }

    private static List<Decision> run(Scenario s, boolean rate) {
        TriageChannel ch = new TriageChannel(Bench.spec(s.model), TriageConfig.defaults());
        List<Decision> out = new ArrayList<>();
        for (int t = 0; t < s.length(); t++) {
            Decision d = rate ? ch.update(s.y[t], s.rate[t]) : ch.update(s.y[t]);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static double pct(int a, int b) {
        return b == 0 ? Double.NaN : 100.0 * a / b;
    }

    private static void write(String name, String content) throws IOException {
        Files.writeString(OUT.resolve(name), content, StandardCharsets.UTF_8);
        System.out.println("wrote results/" + name);
    }
}
