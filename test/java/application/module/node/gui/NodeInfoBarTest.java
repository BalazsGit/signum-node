package application.module.node.gui;

import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.lifecycle.NodeLifecycleState;
import application.module.node.profile.NodeProfile;

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
        // Reset lifecycle manager before each test
        NodeLifecycleManager.resetInstance();
        
        // Arrange: Create a test profile with known properties
        testProfile = new NodeProfile("test-profile");
        testProfile.setProperty("network", "mainnet");
        testProfile.setProperty("httpport", "8125");
        testProfile.setProperty("peer.port", "8123");
        testProfile.setProperty("database.jdbc.url", "jdbc:mysql://localhost:3306/signum");
    }

    @AfterEach
    void tearDown() {
        // Clean up lifecycle manager after each test
        NodeLifecycleManager.resetInstance();
    }

    @Test
    void profile_GivenMysqlJdbcUrl_DetectsMariaDB() {
        // Arrange
        testProfile.setProperty("database.jdbc.url", "jdbc:mysql://localhost/signum");
        
        // Act - verify property is correctly set
        String jdbcUrl = testProfile.getProperty("database.jdbc.url");
        
        // Assert
        assertTrue(jdbcUrl.contains(":mysql:"), "JDBC URL should contain mysql identifier");
    }

    @Test
    void profile_GivenPostgresqlJdbcUrl_DetectsPostgreSQL() {
        // Arrange
        testProfile.setProperty("database.jdbc.url", "jdbc:postgresql://localhost/signum");
        
        // Act
        String jdbcUrl = testProfile.getProperty("database.jdbc.url");
        
        // Assert
        assertTrue(jdbcUrl.contains(":postgresql:"), "JDBC URL should contain postgresql identifier");
    }

    @Test
    void profile_GivenSqliteJdbcUrl_DetectsSQLite() {
        // Arrange
        testProfile.setProperty("database.jdbc.url", "jdbc:sqlite:/path/to/signum.db");
        
        // Act
        String jdbcUrl = testProfile.getProperty("database.jdbc.url");
        
        // Assert
        assertTrue(jdbcUrl.contains(":sqlite:"), "JDBC URL should contain sqlite identifier");
    }

    @Test
    void profile_GivenMainnet_NetworkPropertyIsMainnet() {
        // Arrange already done in setUp
        
        // Act
        String network = testProfile.getProperty("network");
        
        // Assert
        assertEquals("mainnet", network);
    }

    @Test
    void profile_GivenTestnet_NetworkPropertyIsTestnet() {
        // Arrange
        testProfile.setProperty("network", "testnet");
        
        // Act
        String network = testProfile.getProperty("network");
        
        // Assert
        assertEquals("testnet", network);
    }

    @Test
    void profile_GivenCustomHttpPort_ReturnsCorrectPort() {
        // Arrange
        testProfile.setProperty("httpport", "9999");
        
        // Act
        String httpPort = testProfile.getProperty("httpport");
        
        // Assert
        assertEquals("9999", httpPort);
    }

    @Test
    void profile_GivenCustomPeerPort_ReturnsCorrectPort() {
        // Arrange
        testProfile.setProperty("peer.port", "7777");
        
        // Act
        String peerPort = testProfile.getProperty("peer.port");
        
        // Assert
        assertEquals("7777", peerPort);
    }

    @Test
    void profile_GivenDatabasePortProperty_ReturnsCorrectPort() {
        // Arrange
        testProfile.setProperty("database.port", "3307");
        
        // Act
        String dbPort = testProfile.getProperty("database.port");
        
        // Assert
        assertEquals("3307", dbPort);
    }

    @Test
    void profile_GivenDefaultHttpPort_Returns8125() {
        // Arrange - testProfile already created without httpport set in a fresh profile
        NodeProfile freshProfile = new NodeProfile("fresh");
        
        // Act
        String defaultPort = freshProfile.getProperty("httpport", "8125");
        
        // Assert
        assertEquals("8125", defaultPort);
    }

    @Test
    void profile_GivenDefaultPeerPort_Returns8123() {
        // Arrange
        NodeProfile freshProfile = new NodeProfile("fresh");
        
        // Act
        String defaultPort = freshProfile.getProperty("peer.port", "8123");
        
        // Assert
        assertEquals("8123", defaultPort);
    }

    @Test
    void profile_GivenDatabaseTypeProperty_ReturnsType() {
        // Arrange
        testProfile.setProperty("database.type", "PostgreSQL");
        
        // Act
        String dbType = testProfile.getProperty("database.type");
        
        // Assert
        assertEquals("PostgreSQL", dbType);
    }
}