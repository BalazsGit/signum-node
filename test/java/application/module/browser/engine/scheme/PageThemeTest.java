package application.module.browser.engine.scheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PageTheme} (F6, D6/A4): the LAF-palette to
 * CSS-variable conversion and the head injection.
 */
class PageThemeTest {

    @Test
    @DisplayName("hex renders a color as lowercase #rrggbb (alpha ignored)")
    void hexFormat() {
        assertEquals("#000000", PageTheme.hex(Color.BLACK));
        assertEquals("#ffffff", PageTheme.hex(Color.WHITE));
        assertEquals("#14171c", PageTheme.hex(new Color(0x14, 0x17, 0x1c, 0x80)));
        assertEquals("#4f8cff", PageTheme.hex(new Color(0x4f, 0x8c, 0xff)));
    }

    @Test
    @DisplayName("styleBlock builds the :root override in insertion order")
    void styleBlockContent() {
        Map<String, Color> tokens = new LinkedHashMap<>();
        tokens.put("bg", new Color(0x10, 0x11, 0x12));
        tokens.put("fg", new Color(0xe0, 0xe1, 0xe2));
        tokens.put("accent", new Color(0x40, 0x80, 0xff));

        assertEquals("<style>/* signum theme (LAF) */:root{"
                        + "--bg: #101112;--fg: #e0e1e2;--accent: #4080ff}</style>",
                PageTheme.styleBlock(tokens));
    }

    @Test
    @DisplayName("null or empty token maps yield the empty block (static palette wins)")
    void styleBlockEmpty() {
        assertEquals("", PageTheme.styleBlock(null));
        assertEquals("", PageTheme.styleBlock(Map.of()));
        Map<String, Color> withNull = new LinkedHashMap<>();
        withNull.put("bg", null);
        assertEquals("", PageTheme.styleBlock(withNull));
    }

    @Test
    @DisplayName("inject inserts the block right before </head>")
    void injectBeforeHead() {
        byte[] html = "<html><head><link href=\"css/browser.css\"></head><body></body></html>"
                .getBytes(StandardCharsets.UTF_8);

        byte[] out = PageTheme.inject(html, "<style>:root{--bg:#111}</style>");

        assertEquals("<html><head><link href=\"css/browser.css\">"
                        + "<style>:root{--bg:#111}</style></head><body></body></html>",
                new String(out, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an empty block or a missing </head> leaves the page untouched")
    void injectNoop() {
        byte[] html = "<html><head></head><body></body></html>".getBytes(StandardCharsets.UTF_8);
        assertSame(html, PageTheme.inject(html, ""));
        assertSame(html, PageTheme.inject(html, null));
        assertNull(PageTheme.inject(null, "<style>x</style>"));

        byte[] noHead = "<html><body></body></html>".getBytes(StandardCharsets.UTF_8);
        assertSame(noHead, PageTheme.inject(noHead, "<style>x</style>"));
    }

    @Test
    @DisplayName("the injected block lands AFTER the browser.css link (the overrides win the cascade)")
    void injectAfterCssLink() {
        String html = "<html><head><link rel=\"stylesheet\" href=\"css/browser.css\"></head><body></body></html>";
        byte[] out = PageTheme.inject(html.getBytes(StandardCharsets.UTF_8), "<style>/* signum */</style>");
        String rendered = new String(out, StandardCharsets.UTF_8);
        assertTrue(rendered.indexOf("css/browser.css") < rendered.indexOf("/* signum */"));
    }
}
