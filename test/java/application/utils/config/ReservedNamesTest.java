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
    @DisplayName("node reserved names cover the canonical L1 name and both legacy template names")
    void nodeReservedNames_CoverCanonicalAndLegacyNames() {
        assertTrue(ReservedNames.NODE_RESERVED_NAMES.contains("default"));
        assertTrue(ReservedNames.NODE_RESERVED_NAMES.contains("node-default"));
        assertTrue(ReservedNames.NODE_RESERVED_NAMES.contains("logging-default"));
        assertFalse(ReservedNames.NODE_RESERVED_NAMES.contains("mainnet"));
        assertTrue(ReservedNames.isNodeReservedName("default"));
        assertFalse(ReservedNames.isNodeReservedName(null));
        assertFalse(ReservedNames.isNodeReservedName("my-custom-default"));
    }

    @Test
    @DisplayName("logging reserved names cover the canonical L1 name and the legacy template name")
    void loggingReservedNames_CoverCanonicalAndLegacyNames() {
        assertTrue(ReservedNames.LOGGING_RESERVED_NAMES.contains("default"));
        assertTrue(ReservedNames.LOGGING_RESERVED_NAMES.contains("logging-default"));
        assertFalse(ReservedNames.LOGGING_RESERVED_NAMES.contains("node-default"));
        assertTrue(ReservedNames.isLoggingReservedName("default"));
        assertFalse(ReservedNames.isLoggingReservedName("verbose"));
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