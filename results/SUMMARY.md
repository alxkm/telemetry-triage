# Results summary

Generated 2026-10-05T21:16:29.4023646+02:00 by `triage.bench.Experiments` on OpenJDK 64-Bit Server VM 21, Windows 10.

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
| level only | 7014 | 6624 | 7719 |
| level + rate | 7196 | 6734 | 7702 |

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

## E9 — semi-synthetic: the catalogue injected into recorded SKAB channels (level channel only)

Base: the eight channels of SKAB `anomaly-free.csv` (9,405 rows, 1 Hz). Amplitudes in units of each channel's noise as the method estimates it (the σ̂ of its own calibration phase A on the first 300 rows: the robust first-difference estimate, or the standard deviation where the channel is quantized); process changes are set-point moves limited to 0.15σ per sample, declared as the plausible rate; seeds 3000–3019 per channel, run once. Caveat: this file was used during development to fix two calibration rules (TUNING.md, change 9); no attribution threshold was tuned on it.

| channel | σ | events | detected | correct of detected | undetermined | false alarms per hour |
|---|---|---|---|---|---|---|
| Accelerometer1RMS | 0.001086 | 255 | 82.7% | 83.9% | 0.0% | 9.95 |
| Accelerometer2RMS | 0.001370 | 255 | 93.7% | 91.6% | 2.5% | 1.93 |
| Current | 0.2760 | 255 | 80.4% | 75.1% | 0.5% | 10.04 |
| Pressure | 0.2869 | 255 | 76.1% | 86.6% | 4.1% | 0.85 |
| Temperature | 0.1228 | 255 | 69.8% | 74.7% | 0.0% | 2.27 |
| Thermocouple | 0.04210 | 255 | 73.7% | 87.8% | 2.7% | 0.51 |
| Voltage | 11.01 | 255 | 96.1% | 91.4% | 2.4% | 0.06 |
| Volume Flow RateRMS | 0.5076 | 255 | 74.1% | 81.0% | 2.6% | 3.19 |

All channels pooled:

```
truth\pred    events  missed    PROC    INST    DATA   UNDET  acc(det)
PROCESS          472     104     280      77       2       9     76.1%
INSTRUMENT       784     171      91     493       7      22     80.4%
DATA_PATH        784     116      17      31     620       0     92.8%
```

- events 2040, detected 1649 (80.8%); of detected: correct 1393 (84.5%), UNDETERMINED 31 (1.9%), wrong cause 225 (13.6%)
| true cause | events | detected | precision | recall | F1 | UNDETERMINED, % of detected |
|---|---|---|---|---|---|---|
| PROCESS | 472 | 78.0% | 72.2% | 59.3% | 65.1% | 2.4% |
| INSTRUMENT | 784 | 78.2% | 82.0% | 62.9% | 71.2% | 3.6% |
| DATA_PATH | 784 | 85.2% | 98.6% | 79.1% | 87.8% | 0.0% |

Precision is over decisions matched to an event (false alarms are reported separately); recall is over all events, missed ones included.

- detection delay (first trigger − onset), all detected events: median 3 s, P90 77 s
- delay, PROCESS events: median 17 s, P90 151 s
- delay, INSTRUMENT events: median 6 s, P90 86 s
- delay, DATA_PATH events: median 0 s, P90 3 s
- false alarms: 912 in 253.3 h monitored outside event windows = 3.600 per hour

## E10 — 95% bootstrap intervals over runs (test seeds, both models; 2,000 resamples of the 100 runs)

| channels | detected | correct of detected | undetermined of detected | false alarms per hour |
|---|---|---|---|---|
| level only | 95.9% [95.1, 96.7] | 90.6% [90.0, 91.3] | 2.2% [1.7, 2.8] | 0.210 [0.157, 0.263] |
| level + rate | 96.0% [95.3, 96.8] | 99.1% [98.7, 99.4] | 0.0% [0.0, 0.0] | 0.210 [0.157, 0.263] |

## E11 — why events were missed (test seeds, both models, level channel only)

*Absorbed*: a decision window opened before the onset by an earlier trigger was still open at the onset, so the event was decided inside it and its trigger is not in the event window. *Silent*: no window was open and no detector fired within the event window.

| injection | events | missed | absorbed | silent |
|---|---|---|---|---|
| LEVEL_SHIFT | 218 | 24 | 16 | 8 |
| RAMP | 230 | 28 | 3 | 25 |
| REGIME_CHANGE | 218 | 38 | 3 | 35 |
| BIAS_STEP | 222 | 4 | 4 | 0 |
| LINEAR_DRIFT | 236 | 20 | 6 | 14 |
| SUBSTITUTION | 232 | 2 | 2 | 0 |
| REPLAY | 228 | 2 | 2 | 0 |
| ZERO_OR_BOUNDARY | 222 | 2 | 2 | 0 |
| all | | 120 | 38 | 82 |

