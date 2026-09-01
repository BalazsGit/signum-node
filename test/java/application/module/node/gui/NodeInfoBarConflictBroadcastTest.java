package application.module.node.gui;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.module.node.props.Props;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests the cross-profile conflict PUSH broadcast: when any profile's state changes
 * (start/stop), every visible {@link NodeInfoBar} re-evaluates its resource conflicts.
 * This is what makes the red conflict warning appear on an already-open, conflicting
 * profile the moment another profile starts — without waiting for the viewed profile
 * to be (re)started or its tab to be (re)selected.
 * <p>
 * The conflict DETECTION itself is covered by {@code ProfileConflictDetectorTest}; these
 * tests cover the WIRING: that a state-change event refreshes all live bars and that a
 * disposed bar is no longer refreshed. The info bar updates its chips <b>in place</b>
 * (the same {@link JLabel} instance), so a sentinel written on a chip is cleared by a
 * refresh — a reliable signal that the refresh happened.
 * </p>
 */
@DisplayName("NodeInfoBar cross-profile conflict broadcast (state-change PUSH) Tests")
class NodeInfoBarConflictBroadcastTest {

    private static final String SENTINEL = "SENTINEL-CONFLICT-PUSH";

    /** A live info bar bound to a profile with an explicit API port. */
    private static NodeInfoBar newBar(String name, int apiPort) {
        NodeProfile profile = new NodeProfile(name);
        profile.setProperty(Props.API_PORT.getName(), String.valueOf(apiPort));
        return new NodeInfoBar(profile);
    }

    @Test
    @DisplayName("refreshAllConflicts re-renders every live info bar")
    void refreshAllConflicts_rendersAllLiveBars() throws Exception {
        // Arrange: two live bars (each self-registered on construction).
        NodeInfoBar first = newBar("push-a-" + System.nanoTime(), 8125);
        NodeInfoBar second = newBar("push-b-" + System.nanoTime(), 9125);
        try {
            JLabel chip = findChip(first, "API Port");
            assertNotNull(chip, "the API Port chip must exist on the info bar");

            SwingUtilities.invokeLater(() -> chip.setText(SENTINEL));
            SwingUtilities.invokeAndWait(() -> { }); // let the sentinel land
            assertContainsSentinel(chip);

            // Act: broadcast a conflict refresh (as a profile state change would).
            NodeInfoBar.refreshAllConflicts();
            SwingUtilities.invokeAndWait(() -> { }); // flush the deferred EDT refresh

            // Assert: the live bar was re-rendered (sentinel cleared by the in-place update).
            assertNotEquals(SENTINEL, chip.getText(),
                    "refreshAllConflicts() must refresh every live info bar");
        } finally {
            first.dispose();
            second.dispose();
        }
    }

    @Test
    @DisplayName("a disposed bar is no longer refreshed by the broadcast")
    void disposedBar_notRefreshedByBroadcast() throws Exception {
        // Arrange: a live bar that we then dispose (as NodeProfilePanel.dispose() does).
        NodeInfoBar bar = newBar("push-dispose-" + System.nanoTime(), 7125);
        JLabel chip = findChip(bar, "API Port");
        assertNotNull(chip);

        SwingUtilities.invokeLater(() -> chip.setText(SENTINEL));
        SwingUtilities.invokeAndWait(() -> { });
        assertContainsSentinel(chip);

        // Act: dispose, then broadcast.
        bar.dispose();
        NodeInfoBar.refreshAllConflicts();
        SwingUtilities.invokeAndWait(() -> { });

        // Assert: the disposed bar was NOT refreshed (still shows the sentinel).
        assertEquals(SENTINEL, chip.getText(),
                "a disposed bar must be unregistered from the conflict broadcast");
    }

    @Test
    @DisplayName("a profile state change broadcasts a conflict refresh to sibling bars")
    void stateChange_broadcastsToSiblingBars() throws Exception {
        // Arrange: a sibling bar (a different profile already open) with a sentinel.
        NodeInfoBar sibling = newBar("push-sibling-" + System.nanoTime(), 6125);
        JLabel chip = findChip(sibling, "API Port");
        assertNotNull(chip);

        SwingUtilities.invokeLater(() -> chip.setText(SENTINEL));
        SwingUtilities.invokeAndWait(() -> { });
        assertContainsSentinel(chip);

        // The profile whose state is about to change (its own panel + info bar).
        NodeProfilePanel panel = new NodeProfilePanel(null,
                new NodeProfile("push-owner-" + System.nanoTime()), null);
        try {
            // Act: the owner profile's state changes (PUSH) → broadcast to all live bars.
            // Run on the EDT (like the real state listener does), then flush the
            // deferred refreshData() tasks it schedules.
            SwingUtilities.invokeAndWait(() -> panel.refreshFromSignum(null, Signum.State.RUNNING));
            SwingUtilities.invokeAndWait(() -> { });

            // Assert: the SIBLING bar was refreshed by the broadcast (sentinel cleared).
            assertNotEquals(SENTINEL, chip.getText(),
                    "a state change must refresh the conflict chips of every live bar, "
                            + "not only the changed profile's own bar");
        } finally {
            panel.dispose();
            sibling.dispose();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void assertContainsSentinel(JLabel chip) {
        String text = chip.getText();
        if (text == null || !text.contains(SENTINEL)) {
            throw new AssertionError("expected the sentinel to be applied before the broadcast");
        }
    }

    private static JLabel findChip(NodeInfoBar bar, String key) throws Exception {
        JLabel[] result = new JLabel[1];
        SwingUtilities.invokeLater(() -> {
            for (Component c : bar.getComponents()) {
                if (c instanceof JLabel label && label.getText() != null && label.getText().contains(key)) {
                    result[0] = label;
                    break;
                }
            }
        });
        SwingUtilities.invokeAndWait(() -> { });
        return result[0];
    }
}