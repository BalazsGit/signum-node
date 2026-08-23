package application.module.node.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe collector for profile-scoped metrics.
 * <p>
 * Each {@code Signum} instance owns exactly one collector instance.
 * The collector stores the latest value for every metric key
 * ({@code moduleId + "." + metricName}) and allows read access for
 * GUI panels, API endpoints, or external monitoring systems.
 * <p>
 * <h3>Metric Key Format</h3>
 * Internal keys are composed as <code><moduleId>.<metricName></code>.
 * Example: <code>"blockchain.height"</code>, <code>"db.trim.height"</code>.
 * <p>
 * <h3>Thread Safety</h3>
 * All public methods are safe for concurrent use. The underlying map is a
 * {@link ConcurrentHashMap} so reads never block writes and vice versa.
 *
 * @since 4.0
 */
public final class ProfileMetricCollector {

    private final String profileId;
    private final ConcurrentMap<String, ProfileMetric> metrics = new ConcurrentHashMap<>();

    /**
     * Creates a new collector bound to the specified profile.
     *
     * @param profileId the node profile identifier (e.g., "mainnet", "testnet")
     * @throws NullPointerException if profileId is null
     */
    public ProfileMetricCollector(String profileId) {
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
    }

    /**
     * Returns the profile ID this collector belongs to.
     *
     * @return the profile identifier
     */
    public String getProfileId() {
        return profileId;
    }

    // ── Recording ───────────────────────────────────────────────────────

    /**
     * Records a new metric value, overwriting any previous value for the
     * same module + metric combination.
     *
     * @param moduleId   the subsystem producing this metric
     * @param metricName the metric name within that subsystem
     * @param value      the numeric value to record
     * @return the created {@link ProfileMetric} (useful for chaining)
     */
    public ProfileMetric record(String moduleId, String metricName, double value) {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(metricName, "metricName must not be null");
        ProfileMetric metric = ProfileMetric.of(profileId, moduleId, metricName, value);
        metrics.put(metric.getModuleId() + "." + metric.getMetricName(), metric);
        return metric;
    }

    /**
     * Records a long value as a double. Convenience overload for integral metrics.
     */
    public ProfileMetric record(String moduleId, String metricName, long value) {
        return record(moduleId, metricName, (double) value);
    }

    /**
     * Records an integer value as a double.
     */
    public ProfileMetric record(String moduleId, String metricName, int value) {
        return record(moduleId, metricName, (double) value);
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /**
     * Retrieves the latest metric for the given module and name.
     *
     * @param moduleId   the subsystem identifier
     * @param metricName the metric name
     * @return the most recent metric, or {@code null} if not yet recorded
     */
    public ProfileMetric get(String moduleId, String metricName) {
        Objects.requireNonNull(moduleId);
        Objects.requireNonNull(metricName);
        return metrics.get(moduleId + "." + metricName);
    }

    /**
     * Retrieves all metrics produced by a specific module.
     *
     * @param moduleId the subsystem identifier
     * @return an unmodifiable collection of that module's metrics (may be empty)
     */
    public Collection<ProfileMetric> getByModule(String moduleId) {
        Objects.requireNonNull(moduleId);
        String prefix = moduleId + ".";
        Collection<ProfileMetric> result = new ArrayList<>();
        for (ProfileMetric m : metrics.values()) {
            if (m.getModuleId().equals(moduleId)) {
                result.add(m);
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    /**
     * Returns all recorded metrics as an unmodifiable snapshot.
     *
     * @return all metrics currently stored
     */
    public Collection<ProfileMetric> getAll() {
        return Collections.unmodifiableCollection(new ArrayList<>(metrics.values()));
    }

    /**
     * Removes a single metric by module and name.
     *
     * @param moduleId   the subsystem identifier
     * @param metricName the metric name
     * @return the removed metric, or {@code null} if it did not exist
     */
    public ProfileMetric remove(String moduleId, String metricName) {
        Objects.requireNonNull(moduleId);
        Objects.requireNonNull(metricName);
        return metrics.remove(moduleId + "." + metricName);
    }

    /**
     * Clears all metrics for a given module.
     * Useful when a subsystem shuts down and its metrics become stale.
     *
     * @param moduleId the subsystem to clear
     */
    public void clearModule(String moduleId) {
        Objects.requireNonNull(moduleId);
        String prefix = moduleId + ".";
        metrics.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    /**
     * Clears all metrics. Call this when the profile is being shut down.
     */
    public void clear() {
        metrics.clear();
    }

    /**
     * Returns the number of distinct metrics currently stored.
     *
     * @return metric count
     */
    public int size() {
        return metrics.size();
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public String toString() {
        return "ProfileMetricCollector{" +
                "profile='" + profileId + '\'' +
                ", metricCount=" + metrics.size() +
                '}';
    }
}