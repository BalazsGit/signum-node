package application.module.node.profile;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GuiProfileSettings}.
 * <p>
 * Follows AAA pattern (Arrange-Act-Assert). Tests cover:
 * construction with and without console filters, predicate methods,
 * equals/hashCode contract, and toString.
 * </p>
 */
class GuiProfileSettingsTest {

    // ── Construction ────────────────────────────────────────────────────

    @Test
    void constructor_GivenConsoleFilters_CreatesSettings() {
        // Arrange
        ConsoleFilterConfig filters = new ConsoleFilterConfig(
                "debug", Arrays.asList("node"), "error");

        // Act
        GuiProfileSettings settings = new GuiProfileSettings(filters);

        // Assert
        assertNotNull(settings);
        assertSame(filters, settings.getConsoleFilters());
    }

    @Test
    void constructor_GivenNullConsoleFilters_CreatesMinimalSettings() {
        // Arrange / Act
        GuiProfileSettings settings = new GuiProfileSettings(null);

        // Assert
        assertNull(settings.getConsoleFilters());
    }

    // ── Predicate Methods ─────────────────────────────────────────────

    @Test
    void hasConsoleFilters_GivenNonNull_ReturnsTrue() {
        ConsoleFilterConfig filters = new ConsoleFilterConfig("info", null, null);
        GuiProfileSettings settings = new GuiProfileSettings(filters);
        assertTrue(settings.hasConsoleFilters());
    }

    @Test
    void hasConsoleFilters_GivenNull_ReturnsFalse() {
        GuiProfileSettings settings = new GuiProfileSettings(null);
        assertFalse(settings.hasConsoleFilters());
    }

    // ── equals / hashCode ──────────────────────────────────────────────

    @Test
    void equals_GivenIdenticalConsoleFilters_ReturnsTrue() {
        // Arrange
        ConsoleFilterConfig filters = new ConsoleFilterConfig("debug", null, null);
        GuiProfileSettings a = new GuiProfileSettings(filters);
        GuiProfileSettings b = new GuiProfileSettings(filters);

        // Act / Assert
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_GivenSameValuesDifferentInstances_ReturnsTrue() {
        // Arrange
        ConsoleFilterConfig f1 = new ConsoleFilterConfig("info", null, "");
        ConsoleFilterConfig f2 = new ConsoleFilterConfig("info", null, "");
        GuiProfileSettings a = new GuiProfileSettings(f1);
        GuiProfileSettings b = new GuiProfileSettings(f2);

        // Act / Assert
        assertEquals(a, b);
    }

    @Test
    void equals_GivenOneNullFilters_ReturnsFalse() {
        ConsoleFilterConfig filters = new ConsoleFilterConfig("info", null, null);
        GuiProfileSettings a = new GuiProfileSettings(filters);
        GuiProfileSettings b = new GuiProfileSettings(null);
        assertNotEquals(a, b);
    }

    @Test
    void equals_BothNullFilters_ReturnsTrue() {
        GuiProfileSettings a = new GuiProfileSettings(null);
        GuiProfileSettings b = new GuiProfileSettings(null);
        assertEquals(a, b);
    }

    @Test
    void equals_Reflexive() {
        GuiProfileSettings settings = new GuiProfileSettings(
                new ConsoleFilterConfig("info", null, null));
        assertEquals(settings, settings);
    }

    @Test
    void equals_Null_ReturnsFalse() {
        GuiProfileSettings settings = new GuiProfileSettings(null);
        assertNotEquals(null, settings);
    }

    @Test
    void equals_WrongType_ReturnsFalse() {
        GuiProfileSettings settings = new GuiProfileSettings(null);
        assertNotEquals("string", settings);
    }

    // ── toString ────────────────────────────────────────────────────────

    @Test
    void toString_WithFilters_ContainsFilterInfo() {
        ConsoleFilterConfig filters = new ConsoleFilterConfig("debug", null, null);
        GuiProfileSettings settings = new GuiProfileSettings(filters);
        String s = settings.toString();
        assertTrue(s.contains("consoleFilters="));
        assertTrue(s.contains("logLevel='debug'"));
    }

    @Test
    void toString_WithoutFilters_ShowsNull() {
        GuiProfileSettings settings = new GuiProfileSettings(null);
        String s = settings.toString();
        assertTrue(s.contains("consoleFilters=null"));
    }
}