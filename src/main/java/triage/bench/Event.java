package triage.bench;

/**
 * One injected event.
 *
 * @param type what was injected
 * @param start first affected sample
 * @param end last sample of the active interval (equal to {@code start} for a persistent step)
 * @param amplitude size in units of σ where meaningful (step size, slope, noise factor), else 0
 */
public record Event(FaultType type, int start, int end, double amplitude) {
}
