package application.utils.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.JViewport;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

public class GuiUtils {
    /**
     * Configures a horizontal scroll bar listener to adjust the bottom padding of a
     * content component
     * when the scroll bar appears or disappears. This prevents the scroll bar from
     * overlapping the content.
     *
     * @param scrollPane       The scroll pane containing the horizontal scroll bar.
     * @param contentComponent The component whose border should be adjusted.
     * @param defaultInsets    The base insets to use when the scroll bar is hidden.
     */
    public static void addHorizontalScrollPadding(JScrollPane scrollPane, JComponent contentComponent,
            Insets defaultInsets) {
        addHorizontalScrollPadding(scrollPane, contentComponent, defaultInsets, null);
    }

    /**
     * Configures a horizontal scroll bar listener to adjust the bottom padding of a
     * content component.
     *
     * @param scrollPane       The scroll pane.
     * @param contentComponent The component whose border should be adjusted.
     * @param defaultInsets    The base insets.
     * @param onUpdate         An optional callback executed after padding is
     *                         adjusted.
     */
    public static void addHorizontalScrollPadding(JScrollPane scrollPane, JComponent contentComponent,
            Insets defaultInsets, Runnable onUpdate) {
        JScrollBar hBar = scrollPane.getHorizontalScrollBar();
        hBar.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                adjust(hBar.getHeight());
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                adjust(defaultInsets.bottom);
            }

            private void adjust(int bottom) {
                contentComponent
                        .setBorder(new EmptyBorder(defaultInsets.top, defaultInsets.left, bottom, defaultInsets.right));
                contentComponent.revalidate();
                scrollPane.revalidate();
                Container parent = scrollPane.getParent();
                if (parent != null) {
                    parent.revalidate();
                    parent.repaint();
                }
                if (onUpdate != null) {
                    onUpdate.run();
                }
            }
        });
    }

    /**
     * Creates a properly configured JScrollPane for a horizontal toolbar panel.
     * Handles scrollbar appearance/disappearance padding, keeps buttons right-aligned,
     * and prevents vertical expansion (so the toolbar only grows horizontally).
     * This ensures consistent behavior across all panels when the window is narrowed.
     *
     * @param toolbarContent The toolbar content component (typically a JPanel with buttons).
     * @param defaultInsets  The base insets to use for the content component.
     * @return A fully configured JScrollPane ready for use.
     */
    public static JScrollPane createToolbarScrollPane(JComponent toolbarContent, Insets defaultInsets) {
        JScrollPane scrollPane = new JScrollPane(toolbarContent);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        JScrollBar hBar = scrollPane.getHorizontalScrollBar();

        // Prevent the scroll pane from expanding vertically beyond its preferred height.
        // This ensures the toolbar row stays compact regardless of parent layout type.
        final int initialMaxHeight = 64; // generous upper bound for one row of buttons
        scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, initialMaxHeight));

        // Adjust padding when scrollbar appears/disappears, and scroll to right
        addHorizontalScrollPadding(scrollPane, toolbarContent, defaultInsets,
            () -> SwingUtilities.invokeLater(() -> hBar.setValue(hBar.getMaximum())));

        // Keep buttons right-aligned during resize
        scrollPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (hBar.isShowing()) {
                    SwingUtilities.invokeLater(() -> hBar.setValue(hBar.getMaximum()));
                }
            }
        });

        // Initial padding check after the first layout pass to handle cases where
        // the panel opens at a narrow width from the start.
        SwingUtilities.invokeLater(() -> {
            if (hBar.isVisible() || hBar.isShowing()) {
                toolbarContent.setBorder(new EmptyBorder(
                    defaultInsets.top, defaultInsets.left,
                    defaultInsets.bottom + hBar.getHeight(), defaultInsets.right));
                hBar.setValue(hBar.getMaximum());
            } else {
                toolbarContent.setBorder(new EmptyBorder(defaultInsets));
            }
        });

        return scrollPane;
    }
}
