package application.module.node.gui;

import application.module.node.NodeModule;
import application.module.node.profile.NodeProfile;
import application.module.node.props.Props;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the ownership-event-driven cross-profile conflict broadcast. When NodeModule
 * reserves (start) or releases (stop) a profile's resources it fires a claiming-set event;
 * the GUI subscribes {@link NodeInfoBar#refreshAllConflicts()} to that event (see
 * {@link NodePanel}) so that EVERY live info bar re-evaluates its cross-profile warnings
 * immediately — the red conflict surfaces on an already-open, conflicting profile without a
 * tab switch. The "refresh happened" signal is a sentinel left on a chip by the test: a
 * re-render replaces the chip's text and clears it.
 */
class NodeInfoBarConflictBroadcastTest {

    private static final String SENTINEL = "__not_refreshed__";
    private static final List<NodeInfoBar> LIVE_BARS = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (NodeInfoBar bar : LIVE_BARS) {
            bar.dispose();
        }
        LIVE_BARS.clear();
        // Drop any reservation started through the public path so later tests start clean.
        NodeModule.getInstance().stopAll();
        SwingUtilities.invokeAndWait(() -> { });
    }

    private NodeInfoBar newBar(String name, int apiPort) {
        NodeProfile profile = new NodeProfile(name);
        profile.setProperty(Props.API_PORT.getName(), String.valueOf(apiPort));
        profile.setProperty(Props.P2P_PORT.getName(), String.valueOf(apiPort + 1000));
        profile.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "false");
        NodeInfoBar bar = new NodeInfoBar(profile);
        LIVE_BARS.add(bar);
        return bar;
    }

    private static JLabel findChip(NodeInfoBar bar, String key) {
        // Chips render their text as <html><b>Key:</b> Value — match on the key prefix.
        String prefix = "<html><b>" + key + ":";
        for (java.awt.Component child : bar.getComponents()) {
            if (child instanceof JLabel j && j.getText() != null && j.getText().startsWith(prefix)) {
                return j;
            }
        }
        return null;
    }

    private static void setSentinel(JLabel... chips) throws Exception {
        SwingUtilities.invokeLater(() -> {
            for (JLabel chip : chips) {
                chip.setText(SENTINEL);
            }
        });
        SwingUtilities.invokeAndWait(() -> { }); // flush so the sentinel is actually set
        for (JLabel chip : chips) {
            assertEquals(SENTINEL, chip.getText(), "the chip must still carry the sentinel before the refresh");
        }
    }

    @Test
    void ownershipChange_refreshesEveryLiveBar() throws Exception {
        // Arrange: two live sibling bars with sentinels on their chips.
        NodeInfoBar first = newBar("own-first-" + System.nanoTime(), 6125);
        NodeInfoBar second = newBar("own-second-" + System.nanoTime(), 6225);
        JLabel chipA = findChip(first, "P2P Port");
        JLabel chipB = findChip(second, "Database");
        assertNotNull(chipA, "the first bar must render a P2P Port chip");
        assertNotNull(chipB, "the second bar must render a Database chip");
        setSentinel(chipA, chipB);

        // Act: subscribe the GUI's real handler to the ownership event (as NodePanel does),
        // then trigger a reservation through the public start path.
        Runnable handler = NodeInfoBar::refreshAllConflicts;
        NodeModule.getInstance().addClaimingSetListener(handler);
        try {
            NodeModule.getInstance().startNode("own-owner-" + System.nanoTime());
            SwingUtilities.invokeAndWait(() -> { }); // flush the deferred EDT refresh
        } finally {
            NodeModule.getInstance().removeClaimingSetListener(handler);
        }

        // Assert: every live bar was re-rendered (sentinel cleared) — no tab switch needed.
        assertNotEquals(SENTINEL, chipA.getText(), "the ownership event must refresh the first live bar");
        assertNotEquals(SENTINEL, chipB.getText(), "the ownership event must refresh the second live bar");
    }

    @Test
    void refreshAllConflicts_rendersAllLiveBars() throws Exception {
        // Arrange: two live bars with sentinels on their chips.
        NodeInfoBar first = newBar("bc-first-" + System.nanoTime(), 5125);
        NodeInfoBar second = newBar("bc-second-" + System.nanoTime(), 5225);
        JLabel chipA = findChip(first, "P2P Port");
        JLabel chipB = findChip(second, "Database");
        assertNotNull(chipA, "the first bar must render a P2P Port chip");
        assertNotNull(chipB, "the second bar must render a Database chip");
        setSentinel(chipA, chipB);

        // Act: refresh every live bar directly (the handler the GUI subscribes).
        NodeInfoBar.refreshAllConflicts();
        SwingUtilities.invokeAndWait(() -> { });

        // Assert: both were re-rendered.
        assertNotEquals(SENTINEL, chipA.getText(), "refreshAllConflicts must re-render the first live bar");
        assertNotEquals(SENTINEL, chipB.getText(), "refreshAllConflicts must re-render the second live bar");
    }
}