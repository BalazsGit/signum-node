package application.module.browser.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link BrowserLoggingProfile}.
 * <p>
 * The display name is the tab title of the browser module inside the Logging module,
 * so it must stay plain "Browser" (not "Web3 Browser").
 */
@DisplayName("BrowserLoggingProfile Tests")
class BrowserLoggingProfileTest {

    @Test
    @DisplayName("the logging module tab is named 'Browser' (not 'Web3 Browser')")
    void displayNameIsBrowser() {
        assertEquals("Browser", BrowserLoggingProfile.DISPLAY_NAME);
        assertEquals("Browser", new BrowserLoggingProfile().getDisplayName());
    }
}
