package application.module.node.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProfileMetric}.
 * Tests construction, interface contracts, factory method, equality, and toString.
 */
@DisplayName("ProfileMetric Tests")
class ProfileMetricTest {

    private static final String PROFILE = "mainnet";
    private static final String MODULE = "blockchain";
    private static final String NAME = "height";
    private static final double VALUE = 12345.0;
    private static final Instant TS = Instant.parse("2026-08-01T12:00:00Z");

    // ── Construction Tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Constructor creates metric with all fields set")
        void testConstructorSetsAllFields() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);

            assertEquals(PROFILE, metric.getProfileId());
            assertEquals(MODULE, metric.getModuleId());
            assertEquals(NAME, metric.getMetricName());
            assertEquals(VALUE, metric.getValue());
            assertEquals(TS, metric.getTimestamp());
        }

        @Test
        @DisplayName("Constructor rejects null profileId")
        void testNullProfileIdThrows() {
            assertThrows(NullPointerException.class,
                () -> new ProfileMetric(null, MODULE, NAME, VALUE, TS));
        }

        @Test
        @DisplayName("Constructor rejects null moduleId")
        void testNullModuleIdThrows() {
            assertThrows(NullPointerException.class,
                () -> new ProfileMetric(PROFILE, null, NAME, VALUE, TS));
        }

        @Test
        @DisplayName("Constructor rejects null metricName")
        void testNullMetricNameThrows() {
            assertThrows(NullPointerException.class,
                () -> new ProfileMetric(PROFILE, MODULE, null, VALUE, TS));
        }

        @Test
        @DisplayName("Constructor rejects null timestamp")
        void testNullTimestampThrows() {
            assertThrows(NullPointerException.class,
                () -> new ProfileMetric(PROFILE, MODULE, NAME, VALUE, null));
        }

        @Test
        @DisplayName("Factory method creates metric with current timestamp")
        void testFactoryMethodUsesNow() {
            Instant before = Instant.now();
            ProfileMetric metric = ProfileMetric.of(PROFILE, MODULE, NAME, VALUE);
            Instant after = Instant.now();

            assertTrue(metric.getTimestamp().isAfter(before) || metric.getTimestamp().equals(before));
            assertTrue(metric.getTimestamp().isBefore(after) || metric.getTimestamp().equals(after));
        }
    }

    // ── Interface Contract Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Interface Contracts")
    class InterfaceContractTests {

        @Test
        @DisplayName("Implements Measurement correctly")
        void testMeasurementInterface() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);

            // Measurement interface methods
            assertEquals(NAME, metric.getMetricName());
            assertEquals(VALUE, metric.getValue());
            assertEquals(TS, metric.getTimestamp());
        }

        @Test
        @DisplayName("Implements ModuleMetric correctly")
        void testModuleMetricInterface() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);

            // ModuleMetric interface method
            assertEquals(MODULE, metric.getModuleId());

            // Should also work as Measurement
            assertInstanceOf(Measurement.class, metric);
        }

        @Test
        @DisplayName("Can be cast to ModuleMetric")
        void testCastToModuleMetric() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ModuleMetric moduleMetric = metric;
            assertEquals(MODULE, moduleMetric.getModuleId());
        }

        @Test
        @DisplayName("Can be cast to Measurement")
        void testCastToMeasurement() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            Measurement measurement = metric;
            assertEquals(NAME, measurement.getMetricName());
        }
    }

    // ── toKey Tests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("toKey")
    class ToKeyTests {

        @Test
        @DisplayName("toKey returns correct format")
        void testToKeyFormat() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            assertEquals("mainnet:blockchain.height", metric.toKey());
        }

        @Test
        @DisplayName("toKey with database trim metric")
        void testToKeyDatabaseTrim() {
            ProfileMetric metric = new ProfileMetric("testnet", "database", "trim.height", 5000, TS);
            assertEquals("testnet:database.trim.height", metric.toKey());
        }
    }

    // ── Equality Tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("Equals itself")
        void testEqualsSelf() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            assertTrue(metric.equals(metric));
        }

        @Test
        @DisplayName("Equals another with same fields")
        void testEqualsSameFields() {
            ProfileMetric a = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ProfileMetric b = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            assertEquals(a, b);
        }

        @Test
        @DisplayName("Not equal when profile differs")
        void testNotEqualDifferentProfile() {
            ProfileMetric a = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ProfileMetric b = new ProfileMetric("other", MODULE, NAME, VALUE, TS);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Not equal when module differs")
        void testNotEqualDifferentModule() {
            ProfileMetric a = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ProfileMetric b = new ProfileMetric(PROFILE, "peer", NAME, VALUE, TS);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Not equal when name differs")
        void testNotEqualDifferentName() {
            ProfileMetric a = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ProfileMetric b = new ProfileMetric(PROFILE, MODULE, "peers", VALUE, TS);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Not equal when value differs")
        void testNotEqualDifferentValue() {
            ProfileMetric a = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ProfileMetric b = new ProfileMetric(PROFILE, MODULE, NAME, 999.0, TS);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Not equal when timestamp differs")
        void testNotEqualDifferentTimestamp() {
            ProfileMetric a = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ProfileMetric b = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS.plusSeconds(10));
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Not equal to null")
        void testNotEqualNull() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            assertFalse(metric.equals(null));
        }

        @Test
        @DisplayName("Not equal to different class")
        void testNotEqualDifferentClass() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            assertFalse(metric.equals("string"));
        }

        @Test
        @DisplayName("HashCode consistent with equals")
        void testHashCodeConsistent() {
            ProfileMetric a = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            ProfileMetric b = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    // ── toString Tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString contains all field info")
        void testToStringContainsFields() {
            ProfileMetric metric = new ProfileMetric(PROFILE, MODULE, NAME, VALUE, TS);
            String str = metric.toString();

            assertTrue(str.contains("ProfileMetric"));
            assertTrue(str.contains("mainnet"));
            assertTrue(str.contains("blockchain"));
            assertTrue(str.contains("height"));
            assertTrue(str.contains(String.valueOf(VALUE)));
        }
    }
}