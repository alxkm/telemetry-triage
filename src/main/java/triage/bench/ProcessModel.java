package triage.bench;

/** The two synthetic processes of the benchmark, in units of the instrument noise σ. */
public enum ProcessModel {
    /** Regulated tank level: the level moves toward its setpoint at a limited rate (a pump capacity). */
    TANK(200.0, 0.5, 0.0, 400.0, 0.15, 0.0),
    /** Heated vessel: first-order response toward the setpoint with time constant τ = 60 samples. */
    THERMAL(60.0, 0.1, 0.0, 150.0, 0.20, 60.0);

    final double offset;     // nominal value in engineering units
    final double sigma;      // instrument noise in engineering units
    final double rangeMin;
    final double rangeMax;
    final double maxRate;    // largest process change per sample, in σ
    final double tau;        // 0 for rate-limited, otherwise first-order time constant

    ProcessModel(double offset, double sigma, double rangeMin, double rangeMax, double maxRate, double tau) {
        this.offset = offset;
        this.sigma = sigma;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.maxRate = maxRate;
        this.tau = tau;
    }

    /** One step of the process toward {@code target}, in σ units. */
    double step(double x, double target) {
        double d = tau > 0 ? (target - x) / tau : target - x;
        return x + Math.max(-maxRate, Math.min(maxRate, d));
    }

    public double sigma() {
        return sigma;
    }

    public double offset() {
        return offset;
    }

    public double rangeMin() {
        return rangeMin;
    }

    public double rangeMax() {
        return rangeMax;
    }

    /** Plausible rate in engineering units, as declared in the channel specification. */
    public double plausibleRate() {
        return maxRate * sigma;
    }
}
