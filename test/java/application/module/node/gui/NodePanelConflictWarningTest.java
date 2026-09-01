package application.module.node.gui;

import application.module.node.profile.NodeProfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JTabbedPane;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression tests for the cross-profile conflict warning that should appear in a
 * profile's info bar when another (RUNNING) profile occupies the same resource
 * (API/P2P/WebSocket port, database).
 * <p>
 * The {@link NodeInfoBar} only re-computes its conflicts when it is built or when its own
 * profile restarts — it does not observe the other profiles. The reported bug was that
 * switching from a running profile to another tab did NOT surface the conflict, because
 * the already-loaded panel's info bar was never re-evaluated. The fix refreshes the
 * selected profile's info bar on every tab activation (see
 * {@link NodePanel#refreshSelectedProfileInfoBar(JTabbedPane)}).
 * </p>
 * <p>
 * These tests exercise that wiring headlessly: they prove that selecting an already-loaded
 * profile's tab triggers an info-bar refresh (observed by the chip being re-rendered), and
 * that placeholder / null cases are safe. The EDT is pumped via
 * {@link SwingUtilities#invokeAndWait} (the same pattern used elsewhere in this suite).
 * </p>
 */
@DisplayName("NodePanel tab-activation conflict-warning refresh Tests")
class NodePanelConflictWarningTest {

    /** Distinct marker used to prove the info bar chips are re-rendered by a refresh. */
    private static final String SENTINEL = "SENTINEL-REFRESHED";

    @Test
    @DisplayName("selecting an already-loaded profile's tab refreshes its info bar")
    void selectingLoadedTab_refreshesInfoBar() throws Exception {
        // Arrange: an already-loaded (non-placeholder) profile panel, i.e. one that was
        // visited before another profile started. Its info bar currently holds a stale
        // value that a refresh must overwrite.
        String name = "tab-warning-" + System.nanoTime();
        NodeProfilePanel panel = new NodeProfilePanel(null, new NodeProfile(name), null);

        JLabel chip = findChip(panel.getInfoBar(), "API Port");
        assertNotNull(chip, "the API Port chip must exist on the info bar");

        SwingUtilities.invokeLater(() -> chip.setText(SENTINEL));
        SwingUtilities.invokeAndWait(() -> { }); // let the sentinel write land on the EDT
        assertEqualsSentinel(chip);

        // Wire the tabbed pane EXACTLY like NodePanel.initialize() does.
        JTabbedPane tabs = new JTabbedPane();
        tabs.addChangeListener(e -> NodePanel.refreshSelectedProfileInfoBar(tabs));
        tabs.addTab(name, panel);

        // Act: the user switches to this profile's tab (fires the ChangeListener).
        tabs.setSelectedIndex(0);
        SwingUtilities.invokeAndWait(() -> { }); // flush EDT: run the deferred refresh

        // Assert: the chip was re-rendered (sentinel cleared) → the tab activation
        // re-evaluated the profile's information, including its conflict warnings.
        assertNotEquals(SENTINEL, chip.getText(),
                "selecting the profile tab must refresh its info bar (conflict warnings)");
    }

    @Test
    @DisplayName("refreshConflictWarnings re-renders the info bar")
    void refreshConflictWarnings_rebuildsInfoBar() throws Exception {
        // Arrange
        NodeProfilePanel panel = new NodeProfilePanel(null, new NodeProfile("x-" + System.nanoTime()), null);
        JLabel chip = findChip(panel.getInfoBar(), "API Port");
        assertNotNull(chip);

        SwingUtilities.invokeLater(() -> chip.setText(SENTINEL));
        SwingUtilities.invokeAndWait(() -> { });
        assertEqualsSentinel(chip);

        // Act: invoke the new panel-level refresh directly.
        panel.refreshConflictWarnings();
        SwingUtilities.invokeAndWait(() -> { }); // flush EDT

        // Assert
        assertNotEquals(SENTINEL, chip.getText(),
                "refreshConflictWarnings() must re-render the info bar");
    }

    @Test
    @DisplayName("selecting a placeholder tab is a safe no-op")
    void selectingPlaceholderTab_noOp() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addChangeListener(e -> NodePanel.refreshSelectedProfileInfoBar(tabs));
        tabs.addTab("ph", new NodePlaceholderPanel("ph", () -> { }));
        tabs.setSelectedIndex(0);

        assertDoesNotThrow(() -> NodePanel.refreshSelectedProfileInfoBar(tabs));
        assertInstanceOf(NodePlaceholderPanel.class, tabs.getComponentAt(0),
                "a placeholder must not be mutated by the refresh");
    }

    @Test
    @DisplayName("refreshSelectedProfileInfoBar tolerates a null tabbed pane")
    void nullTabbedPane_safeNoOp() {
        assertDoesNotThrow(() -> NodePanel.refreshSelectedProfileInfoBar(null));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void assertEqualsSentinel(JLabel chip) {
        String text = chip.getText();
        if (text == null || !text.contains(SENTINEL)) {
            throw new AssertionError("expected the sentinel to be applied before the refresh");
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
