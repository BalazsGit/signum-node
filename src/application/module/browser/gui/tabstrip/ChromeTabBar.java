package application.module.browser.gui.tabstrip;

import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.module.browser.gui.animation.TabAnimation;
import application.utils.i18n.I18n;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ListDataListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.DefaultListModel;

/**
 * Chrome-style tab strip (T1, T2, T3, T4, T6): one horizontal row of
 * elastic-width tabs, overflow scroll arrows, a "+" button, middle-click
 * close, drag-reorder and hover effects.
 * <p>
 * Pure view: all tab state comes from the {@link TabController} (the bar
 * never mutates tabs except by calling the controller's intent methods).
 * Must be constructed on the EDT.
 */
public final class ChromeTabBar extends JPanel {

    private static final int MIN_TAB_WIDTH = 100;
    private static final int MAX_TAB_WIDTH = 240;
    private static final int SCROLL_STEP_TABS = 4;

    private final TabController controller;
    private final TabAnimation animation = new TabAnimation();
    private final DefaultListModel<BrowserTab> model = new DefaultListModel<>();
    private final JList<BrowserTab> list;
    private final ChromeTabRenderer renderer;
    private final JScrollPane scroll;
    private final TabDragController drag = new TabDragController();
    private final JButton addButton;
    private final JButton scrollLeft;
    private final JButton scrollRight;
    private final Timer repaintTimer;
    private int cellWidth = 200;

