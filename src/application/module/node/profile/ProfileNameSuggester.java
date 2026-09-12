package application.module.node.profile;

import java.util.HashSet;
import java.util.Set;

/**
 * SSOT profile-name suggestion and uniqueness helper.
 * <p>
 * Used both by the Setup Wizard (name prefill) and the {@code profile create} CLI
 * command so that the naming convention and the {@code _01/_02/...} collision
 * suffix are generated in exactly one place.
 * </p>
 * <p>
 * This class is deliberately decoupled from the GUI {@code DatabaseEngine} enum:
 * it derives the engine suffix from the {@code DB.Engine} property value, so it can be
 * reused by the pure (Swing-free) CLI layer and by tests.
 * </p>
 */
public final class ProfileNameSuggester {

    private ProfileNameSuggester() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Builds a base profile name from the selected configuration.
     * <p>Format: {@code mainnet|testnet[-mariadb|-postgres][-<apiPort>]}, e.g.
     * {@code mainnet-mariadb-27101}.</p>
     *
     * @param testnet            true → {@code testnet}, false → {@code mainnet}
     * @param dbEngine           the {@code DB.Engine} property value (e.g. {@code mariaDb},
     *                           {@code postgresql}); null/empty/SQLite → no engine suffix
     * @param apiPortNonDefault  true → append {@code -<apiPort>}
     * @param apiPort            the API port to append when {@code apiPortNonDefault}
     * @return the base name
     */
    public static String baseName(boolean testnet, String dbEngine, boolean apiPortNonDefault, int apiPort) {
        StringBuilder sb = new StringBuilder(testnet ? "testnet" : "mainnet");
        String suffix = engineSuffix(dbEngine);
        if (suffix != null && !suffix.isEmpty()) {
            sb.append('-').append(suffix);
        }
        if (apiPortNonDefault) {
            sb.append('-').append(apiPort);
        }
        return sb.toString();
    }

    /**
     * Returns the next available unique name based on the base name: tries
     * {@code base} first, then {@code base_01}, {@code base_02}, ...
     *
     * @param base   the base name (validated caller-side)
     * @param taken  existing profile names (reserved names are always treated as taken)
     * @return a unique, non-reserved name
     */
    public static String nextAvailableName(String base, Set<String> taken) {
        Set<String> takenSet = taken != null ? taken : new HashSet<>();
        if (isFree(base, takenSet)) {
            return base;
        }
        for (int n = 1; ; n++) {
            String candidate = base + "_" + String.format("%02d", n);
            if (isFree(candidate, takenSet)) {
                return candidate;
            }
        }
    }

    private static boolean isFree(String name, Set<String> taken) {
        return !NodeProfileRepository.isReservedProfileName(name) && !taken.contains(name);
    }

    /**
     * Deterministic, per-profile port assignment used when the user does not set a port.
     * <p>
     * This is the SSOT for "make a freshly-created profile independently runnable out of the box"
     * (see the plan §2.6 isolation contract): a profile's default DB and default ports are otherwise
     * SHARED across all profiles, so two fresh profiles would collide. Deriving three distinct,
     * stable ports from the profile name avoids that collision while remaining predictable
     * (the same name always maps to the same ports, so the assignment is reproducible and testable).
     * </p>
     * <p>
     * The band starts at {@code 9000} so it never overlaps the shared {@code Props} defaults
     * (8123 / 8125 / 8126). All values are within the valid TCP range (1024–65535) and the three
     * ports are guaranteed distinct.
     * </p>
     * <p>
     * <b>Known (accepted) risk:</b> two profiles whose name-hashes land in adjacent bands
     * (probability ≈ 3/45000 per pair) would share one or two ports. This is NOT a silent failure:
     * the runtime first-wins arbitration ({@code NodeModule} + {@link ProfileConflictDetector})
     * detects the collision at start time and rejects the later profile with a clear, actionable
     * message — the isolation contract (§2.6) is enforced at runtime regardless.
     * </p>
     *
     * @param profileName the profile name (used only to derive a stable, unique port band)
     * @return {@code int[]{apiPort, p2pPort, wsPort}}
     */
    public static int[] deriveProfilePorts(String profileName) {
        int h = Math.floorMod(profileName.toLowerCase(java.util.Locale.ROOT).hashCode(), 45000); // 0..44999
        int api = 9000 + h; // 9000..53999 — distinct band, clear of the shared defaults
        return new int[]{api, api + 1, api + 2};
    }

    private static String engineSuffix(String dbEngine) {
        if (dbEngine == null) {
            return null;
        }
        String e = dbEngine.toLowerCase();
        if (e.contains("maria")) {
            return "mariadb";
        }
        if (e.contains("postgres")) {
            return "postgres";
        }
        return null; // SQLite / HSQLDB / Derby → no engine suffix
    }
}