package application.module.browser.gui.toolbar;

import application.module.browser.engine.security.SslStatus;
import application.module.browser.util.UrlUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.i18n.I18n;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.UIManager;
import java.util.function.Consumer;

/**
 * The toolbar's security indicator (F2, S1/S4) — Chrome-style rules: a
 * <em>green closed</em> padlock for valid TLS (clickable — S2 opens the
 * certificate details dialog), a <em>yellow open</em> padlock for plain HTTP
 * ("not secure"), a <em>red open</em> padlock on certificate errors, and a
 * plain tooltip for internal {@code signum://} pages. The icon is embedded
 * in the omnibox's field (left side) and follows the app's toolbar icon
 * size ({@link #refreshSize()} on appearance changes).
 * <p>
 * Must be constructed on the EDT; {@link #update} is called from the
 * toolbar on tab events (already on the EDT).
 */
public final class SecurityIcon extends JComponent {

    /** The painted geometry's design space (the draw code uses 16 px units). */
    private static final int DESIGN_SIZE = 16;
    /** Transparent padding around the glyph (keeps the hit area usable). */
    private static final int PAD = 8;
    /**
     * The rendered icon size follows the app's toolbar icon size (Appearance
     * settings), the same convention as the other modules' icons; it is
     * re-read by {@link #refreshSize()} when the appearance changes.
     */
    private int iconSize = Math.max(14, (int) Math.round(GuiConstants.getToolBarIconSize()));

    private final Consumer<String> clickAction;
    private SslStatus status = SslStatus.INSECURE;
    private String url;
    private boolean clickable;
    /** S5: an insecure subresource was loaded on this (https) page. */
    private boolean mixedContent;

    /**
     * @param clickAction invoked on click with the tab's URL (only when the
     *                    icon is in a clickable state, i.e. valid TLS)
     */
    public SecurityIcon(Consumer<String> clickAction) {
        this.clickAction = clickAction;
        applyPreferredSize();
        setOpaque(false);
        setFocusable(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (clickable) {
                    clickAction.accept(url);
                }
            }
        });
    }

    /**
     * Refreshes the icon, tooltip and clickable state.
     *
     * @param status the tab's security state
     * @param url    the tab's URL (for the tooltip and the click action)
     * @param mixedContent S5: the page loaded an insecure subresource
     */
    public void update(SslStatus status, String url, boolean mixedContent) {
        this.status = status;
        this.url = url;
        this.mixedContent = mixedContent && status == SslStatus.SECURE;
        String host = UrlUtils.host(url);
        switch (status) {
            case SECURE -> {
                if ("https".equals(UrlUtils.scheme(url))) {
                    clickable = true;
                    setToolTipText(mixedContent
                            ? I18n.get("browser.security.mixedContent.tooltip",
                                    host.isEmpty() ? "—" : host)
                            : I18n.get("browser.security.secure.tooltip",
                                    host.isEmpty() ? "—" : host));
                } else {
                    clickable = false;
                    setToolTipText(I18n.get("browser.security.internal.tooltip"));
                }
            }
            case INSECURE -> {
                clickable = false;
                setToolTipText(I18n.get("browser.security.insecure.tooltip",
                        host.isEmpty() ? "—" : host));
            }
            case CERT_ERROR -> {
                clickable = false;
                setToolTipText(I18n.get("browser.security.certError.tooltip",
                        host.isEmpty() ? "—" : host));
            }
        }
        setCursor(clickable
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        repaint();
    }

    /** @return the current status (for the toolbar's dialog decision). */
    public SslStatus getStatus() {
        return status;
    }

    /**
     * Appearance change hook: re-reads the app's toolbar icon size and
     * resizes the component. The omnibox re-measures its own insets for the
     * icon (it embeds this component inside the field).
     */
    public void refreshSize() {
        int size = Math.max(14, (int) Math.round(GuiConstants.getToolBarIconSize()));
        if (size == iconSize) {
            return;
        }
        iconSize = size;
        applyPreferredSize();
        revalidate();
        repaint();
    }

    private void applyPreferredSize() {
        setPreferredSize(new Dimension(iconSize + 2 * PAD, iconSize));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate((getWidth() - iconSize) / 2f, (getHeight() - iconSize) / 2f);
        g2.scale(iconSize / (float) DESIGN_SIZE, iconSize / (float) DESIGN_SIZE);
        if (status == SslStatus.CERT_ERROR) {
            // Chrome-style rule: a failed certificate renders a RED open lock
            paintLock(g2, errorRed(), false);
        } else {
            // Chrome-style rule: valid TLS = green closed lock, plain HTTP
            // (no SSL) = yellow open lock; S5: mixed content warns (orange +
            // badge)
            paintLock(g2, mixedContent ? warnOrange() : (status == SslStatus.SECURE
                    ? secureGreen() : insecureYellow()), status == SslStatus.SECURE);
            if (mixedContent) {
                paintMixedBadge(g2);
            }
        }
        g2.dispose();
    }

    /** S5: the "!" badge in the lock's corner (a small filled circle + stem). */
    private static void paintMixedBadge(Graphics2D g2) {
        Color badge = warnOrange();
        g2.setColor(badge);
        g2.fillOval(11, 0, 5, 5);
        g2.setColor(background());
        g2.setStroke(new java.awt.BasicStroke(0.9f));
        g2.drawLine(13, 2, 13, 3);
        g2.fillOval(12, 4, 2, 2);
    }

    /** The mixed-content warning orange (fixed — the palette has no amber). */
    private static Color warnOrange() {
        return new Color(0xE8, 0x9A, 0x2C);
    }

    /** The Chrome-style "not secure" yellow for plain HTTP (open lock). */
    private static Color insecureYellow() {
        return new Color(0xE8, 0xB9, 0x2C);
    }

    /** The app palette's "ready/running" green (falls back to a fixed green). */
    private static Color secureGreen() {
        Color c = GuiColors.getReady();
        return c != null ? c : new Color(0x2E, 0x9E, 0x5B);
    }

    /** The app palette's error red (falls back to a fixed red). */
    private static Color errorRed() {
        Color c = GuiColors.getContrastRed();
        return c != null ? c : new Color(0xD9, 0x44, 0x34);
    }

    /**
     * The surface behind the glyph (the keyhole cut-outs are painted in it).
     * The icon is embedded in the omnibox's text field, so the field's
     * background wins; the panel background is the fallback.
     */
    private static Color background() {
        Color c = UIManager.getColor("TextField.background");
        if (c == null) {
            c = UIManager.getColor("Panel.background");
        }
        return c != null ? c : Color.WHITE;
    }

    private static void paintLock(Graphics2D g2, Color color, boolean closed) {
        g2.setColor(color);
        g2.fillRoundRect(3, 7, 10, 8, 3, 3);
        g2.setColor(background());
        g2.fillOval(7, 9, 2, 2); // keyhole dot
        g2.drawLine(8, 10, 8, 12); // keyhole stem
        g2.setColor(color);
        g2.setStroke(new java.awt.BasicStroke(1.6f));
        if (closed) {
            g2.drawArc(5, 1, 6, 8, 0, 180); // closed shackle (full top arc)
        } else {
            g2.drawArc(5, 1, 6, 8, 90, 90); // open shackle (left half only)
        }
    }

}

