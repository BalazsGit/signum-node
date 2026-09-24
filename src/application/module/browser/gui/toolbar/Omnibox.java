package application.module.browser.gui.toolbar;

import application.module.browser.util.UrlUtils;
import application.utils.i18n.I18n;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
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
 * cancel — the toolbar routes the remaining Esc to "stop load").
 * <p>
 * Suggestion sources: F2 builds the URL + search entries here
 * ({@code suggestFor}); F3/F4 extend the same list with history and
 * bookmarks. Must be constructed on the EDT.
 */
public final class Omnibox extends JPanel {

    private static final int HEIGHT = 32;

    private final JTextField field;
    private OmniboxPopup popup;
    private final Consumer<String> navigateAction;
    private final BiFunction<Omnibox, String, List<OmniboxPopup.Suggestion>> suggestor;
    private boolean updatingText;

    /**
     * @param navigateAction  invoked with the raw text (Enter) or a suggestion
     *                        target (popup selection)
     * @param suggestor       builds the popup rows for the current input
     */
    public Omnibox(Consumer<String> navigateAction,
                   BiFunction<Omnibox, String, List<OmniboxPopup.Suggestion>> suggestor) {
        super(new BorderLayout());
        this.navigateAction = navigateAction;
        this.suggestor = suggestor;

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
                    refreshPopup();
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!updatingText) {
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
                refreshPopup();
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
                        if (popup.isPopupVisible()) {
                            OmniboxPopup.Suggestion selected =
                                    popup.suggestionAt(popup.getSelectedIndex());
                            if (selected != null) {
                                navigateAction.accept(selected.getTarget());
                                field.setText(selected.getTarget());
                                return;
                            }
                        }
                        navigateAction.accept(field.getText());
                    }
                    case KeyEvent.VK_DOWN -> {
                        e.consume();
                        moveSelection(1);
                    }
                    case KeyEvent.VK_UP -> {
                        e.consume();
                        moveSelection(-1);
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
        this.popup = null; // created lazily — the owner window only exists once shown
    }

    /** The popup, created on first need (the top-level owner must exist then). */
    private OmniboxPopup popup() {
        if (popup == null) {
            java.awt.Container owner = getTopLevelAncestor();
            popup = new OmniboxPopup(owner instanceof Window w ? w : null);
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
     * Mirrors the active tab's URL into the field (tab switch, navigation)
     * without stealing the user's input while the field is focused.
     */
    public void showUrl(String url) {
        if (hasFieldFocus()) {
            return;
        }
        updatingText = true;
        try {
            field.setText(url == null ? "" : url);
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
        if (popup != null) {
            popup.hidePopup();
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