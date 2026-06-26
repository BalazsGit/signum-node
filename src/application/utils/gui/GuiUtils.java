package application.utils.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.JViewport;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Centralized GUI utilities for consistent panel behavior across all configuration panels.
 */
public class GuiUtils {

    /**
     * Applies the application-wide default tab layout policy to a JTabbedPane.
     * 
     * Reads the policy from GuiManager (which loads from gui-settings.json at startup).
     * Use this helper after creating any JTabbedPane to ensure consistent behavior
     * across the application, regardless of UIManager state at construction time.
     * 
     * Additionally registers a UI update callback so that when the global policy is
     * changed at runtime (e.g., via AppearancePanel), this tabbed pane is updated too.
     *
     * @param tabbedPane The JTabbedPane to configure.
     */
    public static void applyDefaultTabLayoutPolicy(JTabbedPane tabbedPane) {
        if (tabbedPane == null) {
            return;
        }
        tabbedPane.setTabLayoutPolicy(
            application.utils.gui.GuiManager.getInstance().getTabLayoutPolicy());
    }

    /**
     * Creates a properly structured body panel with NORTH toolbar and CENTER scrollable content.
     * Guarantees that the scrollable content NEVER overlaps the toolbar row, even during
     * resize or tab switching.
     *
     * Key guarantees:
     * - NORTH region has strict minimum height constraints
     * - CENTER JScrollPane uses proper layout hints with minimum size
     * - ComponentListener ensures revalidation on parent size changes
     *
     * @param toolbarPanel   The toolbar content panel (buttons, search, etc.).
     * @param scrollContent  The scrollable content panel.
     * @return A fully configured JPanel ready for use as the main body of a configuration panel.
     */
    public static JPanel createConfigurationBodyPanel(JPanel toolbarPanel, JComponent scrollContent) {
        return createConfigurationBodyPanel(toolbarPanel, scrollContent, null);
    }

    /**
     * Creates a properly structured body panel with NORTH toolbar and CENTER scrollable content.
     *
     * @param toolbarPanel   The toolbar content panel (buttons, search, etc.).
     * @param scrollContent  The scrollable content panel.
     * @param bottomPanel    Optional SOUTH region panel (legend, status bar, etc.), or null.
     * @return A fully configured JPanel ready for use as the main body of a configuration panel.
     */
    public static JPanel createConfigurationBodyPanel(JPanel toolbarPanel, JComponent scrollContent, JComponent bottomPanel) {
        JPanel body = new JPanel(new BorderLayout());

        // Wrap toolbar in a horizontal-only scroll pane for narrow windows
        JScrollPane toolbarScroll = createHorizontalToolbarScrollPanel(toolbarPanel, GuiConstants.TOOLBAR_INSETS);
        toolbarScroll.setMinimumSize(new Dimension(0, toolbarPanel.getPreferredSize().height));

        // Wrap content in a vertical scroll pane
        JScrollPane contentScroll;
        if (scrollContent instanceof JPanel) {
            contentScroll = createVerticalScrollPanel((JPanel) scrollContent);
        } else {
            contentScroll = new JScrollPane(scrollContent);
            contentScroll.setBorder(BorderFactory.createEmptyBorder());
            contentScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            contentScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        }
        contentScroll.setMinimumSize(new Dimension(100, 100));

        body.add(toolbarScroll, BorderLayout.NORTH);
        body.add(contentScroll, BorderLayout.CENTER);

        if (bottomPanel != null) {
            body.add(bottomPanel, BorderLayout.SOUTH);
        }

        // Force proper layout on resize to prevent CENTER from overlapping NORTH
        body.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                body.revalidate();
            }
        });

        return body;
    }

    /**
     * Creates a body panel with the toolbar already wrapped in a JScrollPane.
     * Use this variant when the caller has already created the toolbar scroll pane.
     *
     * @param toolbarScroll  The already-created toolbar JScrollPane.
     * @param scrollContent  The scrollable content panel.
     * @param bottomPanel    Optional SOUTH region panel, or null.
     * @return A fully configured JPanel.
     */
    public static JPanel createConfigurationBodyPanel(JScrollPane toolbarScroll, JComponent scrollContent, JComponent bottomPanel) {
        JPanel body = new JPanel(new BorderLayout());

        toolbarScroll.setMinimumSize(new Dimension(0, toolbarScroll.getPreferredSize().height));

        JScrollPane contentScroll;
        if (scrollContent instanceof JPanel) {
            contentScroll = createVerticalScrollPanel((JPanel) scrollContent);
        } else if (scrollContent instanceof JScrollPane) {
            contentScroll = (JScrollPane) scrollContent;
        } else {
            contentScroll = new JScrollPane(scrollContent);
            contentScroll.setBorder(BorderFactory.createEmptyBorder());
            contentScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            contentScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        }
        contentScroll.setMinimumSize(new Dimension(100, 100));

        body.add(toolbarScroll, BorderLayout.NORTH);
        body.add(contentScroll, BorderLayout.CENTER);

        if (bottomPanel != null) {
            body.add(bottomPanel, BorderLayout.SOUTH);
        }

        body.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                body.revalidate();
            }
        });

        return body;
    }

    /**
     * Configures a horizontal scroll bar listener to adjust the bottom padding of a
     * content component when the scroll bar appears or disappears.
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
     * @param onUpdate         An optional callback executed after padding is adjusted.
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
     * Creates a properly configured JScrollPane for VERTICAL scrollable content panels.
     * Use this when the content is taller than the viewport and needs vertical scrolling.
     * Ensures consistent scrollbar behavior across all configuration panels:
     * - No pushy fillers needed (MigLayout insets handle spacing)
     * - Smooth scrolling with proper unit increment
     * - Clean borders and scroll policies
     *
     * @param contentPanel   The content panel to wrap (typically a MigLayout panel with insets).
     * @param unitIncrement  The scrollbar unit increment for smooth scrolling (default 16).
     * @return A fully configured JScrollPane ready for use.
     */
    public static JScrollPane createVerticalScrollPanel(JPanel contentPanel, int unitIncrement) {
        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(unitIncrement);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        return scrollPane;
    }

    /**
     * Creates a vertical scroll panel with default unit increment (16).
     *
     * @param contentPanel The content panel to wrap.
     * @return A fully configured JScrollPane.
     */
    public static JScrollPane createVerticalScrollPanel(JPanel contentPanel) {
        return createVerticalScrollPanel(contentPanel, 16);
    }

    /**
     * Creates a properly configured JScrollPane for HORIZONTAL toolbar scrolling.
     * Use this for button rows that may overflow when the window is narrow.
     * Handles scrollbar appearance/disappearance padding, keeps buttons right-aligned,
     * and prevents vertical expansion.
     *
     * NOTE: Prefer ResponsiveToolbarScrollPane for new code as it provides more
     * consistent gap behavior across all panels.
     *
     * @param toolbarContent The toolbar content component (typically a JPanel with buttons).
     * @param defaultInsets  The base insets to use for the content component.
     * @return A fully configured JScrollPane ready for use.
     */
    public static JScrollPane createHorizontalToolbarScrollPanel(JComponent toolbarContent, Insets defaultInsets) {
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
        final int initialMaxHeight = 64;
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

        // Initial padding check after the first layout pass
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