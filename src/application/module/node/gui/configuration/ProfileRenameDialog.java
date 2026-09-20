package application.module.node.gui.configuration;

import application.module.node.profile.NodeProfileRepository;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Set;

/**
 * Modal "Rename Profile" dialog of the profile-actions toolbar.
 * <p>
 * Shown by the Rename toolbar action. States the node's current state (D3,
 * state-preserving rename): a running node is stopped for the duration of the
 * rename and returned to its previous state (running / paused / stopped); a
 * stopped node stays stopped. Offers a live-validated new-name field
 * (SSOT: {@link NodeProfileRepository#checkProfileName}) with the current name
 * shown read-only.
 * </p>
 */
public final class ProfileRenameDialog {

    private ProfileRenameDialog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Shows the modal rename dialog.
     *
     * @param parent              the parent component (dialogs are owned by its window)
     * @param currentProfileName  the current profile name (read-only)
     * @param nodeRunning         whether the profile's node is running (drives the state text)
     * @param nodeUserPaused      whether the running node is currently paused by the user
     * @param suggestedName       the pre-filled name (the current name)
     * @param takenProfileNames   names already used, EXCLUDING {@code currentProfileName}
     *                            (live validation, SSOT:
     *                            {@link NodeProfileRepository#checkProfileName})
     * @return the confirmed new profile name, or {@code null} when cancelled
     */
    public static String show(Component parent, String currentProfileName,
            boolean nodeRunning, boolean nodeUserPaused,
            String suggestedName, Set<String> takenProfileNames) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final String[] confirmedName = { null };

        // ── Current profile (read-only) ──
        JTextField currentField = new JTextField(currentProfileName == null ? "" : currentProfileName);
        currentField.setEditable(false);
        currentField.setFocusable(false);
        currentField.setBackground(new JTextField().getBackground());

        // ── Node-state text (D3: state-preserving rename) ──
        final String stateText;
        if (nodeRunning && nodeUserPaused) {
            stateText = "<html>The node is <b>running (paused by you)</b> — it will be <b>stopped</b> "
                    + "for the duration of the rename and started again when it finishes, "
                    + "<b>then paused as before</b>.</html>";
        } else if (nodeRunning) {
            stateText = "<html>The node is <b>running</b> — it will be <b>stopped</b> "
                    + "for the duration of the rename and started again when it finishes.</html>";
        } else {
            stateText = "<html>The node is <b>not running</b> — it will stay stopped.</html>";
        }

        // ── New name (live-validated) ──
        JTextField nameField = new JTextField(suggestedName == null ? "" : suggestedName);
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(GuiColors.getContrastRed());

        JButton renameBtn = new JButton("Rename",
                IconFontSwing.buildIcon(FontAwesome.PENCIL_SQUARE_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        renameBtn.setEnabled(false);
        JButton cancelBtn = new JButton("Cancel");

        Runnable validation = () -> {
            String name = nameField.getText().trim();
            String error;
            if (name.equals(currentProfileName)) {
                error = "The new name must differ from the current name";
            } else {
                error = NodeProfileRepository.checkProfileName(name, takenProfileNames);
            }
            errorLabel.setText(error != null ? error : " ");
            renameBtn.setEnabled(error == null);
        };
        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                validation.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                validation.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                validation.run();
            }
        });

        JPanel content = new JPanel(new MigLayout("insets 15, wrap 1, fillx", "[grow]", "[]"));
        content.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.PENCIL_SQUARE_O, 32, GuiColors.getButtonIcon())));
        content.add(new JLabel("<html><h2>Rename Profile</h2></html>"));
        content.add(new JLabel(stateText), "growx");
        content.add(new JLabel("Current profile:"), "gaptop 10");
        content.add(currentField, "growx");
        content.add(new JLabel("New profile name:"), "gaptop 10");
        content.add(nameField, "growx");
        content.add(errorLabel, "growx");
        content.add(new JLabel(
                "<html><small>The rename includes the profile's data paths: a per-profile SQLite database<br>"
                        + "is moved to the new name when the profile uses one.</small></html>"),
                "gaptop 10");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(renameBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Rename Profile",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(renameBtn);
        // Enter confirms (default button, only when the name is valid);
        // Escape cancels (codebase pattern).
        ((JComponent) dialog.getContentPane()).registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        renameBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (!name.equals(currentProfileName)
                    && NodeProfileRepository.checkProfileName(name, takenProfileNames) == null) {
                confirmedName[0] = name;
            }
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        validation.run(); // initial state for the pre-filled (current) name

        // Synchronous modal show (called on the EDT from a button listener);
        // the event pump keeps the dialog responsive while it blocks.
        dialog.setVisible(true);
        return confirmedName[0];
    }
}