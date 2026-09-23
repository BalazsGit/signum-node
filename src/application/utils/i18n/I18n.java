package application.utils.i18n;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal localization helper (plan D16, v1).
 * <p>
 * User-visible UI strings (Swing <b>and</b> the data injected into the internal
 * {@code signum://} pages) come exclusively from keyed bundles — hardcoded UI
 * literals are forbidden in the browser module from day one. Key format:
 * {@code <module>.<context>.<key>} (e.g. {@code browser.tab.close}).
 * <p>
 * v1 behavior:
 * <ul>
 *   <li>bundles live in {@code resources/i18n/<module>[_<lang>].properties}
 *       (e.g. {@code browser_en.properties} is the English SSOT);</li>
 *   <li>locale fallback chain: requested locale → English → root bundle;</li>
 *   <li>a missing key is returned as-is, so forgotten entries are visible
 *       during development instead of failing at runtime;</li>
 *   <li>language switching without restart (I3) and a language picker (I2)
 *       are v2 — v1 is English-only, {@link #setLocale} exists for tests and
 *       the future runtime switch.</li>
 * </ul>
 */
public final class I18n {

    /** Base name of the browser module bundle (v1: the only bundled module). */
    private static final String BUNDLE_BASE_NAME = "i18n.browser";

    private static volatile Locale locale = Locale.ENGLISH;
    private static final ConcurrentHashMap<Locale, List<ResourceBundle>> BUNDLE_CACHE = new ConcurrentHashMap<>();

    private I18n() {
        // utility class — never instantiated
    }

    /**
     * @return the active locale (English in v1)
     */
    public static Locale getLocale() {
        return locale;
    }

    /**
     * Sets the active locale (v1: used by tests and the future v2 runtime switch).
     */
    public static void setLocale(Locale newLocale) {
        locale = newLocale == null ? Locale.ENGLISH : newLocale;
    }

    /**
     * Resolves a UI string.
     *
     * @param key the bundle key (e.g. {@code browser.tab.close})
     * @return the localized string, or the key itself when it is missing
     */
    public static String get(String key) {
        return get(key, new Object[0]);
    }

    /**
     * Resolves a UI string with {@link MessageFormat} arguments.
     *
     * @param key  the bundle key
     * @param args format arguments (may be empty)
     * @return the localized, formatted string, or the key itself when it is missing
     */
    public static String get(String key, Object... args) {
        String template = lookup(key);
        if (template == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    private static String lookup(String key) {
        for (ResourceBundle bundle : bundlesFor(locale)) {
            if (bundle.containsKey(key)) {
                return bundle.getString(key);
            }
        }
        return null;
    }

    /**
     * Builds (and caches) the fallback bundle chain for a locale:
     * requested → English → root.
     */
    private static List<ResourceBundle> bundlesFor(Locale requested) {
        return BUNDLE_CACHE.computeIfAbsent(requested, l -> {
            LinkedHashSet<ResourceBundle> bundles = new LinkedHashSet<>();
            for (Locale candidate : new Locale[]{l, Locale.ENGLISH, Locale.ROOT}) {
                try {
                    bundles.add(ResourceBundle.getBundle(BUNDLE_BASE_NAME, candidate));
                } catch (MissingResourceException ignored) {
                    // continue down the fallback chain
                }
            }
            return new ArrayList<>(bundles);
        });
    }
}
