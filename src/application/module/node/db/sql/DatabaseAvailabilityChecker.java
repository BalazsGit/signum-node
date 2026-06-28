package application.module.node.db.sql;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight utility that checks if the configured database is available
 * without performing a full node bootstrap. Uses a minimal JDBC connection
 * test to determine availability.
 *
 * This class follows the Strategy pattern: it encapsulates the algorithm for
 * testing database connectivity, allowing the NodeLifecycleManager to delegate
 * connection checking without knowing database implementation details.
 */
public final class DatabaseAvailabilityChecker {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseAvailabilityChecker.class);

    /** Result of a database availability check. */
    public static class CheckResult {
        private final boolean available;
        private final String reason;

        private CheckResult(boolean available, String reason) {
            this.available = available;
            this.reason = reason;
        }

        public boolean isAvailable() {
            return available;
        }

        public String getReason() {
            return reason;
        }

        public static CheckResult available() {
            return new CheckResult(true, null);
        }

        public static CheckResult unavailable(String reason) {
            return new CheckResult(false, reason);
        }
    }

    /** Retry configuration read from PropertyService. */
    public static class RetryConfig {
        private final boolean enabled;
        private final int maxAttempts;
        private final int intervalMs;

        public RetryConfig(PropertyService propertyService) {
            this.enabled = propertyService.getBoolean(Props.DB_CONNECTION_RETRY_ENABLED);
            this.maxAttempts = propertyService.getInt(Props.DB_CONNECTION_RETRY_MAX_ATTEMPTS);
            this.intervalMs = propertyService.getInt(Props.DB_CONNECTION_RETRY_INTERVAL_MS);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public int getIntervalMs() {
            return intervalMs;
        }

        /** Total timeout in milliseconds before giving up. */
        public long getTotalTimeoutMs() {
            return (long) maxAttempts * intervalMs;
        }
    }

    private DatabaseAvailabilityChecker() {
        // Utility class - no instantiation
    }

    /**
     * Performs a lightweight connection test against the configured database.
     * Does NOT initialize migrations, caches, or any other node infrastructure.
     *
     * @param propertyService the property service containing DB configuration
     * @return CheckResult indicating availability and failure reason if unavailable
     */
    public static CheckResult check(PropertyService propertyService) {
        String dbUrl = propertyService.getString(Props.DB_URL);
        String dbUsername = propertyService.getString(Props.DB_USERNAME);
        String dbPassword = propertyService.getString(Props.DB_PASSWORD);

        if (dbUrl == null || dbUrl.isEmpty()) {
            return CheckResult.unavailable("Database URL is not configured");
        }

        try (Connection connection = createTestConnection(dbUrl, dbUsername, dbPassword)) {
            if (connection != null && !connection.isClosed()) {
                // Additional validation: try a simple query
                connection.isValid(5000);
                logger.debug("Database availability check passed for URL: {}", maskUrl(dbUrl));
                return CheckResult.available();
            }
            return CheckResult.unavailable("Connection was closed immediately");
        } catch (SQLException e) {
            String reason = String.format("Database unavailable: %s", e.getMessage());
            logger.debug(reason);
            return CheckResult.unavailable(reason);
        } catch (Exception e) {
            String reason = String.format("Unexpected error during database check: %s", e.getMessage());
            logger.warn(reason, e);
            return CheckResult.unavailable(reason);
        }
    }

    /**
     * Performs a retry loop with configurable attempts and interval.
     *
     * @param propertyService  the property service containing DB configuration
     * @param retryConfig      the retry configuration
     * @param progressCallback optional callback reporting each attempt progress
     * @return CheckResult - available if succeeded, otherwise last failure reason
     */
    public static CheckResult checkWithRetry(
            PropertyService propertyService,
            RetryConfig retryConfig,
            RetryProgressCallback progressCallback) {

        if (!retryConfig.isEnabled()) {
            logger.info("Database retry is disabled; performing single connection attempt");
            return check(propertyService);
        }

        logger.info("Database connection retry enabled: maxAttempts={}, intervalMs={}",
                retryConfig.getMaxAttempts(), retryConfig.getIntervalMs());

        List<String> attemptErrors = new ArrayList<>();

        for (int attempt = 1; attempt <= retryConfig.getMaxAttempts(); attempt++) {
            if (progressCallback != null) {
                progressCallback.onAttempt(attempt, retryConfig.getMaxAttempts());
            }

            logger.debug("Database connection attempt {}/{}", attempt, retryConfig.getMaxAttempts());

            CheckResult result = check(propertyService);
            if (result.isAvailable()) {
                logger.info("Database connection established on attempt {}/{}",
                        attempt, retryConfig.getMaxAttempts());
                return result;
            }

            attemptErrors.add("Attempt " + attempt + ": " + result.getReason());

            if (attempt < retryConfig.getMaxAttempts()) {
                try {
                    Thread.sleep(retryConfig.getIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Database connection retry interrupted");
                    return CheckResult.unavailable("Retry loop was interrupted: " +
                            String.join("; ", attemptErrors));
                }
            }
        }

        String finalReason = "Failed to connect to database after " +
                retryConfig.getMaxAttempts() + " attempts (" +
                retryConfig.getTotalTimeoutMs() / 1000 + "s timeout):\n" +
                String.join("\n", attemptErrors);
        logger.error(finalReason);
        return CheckResult.unavailable(finalReason);
    }

    /**
     * Creates a minimal JDBC connection for testing purposes.
     */
    private static Connection createTestConnection(String url, String username, String password) throws SQLException {
        if (username != null && !username.isEmpty()) {
            return DriverManager.getConnection(url, username, password);
        }
        return DriverManager.getConnection(url);
    }

    /**
     * Masks the password in a JDBC URL for safe logging.
     */
    private static String maskUrl(String url) {
        if (url == null) {
            return "null";
        }
        // Simple masking: hide anything after 'password=' until the next '&' or end
        int passwordIndex = url.toLowerCase().indexOf("password=");
        if (passwordIndex >= 0) {
            int start = passwordIndex + 9; // length of "password="
            int end = url.indexOf('&', start);
            if (end < 0) {
                end = url.length();
            }
            return url.substring(0, start) + "***" + url.substring(end);
        }
        return url;
    }

    /**
     * Callback interface for reporting retry progress.
     */
    public interface RetryProgressCallback {
        /**
         * Called before each connection attempt.
         *
         * @param attempt     current attempt number (1-based)
         * @param maxAttempts maximum number of attempts
         */
        void onAttempt(int attempt, int maxAttempts);
    }
}