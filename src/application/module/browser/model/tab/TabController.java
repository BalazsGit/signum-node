package application.module.browser.model.tab;

import application.module.browser.model.session.SessionSnapshot;
import application.module.browser.engine.security.SslStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the browser's tabs (plan §4.4).
 * <p>
 * Pure Java — no Swing, no {@code org.cef} imports — so it is fully
 * unit-testable without the engine. Every mutation is synchronized and
 * published as {@link TabEvent}s (dispatched after the state change, still
 * on the caller's thread).
 * <p>
 * Invariants:
 * <ul>
 *   <li>after any {@link #closeTab} at least one tab exists (T2: closing the
 *       last tab opens the New-Tab page);</li>
 *   <li>exactly one active tab (the first one when the list is non-empty);</li>
 *   <li>the closed-tab LRU stack is capped at {@link #MAX_CLOSED_TABS} (T7).</li>
 * </ul>
 */
public final class TabController {

    /** URL of the built-in New Tab page (plan D6). */
    public static final String NEW_TAB_URL = "signum://newtab";

    /** Closed-tab LRU stack cap (T7). */
    public static final int MAX_CLOSED_TABS = 25;

    private static final Logger logger = LoggerFactory.getLogger(TabController.class);

    /** A closed tab remembered for Ctrl+Shift+T (T7) — no CEF data, just enough to reopen. */
    private static final class ClosedTab {
        final String url;
        final String title;

        ClosedTab(String url, String title) {
            this.url = url;
            this.title = title;
        }
    }

    private final List<BrowserTab> tabs = new ArrayList<>();
    private final Deque<ClosedTab> closedTabs = new ArrayDeque<>();
    private final List<TabEventListener> listeners = new CopyOnWriteArrayList<>();
    private int activeIndex = -1;
    /**
     * C8: the live max-tabs cap (0 or negative = unlimited). Set by the GUI
     * from the settings; checked in {@link #openTab}.
     */
    private volatile int maxTabs = 0;

    // ------------------------------------------------------------------
    // Observers
    // ------------------------------------------------------------------

    public void addListener(TabEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(TabEventListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // Open (T1, T7, T8)
    // ------------------------------------------------------------------

    /**
     * Opens a new tab and makes it active.
     *
     * @param url    the initial URL (never null)
     * @param source where the request came from
     * @return the new tab's id, or {@code null} when the live max-tabs cap
     *         (C8) is reached — the caller simply ignores the request
     */
    public synchronized String openTab(String url, TabSource source) {
        Objects.requireNonNull(url, "url");
        int cap = maxTabs;
        if (cap > 0 && tabs.size() >= cap) {
            logger.debug("Max-tabs cap ({}) reached — not opening {}", cap, url);
            return null;
        }
        BrowserTab tab = new BrowserTab(url);
        tabs.add(tab);
        activeIndex = tabs.size() - 1;
        fire(TabEvent.added(tab, activeIndex), TabEvent.activated(tab, activeIndex));
        return tab.getId();
    }

    /** T1: a user-requested New Tab page. */
    public synchronized String openNewTab() {
        return openTab(NEW_TAB_URL, TabSource.USER);
    }

    /**
     * T7: reopens the most recently closed tab (LRU).
     *
     * @return the reopened tab's id, or {@code null} when the stack is empty
     */
    public synchronized String restoreClosedTab() {
        ClosedTab closed = closedTabs.pollLast();
        if (closed == null) {
            return null;
        }
        String id = openTab(closed.url, TabSource.RESTORE);
        BrowserTab tab = byId(id);
        if (tab != null && closed.title != null) {
            tab.setTitle(closed.title);
        }
        return id;
    }

    // ------------------------------------------------------------------
    // Close (T2, T7)
    // ------------------------------------------------------------------

    /**
     * Closes a tab. Idempotent: closing an unknown id (e.g. a late CEF close
     * callback racing the GUI, plan 4.3/3) is a no-op.
     *
     * @return {@code true} when a tab was actually closed
     */
    public synchronized boolean closeTab(String tabId) {
        int index = indexOf(tabId);
        if (index < 0) {
            return false;
        }
        BrowserTab removed = tabs.remove(index);
        rememberClosed(removed);
        boolean wasActive = index == activeIndex;

        List<TabEvent> events = new ArrayList<>();
        events.add(TabEvent.removed(removed, index));
        if (tabs.isEmpty()) {
            // T2: the last closed tab drops into a fresh New Tab page.
            BrowserTab ntp = new BrowserTab(NEW_TAB_URL);
            tabs.add(ntp);
            activeIndex = 0;
            events.add(TabEvent.added(ntp, 0));
            events.add(TabEvent.activated(ntp, 0));
        } else {
            boolean indexShifted = index < activeIndex;
            if (indexShifted) {
                activeIndex--;
            }
            if (wasActive && activeIndex >= tabs.size()) {
                activeIndex = tabs.size() - 1;
            }
            // The active tab changed (wasActive) or its index shifted (a tab left of
            // it was removed) — listeners need the new active position either way.
            if (wasActive || indexShifted) {
                events.add(TabEvent.activated(tabs.get(activeIndex), activeIndex));
            }
        }
        fire(events.toArray(new TabEvent[0]));
        return true;
    }

    private void rememberClosed(BrowserTab tab) {
        closedTabs.addLast(new ClosedTab(tab.getUrl(), tab.getTitle()));
        while (closedTabs.size() > MAX_CLOSED_TABS) {
            closedTabs.pollFirst();
        }
    }

    // ------------------------------------------------------------------
    // Batch close (T9: the tab context menu)
    // ------------------------------------------------------------------

    /**
     * T9: closes every tab except the given one, which becomes active.
     *
     * @return {@code true} when at least one tab was closed
     */
    public synchronized boolean closeOthers(String tabId) {
        int index = indexOf(tabId);
        if (index < 0 || tabs.size() < 2) {
            return false;
        }
        activateById(tabId);
        boolean closed = false;
        for (String otherId : new ArrayList<>(idsExcept(tabId))) {
            if (closeTab(otherId)) {
                closed = true;
            }
        }
        return closed;
    }

    /**
     * T9: closes every tab to the right of the given one (the given tab
     * itself is never closed and becomes active).
     *
     * @return {@code true} when at least one tab was closed
     */
    public synchronized boolean closeRightOf(String tabId) {
        int index = indexOf(tabId);
        if (index < 0 || index >= tabs.size() - 1) {
            return false;
        }
        activateById(tabId);
        boolean closed = false;
        // close from the rightmost leftwards so indices stay valid
        List<String> doomed = new ArrayList<>();
        for (int i = tabs.size() - 1; i > index; i--) {
            doomed.add(tabs.get(i).getId());
        }
        for (String id : doomed) {
            if (closeTab(id)) {
                closed = true;
            }
        }
        return closed;
    }

    private List<String> idsExcept(String keepId) {
        List<String> out = new ArrayList<>();
        for (BrowserTab tab : tabs) {
            if (!tab.getId().equals(keepId)) {
                out.add(tab.getId());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Live limits (C8) and engine flags (S5, T10)
    // ------------------------------------------------------------------

    /**
     * C8: sets the live max-tabs cap (0 or negative = unlimited).
     */
    public void setMaxTabs(int maxTabs) {
        this.maxTabs = Math.max(0, maxTabs);
    }

    /** @return the current max-tabs cap (0 = unlimited). */
    public synchronized int getMaxTabs() {
        return maxTabs;
    }

    /**
     * S5: flags the tab as mixed-content (an http subresource on an https
     * page) and notifies the listeners when the flag changed.
     */
    public synchronized void markMixedContent(String tabId) {
        BrowserTab tab = byId(tabId);
        if (tab == null || tab.isMixedContent()) {
            return;
        }
        tab.markMixedContent();
        fire(TabEvent.updated(tab));
    }

    /**
     * T10/D13: the discard flag of the tab (set by the engine layer when the
     * CEF browser is disposed or recreated); notifies when it changed.
     */
    public synchronized void setDiscarded(String tabId, boolean discarded) {
        BrowserTab tab = byId(tabId);
        if (tab == null || tab.isDiscarded() == discarded) {
            return;
        }
        tab.setDiscarded(discarded);
        fire(TabEvent.updated(tab));
    }

    // ------------------------------------------------------------------
    // Order and activation (T3, T4)
    // ------------------------------------------------------------------

    /**
     * T3: moves a tab. {@code to} is the target cell in the pre-removal list
     * (the drop position the user dragged to); it is clamped to the valid range.
     *
     * @return {@code true} when the tab actually moved
     */
    public synchronized boolean moveTab(int from, int to) {
        if (from < 0 || from >= tabs.size() || tabs.size() < 2) {
            return false;
        }
        int destination = Math.max(0, Math.min(to, tabs.size() - 1));
        if (destination == from) {
            return false;
        }
        BrowserTab activeTab = activeIndex >= 0 ? tabs.get(activeIndex) : null;
        BrowserTab moved = tabs.remove(from);
        tabs.add(destination, moved);
        if (activeTab != null) {
            activeIndex = tabs.indexOf(activeTab);
        }
        fire(TabEvent.moved(moved, from, destination));
        return true;
    }

    /** @return {@code true} when the tab at {@code index} became (or stayed) active. */
    public synchronized boolean activateIndex(int index) {
        if (tabs.isEmpty() || index < 0 || index >= tabs.size()) {
            return false;
        }
        if (index == activeIndex) {
            return true;
        }
        activeIndex = index;
        tabs.get(index).touchActive(); // T10/D13: the discard clock restarts
        fire(TabEvent.activated(tabs.get(index), index));
        return true;
    }

    public synchronized boolean activateById(String tabId) {
        int index = indexOf(tabId);
        return index >= 0 && activateIndex(index);
    }

    /** T4: Ctrl+Tab (wraps around). */
    public synchronized boolean activateNext() {
        if (tabs.isEmpty()) {
            return false;
        }
        return activateIndex((activeIndex + 1) % tabs.size());
    }

    /** T4: Ctrl+Shift+Tab (wraps around). */
    public synchronized boolean activatePrevious() {
        if (tabs.isEmpty()) {
            return false;
        }
        return activateIndex((activeIndex - 1 + tabs.size()) % tabs.size());
    }

    /** T4: Ctrl+9 (the last tab). */
    public synchronized boolean activateLast() {
        if (tabs.isEmpty()) {
            return false;
        }
        return activateIndex(tabs.size() - 1);
    }

    // ------------------------------------------------------------------
    // Content updates (engine-driven; called on the EDT by the handlers)
    // ------------------------------------------------------------------

    /** @return {@code true} when the title actually changed. */
    public synchronized boolean updateTitle(String tabId, String title) {
        BrowserTab tab = byId(tabId);
        if (tab == null || Objects.equals(tab.getTitle(), title)) {
            return false;
        }
        tab.setTitle(title);
        fire(TabEvent.updated(tab));
        return true;
    }

    /** Also refreshes the scheme-derived {@link SslStatus} (replaced in F2 by the real detector). */
    public synchronized boolean updateUrl(String tabId, String url) {
        BrowserTab tab = byId(tabId);
        if (tab == null || Objects.equals(tab.getUrl(), url)) {
            return false;
        }
        tab.setUrl(url);
        tab.resetMixedContent(); // S5: the flag belongs to the page that loaded it
        fire(TabEvent.updated(tab));
        return true;
    }

    public synchronized void setLoading(String tabId, boolean loading) {
        BrowserTab tab = byId(tabId);
        if (tab == null || tab.isLoading() == loading) {
            return;
        }
        tab.setLoading(loading);
        fire(TabEvent.updated(tab));
    }

    public synchronized void setProgress(String tabId, int progress) {
        BrowserTab tab = byId(tabId);
        if (tab == null || tab.getProgress() == progress) {
            return;
        }
        tab.setProgress(progress);
        fire(TabEvent.updated(tab));
    }

    public synchronized void setFavicon(String tabId, byte[] favicon) {
        BrowserTab tab = byId(tabId);
        if (tab == null || Arrays.equals(tab.getFavicon(), favicon)) {
            return;
        }
        tab.setFavicon(favicon);
        fire(TabEvent.updated(tab));
    }

    public synchronized void setSslStatus(String tabId, SslStatus sslStatus) {
        BrowserTab tab = byId(tabId);
        if (tab == null || tab.getSslStatus() == sslStatus) {
            return;
        }
        tab.setSslStatus(sslStatus);
        fire(TabEvent.updated(tab));
    }

    /**
     * N3: updates the tab's back/forward enabled state (the CEF navigation
     * stack, reported by {@code onLoadingStateChange}).
     *
     * @return {@code true} when the state actually changed
     */
    public synchronized boolean setNavigationState(String tabId, boolean canGoBack, boolean canGoForward) {
        BrowserTab tab = byId(tabId);
        if (tab == null || (tab.canGoBack() == canGoBack && tab.canGoForward() == canGoForward)) {
            return false;
        }
        tab.setCanGoBack(canGoBack);
        tab.setCanGoForward(canGoForward);
        fire(TabEvent.updated(tab));
        return true;
    }

    // ------------------------------------------------------------------
    // Reads (GUI)
    // ------------------------------------------------------------------

    /** @return a defensive copy of the tab list in display order. */
    public synchronized List<BrowserTab> getTabs() {
        return List.copyOf(tabs);
    }

    public synchronized Optional<BrowserTab> getTab(String tabId) {
        return Optional.ofNullable(byId(tabId));
    }

    public synchronized Optional<BrowserTab> getTabAt(int index) {
        return (index >= 0 && index < tabs.size()) ? Optional.of(tabs.get(index)) : Optional.empty();
    }

    public synchronized Optional<BrowserTab> getActiveTab() {
        return activeIndex >= 0 ? Optional.of(tabs.get(activeIndex)) : Optional.empty();
    }

    public synchronized int getActiveIndex() {
        return activeIndex;
    }

    public synchronized int size() {
        return tabs.size();
    }

    public synchronized int getClosedTabCount() {
        return closedTabs.size();
    }

    // ------------------------------------------------------------------
    // Session (T8)
    // ------------------------------------------------------------------

    /** @return a serializable picture of the current tabs (never null). */
    public synchronized SessionSnapshot snapshot() {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setActiveIndex(Math.max(0, activeIndex));
        for (BrowserTab tab : tabs) {
            snapshot.addTab(tab.getUrl(), tab.getTitle());
        }
        return snapshot;
    }

    /**
     * T8: replaces the whole tab list with a saved session. Emits one ADDED
     * event per restored tab (the GUI lazily creates the CEF components) plus
     * the final ACTIVATED.
     *
     * @return the number of tabs restored (0 when the snapshot was empty)
     */
    public synchronized int restore(SessionSnapshot snapshot) {
        tabs.clear();
        closedTabs.clear();
        activeIndex = -1;
        if (snapshot == null || snapshot.getTabs().isEmpty()) {
            return 0;
        }
        for (SessionSnapshot.SessionTab entry : snapshot.getTabs()) {
            if (entry.getUrl() == null || entry.getUrl().isBlank()) {
                continue;
            }
            BrowserTab tab = new BrowserTab(entry.getUrl());
            if (entry.getTitle() != null) {
                tab.setTitle(entry.getTitle());
            }
            tabs.add(tab);
        }
        if (tabs.isEmpty()) {
            return 0;
        }
        activeIndex = Math.max(0, Math.min(snapshot.getActiveIndex(), tabs.size() - 1));
        List<TabEvent> events = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            events.add(TabEvent.added(tabs.get(i), i));
        }
        events.add(TabEvent.activated(tabs.get(activeIndex), activeIndex));
        fire(events.toArray(new TabEvent[0]));
        return tabs.size();
    }

    // ------------------------------------------------------------------

    private BrowserTab byId(String tabId) {
        if (tabId == null) {
            return null;
        }
        for (BrowserTab tab : tabs) {
            if (tab.getId().equals(tabId)) {
                return tab;
            }
        }
        return null;
    }

    private int indexOf(String tabId) {
        BrowserTab tab = byId(tabId);
        return tab == null ? -1 : tabs.indexOf(tab);
    }

    private void fire(TabEvent... events) {
        if (listeners.isEmpty()) {
            return;
        }
        for (TabEvent event : events) {
            for (TabEventListener listener : listeners) {
                try {
                    listener.onTabEvent(event);
                } catch (Exception e) {
                    logger.error("Tab event listener failed", e);
                }
            }
        }
    }
}


