# Tuning log

Written before the first run on the test seeds. Every change below was made while looking only at
**development seeds 1000–1019** (`triage.bench.Dev`, `Probe`, `Trace`) or, for real data, only at SKAB's
**`anomaly-free/anomaly-free.csv`**, which SKAB designates as training data (`triage.bench.SkabDev`).
The labelled SKAB files and the test seeds 2000–2049 were not used for any decision.

Freeze: 2026-10-04, after change 9. `TriageConfig.defaults()` and `TriageChannel` are not to be changed for
the results in `results/`; any later change requires re-running all experiments and recording it here.

## Starting point

The design of `code_plan.md`: Hampel (window 11, 4 scaled MADs) with GESD confirmation; scalar Kalman tracker
(q = 0.002σ², r = σ²); CUSUM (k 0.5, h 8) and EWMA (λ 0.1, L 4) on the standardized innovation; windowed
Welford variance of innovations and ADWIN for noise growth; exact-value checks for stuck, replay and
saturation; decision window Δ = 40, drift horizon 240.

First development run (tank, level only): false alarms 0.86 per hour, 49 of 59 attributed to DATA_PATH.

## Changes, in order

1. **Hampel scale.** The MAD of an 11-sample window has a large sampling error; outliers of 5–6σ passed under
   the threshold (dev seed 1000, sample 5609). The window median still comes from `HampelFilter`; the scale
   is now the calibrated σ, threshold 4.5σ. False alarms 0.86 → 0.16 per hour.
2. **Saturation window.** The extremum window of 300 delayed saturation detection to ~62 samples, because
   older, higher values had to leave the window. Window 16, repeats counted over 64. Median delay 62 → 16.
3. **Latch per kind of evidence.** One persistent condition (noise growth of 200–400 samples) produced up to
   ~4 decisions per event. After a decision, the kinds of evidence that led to it are latched for the hold-off,
   which they extend while they keep firing; a different kind opens a new decision at once (this also fixed a
   zero value lost in the hold-off after an unrelated decision, dev seed 1005, sample 8135).
4. **Noise statistic.** The windowed variance of the Kalman innovation reacted to a moving process (a ramp
   biases the innovation), sending process ramps and regime changes to INSTRUMENT. Replaced by the median of
   |Δy| over 40 samples relative to calibration (smooth process movement barely changes it; one outlier does
   not move the median). ADWIN watches the mean of the clipped |Δy|. Floor 1.8× (smallest injected noise
   growth is 2.5×).
5. **Generator fix (not the method).** The regime-change oscillation was added after the rate limiter, so the
   simulated process violated its own declared plausible rate. The oscillation now acts on the setpoint.
6. **Noise must persist.** A transient excursion of the noise statistic opened decisions that were then
   attributed to INSTRUMENT. The noise rule now also requires the statistic above threshold at decision time.
   False alarms 0.31 → 0.09 per hour.
7. **Dismissal does not latch.** A window dismissed as "no event confirmed" latched its CUSUM evidence, so a
   slow process change kept extending the hold-off and was never decided (thermal regime changes: 48.8%
   detected). Dismissal now latches nothing and leaves the detectors' state intact.
8. **Carry-over after dismissal.** A slow change (thermal first-order response) does not reach 2σ in Δ = 40
   samples; each window was dismissed and its baseline lost. After a dismissal, a change alarm within the
   drift horizon continues the earlier window (same baseline and trigger index). Thermal regime change
   detection 53.5% → 79.1%.
9. **Real-data calibration (SKAB anomaly-free file only).** (a) The outlier threshold is the larger of 4.5σ
   and 1.25 × the 99.9th percentile of |y − median|/σ seen in calibration B, so a heavy-tailed channel
   (`Current`: 44 → 4 outlier decisions) does not report its own tail as corruption. (b) A channel on which
   more than 5% of calibration values repeat an earlier value within the replay window is treated as
   quantized and the exact-value checks are disabled (`Thermocouple`, `Volume Flow RateRMS`: replay and
   saturation decisions 33 → 0). Synthetic development results unchanged by (a) and (b).

## Development results at freeze (seeds 1000–1019, 20 runs per model)

| model, channels | false alarms per hour | PROCESS correct | INSTRUMENT correct | DATA_PATH correct |
|---|---|---|---|---|
| tank, level only | 0.146 | 91.3% | 81.4% | 100.0% |
| tank, level + rate | 0.146 | 95.3% | 99.1% | 100.0% |
| thermal, level only | 0.120 | 94.7% | 81.4% | 100.0% |
| thermal, level + rate | 0.120 | 100.0% | 99.1% | 100.0% |

(Share of detected events.) With the level channel only, every detected linear drift is attributed to PROCESS
or UNDETERMINED: this is the non-identifiability the paper states, not a defect to tune away.

## Known residuals, left as they are

- On the SKAB anomaly-free file the remaining decisions are level changes of the real rig attributed to
  PROCESS (accelerometer 1: 18; current: 23). Raising the shift threshold would remove them at the cost of
  sensitivity; not done.
- A trigger a few samples before an onset absorbs the event into a window that started early; the event is
  then scored as missed and the decision as a false alarm (dev seed 1017, sample 9890). The scoring is kept
  strict.
