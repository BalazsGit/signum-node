package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

/**
 * Adoption contract tests for {@link NodeProfilePanel} (v4 P2.2 test matrix).
 * <p>
 * The panel must always hold <b>exactly one</b> {@link Signum.StateListener} on
 * the Signum it currently owns:
 * <ul>
 *   <li>adopting the same instance is idempotent (no duplicate listener),</li>
 *   <li>adopting a different instance unregisters the previous one first,</li>
 *   <li>{@link NodeProfilePanel#dispose()} unbinds the panel from its Signum.</li>
 * </ul>
 * These tests exercise the binding logic only — no node is started (headless-safe:
 * Swing components are constructed but never shown).
 * </p>
 */
@DisplayName("NodeProfilePanel Adoption (single-listener contract) Tests")
class NodeProfilePanelAdoptionTest {

    private static int profileSeq = 0;

    private static NodeProfile newProfile() {
        return new NodeProfile("adoption-test-" + (profileSeq++));
    }

    private static NodeProfilePanel newPanel() {
        return new NodeProfilePanel(null, newProfile(), null);
    }

    private static Signum newSignum(NodeProfile profile) {
        return new Signum(profile, Paths.get("./conf"));
    }

    @Test
    @DisplayName("adopt registers exactly one state listener on the Signum")
    void adopt_registersExactlyOneListener() {
        NodeProfile profile = newProfile();
        NodeProfilePanel panel = new NodeProfilePanel(null, profile, null);
        Signum signum = newSignum(profile);

        panel.adoptSignum(signum);

        List<Signum.StateListener> listeners = signum.getStateListeners();
        assertEquals(1, listeners.size(),
                "the adopted Signum must hold exactly one state listener");
        panel.dispose();
    }

    @Test
    @DisplayName("adopting the same instance twice stays idempotent")
    void adopt_sameInstance_isIdempotent() {
        NodeProfile profile = newProfile();
        NodeProfilePanel panel = new NodeProfilePanel(null, profile, null);
        Signum signum = newSignum(profile);

        panel.adoptSignum(signum);
        panel.adoptSignum(signum);

        assertEquals(1, signum.getStateListeners().size(),
                "re-adopting the same instance must not add a second listener");
        panel.dispose();
    }

    @Test
    @DisplayName("adopting a different instance unregisters the previous one")
    void adopt_differentInstance_unregistersPrevious() {
        NodeProfile profile = newProfile();
        NodeProfilePanel panel = new NodeProfilePanel(null, profile, null);
        Signum first = newSignum(profile);
        Signum second = newSignum(profile);

        panel.adoptSignum(first);
        assertEquals(1, first.getStateListeners().size());

        panel.adoptSignum(second);

        assertTrue(first.getStateListeners().isEmpty(),
                "the previous Signum must be fully unregistered (restart-safe binding)");
        assertEquals(1, second.getStateListeners().size(),
                "the new Signum must hold exactly one listener");
        assertSame(second, panel.getSignum(), "the panel must own the most recently adopted instance");
        panel.dispose();
    }

    @Test
    @DisplayName("dispose unbinds the panel from its Signum")
    void dispose_unregistersListener() {
        NodeProfile profile = newProfile();
        NodeProfilePanel panel = new NodeProfilePanel(null, profile, null);
        Signum signum = newSignum(profile);

        panel.adoptSignum(signum);
        assertEquals(1, signum.getStateListeners().size());

        panel.dispose();

        assertTrue(signum.getStateListeners().isEmpty(),
                "dispose must remove the state listener from the Signum");
        panel.dispose(); // idempotent
    }

    @Test
    @DisplayName("adopt(null) is a safe no-op")
    void adopt_null_isIgnored() {
        NodeProfile profile = newProfile();
        NodeProfilePanel panel = new NodeProfilePanel(null, profile, null);

        assertDoesNotThrow(() -> panel.adoptSignum(null));
        panel.dispose();
    }
}
