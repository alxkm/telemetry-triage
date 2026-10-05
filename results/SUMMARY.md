# Results summary

Generated 2026-10-04T22:07:43.5054851+02:00 by `triage.bench.Experiments` on OpenJDK 64-Bit Server VM 21, Windows 10.

Synthetic runs: 20000 samples each, test seeds 2000–2049 (50 per process model). One sample = 1 s.

## E1 — level channel only (both models pooled)

```
truth\pred    events  missed    PROC    INST    DATA   UNDET  acc(det)
PROCESS          666      90     536       9       1      30     93.1%
INSTRUMENT      1124      24     189     879       0      32     79.9%
DATA_PATH       1140       6       2       0    1132       0     99.8%
```

- events 2930, detected 2810 (95.9%); of detected: correct 2547 (90.6%), UNDETERMINED 62 (2.2%), wrong cause 201 (7.2%)
| true cause | events | detected | precision | recall | F1 | UNDETERMINED, % of detected |
|---|---|---|---|---|---|---|
| PROCESS | 666 | 86.5% | 73.7% | 80.5% | 77.0% | 5.2% |
| INSTRUMENT | 1124 | 97.9% | 99.0% | 78.2% | 87.4% | 2.9% |
| DATA_PATH | 1140 | 99.5% | 99.9% | 99.3% | 99.6% | 0.0% |

Precision is over decisions matched to an event (false alarms are reported separately); recall is over all events, missed ones included.

- detection delay (first trigger − onset), all detected events: median 3 s, P90 35 s
- delay, PROCESS events: median 20 s, P90 81 s
- delay, INSTRUMENT events: median 4 s, P90 36 s
- delay, DATA_PATH events: median 0 s, P90 3 s
- false alarms: 71 in 337.6 h monitored outside event windows = 0.210 per hour

## E2 — level + rate channel (both models pooled)

```
truth\pred    events  missed    PROC    INST    DATA   UNDET  acc(det)
PROCESS          666      90     566       9       1       0     98.3%
INSTRUMENT      1124      20      14    1090       0       0     98.7%
DATA_PATH       1140       6       2       0    1132       0     99.8%
```

- events 2930, detected 2814 (96.0%); of detected: correct 2788 (99.1%), UNDETERMINED 0 (0.0%), wrong cause 26 (0.9%)
| true cause | events | detected | precision | recall | F1 | UNDETERMINED, % of detected |
|---|---|---|---|---|---|---|
| PROCESS | 666 | 86.5% | 97.3% | 85.0% | 90.7% | 0.0% |
| INSTRUMENT | 1124 | 98.2% | 99.2% | 97.0% | 98.1% | 0.0% |
| DATA_PATH | 1140 | 99.5% | 99.9% | 99.3% | 99.6% | 0.0% |

Precision is over decisions matched to an event (false alarms are reported separately); recall is over all events, missed ones included.

- detection delay (first trigger − onset), all detected events: median 3 s, P90 35 s
- delay, PROCESS events: median 20 s, P90 81 s
- delay, INSTRUMENT events: median 4 s, P90 36 s
- delay, DATA_PATH events: median 0 s, P90 3 s
- false alarms: 71 in 337.6 h monitored outside event windows = 0.210 per hour

## E3 — detection against baselines (both models pooled; baselines do not attribute)

| detector | detected / events | PROCESS | INSTRUMENT | DATA_PATH | delay median / P90 (s) | false alarms per hour |
|---|---|---|---|---|---|---|
| triage (level only) | 2810 / 2930 (95.9%) | 86.5% | 97.9% | 99.5% | 3 / 35 | 0.210 |
| rolling z-score | 1797 / 2930 (61.3%) | 65.3% | 60.0% | 60.4% | 0 / 78 | 0.412 |
| restarted CUSUM | 2386 / 2930 (81.4%) | 100.0% | 86.2% | 65.9% | 13 / 72 | 9.712 |

Per fault type: `E3_baselines.txt`.

## E4 — attribution versus step amplitude (level channel only; share of detected events attributed correctly)

