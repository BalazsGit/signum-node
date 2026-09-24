package application.module.browser.gui;

import application.module.node.gui.GuiResources;
import application.utils.i18n.I18n;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.awt.geom.Rectangle2D;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * A8: the animated "preparing" screen shown while the CEF engine boots
 * (asynchronous, 2–10 s — plan D4/R3), plus the failed/idle cards.
 * <p>
 * The logo is the app's Signum mark (same SVG the frame's glass pane uses)
 * rendered through jsvg with a 2 s breathing alpha — the same effect family
 * as the {@code glassPanel} library (D12), applied to a dark tile so the
 * white mark stays visible in any app theme.
 */
public final class EngineReadyScreen extends JPanel {

    private static final String CARD_PREPARING = "preparing";
    private static final String CARD_ERROR = "error";
    private static final String CARD_IDLE = "idle";

    private static final Color FALLBACK_TILE_BG = new Color(0x1F, 0x24, 0x2B);
    private static final Color FALLBACK_TILE_BORDER = new Color(0x2A, 0x30, 0x38);

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final LogoPanel logo;
    private final JLabel reasonLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel hintLabel;

    public EngineReadyScreen() {
        super(new BorderLayout());
        setOpaque(false);
        this.logo = new LogoPanel();

        JPanel preparing = new JPanel(new GridLayout(0, 1, 0, 12));
        preparing.setOpaque(false);
        preparing.setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));
        JPanel logoRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        logoRow.setOpaque(false);
        logo.setPreferredSize(new Dimension(140, 140));
        logoRow.add(logo);
        preparing.add(logoRow);
        preparing.add(centerLabel(I18n.get("browser.engine.preparing")));
        preparing.add(centerLabel(I18n.get("browser.engine.preparing.hint")));
        cardHost.add(preparing, CARD_PREPARING);

        JPanel errorCard = new JPanel(new BorderLayout(0, 10));
        errorCard.setOpaque(false);
        errorCard.setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));
        JPanel errorTop = new JPanel(new GridLayout(0, 1, 0, 8));
        errorTop.setOpaque(false);
        errorTop.add(centerLabel(I18n.get("browser.engine.failed.title")));
        errorTop.add(reasonLabel);
        errorCard.add(errorTop, BorderLayout.CENTER);
        hintLabel = centerLabel(I18n.get("browser.engine.failed.hint"));
        errorCard.add(hintLabel, BorderLayout.SOUTH);
        cardHost.add(errorCard, CARD_ERROR);

        cardHost.add(centerLabel(I18n.get("browser.engine.idle")), CARD_IDLE);

        add(cardHost, BorderLayout.CENTER);
        logo.start();
    }

    private static JLabel centerLabel(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setOpaque(false);
        return label;
    }

    public void showPreparing() {
        logo.start();
        cards.show(cardHost, CARD_PREPARING);
    }

    public void showError(String reason) {
        showError(reason, I18n.get("browser.engine.failed.hint"));
    }

    /**
     * Shows the error card with a custom hint (e.g. the JCEF-install hint when
     * the failure kind is {@code MISSING_JCEF}).
     */
    public void showError(String reason, String hint) {
        logo.stop();
        reasonLabel.setText(I18n.get("browser.engine.failed.reason", reason));
        hintLabel.setText(hint);
        cards.show(cardHost, CARD_ERROR);
    }

    public void showIdle() {
        logo.stop();
        cards.show(cardHost, CARD_IDLE);
    }

    /** The Signum mark with a breathing fade (2 s cycle, like the glassPanel breathing animation). */
    private static final class LogoPanel extends JPanel {

        private final BufferedImage image;
        private final Timer timer;
        private final long startedAt = System.currentTimeMillis();

        LogoPanel() {
            setOpaque(false);
            this.image = loadLogo();
            this.timer = new Timer(33, e -> repaint());
            this.timer.setCoalesce(true);
        }

        private static BufferedImage loadLogo() {
            try {
                URL url = LogoPanel.class.getClassLoader().getResource(GuiResources.SIGNUM_NODE_WHITE_SVG);
                if (url == null) {
                    return null;
                }
                SVGLoader loader = new SVGLoader();
                SVGDocument document = loader.load(url);
                Rectangle2D box = document.viewBox();
                int size = 220;
                BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double scale = Math.min(size / box.getWidth(), size / box.getHeight()) * 0.9;
                g.translate((size - box.getWidth() * scale) / 2.0, (size - box.getHeight() * scale) / 2.0);
                g.scale(scale, scale);
                document.render(null, g);
                g.dispose();
                return img;
            } catch (Exception e) {
                return null; // the painted "S" fallback in paintComponent covers this
            }
        }

        void start() {
            if (!timer.isRunning()) {
                timer.start();
            }
        }

        void stop() {
            timer.stop();
        }

        /** Theme-derived tile background (a darkened panel color in light themes). */
        private static Color tileBg() {
            Color c = UIManager.getColor("Panel.background");
            return c != null ? c.darker() : FALLBACK_TILE_BG;
        }

        /** Theme-derived tile border. */
        private static Color tileBorder() {
            Color c = UIManager.getColor("Separator.foreground");
            return c != null ? c : FALLBACK_TILE_BORDER;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(tileBg());
            g2.fillRoundRect(0, 0, w - 1, h - 1, 20, 20);
            g2.setColor(tileBorder());
            g2.drawRoundRect(0, 0, w - 1, h - 1, 20, 20);

            if (image != null) {
                float phase = (float) (((System.currentTimeMillis() - startedAt) % 2000) / 2000.0);
                float alpha = 0.75f + 0.25f * (float) Math.sin(phase * 2 * Math.PI);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.drawImage(image, (w - image.getWidth()) / 2, (h - image.getHeight()) / 2, null);
            } else {
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 48f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("S", (w - fm.stringWidth("S")) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
            }
            g2.dispose();
        }
    }
}