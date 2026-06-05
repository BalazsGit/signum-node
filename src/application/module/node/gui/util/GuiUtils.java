package application.module.node.gui.util;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
}