package application.module.node.gui.configuration;

import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
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
                    + "is deleted and <b>will not be started again</b>. "
                    + "Stopping the node may take a while; please wait.</html>";
        } else {
            stateText = "<html>The node is <b>not running</b>.</html>";
        }

        // ── Permanent-action warning — short and to the point ──
        JLabel warningLabel = new JLabel(
                "<html><b>This action is permanent.</b> The node profile will be deleted.</html>");
        warningLabel.setForeground(GuiColors.getContrastRed());

        // ── Profile name (display only — the name is not editable, so a plain label, not a text field) ──
        JLabel nameField = new JLabel(profileName == null ? "" : profileName);

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
        // Plain bold title (app font + bold) instead of an <h2> HTML heading:
        // HTML element tags render at relative sizes (h2 = 1.5x, small = 0.83x)
        // which made the dialog text look smaller than the rest of the app.
        JLabel titleLabel = new JLabel("Delete Profile");
        titleLabel.setFont(GuiFontManager.getBoldDefaultFont());
        content.add(titleLabel);
        content.add(new JLabel(stateText), "growx");
        // MigLayout 11.4.2 requires comma-separated cell constraints (a space
        // like "growx gaptop 10" throws IllegalArgumentException and previously
        // killed the whole Delete action silently).
        content.add(warningLabel, "growx, gaptop 10");
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
        // Every dialog text uses the application's current font (family + size),
        // including the HTML labels (their base font follows the component font).
        GuiFontManager.applyFontToTree(dialog, UIManager.getFont("Label.font"));
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
