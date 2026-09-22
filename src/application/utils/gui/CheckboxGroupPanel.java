package application.utils.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.Consumer;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

/**
 * A compact titled box holding a group of visibility checkboxes, all
 * selected by default. Unchecking a box hides whatever the box stands for;
 * while all boxes are selected there is nothing to filter
 * ({@link #isAllSelected()}), so the group acts as a pure visibility filter.
 * <p>
 * <h3>UI Layout</h3>
 * <pre>
 * ┌ Level ──────────────────────────────────────────┐
 * │ Show:  ☑TRACE ☑DEBUG ☑INFO ☑WARN ☑ERROR        │
 * └─────────────────────────────────────────────────┘
 * </pre>
 * </p>
 * <p>
 * Every checkbox added via {@link #addCheckbox} is automatically wired to
 * the shared {@link #setChangeListener(Consumer) change listener}; extra
 * leading/trailing components (labels, buttons) are added with the inherited
 * {@link JPanel#add(Component)} and are not part of the group.
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * All mutations must occur on the Swing EDT.
 * </p>
 *
 * @see application.module.node.gui.ConsoleFilterHeader
 * @see application.module.node.gui.configuration.NodeConfigurationPanel
 */
public final class CheckboxGroupPanel extends JPanel {

    /** Fired (on the EDT) whenever any checkbox of the group changes */
    private Consumer<AbstractButton> changeListener;

    /**
     * Creates an empty titled checkbox group.
     *
     * @param title the box title (e.g. "Level", "Show values")
     */
    public CheckboxGroupPanel(String title) {
        setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        setBorder(new TitledBorder(title != null ? title : ""));
    }

    /**
     * Registers the listener fired by every checkbox added through
     * {@link #addCheckbox}. May be null to clear.
     */
    public void setChangeListener(Consumer<AbstractButton> listener) {
        this.changeListener = listener;
    }

    /**
     * Adds a checkbox selected by default (plain foreground).
     *
     * @param text the checkbox label
     * @return the created checkbox
     */
    public JCheckBox addCheckbox(String text) {
        return addCheckbox(text, null, true);
    }

    /**
     * Adds a checkbox to the group.
     *
     * @param text     the checkbox label
     * @param color    optional foreground color (null = default)
     * @param selected initial selection state
     * @return the created checkbox
     */
    public JCheckBox addCheckbox(String text, Color color, boolean selected) {
        JCheckBox box = new JCheckBox(text);
        if (color != null) {
            box.setForeground(color);
        }
        box.setSelected(selected);
        box.addActionListener(e -> {
            if (changeListener != null) {
                changeListener.accept(box);
            }
        });
        add(box);
        return box;
    }

    /**
     * @return true while every checkbox of the group is selected — the state
     *         where the group filters nothing (empty groups are all-selected)
     */
    public boolean isAllSelected() {
        for (Component component : getComponents()) {
            if (component instanceof JCheckBox box && !box.isSelected()) {
                return false;
            }
        }
        return true;
    }
}
