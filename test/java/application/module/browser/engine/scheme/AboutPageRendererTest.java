package application.module.browser.engine.scheme;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AboutPageRenderer} (F6, C9): the D17 flag
 * diagnostics, the CEF version supplier (org.cef is not unit-tested — the
 * renderer is fed through the injected supplier) and the data island.
 */
class AboutPageRendererTest {

    private static final String ISLAND_OPEN = "<script id=\"data\" type=\"application/json\">";

    private final Gson gson = new Gson();

    /** Parses the injected JSON data island out of a rendered page. */
    private JsonObject island(byte[] page) {
        String html = new String(page, StandardCharsets.UTF_8);
        int start = html.indexOf(ISLAND_OPEN) + ISLAND_OPEN.length();
        int end = html.indexOf("</script>", start);
        assertTrue(end > start, "the rendered page must carry the data island");
        return gson.fromJson(html.substring(start, end), JsonObject.class);
    }

    @Test
    @DisplayName("the D17 flags: no args (or no forbidden switch) means the hardening is in effect")
    void flagOffDiagnostics() {
        assertTrue(AboutPageRenderer.flagOff(null, "--no-sandbox"));
        assertTrue(AboutPageRenderer.flagOff(new String[0], "--disable-gpu"));
        assertTrue(AboutPageRenderer.flagOff(new String[]{"--type=browser"}, "--no-sandbox"));
        assertFalse(AboutPageRenderer.flagOff(new String[]{"--no-sandbox"}, "--no-sandbox"));
        assertFalse(AboutPageRenderer.flagOff(new String[]{"--disable-gpu"}, "--disable-gpu"));
    }

    @Test
    @DisplayName("the about page reports the engine as hardened when no overrides are present")
    void hardenedReport() {
        byte[] page = new AboutPageRenderer(() -> "Chromium 146.0.7680.179", null)
                .render(AboutPageRenderer.PAGE_URL, Map.of());

        assertNotNull(page);
        JsonObject island = island(page);
        assertEquals("enabled", island.get("gpu").getAsString());
        assertEquals("enabled", island.get("sandbox").getAsString());
        assertEquals("Chromium 146.0.7680.179", island.get("cefVersion").getAsString());
        assertEquals(System.getProperty("java.version"), island.get("javaVersion").getAsString());
        assertFalse(island.get("os").getAsString().isBlank());
        assertFalse(new String(page, StandardCharsets.UTF_8).contains("__SIGNUM_DATA__"));
    }

    @Test
    @DisplayName("a forbidden switch in the command line flips the report to disabled")
    void hardenedReportDisabled() {
        JsonObject island = island(new AboutPageRenderer(
                () -> "", new String[]{"--no-sandbox", "--disable-gpu"})
                .render(AboutPageRenderer.PAGE_URL, Map.of()));

        assertEquals("disabled", island.get("gpu").getAsString());
        assertEquals("disabled", island.get("sandbox").getAsString());
    }

    @Test
    @DisplayName("the CEF version supplier: null, blank, throwing and missing suppliers all degrade to \"\"")
    void cefVersionSupplierDegrades() {
        assertEquals("", island(new AboutPageRenderer(() -> null, null)
                .render(AboutPageRenderer.PAGE_URL, Map.of())).get("cefVersion").getAsString());
        assertEquals("", island(new AboutPageRenderer(() -> "   ", null)
                .render(AboutPageRenderer.PAGE_URL, Map.of())).get("cefVersion").getAsString());
        assertEquals("", island(new AboutPageRenderer(() -> {
            throw new IllegalStateException("no engine");
        }, null).render(AboutPageRenderer.PAGE_URL, Map.of())).get("cefVersion").getAsString());
        assertEquals("", island(new AboutPageRenderer(null, null)
                .render(AboutPageRenderer.PAGE_URL, Map.of())).get("cefVersion").getAsString());
    }

    @Test
    @DisplayName("the pinned JCEF release version is read from the build-time-stamped resource")
    void pinnedJcefVersion() {
        String version = AboutPageRenderer.pinnedJcefVersion();
        // the test classpath carries the gradle-expanded /jcef-release.properties
        assertFalse(version.isEmpty());
        assertTrue(version.contains("cef-"), "expected a CEF-tagged pin, got: " + version);
    }

    @Test
    @DisplayName("an unknown about sub-path renders nothing (the 404 fallback wins)")
    void unknownActionReturnsNull() {
        assertNull(new AboutPageRenderer(() -> "", null).render("signum://about/bogus", Map.of()));
    }

    @Test
    @DisplayName("escapeForScript turns every '<' into the valid JSON escape \\u003c (and only that)")
    void escapeForScript() {
        assertEquals("{\"a\":\"\\u003cb>&\"}",
                AboutPageRenderer.escapeForScript("{\"a\":\"<b>&\"}"));
        assertEquals("{\"t\":\"no brackets\"}", AboutPageRenderer.escapeForScript("{\"t\":\"no brackets\"}"));
    }
}
