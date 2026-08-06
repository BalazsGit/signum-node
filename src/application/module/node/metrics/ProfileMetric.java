package application.module.node.metrics;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metric data point bound to a specific node profile and module.
 * <p>
 * <h3>Hierarchy</h3>
 * <pre>
 *   Measurement
 *     └─ ModuleMetric
 *          └─ ProfileMetric  ← this class
 * </pre>
 * <p>
 * <h3>Metric Naming Convention</h3>
 * Metrics use dot-separated names under each module:
 * <ul>
 *   <li><code>"blockchain.height"</code> - Current blockchain height</li>
 *   <li><code>"peer.count"</code> - Active peer count</li>
 *   <li><code>"sync.missingBlocks"</code> - Blocks remaining to sync</li>
 *   <li><code>"db.trim.height"</code> - Current trim height</li>
 *   <li><code>"db.trim.timeMs"</code> - Last trim duration in milliseconds</li>
 * </ul>
 * <p>
 * <h3>Thread Safety</h3>
 * This class is immutable and therefore thread-safe.
 *
 * @since 4.0
 */
public final class ProfileMetric implements ModuleMetric {

    private final String profileId;
    private final String moduleId;
    private final String metricName;
    private final double value;
    private final Instant timestamp;

    /**
     * Creates a new profile metric.
     *
     * @param profileId  the node profile identifier (e.g., "mainnet")
     * @param moduleId   the module that produced this metric (e.g., "blockchain", "peer", "database")
     * @param metricName the metric name within the module
     * @param value      the numeric value
     * @param timestamp  when the measurement was taken
     * @throws NullPointerException if any string parameter is null
     */
    public ProfileMetric(String profileId, String moduleId, String metricName,
                         double value, Instant timestamp) {
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.metricName = Objects.requireNonNull(metricName, "metricName must not be null");
        this.value = value;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /**
     * Creates a metric with the current timestamp.
     *
     * @param profileId  the node profile identifier
     * @param moduleId   the module identifier
     * @param metricName the metric name
     * @param value      the numeric value
     * @return a new ProfileMetric recorded at now()
     */
    public static ProfileMetric of(String profileId, String moduleId,
                                   String metricName, double value) {
        return new ProfileMetric(profileId, moduleId, metricName, value, Instant.now());
    }

    // ── Profile-level identification ──────────────────────────────────────

    /**
     * Returns the node profile this metric belongs to.
     *
     * @return the profile ID (never null)
     */
    public String getProfileId() {
        return profileId;
    }

    // ── ModuleMetric implementation ───────────────────────────────────────

    @Override
    public String getModuleId() {
        return moduleId;
    }

    // ── Measurement implementation ────────────────────────────────────────

    @Override
    public String getMetricName() {
        return metricName;
    }

    @Override
    public double getValue() {
        return value;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the fully-qualified metric key combining profile, module, and name.
     * Format: "{profileId}:{moduleId}.{metricName}"
     * <p>
     * This key is suitable for use as a map key in collectors.
     *
     * @return the fully-qualified metric key
     */
    public String toKey() {
        return profileId + ":" + moduleId + "." + metricName;
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProfileMetric that = (ProfileMetric) o;
        return Double.compare(that.value, value) == 0
                && profileId.equals(that.profileId)
                && moduleId.equals(that.moduleId)
                && metricName.equals(that.metricName)
                && timestamp.equals(that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileId, moduleId, metricName, value, timestamp);
    }

    @Override
    public String toString() {
        return "ProfileMetric{" +
                "profile='" + profileId + '\'' +
                ", module='" + moduleId + '\'' +
                ", name='" + metricName + '\'' +
                ", value=" + value +
                ", ts=" + timestamp +
                '}';
    }
}