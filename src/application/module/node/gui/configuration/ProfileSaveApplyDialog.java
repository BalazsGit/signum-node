package application.module.node.gui.configuration;

import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
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
     * Shows the modal save dialog.
     *
     * @param parent       the parent component (dialogs are owned by its window)
     * @param profileName  the name of the profile that will be saved (read-only)
     * @param changesReport the HTML report of unsaved changes (from
     *                       {@code NodeConfigurationPanel#getUnsavedChangesReport})
     * @return {@code true} when the user confirmed the save, {@code false} when cancelled
     */
    public static boolean show(Component parent, String profileName, String changesReport) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final AtomicBoolean confirmed = new AtomicBoolean(false);

        JTextField nameField = new JTextField(profileName == null ? "" : profileName);
        nameField.setEditable(false);
        nameField.setFocusable(false);
        nameField.setBackground(new JTextField().getBackground());

        JButton saveBtn = new JButton("Save",
                IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        JButton cancelBtn = new JButton("Cancel");

        JPanel content = new JPanel(new MigLayout("insets 15, wrap 1, fillx", "[grow]", "[]"));
        content.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 32, GuiColors.getButtonIcon())));
        content.add(new JLabel("<html><h2>Save Changes</h2></html>"));
        content.add(new JLabel("Profile:"));
        content.add(nameField, "growx");
        if (changesReport != null && !changesReport.isEmpty()) {
            JLabel reportLabel = new JLabel(changesReport);
            reportLabel.setVerticalAlignment(SwingConstants.TOP);
            JScrollPane scroll = new JScrollPane(reportLabel);
            scroll.setPreferredSize(new Dimension(480, 220));
            scroll.setBorder(BorderFactory.createTitledBorder("Changes to be saved"));
            content.add(scroll, "growx, gaptop 10, wpref 480, hpref 220");
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(saveBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Save Configuration",
                Dialog.ModalityType.APPLICATION_MODAL);
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
            confirmed.set(true);
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        // Synchronous modal show (called on the EDT from a button listener);
        // the event pump keeps the dialog responsive while it blocks.
        dialog.setVisible(true);
        return confirmed.get();
    }
}
