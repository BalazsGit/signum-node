package application.utils.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;

/**
 * A single-row, left-aligned flow layout for a strip of titled boxes (the
 * configuration panels' search rows: the "Show values" state filter, the live
 * search box and any reserved host strip).
 * <p>
 * Every visible child keeps its preferred <b>width</b>, all children are
 * stretched to the SAME (tallest) height, the children are separated by one
 * uniform gap, and the strip starts at the container's left inset — so the row's
 * own x coordinate is 0 when the container carries no left margin of its own.
 * The strip never wraps and never stretches beyond its preferred width (the
 * trailing space is left empty), so a {@link ResponsiveToolbarScrollPane}
 * wrapping it shows a horizontal scrollbar instead of a second row when the
 * window narrows.
 * </p>
 * <p>
 * This is the shared form of the module logging profile panel's private
 * {@code LeftFlow}; both panels build their search rows from it so the toolbar /
 * search row geometry stays identical across every profile / configuration tab.
 * </p>
 * <p><h3>Thread Safety</h3>
 * All mutations must occur on the Swing EDT.</p>
 */
public final class LeftFlowLayout implements LayoutManager2 {

    private final int gap;

    /**
     * Creates the layout.
     *
     * @param gap the uniform horizontal gap between the row's boxes (px)
     */
    public LeftFlowLayout(int gap) {
        this.gap = gap;
    }

    @Override
    public void addLayoutComponent(Component comp, Object constraints) {
        // no per-component state
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
        // no per-component state
    }

    @Override
    public void removeLayoutComponent(Component comp) {
        // no per-component state
    }

    /** The tallest preferred height among the visible children (0 when there are none). */
    private int stripHeight(Container parent) {
        int h = 0;
        for (Component child : parent.getComponents()) {
            if (child.isVisible()) {
                h = Math.max(h, child.getPreferredSize().height);
            }
        }
        return h;
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets ins = parent.getInsets();
        // The row's content height is the strip's OWN preferred height (the
        // tallest box). The boxes are height-unified
        // (ConfigurationUtils#unifyTitledBoxHeights), so stretching them to a
        // TALLER parent would distort them; the strip is vertically centered in
        // any extra space instead.
        int h = Math.max(0, stripHeight(parent));
        int y = ins.top + Math.max(0, (parent.getHeight() - ins.top - ins.bottom - h) / 2);
        int x = ins.left; // the strip starts at the container's left inset
        boolean first = true;
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (!first) {
                x += gap;
            }
            Dimension pref = child.getPreferredSize();
            child.setBounds(x, y, pref.width, h);
            x += pref.width;
            first = false;
        }
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        int w = 0;
        int h = 0;
        boolean first = true;
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (!first) {
                w += gap;
            }
            Dimension pref = child.getPreferredSize();
            w += pref.width;
            h = Math.max(h, pref.height);
            first = false;
        }
        Insets ins = parent.getInsets();
        return new Dimension(w + ins.left + ins.right, h + ins.top + ins.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return preferredLayoutSize(parent);
    }

    @Override
    public Dimension maximumLayoutSize(Container target) {
        // The strip never stretches beyond its preferred width — the row stays
        // left-aligned and the trailing space is left empty.
        return preferredLayoutSize(target);
    }

    @Override
    public float getLayoutAlignmentX(Container target) {
        return 0f;
    }

    @Override
    public float getLayoutAlignmentY(Container target) {
        return 0.5f;
    }

    @Override
    public void invalidateLayout(Container target) {
        // no cached state to invalidate
    }
}
