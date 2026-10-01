package application.module.browser.gui.toolbar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.JTextField;

import static org.junit.jupiter.api.Assertions.assertEquals;
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