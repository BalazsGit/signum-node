package application.utils.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReservedNames} — the single source of truth for the
 * reserved profile names and the applied-profile marker equivalence.
 * <p>
 * Follows AAA + JUnit 5 conventions.
 */
@DisplayName("ReservedNames Tests")
class ReservedNamesTest {

    @Test
    @DisplayName("node reserved names are empty in the 2-layer architecture")
    void nodeReservedNames_AreEmpty() {
        assertTrue(ReservedNames.NODE_RESERVED_NAMES.isEmpty());
        assertFalse(ReservedNames.NODE_RESERVED_NAMES.contains("default"));
        assertFalse(ReservedNames.NODE_RESERVED_NAMES.contains("node-default"));
        assertFalse(ReservedNames.NODE_RESERVED_NAMES.contains("logging-default"));
        assertFalse(ReservedNames.isNodeReservedName("default"));
        assertFalse(ReservedNames.isNodeReservedName(null));
    }

    @Test
    @DisplayName("logging reserved names are empty in the 2-layer architecture")
    void loggingReservedNames_AreEmpty() {
        assertTrue(ReservedNames.LOGGING_RESERVED_NAMES.isEmpty());
        assertFalse(ReservedNames.LOGGING_RESERVED_NAMES.contains("default"));
        assertFalse(ReservedNames.LOGGING_RESERVED_NAMES.contains("logging-default"));
        assertFalse(ReservedNames.LOGGING_RESERVED_NAMES.contains("system-default"));
        assertFalse(ReservedNames.isLoggingReservedName("default"));
        assertFalse(ReservedNames.isLoggingReservedName(null));
    }

    @Test
    @DisplayName("default markers: null/blank/canonical/legacy all denote the built-in default")
    void isDefaultMarker_CoversAllDefaultDenotations() {
        assertTrue(ReservedNames.isDefaultMarker(null));
        assertTrue(ReservedNames.isDefaultMarker("  "));
        assertTrue(ReservedNames.isDefaultMarker("default"));
        assertTrue(ReservedNames.isDefaultMarker("logging-default"));
        assertFalse(ReservedNames.isDefaultMarker("verbose"));
        assertFalse(ReservedNames.isDefaultMarker("node-default"));
    }
}