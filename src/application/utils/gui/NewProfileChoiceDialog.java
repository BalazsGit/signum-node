package application.utils.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Window;

/**
 * The shared modal <b>New Profile</b> choice dialog: a single "New Profile"
 * entry point offering the creation paths as selectable options plus the
 * accept / cancel buttons for the selected one.
 *
 * <p>The dialog shows a large New Profile icon in the corner (the same glyph
 * the profile toolbars use, at double the toolbar icon size) and the offered
 * options as radio entries — <b>Launch Setup Wizard</b> (the guided node
 * setup flow, offered by hosts that own one, e.g. the node profile window and
 * the node panel's "+" button) and <b>New Empty Profile</b> (a profile
 * pre-filled with the application default values). Confirming with
 * {@code OK} returns the selected {@link Choice}; {@code Cancel} (or closing
 * the window) returns {@code null}.</p>
 */
public final class NewProfileChoiceDialog {

    /** The creation path selected in the dialog. */
    public enum Choice {
        /** The guided setup wizard (step by step: database, connection, node settings). */
        WIZARD,
        /** A new empty profile pre-filled with the application default values (zero overrides). */
        EMPTY
    }

    /** The dialog's window title (tests locate it by this exact text). */
    public static final String TITLE = "New Profile";

    private NewProfileChoiceDialog() {
        // static utility
    }

    /**
     * Shows the modal New Profile choice dialog owned by the given parent.
     *
     * @param parent       the parent component (its owner window is used for modality;
     *                     may be {@code null} for a root-level dialog)
     * @param offerWizard  {@code true} to offer the <b>Launch Setup Wizard</b> option
     *                     (hosts that own a setup wizard); {@code false} to offer the
     *                     <b>New Empty Profile</b> option only
     * @return the selected {@link Choice} when the user accepted, or {@code null}
     *         when the user cancelled (or closed the window)
     */
    public static Choice show(Component parent, boolean offerWizard) {
        // Defensive icon-font registration (the app registers it at startup;
        // this covers standalone/test construction of the parent panel).
        IconFontSwing.register(FontAwesome.getIconFont());

        final Choice[] result = { null };
        JDialog dialog = new JDialog(ownerOf(parent), TITLE, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setResizable(false);
        dialog.getContentPane().setLayout(new BoxLayout(dialog.getContentPane(), BoxLayout.Y_AXIS));
        dialog.getContentPane().add(buildContent(offerWizard, result));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        dialog.dispose();
        return result[0];
    }

    /**
     * Builds the dialog body: the large New Profile icon in the corner, the
     * bold title, the option description, the offered options (radio entries
     * with a one-line description under each label) and the OK / Cancel row.
     * OK records the selected {@link Choice} into {@code result[0]} and
     * closes the dialog; Cancel (or the window close) leaves it null.
     */
    static Component buildContent(boolean offerWizard, Choice[] result) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));

        // Header: the large New Profile glyph (double the toolbar icon size)
        // in the corner next to the bold title.
        Icon icon = IconFontSwing.buildIcon(FontAwesome.FILE_O, GuiConstants.getToolBarIconSize() * 2f,
                GuiColors.getButtonIcon());
        JLabel iconLabel = new JLabel(icon);
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));

        JLabel title = new JLabel(TITLE);
        GuiFontManager.applyDefaultFont(title);
        title.setFont(GuiFontManager.getBoldScaledDefaultFont(1.4f));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        header.setOpaque(false);
        header.add(iconLabel);
        header.add(title);
        content.add(header);
        content.add(Box.createVerticalStrut(12));

        JLabel description = new JLabel("How would you like to create the new profile?");
        GuiFontManager.applyDefaultFont(description);
        content.add(description);
        content.add(Box.createVerticalStrut(12));

        final Choice[] selectedChoice = { null };
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        JRadioButton first = null;
        if (offerWizard) {
            first = addOption(content, group, Choice.WIZARD, selectedChoice,
                    "Launch Setup Wizard",
                    "Guided step-by-step setup: choose the database, the connection and the node settings.");
        }
        JRadioButton empty = addOption(content, group, Choice.EMPTY, selectedChoice,
                "New Empty Profile",
                "Create a profile pre-filled with the application default values (zero overrides).");
        (first != null ? first : empty).setSelected(true);
        selectedChoice[0] = (Choice) (first != null ? first : empty).getClientProperty("choice");

        content.add(Box.createVerticalStrut(8));

        // The accept / cancel row for the selected option.
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        javax.swing.JButton ok = new javax.swing.JButton("OK");
        javax.swing.JButton cancel = new javax.swing.JButton("Cancel");
        ok.addActionListener(e -> {
            result[0] = selectedChoice[0];
            ((Window) SwingUtilities.windowForComponent(content)).setVisible(false);
        });
        cancel.addActionListener(e -> {
            result[0] = null;
            ((Window) SwingUtilities.windowForComponent(content)).setVisible(false);
        });
        buttons.add(ok);
        buttons.add(cancel);
        content.add(buttons);
        return content;
    }

    private static JRadioButton addOption(JPanel content, javax.swing.ButtonGroup group, Choice choice,
            Choice[] selectedHolder, String label, String detail) {
        JRadioButton radio = new JRadioButton(label);
        radio.putClientProperty("choice", choice);
        GuiFontManager.applyDefaultFont(radio);
        radio.addActionListener(e -> {
            if (radio.isSelected()) {
                selectedHolder[0] = choice;
            }
        });
        group.add(radio);
        content.add(radio);

        JLabel detailLabel = new JLabel(detail);
        GuiFontManager.applyDefaultFont(detailLabel);
        Font font = detailLabel.getFont();
        detailLabel.setFont(font.deriveFont(Font.PLAIN, font.getSize() - 1f));
        detailLabel.setForeground(GuiColors.getSeparator());
        detailLabel.setBorder(BorderFactory.createEmptyBorder(0, 26, 6, 0));
        content.add(detailLabel);
        return radio;
    }

    /** The modal owner window for the parent component (walking up to the frame). */
    private static Window ownerOf(Component parent) {
        if (parent == null) {
            return null;
        }
        Window owner = SwingUtilities.windowForComponent(parent);
        while (owner instanceof JDialog) {
            Window next = ((JDialog) owner).getOwner();
            if (next == null) {
                break;
            }
            owner = next;
        }
        return owner;
    }
}