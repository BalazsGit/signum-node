package application.module.node.gui.configuration;

import application.utils.gui.GuiColors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration test of the JDBC section's profile-select state coloring after
 * a save: picking a database profile that differs from the applied one and
 * saving must move the SAVED (yellow) baseline to the new profile while the
 * APPLIED (green) baseline keeps pointing at the profile the node still runs
 * with — in the popup list AND in the collapsed display, exactly like the
 * other state-aware configuration selects.
 * <p>
 * Works against the real {@code ./conf} runtime folder (backed up / restored,
 * same pattern as {@link NodeConfigurationPanelDatabaseAutoLinkTest}).
 */
@DisplayName("NodeConfigurationPanel - JDBC profile select colors after a save")
class NodeConfigurationPanelJdbcProfileColorTest {

    private static final String PROFILE = "jdbc-color-test";
    private static final String D1 = "jdbc-color-d1";
    private static final String D2 = "jdbc-color-d2";
    private static final Path NODE_PROPS = Path.of("conf", "node", "profiles", PROFILE + ".properties");
    private static final Path DB_META = Path.of("conf", "database", "profile.json");
    private static final Path NODE_META = Path.of("conf", "node", "profile.json");
    private static final Path D1_DIR = Path.of("database", "SQLite", D1);
    private static final Path D2_DIR = Path.of("database", "SQLite", D2);
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

