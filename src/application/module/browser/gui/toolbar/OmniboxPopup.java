package application.module.browser.gui.toolbar;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Arc2D;
import java.awt.geom.GeneralPath;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JWindow;
import javax.swing.ListCellRenderer;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * The omnibox autocomplete dropdown (F2, N2 + A5): a non-modal, non-focusable
 * {@link JWindow} with a suggestion list, fading in below the omnibox. The
 * omnibox keeps the keyboard focus — arrow/Enter/Esc are handled by the
 * omnibox, this component only renders and reports the selection.
 * <p>
 * F2 ships the search suggestion (and the normalized-URL entry); F3/F4 plug
 * history and bookmarks into the same list — no new mechanism.
 */
public final class OmniboxPopup extends JWindow {

    /** One autocomplete row (N2). */
    public static final class Suggestion {

        public enum Kind {
            /** A direct navigation target. */
            URL,
            /** A search-engine query. */
            SEARCH,
            /** A saved bookmark (F4, N2). */
            BOOKMARK
        }

        private final Kind kind;
        private final String label;
        private final String target;

        public Suggestion(Kind kind, String label, String target) {
            this.kind = kind;
            this.label = label;
            this.target = target;
        }

        public Kind getKind() {
            return kind;
        }

        public String getLabel() {
            return label;
        }

        public String getTarget() {
            return target;
        }
    }

    private static final int FADE_STEP_MS = 12;
    private static final int ROW_HEIGHT = 26;
    private static final int MAX_ROWS = 8;

    private final JList<Suggestion> list = new JList<>();
    private final Timer slideTimer;
    private int slideOffset;
    private Point targetLocation;

    public OmniboxPopup(Window owner) {
        super(owner);
        // JWindow is undecorated by default (Window.setUndecorated was
        // removed in JDK 25). JWindow is not a JComponent, so the border
        // goes on the content pane.
        setFocusableWindowState(false);
        setBackground(UIManager.getColor("Popup.foreground"));
        JComponent content = (JComponent) getContentPane();
        content.setOpaque(false);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor(), 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));

        list.setFocusable(false);
        list.setOpaque(false);
        list.setFixedCellHeight(ROW_HEIGHT);
        list.setCellRenderer(new SuggestionRenderer());
        list.setSelectionBackground(accent());
        add(list);

        // A5: the popup slides in 4 px from above while fading in.
        slideTimer = new Timer(FADE_STEP_MS, e -> {
            slideOffset = Math.min(0, slideOffset + 2);
            setLocation(targetLocation.x, targetLocation.y + slideOffset);
            repaint();
            if (slideOffset == 0) {
                ((Timer) e.getSource()).stop();
            }
        });
        slideTimer.setCoalesce(true);
    }

    /**
     * Shows (or updates) the popup below the omnibox.
     *
     * @param locationOnScreen the top-left screen point (below the field)
     * @param width            the popup width (the omnibox width)
     * @param items            the suggestions (must be non-empty)
     */
    public void showAt(Point locationOnScreen, int width, List<Suggestion> items) {
        if (items == null || items.isEmpty()) {
            hidePopup();
            return;
        }
        int rows = Math.min(items.size(), MAX_ROWS);
        List<Suggestion> view = items.subList(0, rows);
        DefaultListModel<Suggestion> model = new DefaultListModel<>();
        view.forEach(model::addElement);
        list.setModel(model);
        list.setSelectedIndex(0);
        setSize(width, rows * ROW_HEIGHT + 8);
        targetLocation = locationOnScreen;
        slideOffset = -4;
        setLocation(locationOnScreen.x, locationOnScreen.y + slideOffset);
        if (!slideTimer.isRunning()) {
            slideTimer.start();
        }
        setVisible(true);
    }

    public void hidePopup() {
        slideTimer.stop();
        setVisible(false);
    }

    public boolean isPopupVisible() {
        return isVisible();
    }

    public int getSelectedIndex() {
        return list.getSelectedIndex();
    }

    public void setSelectedIndex(int index) {
        if (index >= 0) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
    }

    /** @return the suggestion at the given index, or null. */
    public Suggestion suggestionAt(int index) {
        return (index >= 0 && index < list.getModel().getSize())
                ? list.getModel().getElementAt(index)
                : null;
    }

    /** @return the number of visible suggestions. */
    public int selectedCount() {
        return list.getModel().getSize();
    }

    private static Color accent() {
        Color c = UIManager.getColor("Component.focusColor");
        return c != null ? c : new Color(0x4F, 0x8C, 0xFF);
    }

    private static Color borderColor() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(0x2A, 0x30, 0x38);
    }

    private static final class SuggestionRenderer extends JComponent
            implements ListCellRenderer<Suggestion> {

        private Suggestion current;

        @Override
        public Component getListCellRendererComponent(JList<? extends Suggestion> l,
                                                      Suggestion value, int index,
                                                      boolean selected, boolean focus) {
            this.current = value;
            setFont(l.getFont());
            setForeground(selected ? accent()
                    : UIManager.getColor("Label.foreground"));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (current == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getForeground());
            int cx = 12;
            int cy = getHeight() / 2;
            if (current.getKind() == Suggestion.Kind.SEARCH) {
                g2.drawOval(cx - 6, cy - 6, 9, 9);
                g2.drawLine(cx + 2, cy + 2, cx + 6, cy + 6);
            } else if (current.getKind() == Suggestion.Kind.BOOKMARK) {
                g2.fill(drawStar(cx, cy, 7)); // a filled star = a saved bookmark
            } else {
                g2.drawOval(cx - 6, cy - 6, 12, 12);
                g2.drawLine(cx, cy - 6, cx, cy + 6);
                GeneralPath equator = new GeneralPath();
                equator.append(new Arc2D.Double(cx - 6, cy - 2.5, 12, 5, 0, 180, Arc2D.OPEN),
                        false);
                g2.draw(equator);
            }
            g2.setFont(getFont());
            g2.drawString(current.getLabel(), cx + 14,
                    cy + g2.getFontMetrics().getAscent() / 2 - 2);
            g2.dispose();
        }

        /** A 5-point star centered at (cx, cy) with the given outer radius. */
        private static GeneralPath drawStar(double cx, double cy, double radius) {
            GeneralPath star = new GeneralPath();
            for (int i = 0; i < 10; i++) {
                double r = i % 2 == 0 ? radius : radius * 0.45;
                // start at the top (-90°), alternate outer/inner every 36°
                double angle = Math.toRadians(-90 + i * 36);
                double x = cx + r * Math.cos(angle);
                double y = cy + r * Math.sin(angle);
                if (i == 0) {
                    star.moveTo(x, y);
                } else {
                    star.lineTo(x, y);
                }
            }
            star.closePath();
            return star;
        }
    }
}
