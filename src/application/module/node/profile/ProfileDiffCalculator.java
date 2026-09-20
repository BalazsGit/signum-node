package application.module.node.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Pure, GUI-free diff computation for node configuration profiles.
 * <p>
 * SSOT for "what differs from the application default" — the same question the
 * configuration panel asks for its unsaved-change report and the clone action
 * asks when building the minimal override file of a new profile.
 * </p>
 * <p>
 * Both inputs are expected in <b>stored form</b> (the on-disk
 * {@code .properties} representation): list values are
 * {@code ;}-delimited, booleans are {@code true}/{@code false}. A key absent
 * from the effective state is treated as "matches the default" and is never
 * reported; a key absent from the defaults (custom/unknown key) is reported
 * when its effective value is non-empty.
 * </p>
 *
 * @see NodeProfileRepository
 */
public final class ProfileDiffCalculator {

    private ProfileDiffCalculator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * One property whose effective value differs from the application default.
     */
    public static final class ProfileDiffEntry {

        private final String key;
        private final String value;
        private final String label;

        public ProfileDiffEntry(String key, String value) {
            this(key, value, key);
        }

        public ProfileDiffEntry(String key, String value, String label) {
            this.key = Objects.requireNonNull(key, "key");
            this.value = value == null ? "" : value;
            this.label = (label == null || label.isEmpty()) ? key : label;
        }

        /** The {@code .properties} key (e.g. {@code API.Port}). */
        public String getKey() {
            return key;
        }

        /** The effective value in stored form. */
        public String getValue() {
            return value;
        }

        /** Human-readable display name (defaults to the key). */
        public String getLabel() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ProfileDiffEntry)) {
                return false;
            }
            ProfileDiffEntry that = (ProfileDiffEntry) o;
            return key.equals(that.key) && value.equals(that.value) && label.equals(that.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value, label);
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    /**
     * Computes the properties whose effective value differs from the default.
     *
     * @param effective    the effective state in stored form (every key present
     *                     carries its effective value; keys may be absent when
     *                     they equal the default — they are then skipped)
     * @param defaultValues the application defaults in stored form (key →
     *                     default value); {@code null} values are treated as
     *                     empty strings
     * @return an immutable list of differing entries, sorted by key for stable
     *         output; empty when nothing differs from the default
     */
    public static List<ProfileDiffEntry> diffAgainstDefault(Properties effective, Map<String, String> defaultValues) {
        Objects.requireNonNull(effective, "effective");
        Map<String, String> defaults = new TreeMap<>();
        if (defaultValues != null) {
            defaults.putAll(defaultValues);
        }

        Map<String, String> keys = new TreeMap<>(defaults);
        for (String key : effective.stringPropertyNames()) {
            keys.putIfAbsent(key, "");
        }

        List<ProfileDiffEntry> diff = new ArrayList<>();
        for (Map.Entry<String, String> e : keys.entrySet()) {
            String key = e.getKey();
            String defaultValue = normalize(defaults.get(key));
            String rawEffective = effective.getProperty(key);
            String effectiveValue = (rawEffective == null) ? defaultValue : normalize(rawEffective);
            if (valuesDiffer(defaultValue, effectiveValue)) {
                diff.add(new ProfileDiffEntry(key, effectiveValue));
            }
        }
        return Collections.unmodifiableList(diff);
    }

    /**
     * Builds a minimal-override {@link Properties} from a diff list — the exact
     * payload a cloned profile file is created with.
     *
     * @param entries the diff entries (typically from {@link #diffAgainstDefault})
     * @return a new {@link Properties} containing exactly the entry key/values
     */
    public static Properties toProperties(List<ProfileDiffEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        Properties props = new Properties();
        for (ProfileDiffEntry entry : entries) {
            props.setProperty(entry.getKey(), entry.getValue());
        }
        return props;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean valuesDiffer(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        // Boolean-equivalence guard: stored booleans are "true"/"false" from the
        // UI, but hand-edited files may use "yes"/"1"/"on" variants.
        Boolean parsedA = parseBoolLoose(a);
        Boolean parsedB = parseBoolLoose(b);
        return !(parsedA != null && parsedA == parsedB);
    }

    private static Boolean parseBoolLoose(String value) {
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)
                || "on".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)
                || "off".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }
}

