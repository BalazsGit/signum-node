package application.module.node.metrics;

import java.time.Instant;

/**
 * Represents a single measurement data point collected from a running node profile.
 * <p>
 * This is the root interface in the measurement hierarchy:
 * <pre>
 *   Measurement
 *     └─ ModuleMetric
 *          └─ ProfileMetric
 * </pre>
 * <p>
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li><b>Immutable:</b> Once created, a measurement cannot be modified.</li>
 *   <li><b>Thread-safe:</b> Safe to read from any thread after creation.</li>
 *   <li><b>No static state:</b> Pure data carrier, no shared mutable fields.</li>
 * </ul>
 *
 * @since 4.0
 */
public interface Measurement {

    /**
     * Returns the name of this metric (e.g., "blockchain.height", "peer.count").
     *
     * @return the metric name (never null)
     */
    String getMetricName();

    /**
     * Returns the numeric value of this measurement.
     * Use {@link Double#NaN} for undefined values.
     *
     * @return the metric value
     */
    double getValue();

    /**
     * Returns the timestamp when this measurement was recorded.
     *
     * @return the recording timestamp (never null)
     */
    Instant getTimestamp();
}