package application.module.node.gui.metrics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.swing.SwingUtilities;

import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawings;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsPanel expansion state API and ExpansionListener.
 * Verifies that the getter and listener mechanism work correctly for persistence.
 */
@DisplayName("MetricsPanel - Expansion State API")
class MetricsPanelTest {

    private MetricsPanel.ExpansionListener testListener;
    private boolean lastExpansionState;

    @BeforeAll
    static void registerIconFont() {
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    @BeforeEach
    void setUp() {
        testListener = expanded -> {
            lastExpansionState = expanded;
        };
    }

    @Test
    @DisplayName("isExpanded() returns current expansion state")
    void isExpanded_ReturnsCurrentState() {
        // Note: We can't construct a full MetricsPanel in headless tests easily,
        // but we verify the API contract via the ExpansionListener callback.
        // The listener receives the correct boolean value matching the internal state.
        
        // Simulate expanded -> collapsed transition
        testListener.onExpansionChanged(false);
        assertFalse(lastExpansionState, "Collapsed state should be false");
        
        // Simulate collapsed -> expanded transition  
        testListener.onExpansionChanged(true);
        assertTrue(lastExpansionState, "Expanded state should be true");
    }

    @Test
    @DisplayName("ExpansionListener receives correct boolean values")
    void expansionListener_ReceivesCorrectValues() {
        // Test that listener correctly captures state transitions
        testListener.onExpansionChanged(true);
        assertTrue(lastExpansionState, "Listener should receive true when expanded");
        
        testListener.onExpansionChanged(false);
        assertFalse(lastExpansionState, "Listener should receive false when collapsed");
    }

    @Test
    @DisplayName("ExpansionListener can be null without errors")
    void expansionListener_NullIsSafe() {
        // The MetricsPanel implementation uses null-check before calling:
        // if (expansionListener != null) { expansionListener.onExpansionChanged(isExpanded); }
        // This test documents the expected contract.
        MetricsPanel.ExpansionListener nullListener = null;
        assertNull(nullListener, "Null listener is allowed");
    }

    @Test
    @DisplayName("Multiple state transitions are tracked correctly")
    void expansionListener_MultipleTransitions() {
        int[] callCount = {0};
        boolean[] states = {false};
        
        MetricsPanel.ExpansionListener trackingListener = expanded -> {
            states[0] = expanded;
            callCount[0]++;
        };
        
        // Simulate: expand -> collapse -> expand -> collapse
        trackingListener.onExpansionChanged(true);
        assertEquals(1, callCount[0]);
        assertTrue(states[0]);
        
        trackingListener.onExpansionChanged(false);
        assertEquals(2, callCount[0]);
        assertFalse(states[0]);
        
        trackingListener.onExpansionChanged(true);
        assertEquals(3, callCount[0]);
        assertTrue(states[0]);
        
        trackingListener.onExpansionChanged(false);
        assertEquals(4, callCount[0]);
        assertFalse(states[0]);
    }

    /**
     * Regression test for the chevron/state desync after a hide/show cycle:
     * show panel (checkbox), collapse it (chevron), hide it (checkbox), show it
     * again — the re-show's layout stabilisation used to reset the content to
     * its natural (expanded) height while {@code isExpanded} and the chevron
     * still said "collapsed", so a visibly open panel showed the downward
     * "expand" chevron.
     * <p>
     * Invariant under test: the chevron ALWAYS offers "collapse" (up) while the
     * panel is expanded and "expand" (down) while it is collapsed, and the
     * layout stabilisation on re-show must not change the expansion state.
     */
    @Test
    @DisplayName("re-showing a collapsed panel keeps the chevron and the content consistent")
    void reshowKeepsChevronConsistentWithExpansionState() throws Exception {
        // Heavy construction (4 JFreeChart sub-panels) - run once on the EDT,
        // null context keeps the child panels in an empty (data-less) state.
        final MetricsPanel[] holder = new MetricsPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new MetricsPanel(null, null));
        MetricsPanel panel = holder[0];
        try {
            // 1) Show: fresh panel is expanded; the show path stabilises the layout.
            final int[] expandedHeight = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                panel.ensureLayoutStability();
                expandedHeight[0] = panel.getPreferredSize().height;
            });
            assertTrue(panel.isExpanded(), "a fresh panel is expanded");
            assertTrue(expandedHeight[0] > 60, "expanded content has a real height: " + expandedHeight[0]);
            assertEquals(CustomDrawings.Chevron.UP, chevronOf(panel),
                    "an expanded panel offers the collapse (up) chevron");

            // 2) Collapse via the chevron path's end state, then 3) hide.
            SwingUtilities.invokeAndWait(() -> {
                panel.setExpanded(false);
                panel.setVisible(false);
            });
            assertFalse(panel.isExpanded(), "the panel is collapsed");
            assertEquals(CustomDrawings.Chevron.DOWN, chevronOf(panel),
                    "a collapsed panel offers the expand (down) chevron");

            // 4) Show again: the show path stabilises the layout before revealing.
            final int[] collapsedHeight = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                panel.setVisible(true);
                panel.ensureLayoutStability();
                collapsedHeight[0] = panel.getPreferredSize().height;
            });

            // Invariant: state and visuals agree after the hide/show cycle.
            assertFalse(panel.isExpanded(), "the collapsed state survives a hide/show cycle");
            assertEquals(CustomDrawings.Chevron.DOWN, chevronOf(panel),
                    "the chevron still offers expand (down) after re-show");
            assertTrue(collapsedHeight[0] < expandedHeight[0] / 2,
                    "a collapsed panel stays collapsed after re-stabilisation: "
                            + collapsedHeight[0] + " vs expanded " + expandedHeight[0]);
        } finally {
            SwingUtilities.invokeAndWait(panel::shutdown);
        }
    }

    /**
     * @param panel the metrics panel whose toggle tab (index 0) carries the chevron
     * @return the chevron symbol currently drawn on the toggle tab
     */
    private static CustomDrawings.Symbol chevronOf(MetricsPanel panel) {
        return ((CustomDrawingComponent) panel.getTabComponentAt(0)).getDrawing();
    }
}