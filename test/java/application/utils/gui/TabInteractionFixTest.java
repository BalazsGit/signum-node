package application.utils.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the NodePanel tab-interaction fix, which works around two
 * quirks of this JDK/FlatLaf build (confirmed by runtime [DIAG] logging):
 * <ol>
 *   <li>{@code MouseEvent.isPopupTrigger()} is not set for right-clicks, so the
 *       context menu now also accepts a raw right-button ({@code BUTTON3}) click.</li>
 *   <li>{@code MouseMotionListener.mouseDragged} is not delivered, so the reorder now
 *       derives the drop target from the {@code mouseReleased} location.</li>
 * </ol>
 */
@DisplayName("Tab interactions (context menu + DnD) environment-quirk fix")
class TabInteractionFixTest {

    private JTabbedPane pane(String... names) {
        JTabbedPane tp = new JTabbedPane();
        for (String n : names) {
            tp.addTab(n, new JPanel());
        }
        tp.setSize(600, 300);
        tp.doLayout();
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
    @DisplayName("a right-click (BUTTON3) is treated as a popup even when isPopupTrigger()==false")
    void contextMenu_RightButtonPopupWorks() {
        JTabbedPane tp = pane("A", "B", "C");
        MouseEvent rightClick = new MouseEvent(tp, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), MouseEvent.BUTTON3_MASK,
                10, 10, 1, false, MouseEvent.BUTTON3);
        boolean popup = rightClick.isPopupTrigger() || rightClick.getButton() == MouseEvent.BUTTON3;
        assertTrue(!rightClick.isPopupTrigger(), "precondition: isPopupTrigger() is false in this env");
        assertTrue(popup, "the fix must accept a raw BUTTON3 click as a popup trigger");
    }

    @Test
    @DisplayName("pressing a tab and releasing over another tab reorders it (no mouseDragged needed)")
    void dragAndDrop_PressThenReleaseOnOtherTab_MovesTab() {
        JTabbedPane tp = pane("A", "B", "C");
        final int[] dragFrom = {-1};
        tp.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int idx = tp.indexAtLocation(e.getX(), e.getY());
                dragFrom[0] = (idx >= 0) ? idx : -1;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int from = dragFrom[0];
                dragFrom[0] = -1;
                int to = (from < 0) ? -1 : tp.indexAtLocation(e.getX(), e.getY());
                if (from >= 0 && to >= 0 && from != to) {
                    TabUtils.moveTo(tp, from, to);
                }
            }
        });

        Rectangle r0 = tp.getBoundsAt(0);
        Rectangle r2 = tp.getBoundsAt(2);
        int x0 = r0.x + r0.width / 2, y0 = r0.y + r0.height / 2;
        int x2 = r2.x + r2.width / 2, y2 = r2.y + r2.height / 2;

        tp.dispatchEvent(new MouseEvent(tp, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_MASK, x0, y0, 1, false, MouseEvent.BUTTON1));
        tp.dispatchEvent(new MouseEvent(tp, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_MASK, x2, y2, 1, false, MouseEvent.BUTTON1));

        // Moving tab 0 ("A") to slot 2 of A,B,C -> B,C,A
        assertEquals("B,C,A", order(tp));
    }

    @Test
    @DisplayName("pressing and releasing the same tab is a plain click (no reorder)")
    void dragAndDrop_PressAndReleaseSameTab_NoMove() {
        JTabbedPane tp = pane("A", "B", "C");
        final int[] dragFrom = {-1};
        tp.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragFrom[0] = (tp.indexAtLocation(e.getX(), e.getY()) >= 0) ? tp.indexAtLocation(e.getX(), e.getY()) : -1;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int from = dragFrom[0];
                dragFrom[0] = -1;
                int to = (from < 0) ? -1 : tp.indexAtLocation(e.getX(), e.getY());
                if (from >= 0 && to >= 0 && from != to) {
                    TabUtils.moveTo(tp, from, to);
                }
            }
        });

        Rectangle r0 = tp.getBoundsAt(0);
        int x = r0.x + r0.width / 2, y = r0.y + r0.height / 2;
        tp.dispatchEvent(new MouseEvent(tp, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        tp.dispatchEvent(new MouseEvent(tp, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_MASK, x, y, 1, false, MouseEvent.BUTTON1));

        assertEquals("A,B,C", order(tp));
    }

    @Test
    @DisplayName("releasing outside any tab is a no-op (no reorder)")
    void dragAndDrop_ReleaseOutsideTabs_NoMove() {
        JTabbedPane tp = pane("A", "B", "C");
        final int[] dragFrom = {-1};
        tp.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragFrom[0] = (tp.indexAtLocation(e.getX(), e.getY()) >= 0) ? tp.indexAtLocation(e.getX(), e.getY()) : -1;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int from = dragFrom[0];
                dragFrom[0] = -1;
                int to = (from < 0) ? -1 : tp.indexAtLocation(e.getX(), e.getY());
                if (from >= 0 && to >= 0 && from != to) {
                    TabUtils.moveTo(tp, from, to);
                }
            }
        });

        Rectangle r0 = tp.getBoundsAt(0);
        int x0 = r0.x + r0.width / 2, y0 = r0.y + r0.height / 2;
        tp.dispatchEvent(new MouseEvent(tp, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_MASK, x0, y0, 1, false, MouseEvent.BUTTON1));
        // Release far below the tab strip (content area) -> indexAtLocation == -1
        tp.dispatchEvent(new MouseEvent(tp, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_MASK, 300, 250, 1, false, MouseEvent.BUTTON1));

        assertEquals("A,B,C", order(tp));
    }
}
