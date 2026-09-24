package application.module.browser.gui.tabstrip;

import application.module.browser.gui.animation.TabAnimation;
import application.module.browser.model.tab.BrowserTab;
import application.utils.i18n.I18n;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;

/**
 * Renders one tab cell (T5): icon/placeholder + title + loading spinner +
 * close button, with the hover states (A2), the A1 birth scale+fade and the
 * T3 drag placeholder/drop indicator.
 * <p>
 * F1 note on the icon: the pinned JCEF fork has no favicon callback, so the
 * deterministic letter-tile placeholder is what users see; the favicon path
 * (cache + draw) is already in place for a later fetch-based source.
 * <p>
 * The palette is derived from the active look &amp; feel, so the strip follows
 * the app theme (light/dark) without hardcoding colors per theme.
 */
final class ChromeTabRenderer extends JComponent implements ListCellRenderer<BrowserTab> {

    static final int HEIGHT = 36;
    /** Hit area of the close button at the cell's right edge (px). */
    static final int CLOSE_ZONE = 26;
    private static final int PADDING_X = 10;
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 6;
    private static final float TITLE_FONT_SIZE = 12f;

    private final TabAnimation animation;
    private final Map<String, Image> iconCache = new HashMap<>();

    // L&F-derived palette
    private final Color bgInactive = uiColor("control", new Color(0xE8, 0xEA, 0xED));
    private final Color bgHover = lighter(bgInactive, 0.06f);
    private final Color bgActive = lighter(bgInactive, 0.14f);
    private final Color bgDragSource = darker(bgInactive, 0.06f);
    private final Color accent = new Color(0x4F, 0x8C, 0xFF);
    private final Color textColor = uiColor("controlText", new Color(0x20, 0x21, 0x24));
    // JDK 25 removed Color.deriveColor/deriveAlpha — mixed manually.
    private final Color mutedColor = mix(textColor, bgInactive, 0.45f);
    private final Color closeDanger = new Color(0xE5, 0x48, 0x4D);

    private BrowserTab tab;
    private int cellIndex = -1;
    private boolean selected;
    private boolean hover;
    private boolean hoverClose;
    private int dragSourceIndex = -1;
    private int dragTargetIndex = -1;

    ChromeTabRenderer(TabAnimation animation) {
        this.animation = animation;
        setOpaque(false);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends BrowserTab> list, BrowserTab value,
                                                  int index, boolean isSelected, boolean cellHasFocus) {
        this.tab = value;
        this.cellIndex = index;
        this.selected = isSelected;
        setToolTipText(null);
        return this;
    }

    /** A2: hover state (the close flag highlights the close button). */
    void setHover(boolean hover, boolean hoverClose) {
        this.hover = hover;
        this.hoverClose = hoverClose;
    }

    /** T3: drag state (-1 = inactive). */
    void setDragState(int sourceIndex, int targetIndex) {
        this.dragSourceIndex = sourceIndex;
        this.dragTargetIndex = targetIndex;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (tab == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();

        // A1: birth scale+fade around the cell center.
        float birth = animation.birthProgress(tab.getId());
        if (birth >= 0f) {
            float scale = 0.92f + 0.08f * birth;
            g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.4f + 0.6f * birth));
            AffineTransform transform = g2.getTransform();
            g2.translate(w / 2.0, h / 2.0);
            g2.scale(scale, scale);
            g2.translate(-w / 2.0, -h / 2.0);
            g2.setTransform(transform);
        }

        // T3: the dragged tab leaves an empty slot.
        if (cellIndex == dragSourceIndex) {
            g2.setColor(bgDragSource);
            g2.fillRect(0, 0, w, h);
            g2.dispose();
            return;
        }

        // Background (A2 hover + active state).
        Color bg = selected ? bgActive : (hover ? bgHover : bgInactive);
        g2.setColor(bg);
        g2.fillRect(0, 0, w, h);
        if (selected) {
            g2.setColor(accent);
            g2.fillRect(0, 0, w, 2);
        }
        if (cellIndex == dragTargetIndex && dragSourceIndex >= 0) {
            g2.setColor(accent);
            g2.fillRect(0, 0, 2, h);
        }

        int iconX = PADDING_X;
        int iconY = (h - ICON_SIZE) / 2;
        if (tab.isLoading()) {
            drawSpinner(g2, iconX + ICON_SIZE / 2, iconY + ICON_SIZE / 2, ICON_SIZE);
        } else {
            Image icon = iconFor(tab);
            if (icon != null) {
                g2.drawImage(icon, iconX, iconY, ICON_SIZE, ICON_SIZE, null);
            } else {
                drawPlaceholder(g2, tab, iconX, iconY);
            }
        }

