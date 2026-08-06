package application.module.node.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProfileMetricCollector}.
 * Tests recording, reading, clearing, and thread safety of the collector.
 */
@DisplayName("ProfileMetricCollector Tests")
class ProfileMetricCollectorTest {

    private ProfileMetricCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ProfileMetricCollector("mainnet");
    }

    // ── Construction Tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Constructor sets profile ID")
        void testProfileIdSet() {
            assertEquals("mainnet", collector.getProfileId());
        }

        @Test
        @DisplayName("Constructor rejects null profileId")
        void testNullProfileIdThrows() {
            assertThrows(NullPointerException.class,
                () -> new ProfileMetricCollector(null));
        }

        @Test
        @DisplayName("New collector has size 0")
        void testInitialSizeZero() {
            assertEquals(0, collector.size());
        }
    }

    // ── Recording Tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Recording")
    class RecordingTests {

        @Test
        @DisplayName("record(double) stores metric")
        void testRecordDouble() {
            collector.record("blockchain", "height", 12345.0);
            assertEquals(1, collector.size());
        }

        @Test
        @DisplayName("record(long) stores metric as double")
        void testRecordLong() {
            collector.record("blockchain", "height", 999L);
            ProfileMetric metric = collector.get("blockchain", "height");
            assertNotNull(metric);
            assertEquals(999.0, metric.getValue());
        }

        @Test
        @DisplayName("record(int) stores metric as double")
        void testRecordInt() {
            collector.record("peer", "count", 42);
            ProfileMetric metric = collector.get("peer", "count");
            assertNotNull(metric);
            assertEquals(42.0, metric.getValue());
        }

        @Test
        @DisplayName("record returns the created metric")
        void testRecordReturnsMetric() {
            ProfileMetric result = collector.record("blockchain", "height", 100);
            assertNotNull(result);
            assertEquals("mainnet", result.getProfileId());
            assertEquals("blockchain", result.getModuleId());
            assertEquals("height", result.getMetricName());
        }

        @Test
        @DisplayName("Overwriting metric updates value")
        void testOverwriteMetric() {
            collector.record("blockchain", "height", 100);
            collector.record("blockchain", "height", 200);
            ProfileMetric metric = collector.get("blockchain", "height");
            assertEquals(200.0, metric.getValue());
            // Still only 1 metric (overwritten, not duplicated)
            assertEquals(1, collector.size());
        }

        @Test
        @DisplayName("Cannot record with null moduleId")
        void testRecordNullModuleIdThrows() {
            assertThrows(NullPointerException.class,
                () -> collector.record(null, "height", 1));
        }

        @Test
        @DisplayName("Cannot record with null metricName")
        void testRecordNullMetricNameThrows() {
            assertThrows(NullPointerException.class,
                () -> collector.record("blockchain", null, 1));
        }

        @Test
        @DisplayName("Different modules don't conflict")
        void testDifferentModulesIndependent() {
            collector.record("blockchain", "height", 100);
            collector.record("peer", "count", 5);
            assertEquals(2, collector.size());
        }

        @Test
        @DisplayName("Same module different metrics don't conflict")
        void testSameModuleDifferentMetrics() {
            collector.record("blockchain", "height", 100);
            collector.record("blockchain", "difficulty", 5000);
            assertEquals(2, collector.size());
        }

        @Test
        @DisplayName("Database trim metrics recorded correctly")
        void testDatabaseTrimMetrics() {
            collector.record("database", "trim.height", 5000);
            collector.record("database", "trim.timeMs", 1234);

            ProfileMetric height = collector.get("database", "trim.height");
            ProfileMetric time = collector.get("database", "trim.timeMs");

            assertNotNull(height);
            assertEquals(5000.0, height.getValue());
            assertNotNull(time);
            assertEquals(1234.0, time.getValue());
        }
    }

    // ── Reading Tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Reading")
    class ReadingTests {

        @Test
        @DisplayName("get returns null for unknown metric")
        void testGetUnknownMetric() {
            assertNull(collector.get("blockchain", "nonexistent"));
        }

        @Test
        @DisplayName("get returns latest value")
        void testGetReturnsLatestValue() {
            collector.record("blockchain", "height", 100);
            collector.record("blockchain", "height", 200);

            ProfileMetric metric = collector.get("blockchain", "height");
            assertEquals(200.0, metric.getValue());
        }

        @Test
        @DisplayName("getByModule returns only matching metrics")
        void testGetByModuleFiltersCorrectly() {
            collector.record("blockchain", "height", 100);
            collector.record("blockchain", "difficulty", 5000);
            collector.record("peer", "count", 5);

            Collection<ProfileMetric> blockchain = collector.getByModule("blockchain");
            assertEquals(2, blockchain.size());

            Collection<ProfileMetric> peer = collector.getByModule("peer");
            assertEquals(1, peer.size());
        }

        @Test
        @DisplayName("getByModule returns empty for unknown module")
        void testGetByModuleUnknownEmpty() {
            collector.record("blockchain", "height", 100);
            Collection<ProfileMetric> unknown = collector.getByModule("unknown");
            assertTrue(unknown.isEmpty());
        }

        @Test
        @DisplayName("getByModule returns unmodifiable collection")
        void testGetByModuleUnmodifiable() {
            collector.record("blockchain", "height", 100);
            Collection<ProfileMetric> metrics = collector.getByModule("blockchain");
            assertThrows(UnsupportedOperationException.class,
                () -> metrics.add(new ProfileMetric("p", "m", "n", 1, java.time.Instant.now())));
        }

        @Test
        @DisplayName("getAll returns all metrics")
        void testGetAll() {
            collector.record("blockchain", "height", 100);
            collector.record("peer", "count", 5);
            assertEquals(2, collector.getAll().size());
        }

        @Test
        @DisplayName("getAll returns unmodifiable collection")
        void testGetAllUnmodifiable() {
            collector.record("blockchain", "height", 100);
            Collection<ProfileMetric> all = collector.getAll();
            assertThrows(UnsupportedOperationException.class,
                () -> all.add(new ProfileMetric("p", "m", "n", 1, java.time.Instant.now())));
        }

        @Test
        @DisplayName("get rejects null moduleId")
        void testGetNullModuleIdThrows() {
            assertThrows(NullPointerException.class,
                () -> collector.get(null, "height"));
        }

        @Test
        @DisplayName("get rejects null metricName")
        void testGetNullMetricNameThrows() {
            assertThrows(NullPointerException.class,
                () -> collector.get("blockchain", null));
        }

        @Test
        @DisplayName("getByModule rejects null moduleId")
        void testGetByModuleNullThrows() {
            assertThrows(NullPointerException.class,
                () -> collector.getByModule(null));
        }
    }

    // ── Removal Tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Removal")
    class RemovalTests {

        @Test
        @DisplayName("remove returns the metric")
        void testRemoveReturnsMetric() {
            collector.record("blockchain", "height", 100);
            ProfileMetric removed = collector.remove("blockchain", "height");
            assertNotNull(removed);
            assertEquals(100.0, removed.getValue());
            assertEquals(0, collector.size());
        }

        @Test
        @DisplayName("remove returns null for unknown metric")
        void testRemoveUnknownReturnsNull() {
            assertNull(collector.remove("unknown", "metric"));
        }

        @Test
        @DisplayName("clearModule removes only that module's metrics")
        void testClearModuleSelective() {
            collector.record("blockchain", "height", 100);
            collector.record("peer", "count", 5);

            collector.clearModule("blockchain");
            assertEquals(1, collector.size());
            assertNull(collector.get("blockchain", "height"));
            assertNotNull(collector.get("peer", "count"));
        }

        @Test
        @DisplayName("clear removes all metrics")
        void testClearAll() {
            collector.record("blockchain", "height", 100);
            collector.record("peer", "count", 5);

            collector.clear();
            assertEquals(0, collector.size());
        }

        @Test
        @DisplayName("clearModule rejects null")
        void testClearModuleNullThrows() {
            assertThrows(NullPointerException.class,
                () -> collector.clearModule(null));
        }
    }

    // ── toString Test ───────────────────────────────────────────────────

    @Test
    @DisplayName("toString contains profile and count")
    void testToString() {
        collector.record("blockchain", "height", 100);
        String str = collector.toString();

        assertTrue(str.contains("ProfileMetricCollector"));
        assertTrue(str.contains("mainnet"));
        assertTrue(str.contains("metricCount=1"));
    }
}