        // the node profile uses D1's convention URL (auto-links to D1 on load)
        Files.createDirectories(NODE_PROPS.getParent());
        Files.writeString(NODE_PROPS,
                "DB.Url=jdbc:sqlite:file:./database/SQLite/" + D1 + "/signum.sqlite.db" + System.lineSeparator());
        // the database module's applied marker
        Files.createDirectories(DB_META.getParent());
        Files.writeString(DB_META, "{\"appliedProfile\": \"SQLite:" + D1 + "\"}");
        // both database profiles exist in the database profile area (the
        // combo list is scanned from there)
        Files.createDirectories(D1_DIR);
        Files.writeString(D1_DIR.resolve("profile.json"),
                "{\"DB.Url\": \"jdbc:sqlite:file:./database/SQLite/" + D1 + "/signum.sqlite.db\"}");
        Files.createDirectories(D2_DIR);
        Files.writeString(D2_DIR.resolve("profile.json"),
                "{\"DB.Url\": \"jdbc:sqlite:file:./database/SQLite/" + D2 + "/signum.sqlite.db\"}");
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
        Files.deleteIfExists(D1_DIR.resolve("profile.json"));
        Files.deleteIfExists(D1_DIR);
        Files.deleteIfExists(D2_DIR.resolve("profile.json"));
        Files.deleteIfExists(D2_DIR);
    }
    private static NodeConfigurationPanel buildPanel(AtomicReference<Throwable> error) {
        jiconfont.swing.IconFontSwing.register(jiconfont.icons.font_awesome.FontAwesome.getIconFont());
        JFrame owner = new JFrame("jdbc-color-test-owner");
        NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null, null, PROFILE);
        owner.add(panel);
        owner.setSize(900, 700);
        owner.setVisible(true);
        return panel;
    }

    @SuppressWarnings("unchecked")
    private static JdbcConfigurationPanel jdbcPanel(NodeConfigurationPanel panel) throws Exception {
        java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("propertyComponents");
        f.setAccessible(true);
        Map<String, javax.swing.JComponent> comps = (Map<String, javax.swing.JComponent>) f.get(panel);
        javax.swing.JComponent wrapper = comps.get("DB.Url");
        assertNotNull(wrapper, "the DB.Url row wrapper must be registered");
        return (JdbcConfigurationPanel) wrapper.getClientProperty("profilePanel");
    }

    @Test
    @DisplayName("after saving a different profile pick, the popup colors applied (green) and saved (yellow) per baseline")
    void profileSelectColorsFollowBaselinesAfterSave() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelRef.set(buildPanel(error)));
        assertNotNull(panelRef.get(), "the configuration panel must be constructable: " + error.get());
        NodeConfigurationPanel panel = panelRef.get();

        // wait for the async construction + the D1 auto-link
        long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline
                && !("SQLite:" + D1).equals(panel.getLinkedDbProfile())) {
            Thread.sleep(100);
        }
        assertEquals("SQLite:" + D1, panel.getLinkedDbProfile(),
                "the applied database profile must be auto-linked within " + INIT_TIMEOUT_MS + " ms");

        final AtomicReference<JdbcConfigurationPanel> jdbcRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                jdbcRef.set(jdbcPanel(panel));
            } catch (Exception e) {
                error.set(e);
            }
        });
        assertNull(error.get(), "the JDBC panel must be reachable: " + error.get());
        JdbcConfigurationPanel jdbc = jdbcRef.get();

        // Pick the OTHER profile (D2) and save the profile.
        SwingUtilities.invokeAndWait(() -> {
            ((JComboBox<Object>) jdbc.getProfileComboComponent()).setSelectedItem(D2);
        });
        SwingUtilities.invokeAndWait(() -> {
            // flush the link-change follow-up work
        });
        assertEquals("SQLite:" + D2, panel.getLinkedDbProfile(),
                "selecting D2 must move the effective link");

        final Object[] saved = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Method m = NodeConfigurationPanel.class.getDeclaredMethod("doSaveCurrentProfile");
                m.setAccessible(true);
                saved[0] = m.invoke(panel);
            } catch (Exception e) {
                error.set(e);
            }
        });
        assertNull(error.get(), "the save must run without error: " + error.get());
        assertTrue((Boolean) saved[0], "the profile save must succeed");

        // The popup list: D1 (what the node still runs with) is applied
        // (green); D2 (what is now recorded on disk) is saved (yellow).
        final Object[] colors = new Object[2];
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JList<Object> dummy = new javax.swing.JList<>(new Object[] { D1, D2 });
            @SuppressWarnings("unchecked")
            javax.swing.ListCellRenderer<Object> renderer = (javax.swing.ListCellRenderer<Object>)
                    ((JComboBox<?>) jdbc.getProfileComboComponent()).getRenderer();
            colors[0] = renderer.getListCellRendererComponent(dummy, D1, 0, false, false).getForeground();
            colors[1] = renderer.getListCellRendererComponent(dummy, D2, 1, false, false).getForeground();
        });
        assertEquals(GuiColors.getApplied(), colors[0],
                "the applied profile entry must stay applied (green) in the popup after the save");
        assertEquals(GuiColors.getSaved(), colors[1],
                "the newly saved profile entry must be saved (yellow) in the popup after the save");

        // The collapsed display: the selected (D2) link now equals the saved
        // baseline but differs from the applied one -> saved (yellow), the
        // same state the other configuration rows report after a save — and
        // the color must reach the VISIBLE editor text of the editable combo,
        // not only the combo component itself.
        final Object[] collapsed = new Object[2];
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<?> combo = (JComboBox<?>) jdbc.getProfileComboComponent();
            collapsed[0] = combo.getForeground();
            collapsed[1] = ((JComponent) combo.getEditor().getEditorComponent()).getForeground();
        });
        assertEquals(GuiColors.getSaved(), collapsed[0],
                "the collapsed profile select must show the saved (yellow) state after the save");
        assertEquals(GuiColors.getSaved(), collapsed[1],
                "the editable combo's visible editor text must carry the saved (yellow) state after the save");
    }

    // ------------------------------------------------------------------
    // Server engine variant: the applied link is the RECORDED assignment of
    // the applied profile (profiles.json), not a convention derivation.
    // ------------------------------------------------------------------

    private static final String MPROFILE = "jdbc-color-maria";
    private static final String MD1 = "jdbc-color-m1";
    private static final String MD2 = "jdbc-color-m2";
    private static final Path M_NODE_PROPS = Path.of("conf", "node", "profiles", MPROFILE + ".properties");
    private static final Path M_D1_DIR = Path.of("database", "MariaDB", MD1);
    private static final Path M_D2_DIR = Path.of("database", "MariaDB", MD2);
    private static final Path PROFILE_CONFIG = Path.of("conf", "node", "profiles.json");

    private byte[] profileConfigBackup;
    private boolean profileConfigExisted;

    @Test
    @DisplayName("server engine: after saving a different recorded profile pick, the popup colors applied (green) and saved (yellow) per baseline")
    void serverEngineProfileSelectColorsFollowBaselinesAfterSave() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        profileConfigExisted = Files.exists(PROFILE_CONFIG);
        profileConfigBackup = profileConfigExisted ? Files.readAllBytes(PROFILE_CONFIG) : null;
        try {
            // the node profile uses MD1's instance URL
            Files.createDirectories(M_NODE_PROPS.getParent());
            Files.writeString(M_NODE_PROPS,
                    "DB.Url=jdbc:mariadb://db.example:3306/appdb" + System.lineSeparator());
            // the recorded assignment of the loaded (applied) profile: MD1
            Files.writeString(PROFILE_CONFIG,
                    "{\"profiles\":{\"" + MPROFILE + "\":{\"databaseProfileLink\":\"MariaDB:" + MD1 + "\"}}}");
            // both MariaDB profiles exist with their instances
            Files.createDirectories(M_D1_DIR);
            Files.writeString(M_D1_DIR.resolve("profile.json"),
                    "{\"databases\":[{\"name\":\"appdb\",\"url\":\"jdbc:mariadb://db.example:3306/appdb\","
                            + "\"users\":[{\"username\":\"alice\",\"password\":\"secret\"}]}]}");
            Files.createDirectories(M_D2_DIR);
            Files.writeString(M_D2_DIR.resolve("profile.json"),
                    "{\"databases\":[{\"name\":\"otherdb\",\"url\":\"jdbc:mariadb://db.example:3306/otherdb\","
                            + "\"users\":[{\"username\":\"bob\",\"password\":\"other\"}]}]}");

            final AtomicReference<Throwable> error = new AtomicReference<>();
            final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                jiconfont.swing.IconFontSwing.register(jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("jdbc-color-maria-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null, null, MPROFILE);
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                panelRef.set(panel);
            });
            assertNotNull(panelRef.get(), "the configuration panel must be constructable: " + error.get());
            NodeConfigurationPanel panel = panelRef.get();

            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline
                    && !("MariaDB:" + MD1).equals(panel.getLinkedDbProfile())) {
                Thread.sleep(100);
            }
            assertEquals("MariaDB:" + MD1, panel.getLinkedDbProfile(),
                    "the recorded MariaDB assignment must be linked within " + INIT_TIMEOUT_MS + " ms");

            final AtomicReference<JdbcConfigurationPanel> jdbcRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    jdbcRef.set(jdbcPanel(panel));
                } catch (Exception e) {
                    error.set(e);
                }
            });
            assertNull(error.get(), "the JDBC panel must be reachable: " + error.get());
            JdbcConfigurationPanel jdbc = jdbcRef.get();

            // Pick the OTHER profile (MD2) and save the profile.
            SwingUtilities.invokeAndWait(() -> {
                ((JComboBox<Object>) jdbc.getProfileComboComponent()).setSelectedItem(MD2);
            });
            SwingUtilities.invokeAndWait(() -> {
                // flush the link-change follow-up work
            });
            assertEquals("MariaDB:" + MD2, panel.getLinkedDbProfile(),
                    "selecting MD2 must move the effective link");

            final Object[] saved = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Method m = NodeConfigurationPanel.class.getDeclaredMethod("doSaveCurrentProfile");
                    m.setAccessible(true);
                    saved[0] = m.invoke(panel);
                } catch (Exception e) {
                    error.set(e);
                }
            });
            assertNull(error.get(), "the save must run without error: " + error.get());
            assertTrue((Boolean) saved[0], "the profile save must succeed");

            final Object[] colors = new Object[2];
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.JList<Object> dummy = new javax.swing.JList<>(new Object[] { MD1, MD2 });
                @SuppressWarnings("unchecked")
                javax.swing.ListCellRenderer<Object> renderer = (javax.swing.ListCellRenderer<Object>)
                        ((JComboBox<?>) jdbc.getProfileComboComponent()).getRenderer();
                colors[0] = renderer.getListCellRendererComponent(dummy, MD1, 0, false, false).getForeground();
                colors[1] = renderer.getListCellRendererComponent(dummy, MD2, 1, false, false).getForeground();
            });
            assertEquals(GuiColors.getApplied(), colors[0],
                    "the recorded applied MariaDB profile must stay applied (green) after the save");
            assertEquals(GuiColors.getSaved(), colors[1],
                    "the newly saved MariaDB profile must be saved (yellow) after the save");
        } finally {
            if (profileConfigExisted) {
                Files.write(PROFILE_CONFIG, profileConfigBackup);
            } else {
                Files.deleteIfExists(PROFILE_CONFIG);
            }
            Files.deleteIfExists(M_NODE_PROPS);
            Files.deleteIfExists(M_D1_DIR.resolve("profile.json"));
            Files.deleteIfExists(M_D1_DIR);
            Files.deleteIfExists(M_D2_DIR.resolve("profile.json"));
            Files.deleteIfExists(M_D2_DIR);
        }
    }
}

