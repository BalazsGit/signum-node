package application.utils.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ConsoleLimits} — the F4 console-sizing SSOT (max-lines resolution
 * plus the char-cap / replay-buffer derivation from it).
 */
@DisplayName("ConsoleLimits (F4 console SSOT)")
class ConsoleLimitsTest {

    @Test
    @DisplayName("systemMaxLines falls back to the L0 default when the key is absent")
    void systemMaxLines_fallback() {
        assertEquals(ConsoleLimits.DEFAULT_SYSTEM_MAX_LINES, ConsoleLimits.systemMaxLines(null));
        assertEquals(ConsoleLimits.DEFAULT_SYSTEM_MAX_LINES, ConsoleLimits.systemMaxLines(new Properties()));
    }

    @Test
    @DisplayName("systemMaxLines honours the profile value (L1 override)")
    void systemMaxLines_override() {
        Properties p = new Properties();
        p.setProperty(ConsoleLimits.SYSTEM_MAX_LINES_KEY, "2000");
        assertEquals(2000, ConsoleLimits.systemMaxLines(p));
    }

    @Test
    @DisplayName("nodeMaxLines falls back to the node L0 default and honours the override")
    void nodeMaxLines() {
        assertEquals(ConsoleLimits.DEFAULT_NODE_MAX_LINES, ConsoleLimits.nodeMaxLines(null));
        Properties p = new Properties();
        p.setProperty(ConsoleLimits.NODE_MAX_LINES_KEY, "4200");
        assertEquals(4200, ConsoleLimits.nodeMaxLines(p));
    }

    @Test
    @DisplayName("a non-positive or non-numeric value falls back")
    void resolveMaxLines_invalidFallsBack() {
        Properties p = new Properties();
        p.setProperty(ConsoleLimits.SYSTEM_MAX_LINES_KEY, "0");
        assertEquals(ConsoleLimits.DEFAULT_SYSTEM_MAX_LINES, ConsoleLimits.systemMaxLines(p));
        p.setProperty(ConsoleLimits.SYSTEM_MAX_LINES_KEY, "-5");
        assertEquals(ConsoleLimits.DEFAULT_SYSTEM_MAX_LINES, ConsoleLimits.systemMaxLines(p));
        p.setProperty(ConsoleLimits.SYSTEM_MAX_LINES_KEY, "abc");
        assertEquals(ConsoleLimits.DEFAULT_SYSTEM_MAX_LINES, ConsoleLimits.systemMaxLines(p));
    }

    @Test
    @DisplayName("the char cap is derived from the line count (maxLines x 80, floored)")
    void deriveCharCap() {
        assertEquals(1500 * 80, ConsoleLimits.deriveCharCap(1500));
        assertEquals(ConsoleLimits.MIN_CHAR_CAP, ConsoleLimits.deriveCharCap(0));
        assertEquals(ConsoleLimits.MIN_CHAR_CAP, ConsoleLimits.deriveCharCap(10)); // 10 x 80 = 800 < floor
    }

    @Test
    @DisplayName("the replay capacity is derived from the line count")
    void deriveReplayCapacity() {
        assertEquals(1500, ConsoleLimits.deriveReplayCapacity(1500));
        assertEquals(0, ConsoleLimits.deriveReplayCapacity(-3));
    }
}
