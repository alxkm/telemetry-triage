package triage.core;

import java.util.List;

/**
 * The outcome of one attribution.
 *
 * @param cause the attributed cause
 * @param triggerIndex the sample index at which the first detector fired (used for detection delay)
 * @param decisionIndex the sample index at which the rule reached its verdict
 * @param evidence human-readable reasons, in the order the rule evaluated them
 */
public record Decision(Cause cause, long triggerIndex, long decisionIndex, List<String> evidence) {
    public Decision {
        evidence = List.copyOf(evidence);
    }
}
