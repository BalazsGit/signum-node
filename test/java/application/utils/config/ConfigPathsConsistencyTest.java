package application.utils.config;

import application.module.browser.BrowserModule;
import application.module.database.logging.DatabaseLoggingProfile;
import application.module.database.DatabaseModule;
import application.module.node.NodeModule;
import application.module.node.logging.NodeLoggingProfile;
import application.module.node.profile.NodeProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SSOT (Single Source of Truth) consistency guard.
 * <p>
 * Ensures that the canonical module identifiers and profile category names defined in
 * {@link ModuleIds} and {@link ConfigPaths} stay consistent with every consumer that
 * exposes them as public constants or derives config paths from them.
 * <p>
 * If any of these assertions fail, the literal was most likely re-introduced in a
 * consumer class — fix the consumer to reference the SSOT constant instead.
 *
 * @see ModuleIds
 * @see ConfigPaths
 * @see PropertiesProfileLoader
 */
class ConfigPathsConsistencyTest {

    @Nested
    @DisplayName("Module ID SSOT consistency")
    class ModuleIdConsistency {

        @Test
        @DisplayName("NodeProfile.MODULE_ID equals ModuleIds.NODE")
        void nodeProfileModuleId_usesSsot() {
            assertEquals(ModuleIds.NODE, NodeProfile.MODULE_ID);
        }

        @Test
        @DisplayName("NodeLoggingProfile.MODULE_ID equals ModuleIds.NODE")
        void nodeLoggingProfileModuleId_usesSsot() {
            assertEquals(ModuleIds.NODE, NodeLoggingProfile.MODULE_ID);
        }

        @Test
        @DisplayName("DatabaseLoggingProfile.MODULE_ID equals ModuleIds.DATABASE")
        void databaseLoggingProfileModuleId_usesSsot() {
            assertEquals(ModuleIds.DATABASE, DatabaseLoggingProfile.MODULE_ID);
        }

        @Test
        @DisplayName("NodeModule.ID equals ModuleIds.NODE")
        void nodeModuleId_usesSsot() {
            assertEquals(ModuleIds.NODE, NodeModule.ID);
        }

        @Test
        @DisplayName("DatabaseModule.getId() equals ModuleIds.DATABASE")
        void databaseModuleId_usesSsot() {
            assertEquals(ModuleIds.DATABASE, new DatabaseModule().getId());
        }

        @Test
        @DisplayName("BrowserModule.ID equals ModuleIds.BROWSER")
        void browserModuleId_usesSsot() {
            assertEquals(ModuleIds.BROWSER, BrowserModule.ID);
        }
    }

    @Nested
    @DisplayName("Category SSOT consistency")
    class CategoryConsistency {

        @Test
        @DisplayName("PropertiesProfileLoader profile category equals ModuleIds.CATEGORY_PROFILES")
        void profilesCategory_usesSsot() {
            assertEquals(ModuleIds.CATEGORY_PROFILES, PropertiesProfileLoader.DEFAULT_CATEGORY_PROFILES);
        }

        @Test
        @DisplayName("PropertiesProfileLoader logging category equals ModuleIds.CATEGORY_LOGGING")
        void loggingCategory_usesSsot() {
            assertEquals(ModuleIds.CATEGORY_LOGGING, PropertiesProfileLoader.DEFAULT_CATEGORY_LOGGING);
        }

        @Test
        @DisplayName("NodeProfile.CATEGORY equals ModuleIds.CATEGORY_PROFILES")
        void nodeProfileCategory_usesSsot() {
            assertEquals(ModuleIds.CATEGORY_PROFILES, NodeProfile.CATEGORY);
        }
    }

    @Nested
    @DisplayName("Conf root SSOT consistency")
    class ConfRootConsistency {

        @Test
        @DisplayName("PropertiesProfileLoader.DEFAULT_CONF_ROOT equals ConfigPaths.RUNTIME_CONF_ROOT")
        void defaultConfRoot_usesSsot() {
            assertEquals(ConfigPaths.RUNTIME_CONF_ROOT, PropertiesProfileLoader.DEFAULT_CONF_ROOT);
        }

        @Test
        @DisplayName("NodeProfile.CONF_ROOT equals ConfigPaths.RUNTIME_CONF_ROOT")
        void nodeProfileConfRoot_usesSsot() {
            assertEquals(ConfigPaths.RUNTIME_CONF_ROOT, NodeProfile.CONF_ROOT);
        }

        @Test
        @DisplayName("RUNTIME_CONF_ROOT is the expected runtime layout")
        void runtimeConfRoot_hasExpectedValue() {
            assertEquals("./conf", ConfigPaths.RUNTIME_CONF_ROOT);
        }
    }

    @Nested
    @DisplayName("Literal value regression (schema stability)")
    class LiteralStability {

        @Test
        @DisplayName("SSOT literals keep the canonical on-disk values")
        void ssotLiterals_keepCanonicalValues() {
            assertEquals("node", ModuleIds.NODE);
            assertEquals("database", ModuleIds.DATABASE);
            assertEquals("browser", ModuleIds.BROWSER);
            assertEquals("profiles", ModuleIds.CATEGORY_PROFILES);
            assertEquals("logging", ModuleIds.CATEGORY_LOGGING);
            assertEquals("./conf", ConfigPaths.RUNTIME_CONF_ROOT);
            assertEquals("/conf/", ConfigPaths.CLASSPATH_PREFIX);
        }
    }
}