package application.module.node.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProfileDiffCalculator} — the pure "what differs from the
 * default" SSOT used by the clone action and the unsaved-change reports.
 */
@DisplayName("ProfileDiffCalculator Tests")
class ProfileDiffCalculatorTest {

    private static Map<String, String> defaults() {
        Map<String, String> d = new HashMap<>();
        d.put("API.Port", "7875");
        d.put("API.Server", "true");
        d.put("P2P.Port", "7874");
        d.put("DB.Url", "jdbc:sqlite:file:./database/SQLite/sqlite/signum.sqlite.db");
        return d;
    }

    @Test
    @DisplayName("clean default config yields an empty diff")
    void cleanDefaultIsEmpty() {
        Properties effective = new Properties();
        effective.setProperty("API.Port", "7875");
        effective.setProperty("P2P.Port", "7874");
        // API.Server + DB.Url absent -> match the default

        List<ProfileDiffCalculator.ProfileDiffEntry> diff =
                ProfileDiffCalculator.diffAgainstDefault(effective, defaults());
        assertTrue(diff.isEmpty());
    }

    @Test
    @DisplayName("N deviations yield exactly those entries")
    void deviationsAreReported() {
        Properties effective = new Properties();
        effective.setProperty("API.Port", "9999");
        effective.setProperty("DB.Url", "jdbc:mariadb://db:3306/signum");

        List<ProfileDiffCalculator.ProfileDiffEntry> diff =
                ProfileDiffCalculator.diffAgainstDefault(effective, defaults());

        assertEquals(2, diff.size());
        assertEquals("API.Port", diff.get(0).getKey());
        assertEquals("9999", diff.get(0).getValue());
        assertEquals("DB.Url", diff.get(1).getKey());
        assertEquals("jdbc:mariadb://db:3306/signum", diff.get(1).getValue());
    }

    @Test
    @DisplayName("output is sorted by key for stable display")
    void outputIsSortedByKey() {
        Properties effective = new Properties();
        effective.setProperty("P2P.Port", "1111");
        effective.setProperty("API.Port", "2222");
        effective.setProperty("DB.Url", "x");

        List<ProfileDiffCalculator.ProfileDiffEntry> diff =
                ProfileDiffCalculator.diffAgainstDefault(effective, defaults());

        assertEquals(List.of("API.Port", "DB.Url", "P2P.Port"),
                List.of(diff.get(0).getKey(), diff.get(1).getKey(), diff.get(2).getKey()));
    }

    @Test
    @DisplayName("whitespace is trimmed on both sides (not a diff)")
    void whitespaceIsIgnored() {
        Properties effective = new Properties();
        effective.setProperty("API.Port", "  7875  ");
        assertTrue(ProfileDiffCalculator.diffAgainstDefault(effective, defaults()).isEmpty());
    }

    @Test
    @DisplayName("boolean variants of the same value are not a diff")
    void booleanVariantsAreEquivalent() {
        Map<String, String> d = new HashMap<>();
        d.put("API.Server", "true");

        for (String variant : List.of("true", "True", "yes", "1", "on")) {
            Properties effective = new Properties();
            effective.setProperty("API.Server", variant);
            assertTrue(ProfileDiffCalculator.diffAgainstDefault(effective, d).isEmpty(),
                    "variant '" + variant + "' should equal default 'true'");
        }

        Properties off = new Properties();
        off.setProperty("API.Server", "off");
        assertEquals(1, ProfileDiffCalculator.diffAgainstDefault(off, d).size());
    }

    @Test
    @DisplayName("custom key (not in defaults) is reported when non-empty")
    void customKeyIsReported() {
        Properties effective = new Properties();
        effective.setProperty("my.custom.flag", "42");
        List<ProfileDiffCalculator.ProfileDiffEntry> diff =
                ProfileDiffCalculator.diffAgainstDefault(effective, defaults());
        assertEquals(1, diff.size());
        assertEquals("my.custom.flag", diff.get(0).getKey());
    }

    @Test
    @DisplayName("custom key with an empty value is not reported")
    void emptyCustomKeyIsSkipped() {
        Properties effective = new Properties();
        effective.setProperty("my.custom.flag", "   ");
        assertTrue(ProfileDiffCalculator.diffAgainstDefault(effective, defaults()).isEmpty());
    }

    @Test
    @DisplayName("null default value is treated as empty string")
    void nullDefaultTreatedAsEmpty() {
        Map<String, String> d = new HashMap<>(defaults());
        d.put("API.Port", null);
        Properties effective = new Properties();
        effective.setProperty("API.Port", "7875");
        assertEquals(1, ProfileDiffCalculator.diffAgainstDefault(effective, d).size());
    }

    @Test
    @DisplayName("null defaultValues map is allowed (all effective keys are the diff)")
    void nullDefaultsAllowed() {
        Properties effective = new Properties();
        effective.setProperty("a.b", "1");
        List<ProfileDiffCalculator.ProfileDiffEntry> diff =
                ProfileDiffCalculator.diffAgainstDefault(effective, null);
        assertEquals(1, diff.size());
        assertEquals("a.b", diff.get(0).getKey());
    }

    @Test
    @DisplayName("null effective throws")
    void nullEffectiveThrows() {
        assertThrows(NullPointerException.class,
                () -> ProfileDiffCalculator.diffAgainstDefault(null, defaults()));
    }

    @Test
    @DisplayName("toProperties builds the exact minimal-override payload")
    void toPropertiesRoundTrip() {
        Properties effective = new Properties();
        effective.setProperty("API.Port", "9999");
        effective.setProperty("DB.Url", "jdbc:mariadb://db:3306/signum");
        List<ProfileDiffCalculator.ProfileDiffEntry> diff =
                ProfileDiffCalculator.diffAgainstDefault(effective, defaults());

        Properties payload = ProfileDiffCalculator.toProperties(diff);
        assertEquals(2, payload.size());
        assertEquals("9999", payload.getProperty("API.Port"));
        assertEquals("jdbc:mariadb://db:3306/signum", payload.getProperty("DB.Url"));
    }

    @Test
    @DisplayName("entry label defaults to the key and can be overridden")
    void entryLabelBehaviour() {
        ProfileDiffCalculator.ProfileDiffEntry auto =
                new ProfileDiffCalculator.ProfileDiffEntry("API.Port", "9999");
        assertEquals("API.Port", auto.getLabel());

        ProfileDiffCalculator.ProfileDiffEntry custom =
                new ProfileDiffCalculator.ProfileDiffEntry("API.Port", "9999", "API port");
        assertEquals("API port", custom.getLabel());
    }
}

