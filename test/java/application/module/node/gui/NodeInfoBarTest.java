package application.module.node.gui;

import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileConflictDetector;
import application.module.node.props.Props;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeInfoBar.
 * Tests component creation, data population, and state refresh behavior.
 * 
 * Uses JUnit 5 following AAA pattern (Arrange-Act-Assert).
 */
class NodeInfoBarTest {

    private NodeProfile testProfile;

    @BeforeEach
    void setUp() {
        // Arrange: Create a test profile with canonical property keys (as used by the runtime)
        testProfile = new NodeProfile("test-profile");
        testProfile.setProperty("network", "mainnet");
        testProfile.setProperty(Props.API_PORT.getName(), "8125");
        testProfile.setProperty(Props.P2P_PORT.getName(), "8123");
        testProfile.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/signum");
    }

    @AfterEach
    void tearDown() {
        // Cleanup if needed
    }

    @Test
    void profile_MariaDbUrl_RendersEngineAndName() {
        // Arrange: DB.Url is the canonical database property
        testProfile.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/signum_main");

        // Act
        String display = ProfileConflictDetector.dbDisplayName(testProfile);

        // Assert: engine + database name (was previously broken by reading database.type)
        assertEquals("MariaDB/signum_main", display);
        assertEquals(3306, ProfileConflictDetector.dbPort(testProfile));
    }

    @Test
    void profile_PostgresqlUrl_RendersEngineAndName() {
        testProfile.setProperty(Props.DB_URL.getName(), "jdbc:postgresql://localhost:5432/signum");

        assertEquals("PostgreSQL/signum", ProfileConflictDetector.dbDisplayName(testProfile));
        assertEquals(5432, ProfileConflictDetector.dbPort(testProfile));
    }

    @Test
    void profile_SqliteUrl_RendersFileName() {
        testProfile.setProperty(Props.DB_URL.getName(), "jdbc:sqlite:file:./database/SQLite/sqlite/signum.sqlite.db");

        String display = ProfileConflictDetector.dbDisplayName(testProfile);
        assertTrue(display.startsWith("SQLite/"), "SQLite label should start with SQLite/: " + display);
        assertTrue(display.endsWith("signum.sqlite.db"), "SQLite label should end with the file name: " + display);
        assertEquals(0, ProfileConflictDetector.dbPort(testProfile), "SQLite is file-based → no port");
    }

    @Test
    void profile_Mainnet_NetworkPropertyIsMainnet() {
        // Arrange already done in setUp
        assertEquals("mainnet", testProfile.getProperty("network"));
    }

    @Test
    void profile_Testnet_NetworkPropertyIsTestnet() {
        testProfile.setProperty("network", "testnet");
        assertEquals("testnet", testProfile.getProperty("network"));
    }

    @Test
    void profile_CustomApiPort_ReturnsCorrectPort() {
        testProfile.setProperty(Props.API_PORT.getName(), "9999");
        assertEquals("9999", ProfileConflictDetector.apiPort(testProfile));
    }

    @Test
    void profile_CustomP2pPort_ReturnsCorrectPort() {
        testProfile.setProperty(Props.P2P_PORT.getName(), "7777");
        assertEquals("7777", ProfileConflictDetector.p2pPort(testProfile));
    }

    @Test
    void profile_DefaultApiPort_FallsBackToDeclaredDefault() {
        NodeProfile freshProfile = new NodeProfile("fresh");
        assertEquals(String.valueOf(Props.API_PORT.getDefaultValue()), ProfileConflictDetector.apiPort(freshProfile));
    }

    @Test
    void profile_DefaultP2pPort_FallsBackToDeclaredDefault() {
        NodeProfile freshProfile = new NodeProfile("fresh");
        assertEquals(String.valueOf(Props.P2P_PORT.getDefaultValue()), ProfileConflictDetector.p2pPort(freshProfile));
    }

    @Test
    void profile_ConflictWithOtherProfile_IsDetected() {
        // Arrange: another profile on the same API.Port
        NodeProfile other = new NodeProfile("conflict-other");
        other.setProperty(Props.API_PORT.getName(), ProfileConflictDetector.apiPort(testProfile));
        other.setProperty(Props.P2P_PORT.getName(), "9002");
        other.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/otherdb");

        // Act
        var conflicts = ProfileConflictDetector.detect(testProfile, java.util.List.of(other), java.util.Set.of());

        // Assert: an API.Port conflict is surfaced (drives the red warning in the info bar)
        assertTrue(conflicts.stream().anyMatch(c -> c.getField() == ProfileConflictDetector.ConflictField.API_PORT),
                "an API.Port collision with another profile must be detected");
    }

    @Test
    void infoBar_ConstructAndRefresh_DoesNotThrow() {
        // Act: build a headless info bar and drive a data refresh (no-op safe in headless).
        NodeInfoBar bar = new NodeInfoBar(testProfile);
        bar.refreshData();

        // Assert: the bar is wired to the profile and renders without throwing.
        assertSame(testProfile, bar.getProfile());
    }
}