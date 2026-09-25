package application.module.browser.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The built-in search engine presets (plan F9, C3/N11). The active engine is
 * the {@code searchEngineName} + {@code searchEngineTemplate} pair in
 * {@link BrowserSettings}; the settings page offers these presets plus a
 * custom template (any URL containing {@code {query}}). Pure data.
 */
public final class SearchEnginePresets {

    public static final String DUCKDUCKGO = "DuckDuckGo";
    public static final String GOOGLE = "Google";
    public static final String BING = "Bing";

    private static final Map<String, String> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put(DUCKDUCKGO, "https://duckduckgo.com/?q={query}");
        PRESETS.put(GOOGLE, "https://www.google.com/search?q={query}");
        PRESETS.put(BING, "https://www.bing.com/search?q={query}");
    }

    private SearchEnginePresets() {
        // utility class — never instantiated
    }

    /** @return the preset name → template pairs, in display order. */
    public static Map<String, String> all() {
        return Collections.unmodifiableMap(PRESETS);
    }

    /**
     * @param name a preset name
     * @return the template, or {@code null} when the name is not a preset
     */
    public static String templateOf(String name) {
        return name == null ? null : PRESETS.get(name);
    }
}