package application.module.node.profile;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConsoleFilterConfig}.
 * <p>
 * Follows AAA pattern (Arrange-Act-Assert). Tests cover:
 * construction, immutability, accessor behaviour, predicate methods,
 * equals/hashCode contract, and toString.
 * </p>
 */
class ConsoleFilterConfigTest {

    // ── Construction ────────────────────────────────────────────────────

    @Test
    void constructor_GivenAllValuesNonNullable_CreatesConfig() {
        // Arrange
        String logLevel = "debug";
        List<String> modules = Arrays.asList("node", "database");
        String textSearch = "error";

        // Act
        ConsoleFilterConfig config = new ConsoleFilterConfig(logLevel, modules, textSearch);

        // Assert
        assertNotNull(config);
        assertEquals("debug", config.getLogLevel());
        assertEquals(Arrays.asList("node", "database"), config.getModules());
        assertEquals("error", config.getTextSearch());
    }

    @Test
    void constructor_GivenNullModules_ReturnsEmptyImmutableList() {
        // Arrange / Act
        ConsoleFilterConfig config = new ConsoleFilterConfig("info", null, null);

        // Assert
        assertTrue(config.getModules().isEmpty());
        assertNotSame(null, config.getModules()); // never null from accessor
    }

    @Test
    void constructor_GivenAllNulls_CreatesMinimalConfig() {
        // Arrange / Act
        ConsoleFilterConfig config = new ConsoleFilterConfig(null, null, null);

        // Assert
        assertNull(config.getLogLevel());
        assertTrue(config.getModules().isEmpty());
        assertNull(config.getTextSearch());
    }

    // ── Immutability ───────────────────────────────────────────────────

    @Test
    void constructor_ModulesListDefensivelyCopied() {
        // Arrange - use ArrayList because Arrays.asList() returns a fixed-size list
        List<String> mutableModules = new java.util.ArrayList<>(Arrays.asList("node"));
        mutableModules.add("database");

        // Act
        ConsoleFilterConfig config = new ConsoleFilterConfig("info", mutableModules, null);

        // Try to mutate original list after construction
        mutableModules.clear();

        // Assert — internal copy unaffected by external mutation
        assertEquals(Arrays.asList("node", "database"), config.getModules());
    }

    @Test
    void getModules_ReturnsUnmodifiableList() {
        // Arrange
        ConsoleFilterConfig config = new ConsoleFilterConfig(
                "info", Arrays.asList("node"), null);

        // Act / Assert
        assertThrows(UnsupportedOperationException.class,
                () -> config.getModules().add("extra"));
    }

    // ── Predicate Methods ─────────────────────────────────────────────

    @Test
    void hasLogLevel_GivenNonNullNonBlank_ReturnsTrue() {
        ConsoleFilterConfig config = new ConsoleFilterConfig("debug", null, null);
        assertTrue(config.hasLogLevel());
    }

    @Test
    void hasLogLevel_GivenNull_ReturnsFalse() {
        ConsoleFilterConfig config = new ConsoleFilterConfig(null, null, null);
        assertFalse(config.hasLogLevel());
    }

    @Test
    @SuppressWarnings("deprecation")
    void hasLogLevel_GivenBlankString_ReturnsFalse() {
        ConsoleFilterConfig config = new ConsoleFilterConfig("   ", null, null);
        assertFalse(config.hasLogLevel());
    }

    @Test
    void hasModulesFilter_GivenNonEmptyList_ReturnsTrue() {
        ConsoleFilterConfig config = new ConsoleFilterConfig(
                null, Arrays.asList("node"), null);
        assertTrue(config.hasModulesFilter());
    }

    @Test
    void hasModulesFilter_GivenNullInput_ReturnsFalse() {
        ConsoleFilterConfig config = new ConsoleFilterConfig(null, null, null);
        assertFalse(config.hasModulesFilter());
    }

    @Test
    void hasTextSearch_GivenNonBlankString_ReturnsTrue() {
        ConsoleFilterConfig config = new ConsoleFilterConfig(null, null, "filter");
        assertTrue(config.hasTextSearch());
    }

    @Test
    void hasTextSearch_GivenNull_ReturnsFalse() {
        ConsoleFilterConfig config = new ConsoleFilterConfig(null, null, null);
        assertFalse(config.hasTextSearch());
    }

    // ── equals / hashCode ──────────────────────────────────────────────

    @Test
    void equals_GivenIdenticalValues_ReturnsTrue() {
        // Arrange
        List<String> mods = Arrays.asList("node");
        ConsoleFilterConfig a = new ConsoleFilterConfig("debug", mods, "err");
        ConsoleFilterConfig b = new ConsoleFilterConfig("debug", mods, "err");

        // Act / Assert
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_GivenNullModulesVsEmptyList_ReturnsTrue() {
        // null input becomes empty list internally
        ConsoleFilterConfig a = new ConsoleFilterConfig("info", null, null);
        ConsoleFilterConfig b = new ConsoleFilterConfig("info", Collections.emptyList(), null);
        assertEquals(a, b);
    }

    @Test
    void equals_GivenDifferentLogLevel_ReturnsFalse() {
        ConsoleFilterConfig a = new ConsoleFilterConfig("debug", null, null);
        ConsoleFilterConfig b = new ConsoleFilterConfig("info", null, null);
        assertNotEquals(a, b);
    }

    @Test
    void equals_GivenDifferentModules_ReturnsFalse() {
        ConsoleFilterConfig a = new ConsoleFilterConfig(null, Arrays.asList("node"), null);
        ConsoleFilterConfig b = new ConsoleFilterConfig(null, Arrays.asList("database"), null);
        assertNotEquals(a, b);
    }

    @Test
    void equals_Reflexive() {
        ConsoleFilterConfig config = new ConsoleFilterConfig("info", null, null);
        assertEquals(config, config);
    }

    @Test
    void equals_Null_ReturnsFalse() {
        ConsoleFilterConfig config = new ConsoleFilterConfig("info", null, null);
        assertNotEquals(null, config);
    }

    @Test
    void equals_WrongType_ReturnsFalse() {
        ConsoleFilterConfig config = new ConsoleFilterConfig("info", null, null);
        assertNotEquals("string", config);
    }

    // ── toString ────────────────────────────────────────────────────────

    @Test
    void toString_ContainsFieldValues() {
        ConsoleFilterConfig config = new ConsoleFilterConfig(
                "debug", Arrays.asList("node"), "err");
        String s = config.toString();
        assertTrue(s.contains("logLevel='debug'"));
        assertTrue(s.contains("modules="));
        assertTrue(s.contains("textSearch='err'"));
    }
}