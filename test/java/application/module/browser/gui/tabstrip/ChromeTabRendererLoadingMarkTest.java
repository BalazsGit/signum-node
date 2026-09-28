package application.module.browser.gui.tabstrip;

import application.module.browser.gui.animation.TabAnimation;
import application.module.browser.model.tab.BrowserTab;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 loading state: while a tab is loading, the app's Signum mark rotates in
 * the icon slot (wall-clock driven, painted by the ChromeTabBar repaint pump —
 * no per-tab timer). These tests paint the renderer off-screen and verify the
 * animation actually progresses (two paints 80 ms apart differ), and that an
 * idle tab paints a stable image.
 */
@DisplayName("ChromeTabRenderer loading mark (rotating Signum logo) Tests")
class ChromeTabRendererLoadingMarkTest {

    private static BufferedImage paint(ChromeTabRenderer renderer, BrowserTab tab, int w, int h) {
        final BufferedImage[] img = new BufferedImage[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                renderer.getListCellRendererComponent(new JList<>(), tab, 0, false, false);
                renderer.setSize(w, h);
                BufferedImage buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = buffer.createGraphics();
                renderer.paint(g2);
                g2.dispose();
                img[0] = buffer;
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return img[0];
    }

    private static boolean pixelsEqual(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    @DisplayName("a loading tab paints the rotating Signum mark (the paint changes over time)")
    void loadingTabPaintsRotatingMark() throws Exception {
        final ChromeTabRenderer[] holder = new ChromeTabRenderer[1];
        final BrowserTab tab = BrowserTab.forTest("https://signum.example/", true);
        SwingUtilities.invokeAndWait(() -> holder[0] = new ChromeTabRenderer(new TabAnimation()));

        int w = ChromeTabRenderer.CLOSE_ZONE + 120;
        int h = ChromeTabRenderer.HEIGHT;
        BufferedImage a = paint(holder[0], tab, w, h);
        Thread.sleep(80); // ~5 degrees of rotation at the 16 ms/degree cadence
        BufferedImage b = paint(holder[0], tab, w, h);

        assertFalse(pixelsEqual(a, b), "two paints 80 ms apart must differ while the Signum mark rotates");
    }

    @Test
    @DisplayName("an idle tab paints a stable image (no animation while not loading)")
    void idleTabPaintIsStable() throws Exception {
        final ChromeTabRenderer[] holder = new ChromeTabRenderer[1];
        final BrowserTab tab = BrowserTab.forTest("https://idle.example/", false);
        SwingUtilities.invokeAndWait(() -> holder[0] = new ChromeTabRenderer(new TabAnimation()));

        int w = ChromeTabRenderer.CLOSE_ZONE + 120;
        int h = ChromeTabRenderer.HEIGHT;
        BufferedImage a = paint(holder[0], tab, w, h);
        Thread.sleep(80);
        BufferedImage b = paint(holder[0], tab, w, h);

        assertTrue(pixelsEqual(a, b), "an idle tab must paint a stable image (nothing animates)");
    }
}
