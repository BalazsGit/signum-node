package application.utils.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

/**
 * The single renderer for the profile-action confirmation popups (New / Save / Apply /
 * Rename / Delete / Reload / Copy / Clone) across the node configuration, logging, module
 * logging and appearance panels.
 *
 * <p>Two rules keep every one of these popups identical:
 *
 * <ul>
 * <li>The popup's message icon is the <b>same</b> FontAwesome glyph (and colour) as the
 *     toolbar button that opened it — build it with {@link #actionIcon} — instead of the
 *     Look-and-Feel default question / warning / information icon.</li>
 * <li>The message <b>wraps</b> (line breaks) within a uniform {@link #CONTENT_WIDTH}; the
 *     dialog height adapts to the text but never exceeds {@link #MAX_MESSAGE_HEIGHT},
 *     beyond which the message scrolls vertically.</li>
 * </ul>
 *
 * <p>An optional component (e.g. the Apply "restart application" checkbox) can be placed
 * beneath the message via the {@code extra} argument.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * int choice = DialogUtils.confirm(this,
 *         DialogUtils.actionIcon(FontAwesome.TRASH_O, GuiColors.getContrastRed()),
 *         "Delete Profile", "Are you sure you want to delete profile 'x'?",
 *         JOptionPane.YES_NO_OPTION, null, null);
 * }</pre>
 */
public final class DialogUtils {

    /**
     * Uniform width (px) of the wrapping message area in every confirmation popup. A single
     * central value so all of these popups share one width (together with the message icon
     * this matches the {@link HelpDialog#CONTENT_WIDTH} total width the help popups use).
     */
    public static final int CONTENT_WIDTH = 500;

    /**
     * Maximum height (px) of the message area. The dialog grows with the text up to this
     * bound; a longer message becomes vertically scrollable instead of growing the popup.
     */
    public static final int MAX_MESSAGE_HEIGHT = 280;

    static {
        // Defensive icon-font registration (the app registers it at startup in
        // AppearanceModule#init; this covers standalone/test construction).
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    private DialogUtils() {
    }

    /**
     * @return the confirmation popup's message icon: the given FontAwesome glyph at the
     *         standard dialog icon size ({@link GuiConstants#ICON_SIZE_DIALOG}), so it
     *         matches the toolbar button that opened the popup (same glyph, same colour).
     */
    public static Icon actionIcon(FontAwesome glyph, Color color) {
        return IconFontSwing.buildIcon(glyph, GuiConstants.ICON_SIZE_DIALOG, color);
    }

    /**
     * Builds the uniform wrapping message area: the text wraps (line breaks) within
     * {@link #CONTENT_WIDTH}; the area is as short as the wrapped text up to
     * {@link #MAX_MESSAGE_HEIGHT}, beyond which it scrolls vertically. Horizontal
     * scrolling is never shown (the text always wraps).
     *
     * @param message the plain-text message (may contain {@code \n} line breaks)
     * @return the scrollable, width-fixed message area
     */
    public static JScrollPane buildMessageArea(String message) {
        JTextArea area = new JTextArea(message);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(null);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        // Fix the wrap width so the text reflows at CONTENT_WIDTH, then read the resulting
        // height and cap it (short text -> short dialog, long text -> capped + scroll).
        area.setSize(CONTENT_WIDTH, 1);
        int wrappedHeight = area.getPreferredSize().height;
        int height = Math.min(wrappedHeight + 4, MAX_MESSAGE_HEIGHT);
        scroll.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
        return scroll;
    }

    /**
     * The uniform message component: the wrapping area from {@link #buildMessageArea}, with
     * an optional component (e.g. a checkbox) left-aligned beneath it.
     *
     * @param message the plain-text message
     * @param extra   an optional component under the message (e.g. a checkbox), or null
     * @return the message component (the scroll area alone, or the area plus the extra row)
     */
    public static JComponent composeMessageArea(String message, Component extra) {
        JScrollPane scroll = buildMessageArea(message);
        if (extra == null) {
            return scroll;
        }
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.add(scroll, BorderLayout.CENTER);
        JPanel extraRow = new JPanel(new BorderLayout());
        extraRow.setOpaque(false);
        extraRow.add(extra, BorderLayout.WEST);
        panel.add(extraRow, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Shows a modal confirmation popup in the profile-action pattern (see the class
     * javadoc). The message wraps within {@link #CONTENT_WIDTH}, the height adapts to the
     * text up to {@link #MAX_MESSAGE_HEIGHT} (then scrolls), the message icon is the
     * triggering button's glyph, and an optional {@code extra} component sits under the
     * message.
     *
     * @param owner      the owner component (any component; its enclosing window is used)
     * @param icon       the message icon — build it with {@link #actionIcon}; may be null
     * @param title      the window title
     * @param message    the plain-text message, <b>or</b> a pre-built message component
     *                   (e.g. {@link #composeMessageArea} output that already holds an
     *                   extra control); a {@link Component} is used as-is
     * @param optionType the option type (e.g. {@link JOptionPane#YES_NO_OPTION})
     * @param options    explicit option values, or null for the {@code optionType} defaults
     * @param extra      an optional component under the message (only used when
     *                   {@code message} is a {@link String}); may be null
     * @return the selected option value (a {@link JOptionPane} option constant)
     */
    public static int confirm(Component owner, Icon icon, String title, Object message,
                              int optionType, Object[] options, Component extra) {
        Component messageComponent = (message instanceof Component)
                ? (Component) message
                : composeMessageArea(String.valueOf(message), extra);
        if (icon != null) {
            messageComponent = withLeadingIcon(icon, messageComponent);
        }

        JOptionPane pane = new JOptionPane(messageComponent, JOptionPane.PLAIN_MESSAGE, optionType);
        if (options != null) {
            pane.setOptions(options);
        }

        JDialog dialog = pane.createDialog(owner, title);
        dialog.setResizable(false);
        dialog.setVisible(true);
        int result = (pane.getValue() instanceof Integer)
                ? (Integer) pane.getValue()
                : JOptionPane.CLOSED_OPTION;
        dialog.dispose();
        return result;
    }

    /**
     * Places the message icon to the left of the message component (top-aligned) so the
     * popup shows the same glyph as the toolbar button that opened it, independent of the
     * Look-and-Feel's default dialog icon.
     *
     * @param icon             the message icon (build it with {@link #actionIcon})
     * @param messageComponent the message component to place to the right of the icon
     * @return the icon-and-message row
     */
    private static Component withLeadingIcon(Icon icon, Component messageComponent) {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setOpaque(false);
        JLabel iconLabel = new JLabel(icon);
        iconLabel.setVerticalAlignment(JLabel.TOP);
        panel.add(iconLabel, BorderLayout.WEST);
        panel.add(messageComponent, BorderLayout.CENTER);
        return panel;
    }

    /**
     * A {@link #confirm} convenience for the common Yes/No case.
     *
     * @return true when the user chose "Yes"
     */
    public static boolean confirmYesNo(Component owner, Icon icon, String title, String message,
                                       Component extra) {
        return confirm(owner, icon, title, message, JOptionPane.YES_NO_OPTION, null, extra)
                == JOptionPane.YES_OPTION;
    }
}
