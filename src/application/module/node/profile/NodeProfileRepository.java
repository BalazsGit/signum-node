package application.module.node.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.config.PropertiesProfileLoader;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

/**
 * Static factory / repository for node profiles.
 * <p>
 * v4 (P1.1): the profile-discovery and file-management statics were extracted
 * from {@link NodeProfile} so that the profile class itself remains a plain,
 * stateless POJO (identity + property bag + builder). All path-schema
 * knowledge lives here.
 *
 * @see NodeProfile
 * @see PropertiesProfileLoader
 */
public final class NodeProfileRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfileRepository.class);

    private NodeProfileRepository() {
        // static utility
    }

    // ── Discovery ──────────────────────────────────────────────────────

    /**
     * Discovers and loads all node profiles from {@code conf/node/profiles/*.properties}.
     * <p>
     * Reserved profile names (default templates) are excluded from discovery.
     *
     * @return array of loaded NodeProfiles, empty if none found
     */
    public static NodeProfile[] loadAll() {
        try {
            return PropertiesProfileLoader.loadAll(
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                    NodeProfile.RESERVED_PROFILE_NAMES,
                    name -> new NodeProfile(name), NodeProfile.class);
        } catch (Exception e) {
            LOGGER.error("Error loading node profiles", e);
            return new NodeProfile[0];
        }
    }

    /**
     * Loads a specific profile by name from {@code conf/node/profiles/{name}.properties}.
     *
     * @param profileName the profile name (without extension)
     * @return the loaded NodeProfile, or null if not found
     */
    public static NodeProfile loadByName(String profileName) {
        try {
            Properties props = PropertiesProfileLoader.loadProfile(
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY, profileName);

            if (props.isEmpty()) {
                LOGGER.debug("Profile file not found or empty: {}", profileName);
                return null;
            }

            Path propsPath = Paths.get(NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID,
                    NodeProfile.CATEGORY, profileName + ".properties");

            return new NodeProfile.Builder(profileName)
                    .properties(props)
                    .propertiesPath(propsPath)
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error loading profile {}", profileName, e);
            return null;
        }
    }

    /**
     * Checks if a profile name is reserved.
     *
     * @param profileName the name to check
     * @return true if the name is reserved and cannot be used as a profile
     */
    public static boolean isReservedProfileName(String profileName) {
        return profileName != null && NodeProfile.RESERVED_PROFILE_NAMES.contains(profileName);
    }

    /**
     * Discovers all available (non-reserved) profile names.
     *
     * @return sorted list of discoverable profile names
     */
    public static List<String> discoverProfileNames() {
        return PropertiesProfileLoader.discoverProfiles(
                NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                NodeProfile.RESERVED_PROFILE_NAMES);
    }

    // ── Default File Management ────────────────────────────────────────

    /**
     * Synchronizes the default profile file from classpath resources to runtime conf/.
     * Uses SHA-256 hash comparison to detect updates.
     */
    public static void syncDefaultProfileFile() {
        InputStream is = NodeProfile.class.getResourceAsStream(
                "/conf/node/profiles/node-default.properties");
        if (is != null) {
            PropertiesProfileLoader.syncDefaultFile(
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                    NodeProfile.DEFAULT_PROFILE_FILENAME, is);
        } else {
            LOGGER.warn("Default profile resource not found on classpath");
        }
    }

    /**
     * Synchronizes the default logging file from classpath resources to runtime conf/.
     */
    public static void syncDefaultLoggingFile() {
        InputStream is = NodeProfile.class.getResourceAsStream(
                "/conf/node/logging/logging-default.properties");
        if (is != null) {
            PropertiesProfileLoader.syncDefaultFile(
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID,
                    PropertiesProfileLoader.DEFAULT_CATEGORY_LOGGING,
                    NodeProfile.DEFAULT_LOGGING_FILENAME, is);
        } else {
            LOGGER.warn("Default logging resource not found on classpath");
        }
    }

    /**
     * Ensures empty placeholder files exist for both profiles and logging
     * categories when no user profiles are discovered.
     */
    public static void ensureEmptyPlaceholdersIfNeeded() {
        PropertiesProfileLoader.ensureEmptyPlaceholdersForModule(
                NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.RESERVED_PROFILE_NAMES,
                "node", "logging");
    }

    /**
     * Full initialization: sync defaults + create placeholders if needed.
     * <p>
     * Call this once during application startup, before any profile loading occurs.
     */
    public static void initialize() {
        PropertiesProfileLoader.initializeModule(
                NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.RESERVED_PROFILE_NAMES,
                "node", "logging");
    }
}