| model | fault | 2σ | 3σ | 4σ | 5σ | 6σ | 8σ | 10σ |
|---|---|---|---|---|---|---|---|---|
| TANK | LEVEL_SHIFT | 92.0% | 97.5% | 98.3% | 98.2% | 97.8% | 97.2% | 96.8% |
| TANK | BIAS_STEP | 23.9% | 51.1% | 88.7% | 98.8% | 100.0% | 100.0% | 100.0% |
| THERMAL | LEVEL_SHIFT | 84.2% | 94.9% | 99.1% | 99.3% | 99.6% | 99.7% | 99.4% |
| THERMAL | BIAS_STEP | 11.5% | 36.5% | 78.7% | 97.7% | 99.8% | 100.0% | 100.0% |

Plausible-rate bound in the jump test: 5·rate + 2.4σ = 3.15σ (tank), 3.40σ (thermal).

## E5 — sensitivity to the decision window Δ (both models, level channel only; Δ = 40 is the frozen default)

| Δ | detected | correct of detected | UNDETERMINED | decision latency median (s) | false alarms per hour |
|---|---|---|---|---|---|
| 20 | 95.9% | 88.2% | 2.2% | 23 | 0.228 |
| 40 | 95.9% | 90.6% | 2.2% | 43 | 0.210 |
| 80 | 95.9% | 90.3% | 2.3% | 83 | 0.228 |

## E6 — cost per sample (one stream of 1000000 samples, tank model, all fault types injected)

| configuration | median ns per sample (5 timed passes after 3 warm-up passes) | min | max |
|---|---|---|---|
| level only | 6887 | 6749 | 7292 |
| level + rate | 6883 | 6814 | 7266 |

Fixed-size state per channel: 1296 doubles in arrays sized by the configuration (independent of stream length), plus 32 boxed values in the two extremum aggregators. ADWIN keeps O(log W) buckets; the most seen over these passes: 31.

Machine: Windows 10, 12 logical processors, OpenJDK 64-Bit Server VM 21. Wall-clock timing with System.nanoTime, single thread; not a JMH benchmark.

## E7 — SKAB (Skoltech Anomaly Benchmark)

33 labelled files evaluated; warm-up 400 rows (calibration A = 200, B = 200 — the only change from the synthetic configuration, because SKAB anomalies begin near row 570); a channel constant during calibration is not monitored. A file counts as detected when any channel triggers on a labelled anomaly row; delay is from the first labelled row. False alarms: triggers on unlabelled rows after the warm-up, excluding 120 rows after the last labelled row.

Excluded: other/2.csv (first anomaly at row 104, before the end of the 400-row warm-up).

| detector | detected files | delay median / P90 (s) | false alarms per hour, labelled files (2.00 h) | false alarms per hour, anomaly-free file (2.50 h) |
|---|---|---|---|---|
| triage (level only) | 32 / 33 | 15 / 54 | 46.01 | 33.98 |
| rolling z-score | 33 / 33 | 35 / 66 | 11.00 | 11.99 |
| restarted CUSUM | 33 / 33 | 5 / 22 | 179.52 | 173.10 |

Cause attributed by the first in-anomaly decision of the method (descriptive only; SKAB labels anomalies, not causes): {PROCESS=21, INSTRUMENT=3, DATA_PATH=6, UNDETERMINED=2}.

False alarms of the method by attributed cause: labelled files {PROCESS=80, INSTRUMENT=2, DATA_PATH=6, UNDETERMINED=4}; anomaly-free file {PROCESS=70, INSTRUMENT=1, DATA_PATH=11, UNDETERMINED=3}.

## E8 — ablations (both models, level channel only)

| variant | correct of detected | BIAS_STEP correct | LEVEL_SHIFT correct | RAMP correct | UNDETERMINED |
|---|---|---|---|---|---|
| full method | 90.6% | 97.7% | 99.5% | 85.1% | 2.2% |
| no plausible-rate test | 83.3% | 0.0% | 100.0% | 86.1% | 2.2% |
| no deferral (horizon = Δ) | 70.7% | 97.7% | 0.0% | 0.5% | 29.0% |

