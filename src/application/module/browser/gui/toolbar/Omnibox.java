package application.module.browser.gui.toolbar;

import application.module.browser.core.CefFocusGuard;
import application.module.browser.util.UrlUtils;
import application.utils.i18n.I18n;
import org.cef.browser.CefBrowser;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The address/search bar (F2, N1/N9/A5): one field for both URLs and search
 * queries (Chrome semantics via {@link UrlUtils#normalize}), Ctrl+L focus,
 * Enter navigation, Up/Down popup selection, Esc to close the popup (or
 * cancel — the toolbar routes the remaining Esc to "stop load"). The
 * {@link SecurityIcon} lock (S1/S4) is embedded in the field's left side,
 * Chrome-style: the field keeps its native border and the lock rides on the
 * field (managed bounds), so it always sits inside the omnibox box at the
 * lock's own width — only the lock's rectangle is clickable.
 * <p>
 * Suggestion sources: F2 builds the URL + search entries here
 * ({@code suggestFor}); F3/F4 extend the same list with history and
 * bookmarks. Must be constructed on the EDT.
 */
public final class Omnibox extends JPanel {

    private static final int HEIGHT = 32;
    /**
     * Left pad (in field coordinates) where the lock is pinned — hugging the
     * field's left edge, just inside the border line.
     */
    static final int LOCK_LEFT_PAD = 6;
    /** The gap between the lock's right edge and the URL text. */
    static final int LOCK_TEXT_GAP = 2;

    private final JTextField field;
    /** S1/S4: the lock embedded in the field's left side (may be null). */
    private final SecurityIcon securityIcon;
    private OmniboxPopup popup;
    private final Consumer<String> navigateAction;
    private final BiFunction<Omnibox, String, List<OmniboxPopup.Suggestion>> suggestor;
    /** N10: the omnibox's own input history (Up/Down while the popup is closed). */
    private final OmniboxInputHistory inputHistory = new OmniboxInputHistory();
    private boolean updatingText;
    /**
     * True when the user modified the field text since the last mirror
     * ({@link #showUrl}): then the field holds a real query that a tab switch
     * must not clobber. A focused-but-unedited field (Ctrl+L, a plain click)
     * still follows the active tab.
     */
    private boolean userEdited;
    /** Dismisses the popup when a browser takes the CEF keyboard focus (a page click). */
    private final Consumer<CefBrowser> cefFocusGainedListener = browser -> hidePopup();
    /** The toolkit mouse listener of {@link #installDismissalHooks()} (removal on {@link #dispose()}). */
    private AWTEventListener outsidePressListener;

    /**
     * @param navigateAction invoked with the raw text (Enter) or a suggestion
     *                       target (popup selection)
     * @param suggestor      builds the popup rows for the current input
     */
    public Omnibox(Consumer<String> navigateAction,
                   BiFunction<Omnibox, String, List<OmniboxPopup.Suggestion>> suggestor) {
        this(navigateAction, suggestor, null);
    }

    /**
     * @param navigateAction invoked with the raw text (Enter) or a suggestion
     *                       target (popup selection)
     * @param suggestor      builds the popup rows for the current input
     * @param securityIcon   the lock painted inside the field's left side
     *                       (Chrome-style S1/S4); {@code null} omits it
     */
    public Omnibox(Consumer<String> navigateAction,
                   BiFunction<Omnibox, String, List<OmniboxPopup.Suggestion>> suggestor,
                   SecurityIcon securityIcon) {
        super(new BorderLayout());
        this.navigateAction = navigateAction;
        this.suggestor = suggestor;
        this.securityIcon = securityIcon;

        this.field = new JTextField();
        field.setMargin(new Insets(6, 14, 6, 14));
        // App-consistent rectangular field: the native L&F border (same look as
        // every other input in the application) plus the placeholder painting.
        Border lafBorder = UIManager.getBorder("TextField.border");
        if (lafBorder != null) {
            field.setBorder(BorderFactory.createCompoundBorder(lafBorder, new PlaceholderBorder()));
        } else {
            field.setBorder(new PlaceholderBorder());
        }
        field.setPreferredSize(new Dimension(220, HEIGHT));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!updatingText) {
                    userEdited = true;
                    refreshPopup();
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!updatingText) {
                    userEdited = true;
                    refreshPopup();
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // plain text field — never fires
            }
        });
        field.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                field.selectAll(); // Chrome: focusing the bar selects the whole URL
                // Do not auto-open the suggestion popup on focus: a tab switch
                // can move the keyboard focus into this field, and the dropdown
                // must not pop open on a tab change. It opens on real text entry
                // instead (the document listener above calls refreshPopup()).
            }

            @Override
            public void focusLost(FocusEvent e) {
                hidePopup();
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ENTER -> {
                        hidePopup();
                        if (popup != null && popup.isPopupVisible()) {
                            OmniboxPopup.Suggestion selected =
                                    popup.suggestionAt(popup.getSelectedIndex());
                            if (selected != null) {
                                selectSuggestion(selected);
                                return;
                            }
                        }
                        inputHistory.commit(field.getText()); // N10
                        navigateAction.accept(field.getText());
                    }
                    case KeyEvent.VK_DOWN -> {
                        e.consume();
                        if (popup == null || !popup.isPopupVisible()) {
                            setTextFromHistory(inputHistory.down(field.getText()));
                        } else {
                            moveSelection(1);
                        }
                    }
                    case KeyEvent.VK_UP -> {
                        e.consume();
                        if (popup == null || !popup.isPopupVisible()) {
                            setTextFromHistory(inputHistory.up(field.getText()));
                        } else {
                            moveSelection(-1);
                        }
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        e.consume();
                        hidePopup();
                        field.select(0, 0);
                    }
                    default -> {
                        // let the field handle it
                    }
                }
            }
        });
        add(field, BorderLayout.CENTER);
        if (securityIcon != null) {
            installSecurityIcon();
        }
        this.popup = null; // created lazily — the owner window only exists once shown
        installDismissalHooks();
    }

    /**
     * Embeds the lock in the field's left side (vertically centered) as a
     * plain child with <em>managed bounds</em>: the field keeps the native
     * L&amp;F border, so the lock sits within the omnibox box like Chrome's,
     * and it keeps its own mouse handler (the certificate-details click,
     * S2). The bounds are managed here instead of with a layout manager —
     * a previous OverlayLayout attempt did not keep the icon at the field's
     * left side.
     */
    private void installSecurityIcon() {
        field.setLayout(null); // the lock's bounds are managed, not laid out
        field.add(securityIcon);
        field.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                positionSecurityIcon();
            }
        });
        applySecurityIconInsets();
    }

    /**
     * Keeps the field's left text margin clear of the embedded lock (the
     * placeholder painting reuses this margin, so it stays clear too) and
     * re-places the lock. Called again after an appearance change resizes
     * the icon.
     * <p>
     * The margin is derived <em>only</em> from the lock's geometry, never
     * from {@code getBorderInsets}: FlatLaf folds the field's margin into the
     * border insets, so adding the inset here would be self-referential —
     * every call would inflate the margin (and with it the lock's x and the
     * text start) until the lock and the URL text overlapped.
     */
    public void applySecurityIconInsets() {
        if (securityIcon == null) {
            return;
        }
        int iconWidth = securityIcon.getPreferredSize().width;
        field.setMargin(new Insets(6, LOCK_LEFT_PAD + iconWidth + LOCK_TEXT_GAP, 6, 14));
        positionSecurityIcon();
    }

    /**
     * Pins the lock to the field's left edge (a small fixed pad, just inside
     * the border line), vertically centered, at exactly the glyph's
     * preferred size: the lock occupies only its own width, and only that
     * rectangle is clickable. The fixed pad — instead of the
     * (margin-inflated) border inset — keeps it at the left in every L&amp;F.
     */
    private void positionSecurityIcon() {
        if (securityIcon == null || field.getWidth() <= 0 || field.getHeight() <= 0) {
            return;
        }
        Dimension pref = securityIcon.getPreferredSize();
        int x = LOCK_LEFT_PAD;
        int y = Math.max(0, (field.getHeight() - pref.height) / 2);
        securityIcon.setBounds(x, y, pref.width, pref.height);
    }

    /** The popup, created on first need (the top-level owner must exist then). */
    private OmniboxPopup popup() {
        if (popup == null) {
            java.awt.Container owner = getTopLevelAncestor();
            popup = new OmniboxPopup(owner instanceof Window w ? w : null);
            popup.setSelectionAction(this::selectSuggestion); // a row click navigates
        }
        return popup;
    }

    /** N9: Ctrl+L — focus the field and select its whole content. */
    public void focusAndSelect() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    /** @return the underlying text field (for the toolbar's Esc routing). */
    public boolean hasFieldFocus() {
        return field.isFocusOwner();
    }

    /**
     * Esc routing: when the field has focus, close the popup (or clear the
     * selection) and report the key as consumed; otherwise the toolbar may
     * stop the load (N9).
     *
     * @return {@code true} when the Esc was handled here
     */
    public boolean consumeEscape() {
        if (!hasFieldFocus()) {
            return false;
        }
        hidePopup();
        field.select(0, 0);
        return true;
    }

    /**
     * Mirrors the active tab's URL into the field (tab switch, navigation).
     * The mirror is skipped only when the field is focused <em>and</em> the
     * user actually edited it ({@link #userEdited}): a focused but unedited
     * field (Ctrl+L, a plain click) must follow the tab, otherwise clicking
     * a tab while the field has focus leaves the previous tab's URL behind.
     */
    public void showUrl(String url) {
        if (hasFieldFocus() && userEdited) {
            return;
        }
        hidePopup(); // the suggestions belonged to the previous text
        updatingText = true;
        userEdited = false;
        try {
            field.setText(url == null ? "" : url);
            field.setCaretPosition(field.getText().length());
        } finally {
            updatingText = false;
        }
    }

    /** @return the current field text. */
    public String getText() {
        return field.getText();
    }

    private void refreshPopup() {
        String text = field.getText().trim();
        if (text.isEmpty() || !field.isShowing()) {
            hidePopup();
            return;
        }
        List<OmniboxPopup.Suggestion> items = suggestor.apply(this, text);
        if (items == null || items.isEmpty()) {
            hidePopup();
            return;
        }
        java.awt.Point location = field.getLocationOnScreen();
        popup().showAt(new java.awt.Point(location.x, location.y + field.getHeight()),
                Math.max(240, field.getWidth()), items);
    }

    private void hidePopup() {
        if (popup != null && popup.isPopupVisible()) {
            popup.hidePopup();
        }
    }

    /**
     * Public dismissal hook (tab switch): hides the suggestion popup if it is
     * open. The owning tab view calls this when the tab is switched away, so a
     * popup can never linger over another tab's content. Idempotent.
     */
    public void dismissPopup() {
        hidePopup();
    }

    /**
     * Navigates to a chosen suggestion (Enter on the highlighted row, or a
     * mouse click on a row): dismisses the popup, commits the target to the
     * input history and loads it, then mirrors the target into the field.
     */
    private void selectSuggestion(OmniboxPopup.Suggestion suggestion) {
        hidePopup();
        inputHistory.commit(suggestion.getTarget()); // N10
        navigateAction.accept(suggestion.getTarget());
        updatingText = true; // the mirror must not count as user input
        try {
            field.setText(suggestion.getTarget());
        } finally {
            updatingText = false;
        }
    }

    /**
     * Installs the hooks that dismiss the suggestion popup when the user moves
     * out of the omnibox. The field's {@code focusLost} already covers ordinary
     * AWT focus changes (another focusable component, window deactivation);
     * these two hooks cover the cases where AWT stays silent:
     * <ul>
     *   <li>a click on the native CEF page generates no AWT event, so the
     *       CEF focus-gained signal from {@link CefFocusGuard} dismisses the
     *       popup even when the focus request is vetoed;</li>
     *   <li>a mouse press on a non-focusable area of the chrome leaves the
     *       field's AWT focus intact, so a global press listener dismisses the
     *       popup when the press lands outside it and the field.</li>
     * </ul>
     * The omnibox is long-lived (one per tab, for the tab's whole lifetime),
     * so the hooks are installed once at construction and removed again by
     * {@link #dispose()} when the tab closes.
     */
    private void installDismissalHooks() {
        CefFocusGuard.addFocusGainedListener(cefFocusGainedListener);
        if (GraphicsEnvironment.isHeadless()) {
            return; // no AWT mouse events; the field's focusLost still dismisses
        }
        AWTEventListener outsidePress = event -> {
            if (!(event instanceof MouseEvent press) || press.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }
            OmniboxPopup p = popup;
            if (p == null || !p.isPopupVisible()) {
                return;
            }
            // JDK 25 dropped Component.getBoundsOnScreen(), so build the screen
            // rectangles from location + size (same approach as MenuPopupController).
            Point screen = press.getLocationOnScreen();
            Rectangle popupBounds = new Rectangle(p.getLocationOnScreen(), p.getSize());
            Rectangle fieldBounds = new Rectangle(field.getLocationOnScreen(), field.getSize());
            if (popupBounds.contains(screen) || fieldBounds.contains(screen)) {
                return; // a press on the popup or the field must not dismiss
            }
            hidePopup();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(outsidePress,
                AWTEvent.MOUSE_EVENT_MASK);
        outsidePressListener = outsidePress;
    }

    /**
     * Tab close: removes the global dismissal hooks (the CEF focus-gained
     * listener and the toolkit mouse listener) so a closed tab leaves no
     * listener behind. Idempotent.
     */
    public void dispose() {
        CefFocusGuard.removeFocusGainedListener(cefFocusGainedListener);
        if (outsidePressListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(outsidePressListener);
            outsidePressListener = null;
        }
    }

    private void moveSelection(int delta) {
        OmniboxPopup p = popup;
        if (p == null || !p.isPopupVisible()) {
            return;
        }
        int next = p.getSelectedIndex() + delta;
        if (next < 0) {
            next = p.selectedCount() - 1;
        } else if (next >= p.selectedCount()) {
            next = 0;
        }
        p.setSelectedIndex(next);
    }

    /** N10: applies the input-history text without reopening the suggestion popup. */
    private void setTextFromHistory(String text) {
        updatingText = true;
        try {
            field.setText(text);
            field.setCaretPosition(field.getText().length());
        } finally {
            updatingText = false;
        }
    }

    /**
     * Paints only the placeholder text (D16: i18n-backed) inside the field's
     * margin; the border line itself comes from the native L&F.
     */
    private static final class PlaceholderBorder implements Border {

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (!(c instanceof JTextField tf) || !tf.getText().isEmpty() || c.isFocusOwner()) {
                return;
            }
            g.setColor(UIManager.getColor("Label.disabledForeground") != null
                    ? UIManager.getColor("Label.disabledForeground") : Color.GRAY);
            g.setFont(tf.getFont());
            Insets margin = tf.getMargin();
            int tx = x + (margin != null ? margin.left : 0);
            FontMetrics fm = g.getFontMetrics();
            int ty = y + (height + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(I18n.get("browser.omnibox.placeholder"), tx, ty);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(0, 0, 0, 0);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }
}