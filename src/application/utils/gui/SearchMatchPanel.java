package application.utils.gui;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Unified "Search" titled box: a compact, left-aligned live-find panel with a
 * text field, a "current/total" match counter, and previous/next match
 * chevron buttons.
 * <p>
 * <h3>UI Layout</h3>
 * <pre>
 * ┌ Search ─────────────────────────────────────┐
 * │ [text field ............] [1/23] [▲] [▼]    │
 * └─────────────────────────────────────────────┘
 * </pre>
 * </p>
 * <p>
 * The match counter and the chevron buttons are <b>hidden by default</b> (and
 * stay hidden until the owning panel drives them visible from the live match
 * count) — a fresh search box must not show navigation affordances before any
 * search has produced a match.
 * </p>
 * <p>
 * <h3>Listeners</h3>
 * The panel is pure presentation: it reports events, the owner decides what a
 * search means (highlight + match navigation in the console, row filtering +
 * match navigation in the node configuration).
 * </p>
 * <ul>
 * <li><b>text listener</b> — fired after every change (each keystroke, or a
 * programmatic {@link #setSearchText(String)});</li>
 * <li><b>navigation listener</b> — fired by the chevron buttons
 * ({@code true} = next match, {@code false} = previous match);</li>
 * <li><b>enter listener</b> — fired when the user presses Enter in the field
 * (the owner interprets the first Enter as "scroll to the first match").</li>
 * </ul>
 * <p>
 * <h3>Thread Safety</h3>
 * All mutations must occur on the Swing EDT.
 * </p>
 *
 * @see application.module.node.gui.ConsoleFilterHeader
 * @see application.module.node.gui.configuration.NodeConfigurationPanel
 */
public final class SearchMatchPanel extends JPanel {

    private final JTextField searchField;
    private final JLabel matchLabel;
    private final JButton prevButton;
    private final JButton nextButton;

    private Consumer<String> searchTextListener;
    private Consumer<Boolean> searchNavigationListener;
    private Runnable searchEnterListener;

    /**
     * Creates the unified search box.
     *
     * @param fieldTooltip context-specific tooltip for the text field
     *                     (e.g. what the query matches in this view)
     */
    public SearchMatchPanel(String fieldTooltip) {
        setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        // No extra EmptyBorder here: the 4px vertical spacing belongs to the
        // scroll wrapper around the owner's row (like the console filter
        // header), so it also clears the wrapper's horizontal scrollbar when
        // it appears.
        setBorder(new TitledBorder("Search"));

        // Live "find" input — plain text only, no extra settings. Fires on
        // every keystroke via the DocumentListener below.
        searchField = new JTextField(16);
        if (fieldTooltip != null) {
            searchField.setToolTipText(fieldTooltip);
        }
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fireSearchTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fireSearchTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fireSearchTextChanged();
            }
        });
        // Enter key: jump to / advance the active match (the owning panel
        // interprets the first Enter as "scroll to the first match").
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    fireSearchEnter();
                }
            }
        });

        int iconSize = GuiIcons.sizeSmall();

        // Chevron UP: previous match
        prevButton = createChevronButton(GuiIcons.chevronUp(iconSize), "Previous match");
        prevButton.addActionListener(e -> fireSearchNavigation(false));

        // Chevron DOWN: next match
        nextButton = createChevronButton(GuiIcons.chevronDown(iconSize), "Next match");
        nextButton.addActionListener(e -> fireSearchNavigation(true));

        // Match counter: "current/total" (e.g. "1/23") while a search is
        // active. Sized to fit the current text (see
        // {@link #setMatchIndicatorText(String)}) so it hugs both the search
        // field and the chevron buttons; hidden when there is no active query.
        // NOTE: reduced JDK has no java.awt.SwingConstants — use JLabel.LEFT instead.
        matchLabel = new JLabel("", JLabel.LEFT);
        matchLabel.setToolTipText("Current match / total matches");
        matchLabel.setVisible(false);

        add(searchField);
        add(matchLabel);
        add(prevButton);
        add(nextButton);
    }

    /**
     * Creates a flat, non-focusable chevron button (icon-only, no fill, thin
     * border) that starts hidden — the owning panel shows it only while the
     * current search has matches to navigate to.
     */
    private static JButton createChevronButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setBorder(new EmptyBorder(2, 2, 2, 2));
        button.setContentAreaFilled(false);
        button.setVisible(false);
        return button;
    }

    // ── Listeners ──────────────────────────────────────────────────────────

    /**
     * Registers a listener invoked with the current search text after every
     * change (each keystroke). May be null to clear.
     */
    public void setSearchTextListener(Consumer<String> listener) {
        this.searchTextListener = listener;
    }

    /**
     * Registers a listener for chevron navigation:
     * {@code true} = next match, {@code false} = previous match.
     * May be null to clear.
     */
    public void setSearchNavigationListener(Consumer<Boolean> listener) {
        this.searchNavigationListener = listener;
    }

    /**
     * Registers a listener invoked when the user presses Enter in the search
     * field. May be null to clear.
     */
    public void setEnterListener(Runnable listener) {
        this.searchEnterListener = listener;
    }

    /** Fires the search text listener with the current field text. EDT-only. */
    private void fireSearchTextChanged() {
        if (searchTextListener != null) {
            searchTextListener.accept(getSearchText());
        }
    }

    /** Fires the search navigation listener (true = next, false = previous). EDT-only. */
    private void fireSearchNavigation(boolean next) {
        if (searchNavigationListener != null) {
            searchNavigationListener.accept(next);
        }
    }

    /** Fires the search Enter listener. EDT-only. */
    private void fireSearchEnter() {
        if (searchEnterListener != null) {
            searchEnterListener.run();
        }
    }

    // ── Accessors / Mutators ───────────────────────────────────────────────

    /** @return the current search text (never null) */
    public String getSearchText() {
        return searchField.getText();
    }

    /**
     * Sets the search text and fires the text listener (unconditionally, so a
     * programmatic change re-derives the owner's state — the document
     * listener alone would not fire when the text is set to its current
     * value).
     */
    public void setSearchText(String text) {
        searchField.setText(text != null ? text : "");
        fireSearchTextChanged();
    }

    /** @return the search text field (e.g. for direct text changes without listener fire) */
    public JTextField getSearchField() {
        return searchField;
    }

    /** @return the current match indicator text (empty while the label is hidden) */
    public String getMatchIndicatorText() {
        return matchLabel.getText();
    }

    /** @return the match indicator label (visibility/text) */
    public JLabel getMatchIndicatorLabel() {
        return matchLabel;
    }

    /**
     * Updates the match counter label shown next to the search field.
     * A non-empty text (e.g. {@code "1/23"}) makes the label visible;
     * a null or empty text hides it. Must be called on the Swing EDT.
     *
     * @param text the indicator text (null or empty hides the label)
     */
    public void setMatchIndicatorText(String text) {
        if (text == null || text.isEmpty()) {
            matchLabel.setText("");
            matchLabel.setVisible(false);
        } else {
            // Size the label to the current text (small fixed width would
            // leave a visible gap between the number and the chevrons for
            // short values like "1/25"). The chevrons shift by at most one
            // character width when the digit count changes (e.g. 9 -> 10).
            FontMetrics fm = matchLabel.getFontMetrics(matchLabel.getFont());
            Dimension matchSize = new Dimension(fm.stringWidth(text) + 4, fm.getHeight());
            matchLabel.setMinimumSize(matchSize);
            matchLabel.setPreferredSize(matchSize);
            matchLabel.setMaximumSize(matchSize);
            matchLabel.setText(text);
            matchLabel.setVisible(true);
        }
    }

    /** @return the chevron button navigating to the previous match */
    public JButton getPreviousMatchButton() {
        return prevButton;
    }

    /** @return the chevron button navigating to the next match */
    public JButton getNextMatchButton() {
        return nextButton;
    }

    /**
     * Shows or hides the search navigation chevron buttons (previous/next
     * match). They are only useful while the current search has at least one
     * match, so the owning panel drives this from the match count.
     * Must be called on the Swing EDT.
     *
     * @param visible true while the search has matches
     */
    public void setChevronsVisible(boolean visible) {
        prevButton.setVisible(visible);
        nextButton.setVisible(visible);
        // FlowLayout only reflows on the next layout pass — revalidate so the
        // buttons do not leave a ghost gap (or overlap) while hidden.
        revalidate();
        Container parent = getParent();
        if (parent != null) {
            parent.revalidate();
        }
    }
}
