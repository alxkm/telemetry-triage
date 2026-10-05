package triage.core;

/**
 * What is known about a channel before any data arrives.
 *
 * @param name channel name
 * @param rangeMin lowest value the instrument can legitimately report
 * @param rangeMax highest value the instrument can legitimately report
 * @param plausibleRate the largest change of the measured quantity per sample that the physical process allows, in
 *     the channel's own units; {@link Double#POSITIVE_INFINITY} when unknown
 * @param boundaryValues values that indicate a data-path fault when they appear (zero, type limits); may be empty
 */
public record ChannelSpec(String name, double rangeMin, double rangeMax, double plausibleRate, double[] boundaryValues) {
    public ChannelSpec {
        boundaryValues = boundaryValues.clone();
    }

    /** A channel with no declared range, rate bound or boundary values. */
    public static ChannelSpec unconstrained(String name) {
        return new ChannelSpec(name, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, new double[0]);
    }
}
