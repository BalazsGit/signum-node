package application.utils.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modal "Copy Configuration" dialog shared by the profile-action toolbars
 * (node configuration, module logging and appearance).
 * <p>
 * Lists the available profiles; the profile currently loaded into the editor is
 * highlighted in green so the user can always copy back from it (reverting a
 * copy). Choosing a source profile and pressing <b>Copy</b> returns that profile
 * name; cancelling returns {@code null}.
 * </p>
 */
public final class ProfileCopyDialog {

    private ProfileCopyDialog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Shows the modal "Copy Configuration" dialog.
     *
     * @param parent         the parent component (the dialog is owned by its window)
     * @param profiles       the available profile names
     * @param currentProfile the profile currently loaded into the editor (shown green); may be {@code null}
     * @return the selected source profile name, or {@code null} when cancelled
     */
    public static String show(Component parent, List<String> profiles, String currentProfile) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final AtomicReference<String> result = new AtomicReference<>(null);

        List<String> entries = new java.util.ArrayList<>(profiles);
        JComboBox<String> combo = new JComboBox<>(entries.toArray(new String[0]));
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
        JLabel titleLabel = new JLabel("Copy configuration from profile");
        titleLabel.setFont(GuiFontManager.getBoldDefaultFont());
        content.add(titleLabel);
        content.add(combo, "growx, gaptop 5");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(copyBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Copy configuration from profile",
                Dialog.ModalityType.APPLICATION_MODAL);
        GuiFontManager.applyFontToTree(dialog, UIManager.getFont("Label.font"));
        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(copyBtn);
        ((JComponent) dialog.getContentPane()).registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        copyBtn.addActionListener(e -> {
            result.set((String) combo.getSelectedItem());
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.setVisible(true);
        return result.get();
    }
}