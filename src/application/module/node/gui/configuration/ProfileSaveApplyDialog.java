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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Modal "Save Changes" dialog of the profile-actions toolbar.
 * <p>
 * Shown by the Save &amp; Apply toolbar action. Displays the currently loaded
 * profile name (read-only — this action always saves <b>the current</b>
 * profile) together with the list of unsaved changes, so the user can review
 * exactly what is about to be written.
 * </p>
 */
public final class ProfileSaveApplyDialog {

    private ProfileSaveApplyDialog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The user's decision in the save dialog.
     */
    public enum Result {
        /** Save the unsaved changes. */
        SAVE,
        /** Discard the unsaved changes (the editor reverts to the saved configuration). */
        DISCARD,
        /** Do nothing — the unsaved changes stay in the editor. */
        CANCEL
    }

    /**
     * Shows the modal save dialog.
     *
     * @param parent       the parent component (dialogs are owned by its window)
     * @param profileName  the name of the profile that will be saved (read-only)
     * @param changesReport the HTML report of unsaved changes (from
     *                       {@code NodeConfigurationPanel#getUnsavedChangesReport})
     * @return {@link Result#SAVE} when the user confirmed the save,
     *         {@link Result#DISCARD} when the user chose to discard the unsaved
     *         changes (the caller reverts the editor to the saved configuration),
     *         {@link Result#CANCEL} when the dialog was closed without a decision
     */
    public static Result show(Component parent, String profileName, String changesReport) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final AtomicBoolean saveConfirmed = new AtomicBoolean(false);
        final AtomicBoolean discardConfirmed = new AtomicBoolean(false);

        // ── Profile name (display only — the name is not editable, so a plain label, not a text field) ──
        JLabel nameField = new JLabel(profileName == null ? "" : profileName);

        JButton saveBtn = new JButton("Save",
                IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        JButton discardBtn = new JButton("Discard",
                IconFontSwing.buildIcon(FontAwesome.ERASER, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        JButton cancelBtn = new JButton("Cancel");

        JPanel content = new JPanel(new MigLayout("insets 15, wrap 1, fillx", "[grow]", "[]"));
        content.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 32, GuiColors.getButtonIcon())));
        // Plain bold title (app font + bold) instead of an <h2> HTML heading:
        // HTML element tags render at relative sizes (h2 = 1.5x), which made the
        // dialog text look smaller/larger than the rest of the app.
        JLabel titleLabel = new JLabel("Save Changes");
        titleLabel.setFont(GuiFontManager.getBoldDefaultFont());
        content.add(titleLabel);
        content.add(new JLabel("Profile:"));
        content.add(nameField, "growx");
        if (changesReport != null && !changesReport.isEmpty()) {
            JLabel reportLabel = new JLabel(changesReport);
            reportLabel.setVerticalAlignment(SwingConstants.TOP);
            JScrollPane scroll = new JScrollPane(reportLabel);
            scroll.setPreferredSize(new Dimension(480, 220));
            scroll.setBorder(BorderFactory.createTitledBorder("Changes to be saved"));
            // NOTE: only "growx" as constraint — this MigLayout version (11.4.2)
            // rejects size keywords like "wpref"/"prefw" (IllegalArgumentException),
            // which previously killed the whole Save & Apply action silently.
            content.add(scroll, "growx, gaptop 10");
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(saveBtn);
        buttons.add(discardBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Save Configuration",
                Dialog.ModalityType.APPLICATION_MODAL);
        // Every dialog text uses the application's current font (family + size),
        // including the HTML report label (its base font follows the component font).
        GuiFontManager.applyFontToTree(dialog, UIManager.getFont("Label.font"));
        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(saveBtn);
        // Enter confirms the save (default button); Escape cancels (codebase pattern).
        ((JComponent) dialog.getContentPane()).registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        saveBtn.addActionListener(e -> {
            saveConfirmed.set(true);
            dialog.dispose();
        });
        discardBtn.addActionListener(e -> {
            discardConfirmed.set(true);
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        // Synchronous modal show (called on the EDT from a button listener);
        // the event pump keeps the dialog responsive while it blocks.
        dialog.setVisible(true);
        if (saveConfirmed.get()) {
            return Result.SAVE;
        }
        if (discardConfirmed.get()) {
            return Result.DISCARD;
        }
        return Result.CANCEL;
    }
}
