package application.utils.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@link I18n} helper (plan D16, v1): bundle lookup,
 * missing-key behavior and the locale fallback chain.
 */
class I18nTest {

    @AfterEach
    void restoreEnglish() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    @DisplayName("a known key resolves to its English value")
    void knownKeyResolves() {
        assertEquals("Browser", I18n.get("browser.module.name"));
    }

    @Test
    @DisplayName("a missing key is returned as-is (development signal)")
    void missingKeyReturnsKey() {
        assertEquals("browser.does.not.exist", I18n.get("browser.does.not.exist"));
    }

    @Test
    @DisplayName("MessageFormat arguments are applied")
    void argsAreFormatted() {
        assertEquals("Reason: engine exploded",
                I18n.get("browser.smoke.failed.reason", "engine exploded"));
    }

    @Test
    @DisplayName("an untranslated locale falls back to the English bundle")
    void localeFallsBackToEnglish() {
        I18n.setLocale(Locale.forLanguageTag("hu"));

        assertEquals("Browser", I18n.get("browser.module.name"));
    }
}
