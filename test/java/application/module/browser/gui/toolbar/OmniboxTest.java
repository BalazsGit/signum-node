package application.module.browser.gui.toolbar;

import application.module.browser.engine.security.SslStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.JTextField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Unit tests for the {@link Omnibox} URL mirror ({@link Omnibox#showUrl}).
 * <p>
 * The contract: the field follows the active tab's URL — including while it
 * has focus (a tab-strip click never moves the keyboard focus away) — except
 * when the user actually edited the text: then the field holds a real query
 * that a tab switch must not clobber.
 */
@DisplayName("Omnibox Tests")
class OmniboxTest {

    private static Omnibox newOmnibox() {
        return new Omnibox(url -> { }, (box, input) -> java.util.List.of());
    }

    private static JTextField fieldOf(Omnibox box) {
        for (java.awt.Component c : box.getComponents()) {
            if (c instanceof JTextField field) {
                return field;
            }
        }
        throw new AssertionError("the omnibox must host the text field");
    }

    @Test
    @DisplayName("showUrl mirrors the URL while the field is unfocused")
    void showUrl_unfocused_updates() {
        Omnibox box = newOmnibox();

        box.showUrl("https://example.com/");

        assertEquals("https://example.com/", box.getText());
    }

    @Test
    @DisplayName("showUrl follows the tab while the field is focused but unedited (the tab-switch repro)")
    void showUrl_focusedUnedited_updates() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        JFrame frame = new JFrame("OmniboxTest");
        try {
            Omnibox box = newOmnibox();
            frame.setContentPane(box);
            frame.setSize(500, 100);
            frame.setVisible(true);
            JTextField field = fieldOf(box);
            field.requestFocusInWindow();
            awaitFocusOwner(field);

            box.showUrl("https://a.example/");
            box.showUrl("https://b.example/"); // a tab switch while the field still has focus

            assertEquals("https://b.example/", box.getText(),
                    "a focused-but-unedited field must follow the active tab");
        } finally {
            frame.dispose();
        }
    }

    @Test
    @DisplayName("showUrl keeps the user's typed query while the field is focused")
    void showUrl_focusedEdited_keepsUserInput() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        JFrame frame = new JFrame("OmniboxTest");
        try {
            Omnibox box = newOmnibox();
            frame.setContentPane(box);
            frame.setSize(500, 100);
            frame.setVisible(true);
            JTextField field = fieldOf(box);
            field.requestFocusInWindow();
            awaitFocusOwner(field);

            box.showUrl("https://a.example/");
            field.setText("search query"); // the user is typing
            box.showUrl("https://c.example/");

            assertEquals("search query", box.getText(),
                    "a focused field with real user input must not be clobbered");
        } finally {
            frame.dispose();
        }
    }

    @Test
    @DisplayName("the lock sits at the field's left side at its own width, and only the lock is clickable")
    void securityIcon_placedAtLeftWithOwnWidth_onlyIconClickable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        // The embedding uses FlatLaf's leading/trailing-component API, so the
        // production L&F (FlatLaf) must be active for it to take effect.
        javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        try {
            com.formdev.flatlaf.FlatDarkLaf.setup();
            java.util.concurrent.atomic.AtomicInteger clicks =
                    new java.util.concurrent.atomic.AtomicInteger();
            SecurityIcon icon = new SecurityIcon(url -> clicks.incrementAndGet());
            icon.update(SslStatus.SECURE, "https://example.com/", false);
            Omnibox box = new Omnibox(url -> { }, (b, i) -> java.util.List.of(), icon);
            JFrame frame = new JFrame("OmniboxTest-icon");
            try {
                frame.setContentPane(box);
                frame.setSize(500, 100);
                frame.setVisible(true);
                JTextField field = fieldOf(box);
                awaitPlaced(icon);

                assertTrue(icon.getParent() == field,
                        "the lock must be embedded inside the field");
                assertTrue(icon.getX() < 10,
                        "the lock must hug the field's left edge (x=" + icon.getX() + ")");
                assertEquals(icon.getPreferredSize().width, icon.getWidth(),
                        "the lock must occupy exactly its own width");
                assertTrue(icon.getY() < 5 && icon.getHeight() >= field.getHeight() - 10,
                        "the lock must span the field's full height (y=" + icon.getY()
                                + " h=" + icon.getHeight() + " fieldH=" + field.getHeight() + ")");
                box.showUrl("https://example.com/");
                int textX = textStartX(field);
                int lockRight = icon.getX() + icon.getWidth();
                assertTrue(textX >= lockRight + 2,
                        "the URL text (x=" + textX + ") must start after the lock (right edge x="
                                + lockRight + ") with a small gap");

                // S2: a click on the lock opens the certificate details (the action)
                icon.dispatchEvent(new java.awt.event.MouseEvent(icon,
                        java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, 10, 10, 1, false));
                assertEquals(1, clicks.get(), "a click on the lock must invoke the action");

                // ...but a click on the field anywhere else must not
                field.dispatchEvent(new java.awt.event.MouseEvent(field,
                        java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, 60, 16, 1, false));
                assertEquals(1, clicks.get(),
                        "a click on the field must not invoke the lock's action");

                // an insecure lock is not clickable at all
                icon.update(SslStatus.INSECURE, "http://example.com/", false);
                icon.dispatchEvent(new java.awt.event.MouseEvent(icon,
                        java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, 10, 10, 1, false));
                assertEquals(1, clicks.get(), "an insecure lock must not be clickable");
            } finally {
                frame.dispose();
            }
        } finally {
            javax.swing.UIManager.setLookAndFeel(previous);
        }
    }

    @Test
    @DisplayName("FlatLaf (the production L&F): lock pinned left, URL never overlaps it, insets idempotent")
    void flatLaf_lockLeftNoOverlap_insetsIdempotent() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        JFrame frame = new JFrame("OmniboxTest-flatlaf");
        try {
            com.formdev.flatlaf.FlatDarkLaf.setup();
            SecurityIcon icon = new SecurityIcon(url -> {
            });
            icon.update(SslStatus.SECURE, "https://example.com/", false);
            Omnibox box = new Omnibox(url -> {
            }, (b, i) -> java.util.List.of(), icon);
            box.showUrl("https://example.com/");
            frame.setContentPane(box);
            frame.setSize(500, 100);
            frame.setVisible(true);
            JTextField field = fieldOf(box);
            awaitPlaced(icon);

            // Appearance changes re-apply the insets repeatedly: the margin must
            // stay constant. (The old code added the border inset to the margin,
            // but FlatLaf's border insets already fold in the margin — every call
            // inflated it until the lock and the URL text overlapped.)
            box.applySecurityIconInsets();
            int marginAfterFirst = field.getMargin().left;
            box.applySecurityIconInsets();
            box.applySecurityIconInsets();
            assertEquals(marginAfterFirst, field.getMargin().left,
                    "re-applying the security-icon insets must be idempotent");

            assertTrue(icon.getParent() == field,
                    "under FlatLaf the lock must be embedded inside the field");
            assertTrue(icon.getX() < 10,
                    "under FlatLaf the lock must hug the field's left edge (x=" + icon.getX() + ")");
            int textX = textStartX(field);
            int lockRight = icon.getX() + icon.getWidth();
            assertTrue(textX >= lockRight + 2,
                    "under FlatLaf the URL (x=" + textX + ") must start after the lock"
                            + " (right edge x=" + lockRight + ")");
        } finally {
            frame.dispose();
            javax.swing.UIManager.setLookAndFeel(previous);
        }
    }

    @Test
    @DisplayName("font-size increase (appearance flow) keeps lock and star placed")
    void appearanceFontSizeIncrease_keepsEmbeddedComponents() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        JFrame frame = new JFrame("OmniboxTest-font");
        try {
            com.formdev.flatlaf.FlatDarkLaf.setup();
            SecurityIcon icon = new SecurityIcon(url -> {
            });
            icon.update(SslStatus.SECURE, "https://example.com/", false);
            javax.swing.JButton star = new javax.swing.JButton();
            Omnibox box = new Omnibox(url -> {
            }, (b, i) -> java.util.List.of(), icon, star);
            box.showUrl("https://example.com/");
            frame.setContentPane(box);
            frame.setSize(500, 100);
            frame.setVisible(true);
            awaitPlaced(icon);
            JTextField field = fieldOf(box);

            // Simulate the app's global font-size increase (AppearanceModule,
            // all on the EDT): the UIManager font keys are updated, then
            // FlatLaf.updateUI() refreshes every component, then the
            // appearance listeners run (NavigationToolbar#refreshIconSizes).
            java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
            javax.swing.plaf.FontUIResource bigger = new javax.swing.plaf.FontUIResource(
                    base.getFamily(), base.getStyle(), base.getSize() + 4);
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.UIManager.put("TextField.font", bigger);
                javax.swing.UIManager.put("Label.font", bigger);
                javax.swing.UIManager.put("Button.font", bigger);
                com.formdev.flatlaf.FlatLaf.updateUI();
                com.formdev.flatlaf.FlatLaf.revalidateAndRepaintAllFramesAndDialogs();
                icon.refreshSize();
                box.refreshAppearance();
                box.applySecurityIconInsets();
            });
            // pump the EDT so the pending repaints (the self-heal runs at
            // paint time) are processed before asserting
            for (int i = 0; i < 3; i++) {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                });
            }
            awaitPlaced(icon);

            // The regression: FlatLaf.updateUI() re-installs the field's UI
            // and a plain field.add()-embedded child did NOT survive that
            // (the child came back parentless and vanished). With the
            // official leading/trailing-component API the field's UI
            // (re-)adds the components on every install — they must still be
            // embedded AND placed after the appearance change.
            assertTrue(icon.getParent() == field,
                    "after a font-size increase the lock must still be embedded in the field");
            assertTrue(star.getParent() == field,
                    "after a font-size increase the star must still be embedded in the field");
            assertTrue(icon.getWidth() > 0, "the lock must stay placed after a font-size increase");
            assertTrue(star.getWidth() > 0, "the star must stay placed after a font-size increase");
            assertTrue(icon.getX() < 10,
                    "after a font-size increase the lock must hug the field's left edge");
            assertTrue(star.getX() >= icon.getX() + icon.getWidth(),
                    "after a font-size increase the star must sit at the field's right side");
            // The text must fit: the field's preferred height (what the row
            // adopts) must cover the font's line height plus the vertical text
            // margins — the previous guessed factor clipped the "g" descender.
            java.awt.FontMetrics fm = field.getFontMetrics(field.getFont());
            assertTrue(field.getPreferredSize().height >= fm.getHeight() + 2 * Omnibox.FIELD_V_MARGIN,
                    "the field's height must fit the larger font, got pref="
                            + field.getPreferredSize().height + " fontHeight=" + fm.getHeight());
        } finally {
            frame.dispose();
            javax.swing.UIManager.setLookAndFeel(previous);
        }
    }

    /** The field's text start x (where the first character is painted). */
    private static int textStartX(JTextField field) {
        try {
            return field.modelToView(0).x;
        } catch (javax.swing.text.BadLocationException e) {
            throw new AssertionError("modelToView(0) failed", e);
        }
    }

    @Test
    @DisplayName("lock and star stay placed after the field re-appears at the SAME size (stale-bounds repro)")
    void embeddedComponents_reappearAtSameSize_stillPlaced() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        JFrame frame = new JFrame("OmniboxTest-stale-bounds");
        try {
            com.formdev.flatlaf.FlatDarkLaf.setup();
            SecurityIcon icon = new SecurityIcon(url -> {
            });
            icon.update(SslStatus.SECURE, "https://example.com/", false);
            javax.swing.JButton star = new javax.swing.JButton();
            Omnibox box = new Omnibox(url -> {
            }, (b, i) -> java.util.List.of(), icon, star);
            box.showUrl("https://example.com/");
            frame.setContentPane(box);
            frame.setSize(500, 100);
            frame.setVisible(true);
            awaitPlaced(icon);
            assertTrue(star.getWidth() > 0, "the star must be placed at first show");

            // Simulate a hidden browser tab re-appearing at the SAME field
            // size: no resize event fires, so the field's layout must be
            // flushed again on the shown-state change (the hierarchy
            // listener) — otherwise the embedded components keep their
            // stale/zero bounds and "disappear".
            icon.setBounds(0, 0, 0, 0);
            star.setBounds(0, 0, 0, 0);
            frame.setVisible(false);
            frame.setVisible(true);
            awaitPlaced(icon);

            assertTrue(icon.getParent() == fieldOf(box),
                    "after a same-size re-show the lock must still be embedded in the field");
            assertTrue(icon.getX() < 10,
                    "after a same-size re-show the lock must hug the field's left edge again");
            assertTrue(icon.getWidth() > 0, "the lock must have a non-zero size after re-show");
            assertTrue(star.getWidth() > 0, "the star must be placed again after re-show");
            assertTrue(star.getX() >= icon.getX() + icon.getWidth(),
                    "after re-show the star must sit at the field's right side, not over the lock");
        } finally {
            frame.dispose();
            javax.swing.UIManager.setLookAndFeel(previous);
        }
    }

    private static void awaitPlaced(SecurityIcon icon) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && icon.getWidth() <= 0) {
            sleep(20);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitFocusOwner(java.awt.Component expected) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (java.awt.KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().getFocusOwner() == expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}