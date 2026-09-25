package application.module.browser.engine.scheme;

import com.google.gson.Gson;
import application.utils.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Body producer of the {@code signum://about} page (plan F6, C9): the app,
 * the pinned JCEF release, the running CEF/Chromium runtime, the JVM, the
 * OS and the engine-hardening state (D17: the renderer sandbox and the GPU
 * process — reported from the deterministic CEF command line, which
 * carries no overrides in a D17-compliant build).
 * <p>
 * The CEF runtime version is an injected supplier: {@code org.cef} classes
 * are not unit-tested (plan §7), and the engine may simply not be started
 * yet (then the page shows "not started"). The pinned JCEF release comes
 * from the build-time-stamped {@code /jcef-release.properties} resource
 * (the same file the runtime auto-installer reads). No CEF imports —
 * fully unit-testable.
 */
public final class AboutPageRenderer implements InternalPage.PageRenderer {

    /** The page name in the {@link InternalPage} registry. */
    public static final String PAGE = "about";
    /** The page URL (the settings page links to it). */
    public static final String PAGE_URL = BrowserSchemeHandler.SCHEME_PREFIX + PAGE;

    private static final Logger logger = LoggerFactory.getLogger(AboutPageRenderer.class);

    private static final String TEMPLATE = "/html/browser/about.html";
    /** Placeholder of the static template, replaced by the escaped JSON island. */
    private static final String DATA_PLACEHOLDER = "__SIGNUM_DATA__";
    /** The build-time-stamped JCEF release info (SSOT: gradle.properties). */
    private static final String RELEASE_RESOURCE = "/jcef-release.properties";

    private final Supplier<String> cefVersion;
    private final String[] cefCommandline;
    private final Gson gson = new Gson();

    /**
     * @param cefVersion     the running CEF/Chromium version (may return
     *                       blank when the engine is not started), or null
     * @param cefCommandline the CEF command-line arguments the engine runs
     *                       with (D17: deterministic — used for the
     *                       sandbox/GPU diagnostics), or null
     */
    public AboutPageRenderer(Supplier<String> cefVersion, String[] cefCommandline) {
        this.cefVersion = cefVersion;
        this.cefCommandline = cefCommandline;
    }

    @Override
    public byte[] render(String url, Map<String, String> query) {
        if (!PAGE.equals(pathOf(url))) {
            return null; // unknown action → the 404 page
        }
        PageData data = new PageData();
        data.appName = I18n.get("browser.about.app");
        data.jcefVersion = pinnedJcefVersion();
        data.cefVersion = safeCefVersion();
        data.javaVersion = System.getProperty("java.version", "");
        data.os = System.getProperty("os.name", "") + " "
                + System.getProperty("os.version", "") + " ("
                + System.getProperty("os.arch", "") + ")";
        data.gpu = flagOff(cefCommandline, "--disable-gpu") ? "enabled" : "disabled";
        data.sandbox = flagOff(cefCommandline, "--no-sandbox") ? "enabled" : "disabled";
        data.strings.put("title", I18n.get("browser.about.title"));
        data.strings.put("app", I18n.get("browser.about.app"));
        data.strings.put("jcef", I18n.get("browser.about.jcef"));
        data.strings.put("cef", I18n.get("browser.about.cef"));
        data.strings.put("cefNotStarted", I18n.get("browser.about.cef.notStarted"));
        data.strings.put("java", I18n.get("browser.about.java"));
        data.strings.put("os", I18n.get("browser.about.os"));
        data.strings.put("gpu", I18n.get("browser.about.gpu"));
        data.strings.put("gpuEnabled", I18n.get("browser.about.gpu.enabled"));
        data.strings.put("gpuDisabled", I18n.get("browser.about.gpu.disabled"));
        data.strings.put("sandbox", I18n.get("browser.about.sandbox"));
        data.strings.put("sandboxEnabled", I18n.get("browser.about.sandbox.enabled"));
        data.strings.put("sandboxDisabled", I18n.get("browser.about.sandbox.disabled"));
        data.strings.put("back", I18n.get("browser.about.back"));

        String template = readTemplate();
        if (template == null) {
            return null;
        }
        String json = escapeForScript(gson.toJson(data));
        return template.replace(DATA_PLACEHOLDER, json).getBytes(StandardCharsets.UTF_8);
    }
    // ------------------------------------------------------------------
    // Data sources (pure — unit-tested)
    // ------------------------------------------------------------------

    /**
     * The pinned JCEF release version from the build-time-stamped resource
     * ("" when the resource is missing or unreadable — e.g. in a stripped
     * classpath — the page then shows an empty value, never a crash).
     */
    static String pinnedJcefVersion() {
        try (InputStream in = AboutPageRenderer.class.getResourceAsStream(RELEASE_RESOURCE)) {
            if (in == null) {
                return "";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("jcef.version", "").trim();
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read the JCEF release info: {}", e.toString());
            return "";
        }
    }

    /** The running CEF version, or "" (the page falls back to its label). */
    private String safeCefVersion() {
        if (cefVersion == null) {
            return "";
        }
        try {
            String version = cefVersion.get();
            return version == null ? "" : version.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * D17 diagnostic: whether the forbidden switch is ABSENT from the CEF
     * command line (absent = the hardening is in effect).
     */
    static boolean flagOff(String[] args, String flag) {
        if (args == null) {
            return true; // no args = the engine's safe default (F0)
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return false;
            }
        }
        return true;
    }

    private static String pathOf(String url) {
        if (url == null) {
            return "";
        }
        String path = url;
        if (path.startsWith(BrowserSchemeHandler.SCHEME_PREFIX)) {
            path = path.substring(BrowserSchemeHandler.SCHEME_PREFIX.length());
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * S9: makes the JSON safe for inlining into a {@code <script>} tag
     * (every opening angle bracket becomes the {@code \u003c} JSON escape).
     */
    static String escapeForScript(String json) {
        return json.replace("<", "\\u003c");
    }

    private String readTemplate() {
        try (InputStream in = AboutPageRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                logger.error("About page template missing from the classpath: {}", TEMPLATE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not read the about page template {}", TEMPLATE, e);
            return null;
        }
    }

    /** The injected payload (gson-serialized public fields). */
    static final class PageData {
        int v = 1;
        String appName;
        String jcefVersion;
        String cefVersion;
        String javaVersion;
        String os;
        /** "enabled" / "disabled" (the GPU process, D17 diagnostic). */
        String gpu;
        /** "enabled" / "disabled" (the renderer sandbox, D17 diagnostic). */
        String sandbox;
        Map<String, String> strings = new LinkedHashMap<>();
    }
}