        // Title (muted while loading).
        g2.setFont(getFont().deriveFont(Font.PLAIN, TITLE_FONT_SIZE));
        g2.setColor(tab.isLoading() ? mutedColor : textColor);
        FontMetrics fm = g2.getFontMetrics();
        int textX = iconX + ICON_SIZE + ICON_GAP;
        int textWidth = w - textX - PADDING_X - CLOSE_ZONE;
        String title = ellipsize(displayTitle(tab), fm, textWidth);
        g2.drawString(title, textX, iconY + ICON_SIZE / 2 + fm.getAscent() / 2 - 1);

        // Close button (A2: red disc on hover).
        int closeX = w - PADDING_X - 10;
        int closeY = h / 2;
        if (hoverClose) {
            g2.setColor(closeDanger);
            g2.fillOval(closeX - 4, closeY - 8, 18, 18);
            g2.setColor(Color.WHITE);
        } else {
            g2.setColor(mutedColor);
        }
        g2.setStroke(new BasicStroke(1.4f));
        g2.drawLine(closeX, closeY - 4, closeX + 8, closeY + 4);
        g2.drawLine(closeX + 8, closeY - 4, closeX, closeY + 4);

        // T3: brief highlight after a drop.
        float move = animation.moveProgress(tab.getId());
        if (move >= 0f) {
            g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.25f * (1f - move)));
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);
        }
        g2.dispose();
    }

    // ------------------------------------------------------------------
    // Drawing helpers
    // ------------------------------------------------------------------

    private void drawSpinner(Graphics2D g2, int cx, int cy, int size) {
        int r = size - 3;
        int x = cx - size / 2;
        int y = cy - size / 2;
        g2.setStroke(new BasicStroke(2f));
        g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
        g2.setColor(mutedColor);
        g2.drawArc(x, y, r, r, 0, 360);
        g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1f));
        int angle = (int) ((System.currentTimeMillis() / 16) % 360);
        g2.setColor(accent);
        g2.drawArc(x, y, r, r, angle, 250);
    }

    private void drawPlaceholder(Graphics2D g2, BrowserTab tab, int x, int y) {
        String host = hostOf(tab.getUrl());
        String letter = (host != null && !host.isEmpty()) ? host.substring(0, 1).toUpperCase() : "?";
        int hue = Math.floorMod(tab.getUrl() != null ? tab.getUrl().hashCode() : 0, 360);
        g2.setColor(Color.getHSBColor(hue / 360f, 0.35f, 0.45f));
        g2.fillRoundRect(x, y, ICON_SIZE, ICON_SIZE, 5, 5);
        g2.setColor(Color.WHITE);
        g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(letter,
                x + (ICON_SIZE - fm.stringWidth(letter)) / 2,
                y + (ICON_SIZE + fm.getAscent() - fm.getDescent()) / 2 - 1);
    }

    private Image iconFor(BrowserTab tab) {
        byte[] favicon = tab.getFavicon();
        if (favicon == null || favicon.length == 0) {
            return null;
        }
        String key = tab.getId() + "@" + Arrays.hashCode(favicon);
        return iconCache.computeIfAbsent(key, k -> {
            try {
                return ImageIO.read(new ByteArrayInputStream(favicon));
            } catch (Exception e) {
                return null;
            }
        });
    }

    static String displayTitle(BrowserTab tab) {
        if (tab.getTitle() != null && !tab.getTitle().isBlank()) {
            return tab.getTitle();
        }
        String url = tab.getUrl();
        if (application.module.browser.model.tab.TabController.NEW_TAB_URL.equals(url)) {
            return I18n.get("browser.tab.title.newtab");
        }
        String host = hostOf(url);
        return host != null ? host : (url != null ? url : "");
    }

    static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            if (url.startsWith("signum://")) {
                return url.substring("signum://".length()).split("[/?]")[0];
            }
            URI uri = URI.create(url);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
        } catch (Exception ignored) {
            // not a parseable URL — fall through
        }
        return null;
    }

    static String ellipsize(String text, FontMetrics fm, int width) {
        if (text == null) {
            return "";
        }
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "\u2026";
        int i = text.length();
        while (i > 1 && fm.stringWidth(text.substring(0, i - 1) + ellipsis) > width) {
            i--;
        }
        return text.substring(0, Math.max(1, i - 1)) + ellipsis;
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private static Color lighter(Color color, float amount) {
        return mix(color, Color.WHITE, amount);
    }

    private static Color darker(Color color, float amount) {
        return mix(color, Color.BLACK, amount);
    }

    private static Color mix(Color from, Color to, float amount) {
        int r = (int) (from.getRed() + (to.getRed() - from.getRed()) * amount);
        int g = (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * amount);
        int b = (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * amount);
        return new Color(r, g, b);
    }
}
