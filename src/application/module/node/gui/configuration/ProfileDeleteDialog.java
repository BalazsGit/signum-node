package application.module.node.gui.configuration;

import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Modal "Delete Profile" dialog of the profile-actions toolbar.
 * <p>
 * Shown by the Delete toolbar action. States the node's current state: a running node
 * is stopped before the deletion — unlike the state-preserving rename, it is <b>not</b>
 * restarted afterwards (the profile is gone). Offers an optional "also delete the
 * profile's SQLite database data" checkbox, shown only when the profile actually uses
 * its per-profile SQLite database.
 * </p>
 */
public final class ProfileDeleteDialog {

    private ProfileDeleteDialog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The user's confirmed choice: whether the database data is deleted as well. */
    public record Confirmation(boolean deleteData) {
    }

    /**
     * Shows the modal delete-confirmation dialog.
     *
     * @param parent           the parent component (dialogs are owned by its window)
     * @param profileName      the profile name to be deleted
     * @param nodeRunning      whether the profile's node is running (drives the state text)
     * @param sqliteConfigured whether the profile uses its per-profile SQLite database
     *                         (drives the visibility of the data-deletion checkbox)
     * @return the confirmed choice, or {@code null} when cancelled
     */
    public static Confirmation show(Component parent, String profileName,
            boolean nodeRunning, boolean sqliteConfigured) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final boolean[] confirmed = { false };
        final boolean[] confirmedDeleteData = { false };

        // ── Node-state text ──
        final String stateText;
        if (nodeRunning) {
            stateText = "<html>The node is <b>running</b> — it will be <b>stopped</b> before the profile "
                    + "is deleted and <b>will not be started again</b>.</html>";
        } else {
            stateText = "<html>The node is <b>not running</b>.</html>";
        }

        // ── Permanent-action warning (profile names are validated to [a-zA-Z0-9_-] — HTML-safe) ──
        JLabel warningLabel = new JLabel(
                "<html><b>This action is permanent.</b> The profile '<b>" + profileName + "</b>' will be "
                        + (sqliteConfigured
                                ? "deleted — optionally together with its database data — "
                                : "deleted — ")
                        + "and its tab closed.</html>");
        warningLabel.setForeground(GuiColors.getContrastRed());

        // ── Profile name (read-only) ──
        JTextField nameField = new JTextField(profileName == null ? "" : profileName);
        nameField.setEditable(false);
        nameField.setFocusable(false);
        nameField.setBackground(new JTextField().getBackground());

        // ── Optional data deletion (only for per-profile SQLite databases) ──
        final JCheckBox deleteDataCheck = new JCheckBox(
                "Also delete the profile's SQLite database data");

        JButton deleteBtn = new JButton("Delete",
                IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(),
                        GuiColors.getContrastRed()));
        deleteBtn.setForeground(GuiColors.getContrastRed());
        JButton cancelBtn = new JButton("Cancel");

        JPanel content = new JPanel(new MigLayout("insets 15, wrap 1, fillx", "[grow]", "[]"));
        content.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.TRASH, 32, GuiColors.getContrastRed())));
        content.add(new JLabel("<html><h2>Delete Profile</h2></html>"));
        content.add(new JLabel(stateText), "growx");
        content.add(warningLabel, "growx gaptop 10");
        content.add(new JLabel("Profile:"), "gaptop 10");
        content.add(nameField, "growx");
        if (sqliteConfigured) {
            content.add(deleteDataCheck, "gaptop 10");
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(deleteBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Delete Profile",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(deleteBtn);
        // Enter confirms (default button); Escape cancels (codebase pattern).
        ((JComponent) dialog.getContentPane()).registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        deleteBtn.addActionListener(e -> {
            confirmed[0] = true;
            confirmedDeleteData[0] = deleteDataCheck.isSelected();
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        // Synchronous modal show (called on the EDT from a button listener);
        // the event pump keeps the dialog responsive while it blocks.
        dialog.setVisible(true);
        return confirmed[0] ? new Confirmation(confirmedDeleteData[0]) : null;
    }
}
