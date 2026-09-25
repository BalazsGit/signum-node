package application.module.browser.model.tab;

import application.module.browser.engine.security.SslStatus;
import application.module.browser.model.session.SessionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link TabController} SSOT (plan F1: ≥12 cases).
 * The controller is pure Java — no engine, no EDT, fully deterministic.
 */
class TabControllerTest {

    private TabController controller;
    private final List<TabEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        controller = new TabController();
        controller.addListener(events::add);
        events.clear();
    }

    // ------------------------------------------------------------------
    // Open
    // ------------------------------------------------------------------

    @Test
    @DisplayName("openTab adds the tab, activates it and emits ADDED + ACTIVATED")
    void openTabAddsAndActivates() {
        String id = controller.openTab("https://example.com", TabSource.USER);

        assertEquals(1, controller.size());
        assertEquals(id, controller.getActiveTab().orElseThrow().getId());
        assertEquals(0, controller.getActiveIndex());
        assertEquals(2, events.size());
        assertEquals(TabEvent.Type.ADDED, events.get(0).getType());
        assertEquals(TabEvent.Type.ACTIVATED, events.get(1).getType());
    }

    @Test
    @DisplayName("openTab returns a unique id per tab")
    void openTabReturnsUniqueIds() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        String b = controller.openTab("https://b.example", TabSource.USER);

        assertNotEquals(a, b);
        assertEquals(2, controller.size());
    }

    @Test
    @DisplayName("new tabs append to the end (Chrome order)")
    void openTabAppends() {
        controller.openTab("https://a.example", TabSource.USER);
        String second = controller.openTab("https://b.example", TabSource.USER);
        String third = controller.openTab("https://c.example", TabSource.USER);

        assertEquals(second, controller.getTabAt(1).orElseThrow().getId());
        assertEquals(third, controller.getActiveTab().orElseThrow().getId());
    }

    // ------------------------------------------------------------------
    // Close (T2, T7)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("closing an inactive middle tab keeps the current active tab")
    void closeMiddleTabKeepsActive() {
        controller.openTab("https://a.example", TabSource.USER);
        String b = controller.openTab("https://b.example", TabSource.USER);
        String c = controller.openTab("https://c.example", TabSource.USER); // active
        events.clear();

        assertTrue(controller.closeTab(b));

        assertEquals(2, controller.size());
        assertEquals(c, controller.getActiveTab().orElseThrow().getId());
        assertEquals(1, controller.getActiveIndex());
        assertTrue(events.stream().anyMatch(e -> e.getType() == TabEvent.Type.REMOVED));
        assertTrue(events.stream().anyMatch(e -> e.getType() == TabEvent.Type.ACTIVATED));
    }

    @Test
    @DisplayName("closing the last tab automatically opens a New Tab page (T2)")
    void closeLastTabAutoOpensNewTab() {
        String only = controller.openTab("https://a.example", TabSource.USER);
        events.clear();

        assertTrue(controller.closeTab(only));

        assertEquals(1, controller.size());
        BrowserTab ntp = controller.getActiveTab().orElseThrow();
        assertEquals(TabController.NEW_TAB_URL, ntp.getUrl());
        assertTrue(events.stream().anyMatch(e -> e.getType() == TabEvent.Type.ADDED));
    }

    @Test
    @DisplayName("closing an unknown tab is a no-op (idempotent, CEF close-callback race)")
    void closeUnknownTabIsNoop() {
        controller.openTab("https://a.example", TabSource.USER);
        events.clear();

        assertFalse(controller.closeTab("does-not-exist"));

        assertEquals(1, controller.size());
        assertTrue(events.isEmpty());
    }

    // ------------------------------------------------------------------
    // Closed-tab LRU (T7)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("restoreClosedTab reopens tabs in LIFO order with their title (T7)")
    void restoreClosedTabIsLifo() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        String b = controller.openTab("https://b.example", TabSource.USER);
        controller.updateTitle(a, "Page A");
        controller.updateTitle(b, "Page B");
        controller.closeTab(a);
        controller.closeTab(b);

        String restored1 = controller.restoreClosedTab();
        String restored2 = controller.restoreClosedTab();

        assertEquals("https://b.example", controller.getTab(restored1).orElseThrow().getUrl());
        assertEquals("Page B", controller.getTab(restored1).orElseThrow().getTitle());
        assertEquals("https://a.example", controller.getTab(restored2).orElseThrow().getUrl());
        assertNull(controller.restoreClosedTab());
    }

    @Test
    @DisplayName("the closed-tab stack is capped at MAX_CLOSED_TABS")
    void closedTabStackIsCapped() {
        for (int i = 0; i < TabController.MAX_CLOSED_TABS + 5; i++) {
            String id = controller.openTab("https://n" + i + ".example", TabSource.USER);
            controller.closeTab(id);
        }

        assertEquals(TabController.MAX_CLOSED_TABS, controller.getClosedTabCount());
    }

    // ------------------------------------------------------------------
    // Move and activate (T3, T4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("moveTab reorders and keeps the active tab (by identity)")
    void moveTabReordersAndKeepsActive() {
        controller.openTab("https://a.example", TabSource.USER);
        controller.openTab("https://b.example", TabSource.USER);
        String c = controller.openTab("https://c.example", TabSource.USER);
        events.clear();

        assertTrue(controller.moveTab(2, 0));

        assertEquals(c, controller.getTabAt(0).orElseThrow().getId());
        assertEquals(c, controller.getActiveTab().orElseThrow().getId());
        assertEquals(0, controller.getActiveIndex());
        TabEvent moved = events.stream().filter(e -> e.getType() == TabEvent.Type.MOVED).findFirst().orElseThrow();
        assertEquals(2, moved.getIndex());
        assertEquals(0, moved.getNewIndex());
    }

    @Test
    @DisplayName("moveTab rejects invalid sources and no-op destinations")
    void moveTabValidatesBounds() {
        controller.openTab("https://a.example", TabSource.USER);
        controller.openTab("https://b.example", TabSource.USER);

        assertFalse(controller.moveTab(-1, 1));
        assertFalse(controller.moveTab(2, 0));
        assertFalse(controller.moveTab(0, 0));
        assertEquals(2, controller.size());
    }

    @Test
    @DisplayName("Ctrl+Tab / Ctrl+Shift+Tab wrap around (T4)")
    void nextAndPreviousWrapAround() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        String b = controller.openTab("https://b.example", TabSource.USER);
        String c = controller.openTab("https://c.example", TabSource.USER);

        // active = c (last)
        assertTrue(controller.activateNext());
        assertEquals(a, controller.getActiveTab().orElseThrow().getId());
        assertTrue(controller.activatePrevious());
        assertEquals(c, controller.getActiveTab().orElseThrow().getId());
        assertTrue(controller.activatePrevious());
        assertEquals(b, controller.getActiveTab().orElseThrow().getId());
    }

    @Test
    @DisplayName("Ctrl+9 activates the last tab (T4)")
    void activateLastTab() {
        controller.openTab("https://a.example", TabSource.USER);
        controller.openTab("https://b.example", TabSource.USER);
        controller.openTab("https://c.example", TabSource.USER);
        controller.activateIndex(0);

        assertTrue(controller.activateLast());

        assertEquals(2, controller.getActiveIndex());
    }

    // ------------------------------------------------------------------
    // Content updates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateTitle emits UPDATED only when the value actually changes")
    void updateTitleOnlyOnChange() {
        String id = controller.openTab("https://a.example", TabSource.USER);
        events.clear();

        assertTrue(controller.updateTitle(id, "First"));
        assertFalse(controller.updateTitle(id, "First"));
        assertFalse(controller.updateTitle("unknown", "X"));

        assertEquals(1, events.size());
        assertEquals(TabEvent.Type.UPDATED, events.get(0).getType());
        assertEquals("First", controller.getTab(id).orElseThrow().getTitle());
    }

    @Test
    @DisplayName("updateUrl refreshes the scheme-derived SSL status")
    void updateUrlRefreshesSslStatus() {
        String id = controller.openTab("https://secure.example", TabSource.USER);
        assertEquals(SslStatus.SECURE, controller.getTab(id).orElseThrow().getSslStatus());

        controller.updateUrl(id, "http://insecure.example");

        assertEquals(SslStatus.INSECURE, controller.getTab(id).orElseThrow().getSslStatus());
    }

    @Test
    @DisplayName("signum:// internal pages count as secure")
    void internalPagesAreSecure() {
        String id = controller.openTab(TabController.NEW_TAB_URL, TabSource.USER);

        assertEquals(SslStatus.SECURE, controller.getTab(id).orElseThrow().getSslStatus());
    }

    @Test
    @DisplayName("loading/progress/favicon updates are deduplicated")
    void contentUpdatesAreDeduplicated() {
        String id = controller.openTab("https://a.example", TabSource.USER);
        events.clear();

        controller.setLoading(id, true);
        controller.setLoading(id, true); // same value — no event
        controller.setProgress(id, 42);
        controller.setProgress(id, 42);
        byte[] icon = new byte[]{1, 2, 3};
        controller.setFavicon(id, icon);
        controller.setFavicon(id, new byte[]{1, 2, 3});

        assertEquals(3, events.size());
        BrowserTab tab = controller.getTab(id).orElseThrow();
        assertTrue(tab.isLoading());
        assertEquals(42, tab.getProgress());
        assertArrayEquals(icon, tab.getFavicon());
    }

    @Test
    @DisplayName("setNavigationState updates back/forward and fires one event on change (N3)")
    void navigationStateChangeFiresEvent() {
        String id = controller.openTab("https://a.example", TabSource.USER);
        events.clear();

        assertTrue(controller.setNavigationState(id, true, false));
        assertFalse(controller.setNavigationState(id, true, false)); // no change — no event
        assertTrue(controller.setNavigationState(id, true, true));
        assertFalse(controller.setNavigationState("unknown", true, false));

        assertEquals(2, events.size());
        BrowserTab tab = controller.getTab(id).orElseThrow();
        assertTrue(tab.canGoBack());
        assertTrue(tab.canGoForward());
    }

    // ------------------------------------------------------------------
    // Session (T8)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("snapshot captures urls, titles and the active index")
    void snapshotCapturesSession() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        controller.openTab("https://b.example", TabSource.USER);
        controller.updateTitle(a, "Page A");

        SessionSnapshot snapshot = controller.snapshot();

        assertEquals(2, snapshot.getTabs().size());
        assertEquals("https://a.example", snapshot.getTabs().get(0).getUrl());
        assertEquals("Page A", snapshot.getTabs().get(0).getTitle());
        assertEquals(1, snapshot.getActiveIndex());
    }

    @Test
    @DisplayName("restore rebuilds the tabs and activates the saved index (T8)")
    void restoreRebuildsTabs() {
        controller.openTab("https://old.example", TabSource.USER);
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.addTab("https://a.example", "Page A");
        snapshot.addTab("https://b.example", "Page B");
        snapshot.setActiveIndex(1);
        events.clear();

        int restored = controller.restore(snapshot);

        assertEquals(2, restored);
        assertEquals(2, controller.size());
        assertEquals("https://b.example", controller.getActiveTab().orElseThrow().getUrl());
        assertEquals("Page B", controller.getActiveTab().orElseThrow().getTitle());
        assertEquals(3, events.size()); // ADDED, ADDED, ACTIVATED
    }

    @Test
    @DisplayName("restore clamps an out-of-range active index")
    void restoreClampsActiveIndex() {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.addTab("https://a.example", null);
        snapshot.setActiveIndex(10);

        controller.restore(snapshot);

        assertEquals(0, controller.getActiveIndex());
    }

    @Test
    @DisplayName("restore with an empty snapshot leaves the controller empty")
    void restoreEmptySnapshot() {
        controller.openTab("https://old.example", TabSource.USER);

        assertEquals(0, controller.restore(new SessionSnapshot()));

        assertEquals(0, controller.size());
    }

    // ------------------------------------------------------------------
    // Batch close (T9)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("closeOthers keeps only the given tab and activates it")
    void closeOthersKeepsOnlyGiven() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        controller.openTab("https://b.example", TabSource.USER);
        controller.openTab("https://c.example", TabSource.USER);
        controller.activateById(a);

        assertTrue(controller.closeOthers(a));

        assertEquals(1, controller.size());
        assertEquals(a, controller.getActiveTab().orElseThrow().getId());
    }

    @Test
    @DisplayName("closeOthers is a no-op with a single tab or an unknown id")
    void closeOthersEdgeCases() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        controller.openTab("https://b.example", TabSource.USER);

        assertFalse(controller.closeOthers("nope"));
        assertEquals(2, controller.size());
        controller.closeTab(controller.getTabAt(1).orElseThrow().getId());
        assertFalse(controller.closeOthers(a)); // only one tab left
        assertEquals(1, controller.size());
    }

    @Test
    @DisplayName("closeRightOf keeps the given tab and everything to its left")
    void closeRightOfKeepsLeft() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        String b = controller.openTab("https://b.example", TabSource.USER);
        controller.openTab("https://c.example", TabSource.USER);

        assertTrue(controller.closeRightOf(b));

        assertEquals(2, controller.size());
        assertTrue(controller.getTab(a).isPresent());
        assertTrue(controller.getTab(b).isPresent());
        assertEquals(b, controller.getActiveTab().orElseThrow().getId());
    }

    @Test
    @DisplayName("closeRightOf is a no-op for the rightmost tab or unknown id")
    void closeRightOfEdgeCases() {
        controller.openTab("https://a.example", TabSource.USER);
        String b = controller.openTab("https://b.example", TabSource.USER);

        assertFalse(controller.closeRightOf(b)); // nothing to the right
        assertFalse(controller.closeRightOf("nope"));
        assertEquals(2, controller.size());
    }

    // ------------------------------------------------------------------
    // Live limits and engine flags (C8, S5, T10)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the max-tabs cap rejects new tabs once reached (C8)")
    void maxTabsCap() {
        controller.setMaxTabs(2);
        assertEquals(2, controller.getMaxTabs());

        String a = controller.openTab("https://a.example", TabSource.USER);
        String b = controller.openTab("https://b.example", TabSource.USER);
        assertNull(controller.openTab("https://c.example", TabSource.USER));
        assertEquals(2, controller.size());

        // closing one frees a slot; the cap is live
        controller.closeTab(b);
        String c = controller.openTab("https://c.example", TabSource.USER);
        assertNotNull(c);
        assertEquals(2, controller.size());
        assertEquals("https://c.example", controller.getTab(c).orElseThrow().getUrl());
        assertTrue(controller.getTab(a).isPresent());

        // 0 = unlimited
        controller.setMaxTabs(0);
        assertNotNull(controller.openTab("https://d.example", TabSource.USER));
    }

    @Test
    @DisplayName("markMixedContent fires UPDATED once and is idempotent (S5)")
    void markMixedContentFiresOnce() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        events.clear();

        controller.markMixedContent(a);
        controller.markMixedContent(a);
        controller.markMixedContent("nope");

        assertEquals(1, events.size());
        assertEquals(TabEvent.Type.UPDATED, events.get(0).getType());
        assertTrue(controller.getTab(a).orElseThrow().isMixedContent());
    }

    @Test
    @DisplayName("setDiscarded fires UPDATED only on change (T10)")
    void setDiscardedFiresOnChange() {
        String a = controller.openTab("https://a.example", TabSource.USER);
        events.clear();

        controller.setDiscarded(a, false); // no change
        assertEquals(0, events.size());
        controller.setDiscarded(a, true);
        controller.setDiscarded(a, true); // no change
        assertEquals(1, events.size());
        assertTrue(controller.getTab(a).orElseThrow().isDiscarded());
    }
}