    public ChromeTabBar(TabController controller) {
        super(new BorderLayout());
        this.controller = controller;

        this.renderer = new ChromeTabRenderer(animation);
        this.list = new JList<>(model);
        list.setCellRenderer(renderer);
        list.setFocusable(false);
        list.setVisibleRowCount(1); // horizontal layout is the JList default
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellWidth(cellWidth);
        list.setFixedCellHeight(ChromeTabRenderer.HEIGHT);
        list.setTransferHandler(null); // in-list drag only (T3), never OS DnD

        this.scroll = new JScrollPane(list,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        // Note: JViewport.setBorder() is not supported (JDK 25) — no viewport border.

        this.addButton = flatTabButton("+", I18n.get("browser.tab.new.tooltip"));
        addButton.setPreferredSize(new Dimension(32, ChromeTabRenderer.HEIGHT));
        addButton.addActionListener(e -> controller.openNewTab());

        this.scrollLeft = flatArrow("\u25C0", I18n.get("browser.tab.scroll.left"));
        this.scrollRight = flatArrow("\u25B6", I18n.get("browser.tab.scroll.right"));
        scrollLeft.setVisible(false);
        scrollRight.setVisible(false);
        scrollLeft.addActionListener(e -> scrollByTabPages(-1));
        scrollRight.addActionListener(e -> scrollByTabPages(+1));

        JPanel east = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        east.setOpaque(false);
        east.add(scrollRight);
        east.add(addButton);
        add(scrollLeft, BorderLayout.WEST);
        add(scroll, BorderLayout.CENTER);
        add(east, BorderLayout.EAST);
        setOpaque(false);
        setPreferredSize(new Dimension(100, ChromeTabRenderer.HEIGHT));
        setMinimumSize(new Dimension(60, ChromeTabRenderer.HEIGHT));

        installMouseHandling();
        scroll.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                recomputeCellWidth();
            }
        });

        // Repaint pump for the spinner + A1/T3 cell effects (cheap no-op when idle).
        this.repaintTimer = application.module.browser.engine.handler.BrowserCefHandlers
                .createRepaintTimer(this::paintIfNeeded);
        repaintTimer.start();
    }

    /**
     * Resynchronizes the view with the controller. Called on the EDT after
     * every tab event (and after engine-ready).
     */
    public void refresh() {
        List<BrowserTab> tabs = controller.getTabs();
        if (!sameIds(tabs)) {
            model.clear();
            tabs.forEach(model::addElement);
        }
        int active = controller.getActiveIndex();
        if (active >= 0) {
            list.setSelectedIndex(Math.min(active, model.size() - 1));
            if (list.getSelectedIndex() >= 0) {
                list.ensureIndexIsVisible(list.getSelectedIndex());
            }
        }
        recomputeCellWidth();
        updateArrows();
        repaint();
    }

    /** Starts the A1 birth animation for a just-added tab. */
    public void animateBirth(String tabId) {
        animation.noteBirth(tabId);
    }

    /** Starts the T3 drop highlight for the moved tab. */
    public void animateMove(String tabId) {
        animation.noteMove(tabId);
    }

    /** @return the shared animation (the panel uses it to decide repaints). */
    public TabAnimation getAnimation() {
        return animation;
    }

    // ------------------------------------------------------------------
    // Mouse (T1 open, T2 close, T3 drag, T4 click-activate)
    // ------------------------------------------------------------------

    private void installMouseHandling() {
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    close(index); // T2
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (overCloseZone(e, index)) {
                        close(index); // T2 (the cell's x button)
                        return;
                    }
                    drag.press(index, e.getX(), e.getY()); // T3 candidate
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (drag.isDragging()) {
                    commitDrag();
                    return;
                }
                drag.cancel();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index >= 0 && !overCloseZone(e, index)) {
                        BrowserTab tab = model.get(index);
                        controller.activateById(tab.getId()); // T4
                    }
                }
            }
        });
        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                int count = model.size();
                if (count == 0) {
                    return;
                }
                int target = clamp(e.getX() / Math.max(1, cellWidth), 0, count - 1);
                if (drag.movedTo(e.getX(), e.getY(), target)) {
                    renderer.setDragState(drag.sourceIndex(), drag.targetIndex());
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                boolean overClose = index >= 0 && overCloseZone(e, index);
                renderer.setHover(index >= 0, overClose);
                if (index >= 0) {
                    repaint(list.getCellBounds(index, index));
                } else {
                    repaint();
                }
            }
        });
    }

    private void commitDrag() {
        int from = drag.sourceIndex();
        int to = drag.targetIndex();
        drag.cancel();
        renderer.setDragState(-1, -1);
        if (to >= 0 && to != from && controller.moveTab(from, to)) {
            // The moved tab now sits at `to` (pre-removal target).
            controller.getTabAt(to).ifPresent(t -> animation.noteMove(t.getId()));
        }
        repaint();
    }

    private void close(int index) {
        BrowserTab tab = model.get(index);
        controller.closeTab(tab.getId());
    }

    private boolean overCloseZone(MouseEvent e, int index) {
        Rectangle cell = list.getCellBounds(index, index);
        if (cell == null) {
            return false;
        }
        return e.getX() - cell.x >= cellWidth - ChromeTabRenderer.CLOSE_ZONE;
    }

    // ------------------------------------------------------------------
    // Layout (T6: elastic tab width + overflow arrows)
    // ------------------------------------------------------------------

    private void recomputeCellWidth() {
        int count = model.size();
        if (count == 0) {
            return;
        }
        int available = Math.max(0, scroll.getWidth() - 80); // arrows + button reserve
        int width = clamp(available / Math.max(count, 3), MIN_TAB_WIDTH, MAX_TAB_WIDTH);
        if (width != cellWidth) {
            cellWidth = width;
            list.setFixedCellWidth(cellWidth);
            list.setFixedCellHeight(ChromeTabRenderer.HEIGHT);
            updateArrows();
        }
    }

    private void updateArrows() {
        int count = model.size();
        int first = list.getFirstVisibleIndex();
        int last = list.getLastVisibleIndex();
        scrollLeft.setVisible(first > 0);
        scrollRight.setVisible(last < count - 1);
    }

    private void scrollByTabPages(int direction) {
        JScrollBar bar = scroll.getHorizontalScrollBar();
        if (bar == null) {
            return;
        }
        int page = Math.max(1, SCROLL_STEP_TABS * cellWidth);
        bar.setValue(bar.getValue() + direction * page);
        updateArrows();
    }

    private void paintIfNeeded() {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).isLoading()) {
                list.repaint();
                return;
            }
        }
        if (animation.isAnimating() || drag.isDragging()) {
            list.repaint();
            return;
        }
        repaintTimer.stop();
    }

    private boolean sameIds(List<BrowserTab> tabs) {
        if (tabs.size() != model.size()) {
            return false;
        }
        for (int i = 0; i < tabs.size(); i++) {
            if (!tabs.get(i).getId().equals(model.get(i).getId())) {
                return false;
            }
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Flat buttons
    // ------------------------------------------------------------------

    private static JButton flatTabButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setUI(new BasicButtonUI());
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        button.setToolTipText(tooltip);
        return button;
    }

    private static JButton flatArrow(String text, String tooltip) {
        JButton button = flatTabButton(text, tooltip);
        button.setPreferredSize(new Dimension(18, ChromeTabRenderer.HEIGHT));
        return button;
    }
}
