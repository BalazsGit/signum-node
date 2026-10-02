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
    void securityIcon_placedAtLeftWithOwnWidth_onlyIconClickable() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
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

            assertEquals(Omnibox.LOCK_LEFT_PAD, icon.getX(),
                    "the lock must hug the field's left edge (a small fixed pad)");
            assertEquals(icon.getPreferredSize().width, icon.getWidth(),
                    "the lock must occupy exactly its own width");
            int expectedY = (field.getHeight() - icon.getPreferredSize().height) / 2;
            assertEquals(expectedY, icon.getY(), 1, "the lock must be vertically centered");
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

            assertEquals(Omnibox.LOCK_LEFT_PAD, icon.getX(),
                    "under FlatLaf the lock must hug the field's left edge");
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

    /** The field's text start x (where the first character is painted). */
    private static int textStartX(JTextField field) {
        try {
            return field.modelToView(0).x;
        } catch (javax.swing.text.BadLocationException e) {
            throw new AssertionError("modelToView(0) failed", e);
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