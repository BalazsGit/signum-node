package application.module.browser.model.tab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the inactive-tab discard policy (plan F9, T10/D13).
 */
class TabDiscardPolicyTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("a single tab is never discarded")
    void singleTabNever() {
        BrowserTab only = new BrowserTab("https://a.example/");
        assertTrue(TabDiscardPolicy.discardable(List.of(only), only.getId(), NOW, 5)
                .isEmpty());
    }

    @Test
    @DisplayName("discard is disabled with a non-positive delay")
    void disabledWithNonPositiveDelay() {
        BrowserTab a = new BrowserTab("https://a.example/");
        BrowserTab b = new BrowserTab("https://b.example/");
        b.setLastActiveAtForTest(NOW - 100 * MINUTE);

        assertTrue(TabDiscardPolicy.discardable(List.of(a, b), a.getId(), NOW, 0)
                .isEmpty());
        assertTrue(TabDiscardPolicy.discardable(List.of(a, b), a.getId(), NOW, -3)
                .isEmpty());
    }

    @Test
    @DisplayName("stale inactive tabs are discarded; active/loading/discarded are not")
    void staleTabsDiscarded() {
        BrowserTab active = new BrowserTab("https://active.example/");
        active.setLastActiveAtForTest(NOW - 100 * MINUTE); // stale, but active
        BrowserTab loading = new BrowserTab("https://loading.example/");
        loading.setLoading(true);
        loading.setLastActiveAtForTest(NOW - 100 * MINUTE);
        BrowserTab discarded = new BrowserTab("https://gone.example/");
        discarded.setDiscarded(true);
        discarded.setLastActiveAtForTest(NOW - 100 * MINUTE);
        BrowserTab internal = new BrowserTab("signum://settings");
        internal.setLastActiveAtForTest(NOW - 100 * MINUTE);
        BrowserTab stale = new BrowserTab("https://stale.example/");
        stale.setLastActiveAtForTest(NOW - 100 * MINUTE);
        BrowserTab fresh = new BrowserTab("https://fresh.example/");
        fresh.setLastActiveAtForTest(NOW - MINUTE);

        List<String> out = TabDiscardPolicy.discardable(
                List.of(active, loading, discarded, internal, stale, fresh),
                active.getId(), NOW, 15);

        assertEquals(List.of(stale.getId()), out);
    }

    @Test
    @DisplayName("a tab active right before the cutoff is kept (boundary)")
    void boundaryKept() {
        BrowserTab a = new BrowserTab("https://a.example/");
        BrowserTab b = new BrowserTab("https://b.example/");
        b.setLastActiveAtForTest(NOW - 15 * MINUTE); // exactly at the cutoff

        // the cutoff is exclusive: lastActive < now - minutes
        assertTrue(TabDiscardPolicy.discardable(List.of(a, b), a.getId(), NOW, 15)
                .isEmpty());
        b.setLastActiveAtForTest(NOW - 15 * MINUTE - 1);
        assertEquals(List.of(b.getId()),
                TabDiscardPolicy.discardable(List.of(a, b), a.getId(), NOW, 15));
    }

    @Test
    @DisplayName("an unknown active id does not crash and discards everything stale")
    void unknownActiveId() {
        BrowserTab a = new BrowserTab("https://a.example/");
        a.setLastActiveAtForTest(NOW - 100 * MINUTE);
        BrowserTab b = new BrowserTab("https://b.example/");
        b.setLastActiveAtForTest(NOW - 100 * MINUTE);

        assertEquals(List.of(a.getId(), b.getId()),
                TabDiscardPolicy.discardable(List.of(a, b), "unknown", NOW, 15));
    }
}