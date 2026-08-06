package application.module.node.metrics;

/**
 * Extends {@link Measurement} with module-level identification.
 * <p>
 * Metrics are organized by module (e.g., "node", "database"), allowing
 * structured grouping of measurements from different subsystems within
 * the same profile.
 * <p>
 * <h3>Hierarchy</h3>
 * <pre>
 *   Measurement
 *     └─ ModuleMetric  ← this interface
 *          └─ ProfileMetric
 * </pre>
 *
 * @since 4.0
 */
public interface ModuleMetric extends Measurement {

    /**
     * Returns the module identifier that produced this metric.
     * Common values: "node", "database", "peer", "blockchain".
     *
     * @return the module ID (never null)
     */
    String getModuleId();
}