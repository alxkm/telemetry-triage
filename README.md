# telemetry-triage

Reference implementation and benchmark for the paper *Distinguishing Process Change, Instrument Fault and
Data-Path Corruption in Streaming Telemetry*.

A reading departs from expectation for one of three reasons: the **process** changed, the **instrument** is
failing, or the **data path** corrupted the value. `TriageChannel` runs a bank of online detectors on one
channel and attributes each confirmed departure to one of the three causes, or to `UNDETERMINED` when one
channel cannot separate them.

## Reproduce every number

Requirements: JDK 21, Maven 3.9, bash (for the dataset script). No runtime dependencies.

```
datasets/download_skab.sh                     # SKAB, GPL-3.0, verified against datasets/skab.sha256
mvn -q package                                # builds and runs the unit tests
java -cp target/classes triage.bench.Experiments
```

The last command takes about eight minutes on one core and writes `results/`:

| file | content |
|---|---|
| `SUMMARY.md` | every headline number of the paper, with the environment it was produced on |
| `config.txt` | the frozen configuration |
| `E1_level.*`, `E2_level_rate.*` | detection and attribution per fault type, one channel and with the rate channel |
| `E3_baselines.*` | detection against a rolling z-score and a restarted CUSUM |
| `E4_amplitude.csv`, `figures/E4_amplitude.svg` | attribution of steps versus amplitude |
| `E5_decision_window.csv` | sensitivity to the decision window Δ |
| `E6_cost.csv` | ns per sample and state size |
| `E7_skab.csv` | SKAB, per file and detector |
| `E8_ablation.txt` | without the plausible-rate test; without deferral |
| `E9_semisynthetic.*` | the catalogue injected into the eight recorded channels of SKAB's anomaly-free file |
| `E10_bootstrap.csv` | 95% bootstrap intervals over the 100 test runs |
| `E11_misses.csv` | missed events, absorbed by an open window or silent |
| `figures/example_events.svg` | four injected events with triggers and decisions |

Synthetic results are deterministic for a given seed. Single experiments: `... Experiments E4 E7`.

## Layout

```
src/main/java/com/thealgorithms/   vendored, unmodified, MIT — see below
src/main/java/triage/core/         the method: TriageChannel, TriageConfig, ChannelSpec, Decision, Cause
src/main/java/triage/bench/        benchmark: process models, fault injection, scoring, baselines, SKAB, plots
src/test/java/                     unit tests
docs/TUNING.md                     every change made on development data before the freeze
```

## Vendored library code

The detectors are taken unmodified from [TheAlgorithms/Java](https://github.com/TheAlgorithms/Java) at commit
`3f067087f217afc6c16f0d0ca90d194bd7a55726` (MIT, `docs/LICENSE-TheAlgorithms`). They were contributed by the
author in TheAlgorithms/Java#7591, #7592, #7593, #7598, #7600, #7602, #7603, #7606, #7612, #7614, #7615,
#7619 and #7622, each approved and merged by a maintainer of that project. `MedianFilter` and
`ExponentialMovingAverage` arrived with #7598 and #7600 as dependencies of `HampelFilter` and
`EwmaChangeDetector`.

Used in the evaluated bank: `KalmanFilter`, `ComplementaryFilter`, `CusumDetector`, `EwmaChangeDetector`,
`HampelFilter` (window median), `GeneralizedEsd`, `WelfordAlgorithm`, `P2QuantileEstimator`, `Adwin`,
`SlidingWindowAggregator`. Vendored but **not** used in any reported result: `ExtendedKalmanFilter`,
`UnscentedKalmanFilter`, `DynamicTimeWarping`, `InverseOfMatrix`.

## Protocol

- Development seeds 1000–1019 and SKAB's `anomaly-free.csv` (its training file) were used to fix the
  configuration; `docs/TUNING.md` lists every change. Test seeds 2000–2049 and the 34 labelled SKAB files
  were run only after the freeze.
- On SKAB the only change is a shorter calibration (200 + 200 rows), because the anomalies begin near row 570.
- E9 injects into SKAB's anomaly-free file, which was used during development for two calibration rules (TUNING.md, change 9); no attribution threshold was tuned on it. Its seeds (3000–3019) were run once.
- One sample is taken as one second for rates per hour.

## Departures from the original plan (`code_plan.md`)

- **Noise growth** is tested on the robust spread of first differences (median |Δy| over 40 samples relative to
  calibration), not on the windowed variance of the Kalman innovation: the latter reacts to a moving process.
- **Hampel scale** is the calibrated σ (with a data-driven floor), not the MAD of the 11-sample window, whose
  sampling error let 5–6σ outliers through.
- **Replay** (a segment of earlier values re-sent) replaces "exact duplicate run"; a duplicated *consecutive*
  value is indistinguishable from a stuck instrument.
- **Isolation forest** was not implemented; the baselines are a rolling z-score and a restarted CUSUM.
- **Timing** uses `System.nanoTime` over 10⁶ samples after warm-up, not JMH.
- **DTW, EKF, UKF** are not part of the evaluated rule.

## Licence

Code in `triage/`: MIT. Vendored code: MIT (TheAlgorithms). SKAB data is not redistributed here (GPL-3.0); the
script downloads it.
