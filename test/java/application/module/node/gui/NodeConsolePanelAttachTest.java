package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import application.module.logging.context.NodeLoggingContextManager;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

/**
 * Attach contract tests for {@link NodeConsolePanel#ensureProfileLoggerAttached()}
 * (v4 P2.2 test matrix).
 * <p>
 * Guarantees under test (v4 §4.3 "Start" flow, step 3 — order-independent
 * attach + replay):
 * <ul>
 *   <li>attaching twice is idempotent — each event is delivered <b>exactly once</b>
 *       to the console (no duplicates),</li>
 *   <li>events logged <i>before</i> the attach are replayed (no startup output lost),</li>
 *   <li>attaching without a Signum is a safe no-op (panel never crashes).</li>
 * </ul>
 * Headless-safe: Swing components are constructed but never shown.
 * </p>
 */
@DisplayName("NodeConsolePanel ProfileLogger attach (idempotent + replay) Tests")
class NodeConsolePanelAttachTest {

    private static int profileSeq = 0;

    @TempDir static java.nio.file.Path confRoot;

    private static String newProfileName() {
        return "attach-test-" + (profileSeq++);
    }

    private static int countOccurrences(String haystack, String needle) {
        AtomicInteger count = new AtomicInteger(0);
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count.incrementAndGet();
            from += needle.length();
        }
        return count.get();
    }

    private static String consoleText(NodeConsolePanel panel) {
        var doc = panel.getUnifiedConsole().getTextPane().getStyledDocument();
        try {
            return doc.getText(0, doc.getLength());
        } catch (Exception e) {
            throw new IllegalStateException("cannot read console document", e);
        }
    }

    /**
     * Polls the console document until the marker appears the expected number of
     * times (the console renders via LogEventBatcher → EDT, so delivery is async).
     *
     * @return true if the expected occurrence count was observed before timeout
     */
    private static boolean awaitOccurrences(NodeConsolePanel panel, String marker, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countOccurrences(consoleText(panel), marker) == expected) {
                return true;
            }
            Thread.sleep(50);
        }
        return countOccurrences(consoleText(panel), marker) == expected;
    }

    private static void assertEventuallyOccurrences(NodeConsolePanel panel, String marker, int expected,
                                                     String message) throws InterruptedException {
        assertTrue(awaitOccurrences(panel, marker, expected, 5000), message);
    }

    @Test
    @DisplayName("the status strip is built by the console but hosted by the NodeProfilePanel")
    void statusPanel_builtByConsole_notHostedByConsole() {
        NodeProfile profile = new NodeProfile(newProfileName());
        NodeConsolePanel panel = new NodeConsolePanel(null, profile);
        javax.swing.JPanel status = panel.getStatusPanel();
        assertNotNull(status, "the console builds its bottom status strip");
        assertNull(status.getParent(),
                "the console must NOT host the strip — the NodeProfilePanel (BorderLayout.SOUTH) does");
        assertFalse(status.isOpaque(), "the strip is transparent (thin margin against the window edge)");
    }

    @Test
    @DisplayName("double attach delivers each event exactly once (idempotent)")
    void attach_twice_deliversEachEventExactlyOnce() throws InterruptedException {
        String profileName = newProfileName();
        NodeProfile profile = new NodeProfile(profileName);
        NodeConsolePanel panel = new NodeConsolePanel(null, profile);
        Signum signum = new Signum(new NodeProfile(profileName), confRoot);
        panel.setSignum(signum);

        // Idempotent double attach
        panel.ensureProfileLoggerAttached();
        panel.ensureProfileLoggerAttached();

        String marker = "ATTACH_ONCE_" + System.nanoTime();
        signum.getProfileLogger().info(marker);

        assertEventuallyOccurrences(panel, marker, 1,
                "a double attach must not duplicate delivery of the same event (actual="
                        + countOccurrences(consoleText(panel), marker) + ")");
    }

    @Test
    @DisplayName("events logged before attach are replayed to the console")
    void replay_eventsLoggedBeforeAttach_areDelivered() throws InterruptedException {
        String profileName = newProfileName();
        NodeProfile profile = new NodeProfile(profileName);
        NodeConsolePanel panel = new NodeConsolePanel(null, profile);
        Signum signum = new Signum(new NodeProfile(profileName), confRoot);
        panel.setSignum(signum);

        // Log BEFORE the console attached its subscriber (startup output).
        String markerA = "REPLAY_A_" + System.nanoTime();
        String markerB = "REPLAY_B_" + System.nanoTime();
        signum.getProfileLogger().info(markerA);
        signum.getProfileLogger().info(markerB);

        panel.ensureProfileLoggerAttached();

        // A late event must also arrive (live path).
        String markerC = "LIVE_C_" + System.nanoTime();
        signum.getProfileLogger().info(markerC);

        assertEventuallyOccurrences(panel, markerA, 1, "early event A must be replayed exactly once");
        assertEventuallyOccurrences(panel, markerB, 1, "early event B must be replayed exactly once");
        assertEventuallyOccurrences(panel, markerC, 1, "live event C must be delivered exactly once");
    }

    @Test
    @DisplayName("attach without a Signum is a safe no-op")
    void attach_withoutSignum_isSafeNoOp() {
        String profileName = newProfileName();
        NodeConsolePanel panel = new NodeConsolePanel(null, new NodeProfile(profileName));

        // No setSignum() and no registered instance in NodeModule for this profile:
        // attach must log a warning and return without throwing.
        panel.ensureProfileLoggerAttached();
        assertTrue(true, "ensureProfileLoggerAttached must not throw when no Signum exists");
    }

    @Test
    @DisplayName("attach latch resets on STOPPED so a restart can re-attach (restart-safe)")
    void restart_attachLatchResetsOnStopped() throws Exception {
        String profileName = "restart-" + System.nanoTime();
        NodeConsolePanel panel = new NodeConsolePanel(null, new NodeProfile(profileName));

        // First lifecycle: attach + a delivered event.
        Signum first = new Signum(new NodeProfile(profileName), confRoot);
        panel.setSignum(first); // attaches → latch becomes true
        String m1 = "RESTART_M1_" + System.nanoTime();
        first.getProfileLogger().info(m1);
        assertEventuallyOccurrences(panel, m1, 1, "first-cycle event must be delivered");

        // Node stops: onNodeStateChanged(STOPPED) must clear the attach latch.
        panel.onNodeStateChanged(Signum.State.RUNNING, Signum.State.STOPPED);
        javax.swing.SwingUtilities.invokeAndWait(() -> { }); // pump EDT → STOPPED branch (latch reset) runs

        // Restart: a fresh Signum (fresh ProfileLogger) is adopted. Its startup log
        // (emitted before the console re-attaches) must be replayed — this only works
        // if the latch was cleared on STOPPED (otherwise the re-attach is a no-op).
        Signum second = new Signum(new NodeProfile(profileName), confRoot);
        String m2 = "RESTART_M2_" + System.nanoTime();
        second.getProfileLogger().info(m2);
        panel.setSignum(second); // ensureProfileLoggerAttached → attaches to SECOND (latch was reset)

        assertEventuallyOccurrences(panel, m2, 1,
                "after STOPPED the attach latch must be cleared so the restarted node's logs are delivered");
    }

    @Test
    @DisplayName("console log feed survives a tab move (subscriber disposed by removeNotify is recreated + re-attached)")
    void tabMove_logFeedStaysContinuous() throws InterruptedException {
        String profileName = newProfileName();
        NodeConsolePanel panel = new NodeConsolePanel(null, new NodeProfile(profileName));
        Signum signum = new Signum(new NodeProfile(profileName), confRoot);
        panel.setSignum(signum);

        // A pre-move event must arrive (attach works).
        String m1 = "TABMOVE_M1_" + System.nanoTime();
        signum.getProfileLogger().info(m1);
        assertEventuallyOccurrences(panel, m1, 1, "pre-move event must be delivered");

        // Simulate a tab move: TabUtils.moveTo does removeTabAt()+insertTab(), which
        // triggers removeNotify() on the console panel — disposing the subscriber.
        panel.getUnifiedConsole().dispose();
        assertNull(panel.getUnifiedConsole().getSubscriber(),
                "precondition: the tab move's removeNotify() disposed the subscriber");

        // The addNotify() recovery must recreate + re-attach the subscriber so the feed resumes.
        panel.addNotify();
        assertNotNull(panel.getUnifiedConsole().getSubscriber(),
                "addNotify() must recreate the subscriber after a tab move");

        // A post-move event must arrive (feed restored) — this is the user-visible fix.
        String m2 = "TABMOVE_M2_" + System.nanoTime();
        signum.getProfileLogger().info(m2);
        assertEventuallyOccurrences(panel, m2, 1, "post-move event must be delivered (feed restored across the drag)");
    }

    @Test
    @DisplayName("A9: new / replay / displayed are measured separately (total line length alone does not prove no-duplication)")
    void a9_newReplayDisplayedMeasuredSeparately() throws InterruptedException {
        String profileName = newProfileName();
        NodeProfile profile = new NodeProfile(profileName);
        NodeConsolePanel panel = new NodeConsolePanel(null, profile);
        Signum signum = new Signum(new NodeProfile(profileName), confRoot);
        try {
            // 3 REPLAY events: logged to the bus BEFORE the console subscribes (startup
            // output). They sit in the ProfileLogger replay ring and are delivered when
            // the subscriber attaches.
            String r0 = "A9_REPLAY_R0_" + System.nanoTime();
            String r1 = "A9_REPLAY_R1_" + System.nanoTime();
            String r2 = "A9_REPLAY_R2_" + System.nanoTime();
            signum.getProfileLogger().info(r0);
            signum.getProfileLogger().info(r1);
            signum.getProfileLogger().info(r2);

            panel.setSignum(signum);             // adopt + (re)attach the console subscriber
            panel.ensureProfileLoggerAttached(); // -> the 3 above are REPLAYED to the console

            // 2 NEW events: logged AFTER the attach (live path).
            String n0 = "A9_NEW_N0_" + System.nanoTime();
            String n1 = "A9_NEW_N1_" + System.nanoTime();
            signum.getProfileLogger().info(n0);
            signum.getProfileLogger().info(n1);

            String[] replay = {r0, r1, r2};
            String[] fresh = {n0, n1};
            String[] all = {r0, r1, r2, n0, n1};

            // wait (async EDT render) until every one of the 5 markers is present
            long deadline = System.currentTimeMillis() + 5000;
            String text = consoleText(panel);
            while (!allPresent(text, all) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                text = consoleText(panel);
            }
            Thread.sleep(150); // settle: let any in-flight EDT render complete before counting
            text = consoleText(panel);

            // (1) REPLAY measured on its own: each pre-attach event displayed exactly once
            int replaySeen = sumOccurrences(text, replay);
            assertEquals(replay.length, replaySeen,
                    "the 3 pre-attach (replay) events must each be displayed exactly once");

            // (2) NEW measured on its own: each post-attach event displayed exactly once
            int newSeen = sumOccurrences(text, fresh);
            assertEquals(fresh.length, newSeen,
                    "the 2 post-attach (new) events must each be displayed exactly once");

            // (3) DISPLAYED = replay + NEW as a PARTITION. A naive "total line count" check
            //     cannot prove this: a replay-duplication + a dropped new event leave the
            //     total length unchanged, but break the per-category counts and the
            //     displayed == replay + new identity.
            int displayed = sumOccurrences(text, all);
            assertEquals(replaySeen + newSeen, displayed,
                    "displayed markers must equal replay + new (no duplication, no loss)");
            assertEquals(all.length, displayed,
                    "displayed markers must be exactly 5 (3 replay + 2 new)");

            // (4) no individual marker may be duplicated
            for (String m : all) {
                assertEquals(1, countOccurrences(text, m),
                        "a marker must appear exactly once (no duplication): " + m);
            }
        } finally {
            try {
                NodeLoggingContextManager.shared().remove(profileName);
            } catch (Exception ignored) {
                // best-effort cleanup; never fail the test on it
            }
        }
    }

    /** True when every marker occurs at least once in the given text. */
    private static boolean allPresent(String text, String... markers) {
        for (String m : markers) {
            if (countOccurrences(text, m) < 1) {
                return false;
            }
        }
        return true;
    }

    /** Total number of occurrences of all the given markers in the given text. */
    private static int sumOccurrences(String text, String... markers) {
        int total = 0;
        for (String m : markers) {
            total += countOccurrences(text, m);
        }
        return total;
    }
}
