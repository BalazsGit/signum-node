package application.utils.gui;

import javax.swing.*;
import javax.swing.BorderFactory;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * A specialized JScrollPane for toolbars or single-row containers that
 * automatically adjusts its preferred height to include the horizontal
 * scrollbar
 * when it becomes visible, preventing it from overlapping the content.
 * It also manages padding to ensure the scrollbar doesn't add unnecessary gaps.
 */
public class ResponsiveToolbarScrollPane extends JScrollPane {
    private final JPanel contentWrapper;
    private final int bottomMargin;

    public ResponsiveToolbarScrollPane(Component view, Insets contentInsets) {
        super();
        this.bottomMargin = contentInsets.bottom;

        setBorder(BorderFactory.createEmptyBorder());
        setViewportBorder(null);

        this.contentWrapper = new JPanel(new BorderLayout());
        this.contentWrapper.setOpaque(false);
        this.contentWrapper.setBorder(BorderFactory.createEmptyBorder(contentInsets.top, contentInsets.left, 0,
                contentInsets.right));
        this.contentWrapper.add(view, BorderLayout.CENTER);
        setViewportView(contentWrapper);

        getHorizontalScrollBar().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                revalidateParent();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                revalidateParent();
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
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension pref = super.getPreferredSize();
        if (getHorizontalScrollBar() != null && getHorizontalScrollBar().isVisible()) {
            pref.height += getHorizontalScrollBar().getPreferredSize().height;
        } else {
            pref.height += bottomMargin;
        }
        return pref;
    }
}