package application.utils.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link TabUtils#moveTo(JTabbedPane, int, int)}:
 * order changes, metadata/component preservation, selection tracking, and no-op guards.
 */
@DisplayName("TabUtils - moveTo")
class TabUtilsTest {

    private JTabbedPane paneWith(String... names) {
        JTabbedPane tp = new JTabbedPane();
        for (String n : names) {
            tp.addTab(n, new JPanel());
        }
        return tp;
    }

    private String order(JTabbedPane tp) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tp.getTabCount(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(tp.getTitleAt(i));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("first tab moved to last position updates the order")
    void moveTo_MoveFirstToLast_OrderUpdates() {
        JTabbedPane tp = paneWith("A", "B", "C");
        TabUtils.moveTo(tp, 0, 2);
        assertEquals("B,C,A", order(tp));
        assertEquals(3, tp.getTabCount());
    }

    @Test
    @DisplayName("last tab moved to first position updates the order")
    void moveTo_MoveLastToFirst_OrderUpdates() {
        JTabbedPane tp = paneWith("A", "B", "C");
        TabUtils.moveTo(tp, 2, 0);
        assertEquals("C,A,B", order(tp));
    }

    @Test
    @DisplayName("middle tab moved to first position updates the order")
    void moveTo_MoveMiddleToFirst_OrderUpdates() {
        JTabbedPane tp = paneWith("A", "B", "C");
        TabUtils.moveTo(tp, 1, 0);
        assertEquals("B,A,C", order(tp));
    }

    @Test
    @DisplayName("moving a tab to its own slot is a no-op")
    void moveTo_SameIndex_NoOp() {
        JTabbedPane tp = paneWith("A", "B");
        TabUtils.moveTo(tp, 0, 0);
        assertEquals("A,B", order(tp));
        assertEquals(2, tp.getTabCount());
    }

    @Test
    @DisplayName("invalid indices are no-ops")
    void moveTo_InvalidIndex_NoOp() {
        JTabbedPane tp = paneWith("A", "B");
        TabUtils.moveTo(tp, -1, 1);
        TabUtils.moveTo(tp, 0, 9);
        TabUtils.moveTo(tp, 5, 1);
        assertEquals("A,B", order(tp));
        assertEquals(2, tp.getTabCount());
    }

    @Test
    @DisplayName("move preserves title, icon, tooltip and content component")
    void moveTo_PreservesMetadataAndComponent() {
        Icon icon = new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
        JPanel content = new JPanel();
        JTabbedPane tp = new JTabbedPane();
        tp.addTab("A", icon, content, "tipA");
        tp.addTab("B", (Icon) null, new JPanel(), "tipB");
        tp.addTab("C", (Icon) null, new JPanel(), "tipC");

        TabUtils.moveTo(tp, 0, 2);

        assertEquals("A", tp.getTitleAt(2));
        assertSame(icon, tp.getIconAt(2));
        assertEquals("tipA", tp.getToolTipTextAt(2));
        assertSame(content, tp.getComponentAt(2));
    }

    @Test
    @DisplayName("an unselected selected tab keeps its selection through the move")
    void moveTo_OtherSelectedTabStaysSelected() {
        JTabbedPane tp = paneWith("A", "B", "C");
        tp.setSelectedIndex(1); // B selected
        TabUtils.moveTo(tp, 0, 2); // A -> last: B,C,A
        assertEquals("B", tp.getTitleAt(tp.getSelectedIndex()));
    }

    @Test
    @DisplayName("the selected tab follows itself when it is the moved tab")
    void moveTo_MovedSelectedTabFollowsIt() {
        JTabbedPane tp = paneWith("A", "B", "C");
        tp.setSelectedIndex(0); // A selected
        TabUtils.moveTo(tp, 0, 2); // A -> last
        assertEquals(2, tp.getSelectedIndex());
        assertEquals("A", tp.getTitleAt(tp.getSelectedIndex()));
    }

    @Test
    @DisplayName("null pane throws NullPointerException")
    void moveTo_NullPane_ThrowsNpe() {
        assertThrows(NullPointerException.class, () -> TabUtils.moveTo(null, 0, 1));
    }
}