package application.module.browser.engine.scheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the fixed internal-page palettes (plan F9, C4).
 */
class ThemePaletteTest {

    @Test
    @DisplayName("tokens() returns the fixed palettes per mode")
    void tokensPerMode() {
        assertTrue(ThemePalette.tokens(ThemePalette.FOLLOW_APP).isEmpty());
        assertTrue(ThemePalette.tokens("nonsense").isEmpty());
        assertTrue(ThemePalette.tokens(null).isEmpty());

        Map<String, Color> light = ThemePalette.tokens(ThemePalette.LIGHT);
        assertEquals(6, light.size());
        assertEquals(ThemePalette.light().get("bg"), light.get("bg"));
        assertTrue(light.get("bg").getRed() > 0xC0); // a light background

        Map<String, Color> dark = ThemePalette.tokens(ThemePalette.DARK);
        assertEquals(6, dark.size());
        assertEquals(ThemePalette.DARK_BG, dark.get("bg"));
        assertEquals(ThemePalette.DARK_FG, dark.get("fg"));
    }

    @Test
    @DisplayName("the dark palette is the css/browser.css default (bg #14171c)")
    void darkIsTheDefault() {
        assertEquals("#14171c", PageTheme.hex(ThemePalette.DARK_BG));
        assertEquals("#4f8cff", PageTheme.hex(ThemePalette.ACCENT));
    }

    @Test
    @DisplayName("isKnown accepts exactly the three modes")
    void knownModes() {
        assertTrue(ThemePalette.isKnown("follow-app"));
        assertTrue(ThemePalette.isKnown("light"));
        assertTrue(ThemePalette.isKnown("dark"));
        assertFalse(ThemePalette.isKnown("Follow-App"));
        assertFalse(ThemePalette.isKnown(""));
        assertFalse(ThemePalette.isKnown(null));
    }
}