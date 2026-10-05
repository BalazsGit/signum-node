package application.module.browser.gui.toolbar;

import application.module.browser.core.CefFocusGuard;
import application.module.browser.util.UrlUtils;
import application.utils.i18n.I18n;
import com.formdev.flatlaf.FlatClientProperties;
import org.cef.browser.CefBrowser;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.HierarchyEvent;
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
 * {@link SecurityIcon} lock (S1/S4) and the bookmark star (B1) ride inside
 * the field via FlatLaf's official {@code TEXT_FIELD_LEADING_COMPONENT} /
 * {@code TEXT_FIELD_TRAILING_COMPONENT} client properties — the field's UI
 * (re-)adds and lays them out on <em>every</em> UI install (including the
 * full re-init of {@code FlatLaf.updateUI()} on Appearance changes), so they
 * can never be lost by a font-size change, and the URL text insets follow
 * them automatically.
 * <p>
 * Suggestion sources: F2 builds the URL + search entries here
 * ({@code suggestFor}); F3/F4 extend the same list with history and
 * bookmarks. Must be constructed on the EDT.
 */
public final class Omnibox extends JPanel {

    /** The field's top/bottom text margin (the vertical padding inside the border). */
    static final int FIELD_V_MARGIN = 6;

    /**
     * The minimum width of the field (pixels): the row's initial preferred
     * size stays stable with an empty field; longer URLs simply stretch it.
     * The HEIGHT is deliberately never pinned — the UI delegate computes
     * exactly what the current font needs, so the box always fits the text
     * (a guessed factor clipped the descenders at larger Appearance fonts).
     */
    static final int MIN_WIDTH = 220;
    /** The gap between the lock's right edge and the URL text. */
    static final int LOCK_TEXT_GAP = 2;
    /** The gap between the URL text and the star's left edge. */
    static final int STAR_TEXT_GAP = 2;
    /** The plain margin of the field's left/right text side (no embedded component). */
    static final int PLAIN_MARGIN = 14;

