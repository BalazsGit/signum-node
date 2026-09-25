package application.module.browser.gui.dialogs;

import application.utils.i18n.I18n;

import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * The clear-browsing-data dialog (plan F9, C6 + S6): a small modal asking
 * what to remove — the browsing history, the cookies, and (S6) optionally
 * restricted to a single site's host.
 * <p>
 * The dialog only <em>asks</em>; the actual removal happens in the caller
 * (the panel orchestrates the {@code HistoryStore} and the engine's
 * {@code CefDataCleaner}) so the dialog itself stays a pure view. Must be
 * shown on the EDT.
 */
public final class ClearDataDialog {

    /** The user's choice ({@code host} blank = all sites). */
    public record Selection(boolean history, boolean cookies, String host) {
        public boolean anything() {
            return history || cookies;
        }
    }

    private ClearDataDialog() {
        // utility class — never instantiated
    }

    /**
     * Shows the dialog modally.
     *
     * @return the user's choice, or {@code null} when canceled (or nothing
     *         was selected)
     */
    public static Selection show(Window owner) {
        // (APPLICATION_MODAL — this JDK's Dialog.ModalityType has no plain MODAL)
        JDialog dialog = new JDialog(owner, I18n.get("browser.clearData.title"),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(18, 22, 8, 22));
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JCheckBox historyBox = new JCheckBox(I18n.get("browser.clearData.history"), true);
        JCheckBox cookiesBox = new JCheckBox(I18n.get("browser.clearData.cookies"), true);
        JTextField hostField = new JTextField(22);
        hostField.setToolTipText(I18n.get("browser.clearData.site.hint"));
        JLabel hostLabel = new JLabel(I18n.get("browser.clearData.site"), SwingConstants.LEFT);
        hostLabel.setAlignmentX(0f);

        form.add(historyBox);
        form.add(cookiesBox);
        form.add(hostLabel);
        form.add(hostField);

        JButton ok = new JButton(I18n.get("browser.clearData.ok"));
        JButton cancel = new JButton(I18n.get("browser.clearData.cancel"));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(ok);
        buttons.add(cancel);

        final Selection[] result = new Selection[1];
        ok.addActionListener(e -> {
            String host = hostField.getText() == null ? "" : hostField.getText().trim();
            if (!historyBox.isSelected() && !cookiesBox.isSelected()) {
                return; // nothing selected — stay open
            }
            result[0] = new Selection(historyBox.isSelected(), cookiesBox.isSelected(), host);
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(ok);

        JPanel root = new JPanel(new java.awt.BorderLayout(0, 8));
        root.setOpaque(false);
        root.add(form, java.awt.BorderLayout.CENTER);
        root.add(buttons, java.awt.BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getContentPane().setPreferredSize(new Dimension(360, 190));
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return result[0];
    }

    /**
     * Shows the outcome message (the caller builds the text with the actual
     * counts — the dialog layer never touches the stores).
     */
    public static void showResult(Window owner, String message) {
        JOptionPane.showMessageDialog(owner, message,
                I18n.get("browser.clearData.title"), JOptionPane.INFORMATION_MESSAGE);
    }
}