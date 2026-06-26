package application.utils.gui;

import javax.swing.*;
import javax.swing.BorderFactory;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * A specialized JScrollPane for toolbars or single-row containers that
 * automatically adjusts its preferred height to include the horizontal
 * scrollbar when it becomes visible, preventing it from overlapping the content.
 * <p>
 * This is the consolidated toolbar scrolling solution used across all configuration
 * panels and the NodeConsolePanel. It provides:
 * <ul>
 *   <li>Horizontal-only overflow scrolling when window is narrow</li>
 *   <li>Dynamic height adjustment (no content overlap with scrollbar)</li>
 *   <li>Consistent 2px gap between button row and scrollbar</li>
 *   <li>Right-alignment of buttons during resize</li>
 *   <li>Maximum height constraint to prevent vertical expansion</li>
 * </ul>
 */
public class ResponsiveToolbarScrollPane extends JScrollPane {
    private final JPanel contentWrapper;
    private final int bottomMargin;

    public ResponsiveToolbarScrollPane(Component view, Insets contentInsets) {
        super();
        this.bottomMargin = contentInsets.bottom;

        // Clean borders - no extra spacing from the scroll pane itself
        setBorder(BorderFactory.createEmptyBorder());
        setViewportBorder(null);

        // Transparent wrapper for proper layout delegation
        this.contentWrapper = new JPanel(new BorderLayout());
        this.contentWrapper.setOpaque(false);
        // Only apply top/left/right insets; bottom is handled dynamically by getPreferredSize()
        this.contentWrapper.setBorder(BorderFactory.createEmptyBorder(contentInsets.top, contentInsets.left, 0,
                contentInsets.right));
        this.contentWrapper.add(view, BorderLayout.CENTER);
        setViewportView(contentWrapper);

        // Use SIMPLE_SCROLL_MODE for better rendering performance
        getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);

        // Horizontal-only scrolling
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_AS_NEEDED);
        setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_NEVER);
        getHorizontalScrollBar().setUnitIncrement(16);

        // Prevent vertical expansion beyond preferred height
        final int initialMaxHeight = 64;
        setMaximumSize(new Dimension(Integer.MAX_VALUE, initialMaxHeight));

        JScrollBar hBar = getHorizontalScrollBar();

        // Keep buttons right-aligned when scrollbar appears/disappears
        hBar.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                scrollRight();
                revalidateParent();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                revalidateParent();
            }

            private void scrollRight() {
                SwingUtilities.invokeLater(() -> hBar.setValue(hBar.getMaximum()));
            }

            private void revalidateParent() {
                SwingUtilities.invokeLater(() -> {
                    revalidate();
                    Container parent = getParent();
                    if (parent != null) {
                        parent.revalidate();
                        parent.repaint();
                    }
                });
            }
        });

        // Keep buttons right-aligned during resize when scrollbar is visible
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (hBar.isShowing()) {
                    SwingUtilities.invokeLater(() -> hBar.setValue(hBar.getMaximum()));
                }
            }
        });

        // Initial scroll-right after first layout pass
        SwingUtilities.invokeLater(() -> {
            if (hBar.isVisible() || hBar.isShowing()) {
                hBar.setValue(hBar.getMaximum());
            }
        });
    }

    /**
     * Returns the preferred size, dynamically adjusting height based on whether
     * the horizontal scrollbar is visible.
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension pref = super.getPreferredSize();
        JScrollBar hBar = getHorizontalScrollBar();
        if (hBar != null && (hBar.isVisible() || hBar.isShowing())) {
            pref.height += hBar.getPreferredSize().height;
        } else {
            pref.height += bottomMargin;
        }
        return pref;
    }
}
