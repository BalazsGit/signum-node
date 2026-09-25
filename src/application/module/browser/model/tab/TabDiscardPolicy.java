package application.module.browser.model.tab;

import java.util.ArrayList;
import java.util.List;

/**
 * The inactive-tab discard decision (plan F9, T10/D13) — pure logic, fully
 * unit-testable (the engine layer applies the result: it disposes the tab's
 * CEF browser; the tab stays in the strip and is restored transparently on
 * activation).
 * <p>
 * A tab is discardable when it is not the active one, not loading, not
 * already discarded, not an internal {@code signum://} page (cheap to
 * recreate, and the session state lives in the module, not in the page), and
 * its last activity is older than the discard delay. The active tab is never
 * the only tab that may go.
 */
public final class TabDiscardPolicy {

    private TabDiscardPolicy() {
        // utility class — never instantiated
    }

    /**
     * @param tabs            the current tabs in display order
     * @param activeTabId     the active tab's id (never discarded)
     * @param nowMs           the reference "now" (epoch millis)
     * @param discardMinutes  the inactivity delay (minutes); {@code <= 0}
     *                        disables discarding entirely
     * @return the ids of the tabs that may be discarded (in display order),
     *         never null
     */
    public static List<String> discardable(List<BrowserTab> tabs, String activeTabId,
                                           long nowMs, int discardMinutes) {
        List<String> out = new ArrayList<>();
        if (tabs == null || tabs.size() < 2 || discardMinutes <= 0 || nowMs <= 0) {
            return out;
        }
        long cutoff = nowMs - discardMinutes * 60_000L;
        for (BrowserTab tab : tabs) {
            if (tab.getId().equals(activeTabId) || tab.isLoading() || tab.isDiscarded()) {
                continue;
            }
            if (!tab.getUrl().startsWith("signum://")
                    && tab.getLastActiveAt() < cutoff) {
                out.add(tab.getId());
            }
        }
        return out;
    }
}