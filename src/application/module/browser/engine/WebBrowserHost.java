package application.module.browser.engine;

import java.awt.Component;

/**
 * The CEF-facing surface a tab's GUI needs from its browser (one per tab).
 * <p>
 * Implemented by {@link WebBrowser}. The GUI ({@code BrowserTabView})
 * programs against this interface so it never touches the raw
 * {@code org.cef} types, a tab's browser can be swapped in place
 * (discard/restore, T10) without rewiring the component tree, and unit
 * tests can substitute a plain component for the windowed CEF canvas.
 */
public interface WebBrowserHost {

    /** @return the persistent AWT component shown in the tab's content slot. */
    Component getUiComponent();

    /** Navigates to {@code url} (already normalized by the caller). */
    void loadUrl(String url);

    void goBack();

    void goForward();

    void reload();

    /** Stops the current load (Esc). */
    void stop();

    /** Tears the browser down (EDT). Idempotent. */
    void dispose();
}
