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
 * Lists the available profiles plus a virtual "reset to defaults" entry whose
 * label is {@code defaultEntry}; the profile currently loaded into the editor is
 * highlighted in green so the user can always copy back from it (reverting a
 * copy). Choosing a source profile and pressing <b>Copy</b> returns that profile
 * name; choosing the virtual entry or pressing <b>Reset to Defaults</b> returns
 * {@code defaultEntry}; cancelling returns {@code null}.
 * </p>
 */
public final class ProfileCopyDialog {

    /**
     * The virtual "reset to defaults" entry label used by the node configuration
     * panel. Selecting it (or the dialog's Reset to Defaults button) does not copy
     * a profile — it resets the editor to the application default values: the
     * relocated reset-to-defaults action that used to be a standalone toolbar button.
     */
    public static final String DEFAULT_CONFIGURATION = "Default configuration";

    private ProfileCopyDialog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Shows the modal "Copy Configuration" dialog.
     *
     * @param parent         the parent component (the dialog is owned by its window)
     * @param profiles       the available (non-default) profile names
     * @param currentProfile the profile currently loaded into the editor (shown green); may be {@code null}
     * @param defaultEntry   the label of the virtual "reset to defaults" entry; choosing it or the
     *                       <i>Reset to Defaults</i> button returns this value
     * @return the selected source profile name, or {@code defaultEntry} when a reset was requested,
     *         or {@code null} when cancelled
     */
    public static String show(Component parent, List<String> profiles, String currentProfile,
            String defaultEntry) {
        final Window owner = SwingUtilities.windowForComponent(parent);
        final AtomicReference<String> result = new AtomicReference<>(null);

        // The virtual "reset to defaults" entry leads the list: choosing it (or the
        // Reset to Defaults button) resets the editor instead of copying a profile.
        List<String> entries = new ArrayList<>();
        entries.add(defaultEntry);
        entries.addAll(profiles);
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
        // The relocated reset-to-defaults action, carrying the same (swapped) refresh glyph
        // the toolbars' reset buttons used to show.
        JButton resetBtn = new JButton("Reset to Defaults",
                IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        JButton cancelBtn = new JButton("Cancel");

        JPanel content = new JPanel(new MigLayout("insets 15, wrap 1, fillx", "[grow]", "[]"));
        content.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.CLIPBOARD, 32, GuiColors.getButtonIcon())));
        // Plain bold title (app font + bold); intentionally kept minimal.
        JLabel titleLabel = new JLabel("Copy configuration from profile");
        titleLabel.setFont(GuiFontManager.getBoldDefaultFont());
        content.add(titleLabel);
        content.add(combo, "growx, gaptop 5");
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightButtons.add(copyBtn);
        rightButtons.add(cancelBtn);
        // Reset to Defaults sits on the left; Copy / Cancel on the right (a two-group row).
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(resetBtn, BorderLayout.WEST);
        buttons.add(rightButtons, BorderLayout.EAST);
        content.add(buttons, "gaptop 15");

        JDialog dialog = new JDialog(owner, "Copy configuration from profile",
                Dialog.ModalityType.APPLICATION_MODAL);
        // Every dialog text uses the application's current font (family + size).
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
        resetBtn.addActionListener(e -> {
            result.set(defaultEntry);
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.setVisible(true);
        return result.get();
    }
}