package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("double attach delivers each event exactly once (idempotent)")
    void attach_twice_deliversEachEventExactlyOnce() throws InterruptedException {
        String profileName = newProfileName();
        NodeProfile profile = new NodeProfile(profileName);
        NodeConsolePanel panel = new NodeConsolePanel(null, profile);
        Signum signum = new Signum(new NodeProfile(profileName), Paths.get("./conf"));
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
        Signum signum = new Signum(new NodeProfile(profileName), Paths.get("./conf"));
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
        Signum first = new Signum(new NodeProfile(profileName), Paths.get("./conf"));
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
        Signum second = new Signum(new NodeProfile(profileName), Paths.get("./conf"));
        String m2 = "RESTART_M2_" + System.nanoTime();
        second.getProfileLogger().info(m2);
        panel.setSignum(second); // ensureProfileLoggerAttached → attaches to SECOND (latch was reset)

        assertEventuallyOccurrences(panel, m2, 1,
                "after STOPPED the attach latch must be cleared so the restarted node's logs are delivered");
    }
}