    private final JTextField field;
    /** S1/S4: the lock embedded in the field's left side (may be null). */
    private final SecurityIcon securityIcon;
    /**
     * F4 (B1): the bookmark star embedded in the field's RIGHT side
     * (Chrome-style, always available on every page type — the toolbar
     * syncs its visibility/state); may be null.
     */
    private final JComponent trailingButton;
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
        this(navigateAction, suggestor, securityIcon, null);
    }

    /**
     * @param navigateAction invoked with the raw text (Enter) or a suggestion
     *                       target (popup selection)
     * @param suggestor      builds the popup rows for the current input
     * @param securityIcon   the lock painted inside the field's left side
     *                       (Chrome-style S1/S4); {@code null} omits it
     * @param trailingButton the button painted inside the field's right
     *                       side (the bookmark star, F4 B1); {@code null}
     *                       omits it
     */
    public Omnibox(Consumer<String> navigateAction,
                   BiFunction<Omnibox, String, List<OmniboxPopup.Suggestion>> suggestor,
                   SecurityIcon securityIcon,
                   JComponent trailingButton) {
        super(new BorderLayout());
        this.navigateAction = navigateAction;
        this.suggestor = suggestor;
        this.securityIcon = securityIcon;
        this.trailingButton = trailingButton;

        // The field's font follows the app's UI font (Appearance settings);
        // refreshAppearance() re-applies it on appearance changes. No explicit
        // size is set: the preferred HEIGHT always follows the current font
        // (Field#getPreferredSize), so the text never clips at any size.
        this.field = new Field(this);
        Font uiFont = UIManager.getFont("TextField.font");
        if (uiFont != null) {
            field.setFont(uiFont);
        }
        field.setMargin(new Insets(FIELD_V_MARGIN, PLAIN_MARGIN, FIELD_V_MARGIN, PLAIN_MARGIN));
        // App-consistent rectangular field: the native L&F border (same look as
        // every other input in the application) plus the placeholder painting.
        Border lafBorder = UIManager.getBorder("TextField.border");
        if (lafBorder != null) {
            field.setBorder(BorderFactory.createCompoundBorder(lafBorder, new PlaceholderBorder()));
        } else {
            field.setBorder(new PlaceholderBorder());
        }
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
        if (trailingButton != null) {
            installTrailingButton();
        }
        this.popup = null; // created lazily — the owner window only exists once shown
        installDismissalHooks();
    }

    /**
     * Embeds the lock in the field's left side via FlatLaf's official
     * leading-component API: the field's UI adds it on every UI install
     * (including the full re-init of {@code FlatLaf.updateUI()} on
     * appearance changes — a plain {@code field.add()} did NOT survive that
     * re-init: the children came back parentless and vanished) and lays it
     * out itself (left edge, full field height, its own preferred width).
     * The lock keeps its native border and its own mouse handler (the
     * certificate-details click, S2).
     */
    private void installSecurityIcon() {
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_COMPONENT, securityIcon);
        applySecurityIconInsets();
    }

    /**
     * F4 (B1): embeds the trailing button (the bookmark star) in the field's
     * right side via FlatLaf's official trailing-component API — the same
     * (re-)install-on-every-UI-update guarantee as the lock, so an
     * appearance change can never make it vanish. A hidden star is simply
     * not placed (FlatLaf skips invisible components in layout and insets).
     */
    private void installTrailingButton() {
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, trailingButton);
        // A hidden browser tab is re-shown at the same size (no resize
        // event): make sure the field's layout is flushed again on every
        // shown-state change, so the embedded components are always placed.
        field.addHierarchyListener(e -> {
            if (e.getChangeFlags() == HierarchyEvent.SHOWING_CHANGED) {
                field.revalidate();
            }
        });
        // The right text margin follows the star's visibility.
        trailingButton.addHierarchyListener(e -> {
            if (e.getChangeFlags() == HierarchyEvent.SHOWING_CHANGED) {
                applySecurityIconInsets();
            }
        });
        applySecurityIconInsets();
    }

    /**
     * Sets the field's text margins so the URL text is separated from the
     * embedded components by a small gap (the components' OWN widths are
     * already accounted for by FlatLaf's text insets — adding them here
     * again would double the padding). The right gap follows the star's
     * VISIBILITY (it is kept visible on every page type — on non-bookmarkable
     * schemes it simply stays outlined and inert). Called again after an
     * appearance change resizes the glyphs; idempotent.
     */
    public void applySecurityIconInsets() {
        int left = securityIcon != null ? LOCK_TEXT_GAP : PLAIN_MARGIN;
        int right = trailingButton != null && trailingButton.isVisible()
                ? STAR_TEXT_GAP : PLAIN_MARGIN;
        field.setMargin(new Insets(FIELD_V_MARGIN, left, FIELD_V_MARGIN, right));
        field.revalidate();
        field.repaint();
    }

    /**
     * Appearance change hook: re-applies the app's UI font to the field. The
     * field's height follows the font automatically (see {@link Field#
     * getPreferredSize()}), so only revalidate is needed to let the row pick
     * up the new preferred height; the glyphs are refreshed by the toolbar.
     */
    void refreshAppearance() {
        Font font = UIManager.getFont("TextField.font");
        if (font != null && !font.equals(field.getFont())) {
            field.setFont(font);
        }
        revalidate();
        repaint();
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
     * The omnibox field: the preferred WIDTH never drops below
     * {@link #MIN_WIDTH} (the row's initial size stays stable), and the
     * preferred HEIGHT is always the UI delegate's exact requirement for the
     * current font — never a guessed factor, so the text (descenders
     * included) always fits after any Appearance font-size change.
     */
    private static final class Field extends JTextField {

        Field(Omnibox omnibox) {
            super(0);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(MIN_WIDTH, d.width), d.height);
        }
    }

    /**
     * Paints only the placeholder text (D16: i18n-backed) inside the field's
     * margin; the border line itself comes from the native L&F. When a
     * leading component (the lock) is embedded, the placeholder starts
     * after it — the text margin no longer reserves the lock's width
     * (FlatLaf's insets do that for the URL text).
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
            // start after any left-embedded component (the lock), not under it
            for (Component child : tf.getComponents()) {
                if (child.isVisible() && child.getX() < width / 2) {
                    tx = Math.max(tx, x + child.getX() + child.getWidth() + LOCK_TEXT_GAP);
                }
            }
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
