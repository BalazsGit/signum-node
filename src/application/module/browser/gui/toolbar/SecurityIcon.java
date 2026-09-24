package application.module.browser.gui.toolbar;

import application.module.browser.engine.security.SslStatus;
import application.module.browser.util.UrlUtils;
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
 * The toolbar's security indicator (F2, S1/S4): a green padlock for valid
 * TLS (clickable — S2 opens the certificate details dialog), a gray open
 * padlock for plain HTTP ("not secure"), a red warning triangle on
 * certificate errors, and a plain tooltip for internal {@code signum://}
 * pages.
 * <p>
 * Must be constructed on the EDT; {@link #update} is called from the
 * toolbar on tab events (already on the EDT).
 */
public final class SecurityIcon extends JComponent {

    private static final int ICON_SIZE = 16;
    private static final Color SECURE_GREEN = new Color(0x2E, 0x9E, 0x5B);
    private static final Color ERROR_RED = new Color(0xD9, 0x44, 0x34);

    private final Consumer<String> clickAction;
    private SslStatus status = SslStatus.INSECURE;
    private String url;
    private boolean clickable;

    /**
     * @param clickAction invoked on click with the tab's URL (only when the
     *                    icon is in a clickable state, i.e. valid TLS)
     */
    public SecurityIcon(Consumer<String> clickAction) {
        this.clickAction = clickAction;
        setPreferredSize(new Dimension(28, 28));
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
     */
    public void update(SslStatus status, String url) {
        this.status = status;
        this.url = url;
        String host = UrlUtils.host(url);
        switch (status) {
            case SECURE -> {
                if ("https".equals(UrlUtils.scheme(url))) {
                    clickable = true;
                    setToolTipText(I18n.get("browser.security.secure.tooltip",
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate((getWidth() - ICON_SIZE) / 2, (getHeight() - ICON_SIZE) / 2);
        Color color = switch (status) {
            case SECURE -> SECURE_GREEN;
            case CERT_ERROR -> ERROR_RED;
            case INSECURE -> gray();
        };
        if (status == SslStatus.CERT_ERROR) {
            paintWarning(g2, color);
        } else {
            paintLock(g2, color, status == SslStatus.SECURE);
        }
        g2.dispose();
    }

    private static Color gray() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    private static Color background() {
        Color c = UIManager.getColor("Panel.background");
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

    private static void paintWarning(Graphics2D g2, Color color) {
        java.awt.geom.GeneralPath triangle = new java.awt.geom.GeneralPath();
        triangle.moveTo(8, 1);
        triangle.lineTo(15, 14);
        triangle.lineTo(1, 14);
        triangle.closePath();
        g2.setColor(color);
        g2.fill(triangle);
        g2.setColor(background());
        g2.setStroke(new java.awt.BasicStroke(1.6f));
        g2.drawLine(8, 5, 8, 9);
        g2.fillOval(7, 11, 2, 2);
    }
}

