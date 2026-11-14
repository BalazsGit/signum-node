package application.module.node.gui.wizard;

import application.utils.gui.GuiFontManager;

import javax.swing.JEditorPane;
import javax.swing.text.Document;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * Selectable display text of the node setup wizard.
 * <p>
 * Every display text of the wizard (step titles, keyword headings, engine-detail
 * bodies, descriptions and confirmation summary rows) renders as a non-editable,
 * transparent {@link JEditorPane}: the user can <b>select and copy</b> the text,
 * while the format stays the same as the former labels — the default UI font for
 * the body texts, the bold 1.2× keyword heading font for the titles/keywords,
 * left-aligned, no border.
 * </p>
 * <p>
 * The pane reports its preferred size <b>reflowed at a fixed wrap width</b> (the
 * natural width of an HTML document is the full unwrapped line width, which would
 * overflow the fixed-size wizard dialog); the maximum width is clamped to the same
 * wrap width so the text wraps inside BoxLayout containers as well.
 * </p>
 */
public final class WizardTexts {

    /** The wrap width of the wizard step content (the 780 px dialog minus the step insets). */
    public static final int STEP_WRAP_WIDTH = 720;

    /** The wrap width of the wizard's dialog messages (e.g. the exit confirmation). */
    public static final int DIALOG_WRAP_WIDTH = 520;

    private WizardTexts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Selectable body text (the default UI font) wrapped at the step content width. */
    public static JEditorPane info(String htmlContent) {
        return info(htmlContent, STEP_WRAP_WIDTH);
    }

    /** Selectable body text (the default UI font) wrapped at the given width. */
    public static JEditorPane info(String htmlContent, int wrapWidth) {
        JEditorPane pane = new WrappedTextPane(wrap(htmlContent), wrapWidth);
        GuiFontManager.applyDefaultFont(pane);
        return pane;
    }

    /** Selectable heading (the bold 1.2× keyword font — the step-title/keyword style). */
    public static JEditorPane heading(String plainText) {
        return heading(plainText, STEP_WRAP_WIDTH);
    }

    /** Selectable heading (the bold 1.2× keyword font) wrapped at the given width. */
    public static JEditorPane heading(String plainText, int wrapWidth) {
        JEditorPane pane = new WrappedTextPane(wrap(escape(plainText)), wrapWidth);
        pane.setFont(GuiFontManager.getBoldScaledDefaultFont(WizardStep.KEYWORD_FONT_SCALE));
        return pane;
    }

    /**
     * Wraps an HTML fragment into the margin-free document the panes use, so the
     * text sits exactly where the former label sat (no HTML body indentation).
     */
    public static String wrap(String htmlContent) {
        return "<html><body style='margin:0'>" + (htmlContent == null ? "" : htmlContent) + "</body></html>";
    }

    /** Escapes the HTML special characters of a plain text (for {@link #wrap}). */
    public static String escape(String plainText) {
        if (plainText == null) {
            return "";
        }
        return plainText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A non-editable, non-focus-trapping text pane whose preferred size is reflowed
     * at a fixed wrap width (see the class javadoc).
     */
    private static final class WrappedTextPane extends JEditorPane {

        private final int wrapWidth;

        WrappedTextPane(String html, int wrapWidth) {
            super("text/html", html);
            this.wrapWidth = wrapWidth;
            // Non-editable JEditorPane: the display text is NOT typeable but
            // stays SELECTABLE (copyable) like every other text of the
            // application — that is the intended display-text behavior of the
            // wizard (a non-editable editor pane is selectable by default).
            setEditable(false);
            setFocusable(false);
            setOpaque(false);
            setBorder(null);
            // Note: HONOR_DISPLAY_PROPERTIES is deliberately NOT set — the pane's
            // component font (the default UI font / the 1.2× bold heading font) must
            // drive the rendering, not the HTML document's display properties.
            setCaretPosition(0);
        }

        @Override
        public Dimension getPreferredSize() {
            try {
                // The document's natural (unwrapped) width can far exceed the wizard's
                // content width — report the size reflowed at the fixed wrap width.
                // The panes render in a single font (the component font), so the
                // wrap is measured with the font metrics (headless-safe, no view
                // tree needed — the detached view of this JDK reports no children).
                Document doc = getDocument();
                String text = doc.getText(doc.getStartPosition().getOffset(), doc.getLength());
                java.awt.FontMetrics fm = getFontMetrics(getFont());
                int lines = 0;
                for (String line : reflowedLines(text)) {
                    lines += wrappedLineCount(line, fm);
                }
                if (lines == 0) {
                    lines = 1; // an empty text still occupies one line height
                }
                return new Dimension(wrapWidth, lines * fm.getHeight());
            } catch (Exception e) {
                return super.getPreferredSize();
            }
        }

        /**
         * The text's logical lines: {@code <br>} breaks a line, every other tag
         * (the document wrapper) is dropped, the HTML entities are decoded.
         * (The serialized document's own line breaks/indentation are NOT line
         * breaks of the rendered text — they are collapsed like every other
         * whitespace.)
         */
        private static List<String> reflowedLines(String documentText) {
            String text = documentText == null ? "" : documentText;
            text = text.replaceAll("(?i)<br\\s*/?>", "\u0000"); // sentinel: a real break
            text = text.replaceAll("<[^>]*>", " ");
            text = decodeEntities(text);
            text = text.replaceAll("\\s+", " "); // collapse the serialization's line breaks too
            String[] raw = text.split("\u0000", -1);
            List<String> out = new ArrayList<>();
            for (String r : raw) {
                out.add(r.trim());
            }
            return out;
        }

        /**
         * The number of rendered lines of a (tag-free) line of text word-wrapped
         * at the pane's wrap width (CSS-style word wrap on the spaces).
         */
        private int wrappedLineCount(String line, java.awt.FontMetrics fm) {
            if (line.isEmpty()) {
                return 1;
            }
            int width = 0;
            int lines = 1;
            for (String word : line.split(" ", -1)) {
                int wordWidth = fm.stringWidth(word);
                if (width == 0) {
                    width = wordWidth;
                    while (width > wrapWidth) { // a single word wider than the pane
                        width -= wrapWidth;
                        lines++;
                    }
                } else if (width + fm.stringWidth(" ") + wordWidth > wrapWidth) {
                    width = wordWidth;
                    lines++;
                    while (width > wrapWidth) {
                        width -= wrapWidth;
                        lines++;
                    }
                } else {
                    width += fm.stringWidth(" ") + wordWidth;
                }
            }
            return lines;
        }

        /** Decodes the HTML entities the document serializer emits. */
        private static String decodeEntities(String text) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);").matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String code = m.group(1);
                int cp = (code.startsWith("x") || code.startsWith("X"))
                        ? Integer.parseInt(code.substring(1), 16)
                        : Integer.parseInt(code);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) cp)));
            }
            m.appendTail(sb);
            return sb.toString()
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&nbsp;", " ");
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(wrapWidth, Integer.MAX_VALUE);
        }
    }
}
