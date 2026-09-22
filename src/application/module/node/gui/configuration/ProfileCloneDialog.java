package application.module.node.gui.configuration;

import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileDiffCalculator;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Set;

/**
 * Modal "Clone Configuration" dialog of the profile-actions toolbar.
 * <p>
 * Shown by the Clone toolbar action. Displays the properties whose value
 * differs from the application default in the <b>current (unsaved, editor)
 * effective state</b> (the minimal-override payload of the clone, computed
 * with {@link ProfileDiffCalculator}) together with a live-validated
 * new-profile name field. Create-only: the cloned profile is not started and
 * the source profile's editor state is left untouched.
 * </p>
 */
public final class ProfileCloneDialog {

    private ProfileCloneDialog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Shows the modal clone dialog.
     *
     * @param parent            the parent component (dialogs are owned by its window)
     * @param sourceProfileName the name of the profile being cloned (read-only)
     * @param diffEntries       the properties that differ from the default in the
     *                          current editor state (may be empty — the clone is
     *                          then an empty profile)
     * @param suggestedName     the pre-filled name for the new profile
     * @param takenProfileNames names already used (live validation, SSOT:
     *                          {@link NodeProfileRepository#checkProfileName})
     * @return the confirmed new profile name, or {@code null} when cancelled
     */
    public static String show(Component parent, String sourceProfileName,
            List<ProfileDiffCalculator.ProfileDiffEntry> diffEntries,
            String suggestedName, Set<String> takenProfileNames) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final String[] confirmedName = { null };
        final List<ProfileDiffCalculator.ProfileDiffEntry> entries =
                diffEntries != null ? diffEntries : List.of();

        // ── Source profile (display only — the name is not editable, so a plain label, not a text field) ──
        JLabel sourceField = new JLabel(sourceProfileName == null ? "" : sourceProfileName);

        // ── Diff area: values that differ from the default ──
        JTextArea diffArea = new JTextArea();
        diffArea.setEditable(false);
        diffArea.setLineWrap(true);
        diffArea.setWrapStyleWord(true);
        if (entries.isEmpty()) {
            diffArea.setFont(new JLabel().getFont());
            diffArea.setText("The configuration matches the default — the clone will be an empty profile.");
        } else {
            // App font (family + size) — no hardcoded 12pt monospace, which
            // rendered smaller than the rest of the UI.
            diffArea.setFont(UIManager.getFont("TextArea.font"));
            StringBuilder sb = new StringBuilder();
            for (ProfileDiffCalculator.ProfileDiffEntry entry : entries) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                if (entry.getLabel() != null && !entry.getLabel().equals(entry.getKey())) {
                    sb.append(entry.getLabel()).append(": ");
                }
                sb.append(entry.getKey()).append(" = ").append(entry.getValue());
            }
            sb.append('\n').append('\n');
            sb.append(entries.size()).append(" value(s) differ from the default.");
            diffArea.setText(sb.toString());
        }
        diffArea.setCaretPosition(0);
        diffArea.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane diffScroll = new JScrollPane(diffArea);
        diffScroll.setPreferredSize(new Dimension(480, 200));
        diffScroll.setBorder(BorderFactory.createTitledBorder("Values that differ from the default"));

        // ── New profile name with live validation (SSOT: NodeProfileRepository) ──
        JTextField nameField = new JTextField(suggestedName == null ? "" : suggestedName);
        nameField.selectAll();
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(new Color(0xC0, 0x39, 0x2B));

        JButton cloneBtn = new JButton("Clone",
                IconFontSwing.buildIcon(FontAwesome.FILES_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        cloneBtn.setEnabled(false);
        JButton cancelBtn = new JButton("Cancel");

        Runnable validation = () -> {
            String error = NodeProfileRepository.checkProfileName(
                    nameField.getText().trim(), takenProfileNames);
            errorLabel.setText(error != null ? error : " ");
            cloneBtn.setEnabled(error == null);
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
        content.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.FILES_O, 32, GuiColors.getButtonIcon())));
        // Plain bold title (app font + bold) instead of an <h2> HTML heading.
        JLabel titleLabel = new JLabel("Clone Configuration");
        titleLabel.setFont(GuiFontManager.getBoldDefaultFont());
        content.add(titleLabel);
        content.add(new JLabel("Source profile:"));
        content.add(sourceField, "growx");
        content.add(diffScroll, "growx, gaptop 10");
        content.add(new JLabel("New profile name:"), "gaptop 10");
        content.add(nameField, "growx");
        content.add(errorLabel, "growx");
        // Plain info text at the app font size (no <small> tag, which rendered
        // at 0.83x the base size).
        content.add(new JLabel(
                "The cloned profile keeps the source's ports and database settings as-is. "
                        + "Starting both profiles at the same time may be rejected by the "
                        + "resource-conflict protection."),
                "gaptop 10");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cloneBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Clone Configuration",
                Dialog.ModalityType.APPLICATION_MODAL);
        // Every dialog text uses the application's current font (family + size).
        GuiFontManager.applyFontToTree(dialog, UIManager.getFont("Label.font"));
        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(cloneBtn);
        // Enter confirms (default button, only when the name is valid);
        // Escape cancels (codebase pattern).
        ((JComponent) dialog.getContentPane()).registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        cloneBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (NodeProfileRepository.checkProfileName(name, takenProfileNames) == null) {
                confirmedName[0] = name;
            }
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        validation.run(); // initial state for the pre-filled (suggested) name

        // Synchronous modal show (called on the EDT from a button listener);
        // the event pump keeps the dialog responsive while it blocks.
        dialog.setVisible(true);
        return confirmedName[0];
    }
}

