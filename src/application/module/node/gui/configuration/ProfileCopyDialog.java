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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modal "Copy Configuration" dialog of the profile-actions toolbar.
 * <p>
 * Lists every available node profile; the profile currently loaded into the
 * editor is highlighted in green so the user can always copy back from it
 * (reverting a copy). Choosing a source and pressing <b>Copy</b> returns the
 * source profile name; cancelling returns {@code null}.
 * </p>
 */
public final class ProfileCopyDialog {

    private ProfileCopyDialog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Shows the modal copy dialog.
     *
     * @param parent      the parent component (dialogs are owned by its window)
     * @param profiles    the available profile names
     * @param currentProfile the profile currently loaded into the editor (shown green)
     * @return the selected source profile name, or {@code null} when cancelled
     */
    public static String show(Component parent, List<String> profiles, String currentProfile) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final AtomicReference<String> result = new AtomicReference<>(null);

        JComboBox<String> combo = new JComboBox<>(profiles.toArray(new String[0]));
        combo.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        combo.setRenderer(new ListCellRenderer<String>() {
            @Override
            public Component getListCellRendererComponent(JList<? extends String> list, String value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = new JLabel(value == null ? "" : value);
                label.setOpaque(true);
                if (isSelected) {
                    label.setBackground(list.getSelectionBackground());
                    label.setForeground(list.getSelectionForeground());
                } else {
                    label.setBackground(list.getBackground());
                    label.setForeground(value != null && value.equals(currentProfile)
                            ? GuiColors.getApplied()
                            : list.getForeground());
                }
                return label;
            }
        });
        if (currentProfile != null) {
            combo.setSelectedItem(currentProfile);
        }

        JButton copyBtn = new JButton("Copy",
                IconFontSwing.buildIcon(FontAwesome.CLIPBOARD, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        JButton cancelBtn = new JButton("Cancel");

        JPanel content = new JPanel(new MigLayout("insets 15, wrap 1, fillx", "[grow]", "[]"));
        content.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.CLIPBOARD, 32, GuiColors.getButtonIcon())));
        // Plain bold title (app font + bold) instead of an <h2> HTML heading.
        JLabel titleLabel = new JLabel("Copy Configuration");
        titleLabel.setFont(GuiFontManager.getBoldDefaultFont());
        content.add(titleLabel);
        content.add(new JLabel("Select the profile whose configuration you want to copy into the editor:"));
        content.add(combo, "growx, gaptop 5");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copyBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Copy Configuration",
                Dialog.ModalityType.APPLICATION_MODAL);
        // Every dialog text uses the application's current font (family + size).
        GuiFontManager.applyFontToTree(dialog, UIManager.getFont("Label.font"));
        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(copyBtn);
        // Enter confirms the copy (default button); Escape cancels (codebase pattern).
        ((JComponent) dialog.getContentPane()).registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        copyBtn.addActionListener(e -> {
            result.set((String) combo.getSelectedItem());
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        // Synchronous modal show (called on the EDT from a button listener);
        // the event pump keeps the dialog responsive while it blocks.
        dialog.setVisible(true);
        return result.get();
    }
}
