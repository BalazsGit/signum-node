package application.utils.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;

/**
 * Segédosztály a JTabbedPane hover állapotának megőrzéséhez revalidate vagy
 * repaint hívások során.
 */
public class TabbedPaneHoverHelper {
    private boolean isProcessing = false;

    /**
     * A JTabbedPane repaint() metódusából hívandó.
     */
    public void handleRepaint(JTabbedPane pane) {
        if (!isProcessing) {
            syncHover(pane);
        }
    }

    /**
     * A JTabbedPane revalidate() metódusából hívandó.
     */
    public void handleRevalidate(JTabbedPane pane) {
        if (!isProcessing) {
            if (SwingUtilities.isEventDispatchThread()) {
                syncHover(pane);
            } else {
                SwingUtilities.invokeLater(() -> syncHover(pane));
            }
        }
    }

    private void syncHover(JTabbedPane pane) {
        if (isProcessing || !pane.isShowing()) {
            return;
        }

        Point p = pane.getMousePosition();
        if (p != null && pane.indexAtLocation(p.x, p.y) != -1) {
            isProcessing = true;
            try {
                pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, p.x, p.y, 0, false));
            } finally {
                isProcessing = false;
            }
        }
    }
}