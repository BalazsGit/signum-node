package application.module.node.gui.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test of the applied-SQLite-profile auto-link: when the database
 * module has an <b>applied</b> SQLite profile and the node profile uses the
 * matching SQLite database without an explicit database link, the panel
 * records the assignment on load — the "Linked Database Profile" checkbox is
 * selected, the link is persisted into the node metadata, and the UI shows
 * the linked profile name (the profile card is active).
 * <p>
 * The test works against the real {@code ./conf} runtime folder (the panel
 * resolves node profiles from it) and backs up / restores the touched
 * metadata files so it never destroys user state.
 */
@DisplayName("NodeConfigurationPanel - applied database profile auto-link")
class NodeConfigurationPanelDatabaseAutoLinkTest {

    private static final String PROFILE = "db-autolink-test";
    private static final Path NODE_PROPS = Path.of("conf", "node", "profiles", PROFILE + ".properties");
    private static final Path DB_META = Path.of("conf", "database", "profile.json");
    private static final Path NODE_META = Path.of("conf", "node", "profile.json");
    private static final Path DB_PROFILE_DIR = Path.of("database", "SQLite", PROFILE);
    private static final long INIT_TIMEOUT_MS = 30_000;

    private byte[] dbMetaBackup;
    private boolean dbMetaExisted;
    private byte[] nodeMetaBackup;
    private boolean nodeMetaExisted;

    @BeforeEach
    void setUp() throws IOException {
        dbMetaExisted = Files.exists(DB_META);
        dbMetaBackup = dbMetaExisted ? Files.readAllBytes(DB_META) : null;
        nodeMetaExisted = Files.exists(NODE_META);
        nodeMetaBackup = nodeMetaExisted ? Files.readAllBytes(NODE_META) : null;

        // the node profile carries the per-profile SQLite URL
        Files.createDirectories(NODE_PROPS.getParent());
        Files.writeString(NODE_PROPS,
                "DB.Url=jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/signum.sqlite.db" + System.lineSeparator());
        // the database module's applied marker
        Files.createDirectories(DB_META.getParent());
        Files.writeString(DB_META, "{\"appliedProfile\": \"SQLite:" + PROFILE + "\"}");
        // the profile must exist in the database profile area (the combo list
        // is scanned from there); the profile.json carries the very URL the
        // node profile uses, so the linked card can select that instance
        Files.createDirectories(DB_PROFILE_DIR);
        Files.writeString(DB_PROFILE_DIR.resolve("profile.json"),
                "{\"DB.Url\": \"jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/signum.sqlite.db\"}");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dbMetaExisted) {
            Files.write(DB_META, dbMetaBackup);
        } else {
            Files.deleteIfExists(DB_META);
        }
        if (nodeMetaExisted) {
            Files.write(NODE_META, nodeMetaBackup);
        } else {
            Files.deleteIfExists(NODE_META);
        }
        Files.deleteIfExists(NODE_PROPS);
        Files.deleteIfExists(DB_PROFILE_DIR.resolve("profile.json"));
        Files.deleteIfExists(DB_PROFILE_DIR); // created by this test
    }

    @Test
    @DisplayName("an applied SQLite profile is auto-linked on load: checkbox on, link persisted, name selected")
    void autoLinkAppliedDatabaseProfile() throws Exception {
        final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
        final AtomicReference<JFrame> ownerRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                // the same icon-font registration the application performs at
                // startup so IconFontSwing resolves
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("db-autolink-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null, null, PROFILE);
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                panelRef.set(panel);
                ownerRef.set(owner);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNotNull(panelRef.get(), "the configuration panel must be constructable: " + error.get());
        NodeConfigurationPanel panel = panelRef.get();
        try {
            // wait for the async UI construction AND the auto-link (the
            // persisted link shows up as the panel's linked database profile)
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline
                    && !("SQLite:" + PROFILE).equals(panel.getLinkedDbProfile())) {
                Thread.sleep(100);
            }
            assertEquals("SQLite:" + PROFILE, panel.getLinkedDbProfile(),
                    "the applied database profile must be auto-linked within " + INIT_TIMEOUT_MS + " ms");

            // the "Linked Database Profile" checkbox of the DB.Url row is on
            final Object[] wrapperRef = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("propertyComponents");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, javax.swing.JComponent> comps = (Map<String, javax.swing.JComponent>) f.get(panel);
                    wrapperRef[0] = comps.get("DB.Url");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Object useProfileCheck = ((javax.swing.JComponent) wrapperRef[0]).getClientProperty("useProfileCheck");
            assertTrue(useProfileCheck instanceof JCheckBox && ((JCheckBox) useProfileCheck).isSelected(),
                    "the Linked Database Profile checkbox must be selected");

            // the profile card shows the linked profile name
            final Object[] profileSelRef = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Object pp = ((javax.swing.JComponent) wrapperRef[0]).getClientProperty("profilePanel");
                    java.lang.reflect.Field combo = pp.getClass().getDeclaredField("profileCombo");
                    combo.setAccessible(true);
                    profileSelRef[0] = ((javax.swing.JComboBox<?>) combo.get(pp)).getSelectedItem();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals(PROFILE, profileSelRef[0], "the profile card must show the linked profile name");

            // the profile card is the active card of the JDBC row and its
            // database row is materialized (the linked profile's configuration
            // is in effect, not a half-empty card)
            final Object[] activeCardRef = new Object[1];
            final Object[] dbSelRef = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    javax.swing.JComponent wrapper = (javax.swing.JComponent) wrapperRef[0];
                    Object currentCard = wrapper.getClientProperty("currentJdbcCard");
                    activeCardRef[0] = currentCard instanceof java.awt.Component[] cards ? cards[0] : currentCard;
                    Object pp = wrapper.getClientProperty("profilePanel");
                    java.lang.reflect.Field dbCombo = pp.getClass().getDeclaredField("dbCombo");
                    dbCombo.setAccessible(true);
                    dbSelRef[0] = ((javax.swing.JComboBox<?>) dbCombo.get(pp)).getSelectedItem();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            javax.swing.JComponent wrapper = (javax.swing.JComponent) wrapperRef[0];
            assertSame(wrapper.getClientProperty("profilePanel"), activeCardRef[0],
                    "the profile card must be the active card of the JDBC row");
            assertNotNull(dbSelRef[0], "the linked profile's database instance must be selected");

            // the assignment is persisted into the node metadata
            String meta = Files.readString(NODE_META);
            assertTrue(meta.contains("\"database\"") && meta.contains("SQLite:" + PROFILE),
                    "the node metadata must carry the persisted database link");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = ownerRef.get();
                if (owner != null) {
                    owner.dispose();
                }
            });
        }
    }
}

