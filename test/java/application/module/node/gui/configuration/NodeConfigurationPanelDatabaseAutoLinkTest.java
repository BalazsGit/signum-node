package application.module.node.gui.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration test of the SQLite database auto-link: when the node profile
 * uses a SQLite database that follows the per-profile convention
 * ({@code ./database/SQLite/<profile>}) and that database profile exists,
 * the panel auto-links on load — the "Linked Database Profile" checkbox is
 * selected and the unified JDBC panel switches to linked mode resolved to
 * the profile. The file-based assignment is derivable from the properties
 * alone, so nothing is persisted for it.
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
    private static final Path NODE_PROFILES_META = Path.of("conf", "node", "profiles.json");
    private static final Path DB_PROFILE_DIR = Path.of("database", "SQLite", PROFILE);
    private static final long INIT_TIMEOUT_MS = 30_000;

    private byte[] dbMetaBackup;
    private boolean dbMetaExisted;
    private byte[] nodeMetaBackup;
    private boolean nodeMetaExisted;
    private byte[] nodeProfilesMetaBackup;
    private boolean nodeProfilesMetaExisted;

    @BeforeEach
    void setUp() throws IOException {
        dbMetaExisted = Files.exists(DB_META);
        dbMetaBackup = dbMetaExisted ? Files.readAllBytes(DB_META) : null;
        nodeMetaExisted = Files.exists(NODE_META);
        nodeMetaBackup = nodeMetaExisted ? Files.readAllBytes(NODE_META) : null;
        nodeProfilesMetaExisted = Files.exists(NODE_PROFILES_META);
        nodeProfilesMetaBackup = nodeProfilesMetaExisted ? Files.readAllBytes(NODE_PROFILES_META) : null;

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
        if (nodeProfilesMetaExisted) {
            Files.write(NODE_PROFILES_META, nodeProfilesMetaBackup);
        } else {
            Files.deleteIfExists(NODE_PROFILES_META);
        }
        Files.deleteIfExists(NODE_PROPS);
        Files.deleteIfExists(DB_PROFILE_DIR.resolve("profile.json"));
        Files.deleteIfExists(DB_PROFILE_DIR); // created by this test
    }

    @Test
    @DisplayName("an applied SQLite profile is auto-linked on load: checkbox on, derived (unpersisted) link, name selected")
    void autoLinkAppliedDatabaseProfile() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
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

            // the unified JDBC panel is in linked mode, resolved to the
            // matching applied profile, and materializes its JDBC URL
            final Object[] jdbc = new Object[3];
            SwingUtilities.invokeAndWait(() -> {
                JdbcConfigurationPanel pp = (JdbcConfigurationPanel) ((javax.swing.JComponent) wrapperRef[0])
                        .getClientProperty("profilePanel");
                jdbc[0] = pp.isLinkedMode();
                jdbc[1] = pp.getProfileName();
                jdbc[2] = pp.getJdbcUrl();
            });
            assertTrue((Boolean) jdbc[0], "the JDBC row must be in linked mode after the auto-link");
            assertEquals(PROFILE, jdbc[1], "the JDBC row must resolve the auto-linked profile name");
            assertNotNull(jdbc[2], "the linked mode must materialize the profile's JDBC URL");
            assertTrue(((String) jdbc[2]).contains(PROFILE),
                    "the materialized JDBC URL must point at the linked profile's database");

            // The file-based assignment is derivable from the properties
            // alone, so it must NOT be persisted to the node profile metadata
            // (server-engine links are the only ones persisted).
            String meta = Files.exists(NODE_PROFILES_META) ? Files.readString(NODE_PROFILES_META) : "";
            assertFalse(meta.contains("\"databaseProfileLink\""),
                    "a SQLite assignment is derived from the properties and must not be persisted");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = ownerRef.get();
                if (owner != null) {
                    owner.dispose();
                }
            });
        }
    }

    @Test
    @DisplayName("reload from disk re-derives the JDBC row: fresh discovery, the link checkbox follows the reloaded values")
    void reloadRederivesTheJdbcRowFromDisk() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
        final AtomicReference<JFrame> ownerRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                // the same icon-font registration the application performs at
                // startup so IconFontSwing resolves
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("db-autolink-reload-test-owner");
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
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline
                    && !("SQLite:" + PROFILE).equals(panel.getLinkedDbProfile())) {
                Thread.sleep(100);
            }
            assertEquals("SQLite:" + PROFILE, panel.getLinkedDbProfile(),
                    "the auto-link must settle first");

            // The database file on disk changed (an external edit): a
            // different file of the SAME convention profile.
            Files.writeString(DB_PROFILE_DIR.resolve("other.db"), "");
            Files.writeString(NODE_PROPS,
                    "DB.Url=jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/other.db" + System.lineSeparator());
            reloadFromDisk(panel);

            JdbcConfigurationPanel jdbc = jdbcPanelOf(panel);
            assertTrue(jdbc.isLinkedMode(),
                    "the convention URL must still auto-link after the reload");
            assertEquals(PROFILE, jdbc.getProfileName(),
                    "the profile discovery must resolve the same profile");
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/other.db", jdbc.getJdbcUrl(),
                    "the JDBC row must reflect the reloaded database file");
            assertTrue(linkCheckboxSelected(panel),
                    "the Linked Database Profile checkbox must stay on (still convention)");

            // And when the disk no longer follows the convention: manual mode,
            // checkbox off, every field from the reloaded values.
            Files.writeString(NODE_PROPS,
                    "DB.Url=jdbc:sqlite:file:./elsewhere/manual.db" + System.lineSeparator());
            reloadFromDisk(panel);

            jdbc = jdbcPanelOf(panel);
            assertFalse(jdbc.isLinkedMode(),
                    "a non-convention URL must reload into manual mode");
            assertEquals("jdbc:sqlite:file:./elsewhere/manual.db", jdbc.getJdbcUrl(),
                    "the manual Path + DB file must come from the reloaded values");
            assertFalse(linkCheckboxSelected(panel),
                    "the Linked Database Profile checkbox must follow the reload off");
        } finally {
            // the class tearDown deletes DB_PROFILE_DIR non-recursively
            Files.deleteIfExists(DB_PROFILE_DIR.resolve("other.db"));
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = ownerRef.get();
                if (owner != null) {
                    owner.dispose();
                }
            });
        }
    }

    /** Invokes the private reloadFromDisk on the EDT (the Reload button's discard path). */
    private static void reloadFromDisk(NodeConfigurationPanel panel) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Method m = NodeConfigurationPanel.class.getDeclaredMethod("reloadFromDisk");
                m.setAccessible(true);
                m.invoke(panel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("node (re)start re-anchors the applied baseline and refreshes the JDBC row from disk (no manual Reload)")
    void onNodeRestartedRefreshesTheJdbcRowFromDisk() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
        final AtomicReference<JFrame> ownerRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("db-autolink-restart-test-owner");
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
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline
                    && !("SQLite:" + PROFILE).equals(panel.getLinkedDbProfile())) {
                Thread.sleep(100);
            }
            assertEquals("SQLite:" + PROFILE, panel.getLinkedDbProfile(),
                    "the auto-link must settle first");
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/signum.sqlite.db",
                    appliedDbUrl(panel),
                    "before the restart the applied baseline is the loaded (stale) file");

            // The profile file changed on disk (saved / externally edited) and
            // the node was restarted: it now runs with the on-disk state.
            Files.writeString(DB_PROFILE_DIR.resolve("other.db"), "");
            Files.writeString(NODE_PROPS,
                    "DB.Url=jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/other.db" + System.lineSeparator());
            onNodeRestarted(panel);

            JdbcConfigurationPanel jdbc = jdbcPanelOf(panel);
            assertTrue(jdbc.isLinkedMode(),
                    "the convention URL must still be linked after the restart synchronization");
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/other.db", jdbc.getJdbcUrl(),
                    "the JDBC row must be refreshed from the on-disk state");
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/other.db", appliedDbUrl(panel),
                    "the applied baseline must be re-anchored to the on-disk state");
            assertFalse(applyBtnEnabled(panel),
                    "with applied == saved there are no saved-not-applied changes (Apply disabled)");
        } finally {
            Files.deleteIfExists(DB_PROFILE_DIR.resolve("other.db"));
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = ownerRef.get();
                if (owner != null) {
                    owner.dispose();
                }
            });
        }
    }

    @Test
    @DisplayName("node (re)start with unsaved editor changes: the work is kept, only the applied baseline is re-anchored")
    void onNodeRestartedKeepsUnsavedEditorChanges() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
        final AtomicReference<JFrame> ownerRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("db-autolink-restart-unsaved-test-owner");
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
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline
                    && !("SQLite:" + PROFILE).equals(panel.getLinkedDbProfile())) {
                Thread.sleep(100);
            }
            assertEquals("SQLite:" + PROFILE, panel.getLinkedDbProfile(),
                    "the auto-link must settle first");

            // An unsaved edit in the editor (a plain text row) and a changed
            // profile file on disk, then the node restarts.
            setFieldText(panel, "P2P.myPlatform", "unsaved-platform");
            Files.writeString(DB_PROFILE_DIR.resolve("other.db"), "");
            Files.writeString(NODE_PROPS,
                    "DB.Url=jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/other.db" + System.lineSeparator());
            onNodeRestarted(panel);

            assertEquals("unsaved-platform", fieldText(panel, "P2P.myPlatform"),
                    "unsaved editor work must survive the restart synchronization");
            JdbcConfigurationPanel jdbc = jdbcPanelOf(panel);
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/signum.sqlite.db",
                    jdbc.getJdbcUrl(),
                    "the editor must keep the user's current (not yet saved) state");
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/other.db", appliedDbUrl(panel),
                    "the applied baseline must still be re-anchored to the on-disk state");
        } finally {
            Files.deleteIfExists(DB_PROFILE_DIR.resolve("other.db"));
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = ownerRef.get();
                if (owner != null) {
                    owner.dispose();
                }
            });
        }
    }

    /** Invokes the public onNodeRestarted on the EDT (the node state push target). */
    private static void onNodeRestarted(NodeConfigurationPanel panel) throws Exception {
        SwingUtilities.invokeAndWait(panel::onNodeRestarted);
    }

    private static JdbcConfigurationPanel jdbcPanelOf(NodeConfigurationPanel panel) throws Exception {
        final Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("propertyComponents");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, javax.swing.JComponent> comps = (Map<String, javax.swing.JComponent>) f.get(panel);
                out[0] = ((javax.swing.JComponent) comps.get("DB.Url")).getClientProperty("profilePanel");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return (JdbcConfigurationPanel) out[0];
    }

    private static boolean linkCheckboxSelected(NodeConfigurationPanel panel) throws Exception {
        final Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("propertyComponents");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, javax.swing.JComponent> comps = (Map<String, javax.swing.JComponent>) f.get(panel);
                out[0] = ((javax.swing.JComponent) comps.get("DB.Url")).getClientProperty("useProfileCheck");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return out[0] instanceof JCheckBox && ((JCheckBox) out[0]).isSelected();
    }

    /** The DB.Url of the applied baseline (the on-disk state the node runs with). */
    private static String appliedDbUrl(NodeConfigurationPanel panel) throws Exception {
        final Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("appliedProfile");
                f.setAccessible(true);
                out[0] = ((application.module.node.profile.NodeProfile) f.get(panel))
                        .getProperties().getProperty("DB.Url");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return (String) out[0];
    }

    /** Whether the Apply toolbar button is enabled (saved, not-yet-applied changes present). */
    private static boolean applyBtnEnabled(NodeConfigurationPanel panel) throws Exception {
        final Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("applyBtn");
                f.setAccessible(true);
                out[0] = ((javax.swing.JButton) f.get(panel)).isEnabled();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return (Boolean) out[0];
    }

    /** Reads a value row's current text from the EDT (plain field or state-aware select editor). */
    private static String fieldText(NodeConfigurationPanel panel, String key) throws Exception {
        final Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("propertyComponents");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, javax.swing.JComponent> comps = (Map<String, javax.swing.JComponent>) f.get(panel);
                out[0] = editableText(comps.get(key)).getText();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return (String) out[0];
    }

    /** Types into a value row's editor on the EDT (commits as an unsaved change). */
    private static void setFieldText(NodeConfigurationPanel panel, String key, String value) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field f = NodeConfigurationPanel.class.getDeclaredField("propertyComponents");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, javax.swing.JComponent> comps = (Map<String, javax.swing.JComponent>) f.get(panel);
                editableText(comps.get(key)).setText(value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** The editor text field of a value row (plain field or state-aware select). */
    private static javax.swing.JTextField editableText(javax.swing.JComponent c) {
        if (c instanceof javax.swing.JTextField tf) {
            return tf;
        }
        if (c instanceof javax.swing.JComboBox<?> cb && cb.isEditable()
                && cb.getEditor().getEditorComponent() instanceof javax.swing.JTextField ef) {
            return ef;
        }
        throw new AssertionError("no editable text input for component: " + c);
    }
}
