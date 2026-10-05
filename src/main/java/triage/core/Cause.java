package triage.core;

/** The cause to which a departure of a reading from expectation is attributed. */
public enum Cause {
    /** The measured physical quantity itself changed. */
    PROCESS,
    /** The instrument no longer tracks the quantity: bias, drift, noise growth, stuck, saturation. */
    INSTRUMENT,
    /** The value was corrupted between the instrument and the software: transport, serialization, middleware. */
    DATA_PATH,
    /** The evidence does not separate the causes; a second channel or an operator is needed. */
    UNDETERMINED
}
