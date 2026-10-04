package application.utils.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.Window;

/**
 * A compact, structured help dialog — the single renderer for in-app help popups.
 * <p>
 * Unlike a plain {@code JOptionPane.showMessageDialog} (which stretches a long HTML label to a
 * very wide, unstructured banner), a {@code HelpDialog} has a FIXED content width
 * ({@link #CONTENT_WIDTH}), sectioned content built from the small components below
 * (headings, paragraphs, color legend rows, toolbar-action rows with their real icons), and a
 * palette-styled {@code Close} button (ESC also closes). All colors come from the
 * {@link GuiColors} palette SSOT — nothing is hardcoded.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * JComponent content = HelpDialog.content();
 * content.add(HelpDialog.title("Logging Profile Help"));
 * content.add(HelpDialog.heading("Row colors"));
 * content.add(HelpDialog.legendRow(GuiColors.getApplied(), "Applied", "Matches the running configuration."));
 * content.add(HelpDialog.actionRow(FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied(), "Apply", "Marks the profile as applied."));
 * HelpDialog.show(owner, "Help", content);
 * }</pre>
 */
public final class HelpDialog {

    /**
     * The dialog's fixed content width (px). The dialog packs to this width instead of
     * stretching across the screen — the whole point of this class.
     */
    public static final int CONTENT_WIDTH = 560;

    static {
        // Defensive icon-font registration (the app registers it at startup in
        // AppearanceModule#init; this covers standalone/test construction).
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    private HelpDialog() {
    }

    /**
     * Shows a modal help dialog with the given structured content and a palette-styled
     * {@code Close} button (ESC also closes). The dialog packs to {@link #CONTENT_WIDTH}
     * and is centered over the owner.
     *
     * @param owner   the owner component (may be any component; a {@link Window} owner is preferred)
     * @param title   the window title
     * @param content the structured content (build it with {@link #content()} and the row/section helpers)
     */
    public static void show(Component owner, String title, JComponent content) {
        Window window = null;
        if (owner instanceof Window) {
            window = (Window) owner;
        }
        JDialog dialog = new JDialog(window, title, JDialog.ModalityType.APPLICATION_MODAL);
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(18, 22, 14, 22));
        // Fix the width FIRST (measure the natural height), so pack() yields a compact dialog.
        content.setPreferredSize(new Dimension(CONTENT_WIDTH, Math.max(content.getPreferredSize().height, 1)));
        root.add(content, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(closeButton(dialog));
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        // ESC closes the dialog as well.
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "help-dialog-close");
        dialog.getRootPane().getActionMap().put("help-dialog-close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Shows a modal help dialog for a host-supplied HTML help text (the
     * {@code ModuleLoggingProfilePanel#setHelpSupplier} contract). The HTML is wrapped into a
     * width-bounded {@code <body>} when it has none, so it stays compact like the structured
     * dialogs.
     *
     * @param owner the owner component
     * @param title the window title
     * @param html  the HTML help text (an {@code <html>...</html>} document or a body fragment)
     */
    public static void showHtml(Component owner, String title, String html) {
        JComponent content = content();
        content.add(paragraph(boundHtmlBody(html)));
        show(owner, title, content);
    }

    /**
     * @return a new top-to-bottom content panel for the dialog (non-opaque, left-aligned).
     */
    public static JPanel content() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    /**
     * @return the dialog title label (bold, larger font).
     */
    public static JComponent title(String text) {
        JLabel label = new JLabel("<html><b>" + text + "</b></html>");
        label.setFont(label.getFont().deriveFont(label.getFont().getSize() + 4f));
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        label.setAlignmentY(JComponent.TOP_ALIGNMENT);
        return label;
    }

    /**
     * @return a section heading label, painted in the palette's help color.
     */
    public static JComponent heading(String text) {
        JLabel label = new JLabel("<html><b>" + text + "</b></html>");
        label.setForeground(GuiColors.getHelpIcon());
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return label;
    }

    /**
     * @return a wrapped paragraph label (HTML body fragment, width-bounded to the dialog).
     */
    public static JComponent paragraph(String htmlText) {
        JLabel label = new JLabel("<html><body style='width: 510px'>" + htmlText + "</body></html>");
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        label.setAlignmentY(JComponent.TOP_ALIGNMENT);
        return label;
    }

    /**
     * @return a color-legend row: a palette-colored square glyph, the (bold, colored) state
     * name, and the wrapped description underneath.
     */
    public static JComponent legendRow(Color color, String name, String description) {
        JLabel glyph = new JLabel("\u25A0");
        glyph.setForeground(color);
        JLabel nameLabel = new JLabel(name);
        nameLabel.setForeground(color);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
        return row(glyph, nameLabel, description);
    }

    /**
     * @return a toolbar-action row: the action's real FontAwesome glyph (palette-colored), the
     * bold action name, and the wrapped description underneath.
     */
    public static JComponent actionRow(FontAwesome icon, Color color, String name, String description) {
        JLabel glyph = new JLabel(IconFontSwing.buildIcon(icon, GuiConstants.getButtonIconSize(), color));
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
        return row(glyph, nameLabel, description);
    }

    /**
     * @return a palette-colored horizontal separator (with breathing room above and below).
     */
    public static JComponent separator() {
        Box box = Box.createVerticalBox();
        box.add(Box.createVerticalStrut(6));
        JSeparator separator = new JSeparator();
        separator.setForeground(GuiColors.getSeparator());
        separator.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        box.add(separator);
        box.add(Box.createVerticalStrut(6));
        return box;
    }

    private static JComponent row(JComponent glyph, JLabel nameLabel, String description) {
        JPanel glyphBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        glyphBox.setOpaque(false);
        glyphBox.add(glyph);
        glyphBox.setPreferredSize(new Dimension(24, 24));
        glyphBox.setMaximumSize(new Dimension(24, 24));

        Box right = Box.createVerticalBox();
        nameLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        right.add(nameLabel);
        right.add(Box.createVerticalStrut(2));
        JLabel descriptionLabel = new JLabel("<html><body style='width: 480px'>" + description + "</body></html>");
        descriptionLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        right.add(descriptionLabel);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(glyphBox, BorderLayout.WEST);
        row.add(right, BorderLayout.CENTER);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return row;
    }

    private static JButton closeButton(JDialog dialog) {
        JButton close = new JButton("Close");
        close.setFont(close.getFont().deriveFont(Font.BOLD));
        close.setForeground(GuiColors.getHelpIcon());
        close.setFocusable(false);
        close.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GuiColors.getSeparator(), 1),
                new javax.swing.border.EmptyBorder(5, 22, 5, 22)));
        close.addActionListener(e -> dialog.dispose());
        return close;
    }

    /**
     * Wraps a raw HTML help document into a width-bounded {@code <body>} when it declares none,
     * so host-supplied help renders just as compactly as the structured content.
     */
    private static String boundHtmlBody(String html) {
        String h = html.strip();
        if (h.toLowerCase().contains("<body")) {
            return h;
        }
        String inner = h;
        if (inner.toLowerCase().startsWith("<html>")) {
            inner = inner.substring(6);
        }
        if (inner.toLowerCase().endsWith("</html>")) {
            inner = inner.substring(0, inner.length() - 7);
        }
        return "<html><body style='width: 500px'>" + inner + "</body></html>";
    }
}