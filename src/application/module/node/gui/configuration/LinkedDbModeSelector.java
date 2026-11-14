package application.module.node.gui.configuration;

import application.utils.gui.GuiFontManager;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * The database-URL source selector — the two stacked radio buttons that
 * replace the former "Linked Database Profile" check box everywhere the
 * database source is chosen (the node install wizard's database-configuration
 * step and the node configuration's DB.Url row). The options:
 * </p>
 * <ul>
 *   <li><b>{@link #TEXT_LINKED}</b> (preselected) — the node reuses an
 *       <i>existing database profile</i>: the connection URL / settings are
 *       taken from the selected profile.</li>
 *   <li><b>{@link #TEXT_CUSTOM}</b> — the node keeps its own <i>dedicated</i>
 *       database URL (SQLite) or its own connection settings (server engines).</li>
 * </ul>
 * <p>
 * A radio pair always has exactly one selection, so the two options are an
 * explicit either/or (never "neither" as the former check box's unchecked
 * state was). Selecting a radio fires its action event like a user click —
 * hosts guard programmatic selections with their own
 * {@code isProgrammaticChange}/{@code programmaticLinkChange} flags.
 * </p>
 */
public class LinkedDbModeSelector extends JPanel {

    /** The first radio's text: an existing database profile supplies the connection. */
    public static final String TEXT_LINKED = "Linked Database Profile";

    /** The second radio's text: the node keeps its own dedicated database URL / settings. */
    public static final String TEXT_CUSTOM = "Custom db.URL";

    private final JRadioButton linkedRadio = new JRadioButton(TEXT_LINKED, true);
    private final JRadioButton customRadio = new JRadioButton(TEXT_CUSTOM);

    public LinkedDbModeSelector() {
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        linkedRadio.setOpaque(false);
        customRadio.setOpaque(false);
        GuiFontManager.applyDefaultFont(linkedRadio);
        GuiFontManager.applyDefaultFont(customRadio);
        linkedRadio.setAlignmentX(0f);
        customRadio.setAlignmentX(0f);
        add(linkedRadio);
        add(customRadio);

        ButtonGroup group = new ButtonGroup();
        group.add(linkedRadio);
        group.add(customRadio);
    }

    /** Whether the "Linked Database Profile" radio is selected. */
    public boolean isLinkedMode() {
        return linkedRadio.isSelected();
    }

    /**
     * Selects the radio matching the given link mode (fires the newly selected
     * radio's action event — like a user click; hosts guard programmatic
     * changes). A no-op when the mode already matches.
     */
    public void setLinkedMode(boolean value) {
        if (isLinkedMode() == value) {
            return;
        }
        (value ? linkedRadio : customRadio).setSelected(true);
    }

    /** The "Linked Database Profile" radio (hosts attach their action listeners to it). */
    public JRadioButton getLinkedRadio() {
        return linkedRadio;
    }

    /** The "Custom db.URL" radio (hosts attach their action listeners to it). */
    public JRadioButton getCustomRadio() {
        return customRadio;
    }
}