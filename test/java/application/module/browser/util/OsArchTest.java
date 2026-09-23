package application.module.browser.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link OsArch}: the JVM property mapping must stay aligned with
 * the Gradle JCEF contract (build.gradle jcefKey).
 */
class OsArchTest {

    @Test
    @DisplayName("windows + amd64 -> windows-amd64")
    void windowsAmd64() {
        OsArch osArch = OsArch.of("Windows 11", "amd64");
        assertEquals(OsArch.Os.WINDOWS, osArch.os());
        assertEquals(OsArch.Arch.AMD64, osArch.arch());
        assertEquals("windows-amd64", osArch.key());
    }

    @Test
    @DisplayName("windows + aarch64 -> windows-arm64")
    void windowsArm64() {
        assertEquals("windows-arm64", OsArch.of("Windows 11", "aarch64").key());
    }

    @Test
    @DisplayName("linux + x86_64 -> linux-amd64")
    void linuxAmd64() {
        assertEquals("linux-amd64", OsArch.of("Linux", "x86_64").key());
    }

    @Test
    @DisplayName("macos + arm64 -> macosx-arm64")
    void macosxArm64() {
        assertEquals("macosx-arm64", OsArch.of("Mac OS X", "arm64").key());
    }

    @Test
    @DisplayName("os names are matched case-insensitively and trimmed")
    void osNameNormalization() {
        assertEquals("linux-amd64", OsArch.of("  Linux  ", "amd64").key());
    }

    @Test
    @DisplayName("unknown OS -> IllegalArgumentException")
    void unknownOsThrows() {
        assertThrows(IllegalArgumentException.class, () -> OsArch.of("Solaris", "amd64"));
    }

    @Test
    @DisplayName("unknown architecture -> IllegalArgumentException")
    void unknownArchThrows() {
        assertThrows(IllegalArgumentException.class, () -> OsArch.of("Windows 11", "sparc"));
    }

    @Test
    @DisplayName("null input -> IllegalArgumentException")
    void nullInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> OsArch.of(null, "amd64"));
        assertThrows(IllegalArgumentException.class, () -> OsArch.of("Windows 11", null));
    }
}
