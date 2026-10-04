package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the value-status coloring baseline of
 * {@link NodeConfigurationPanel}: the values LOADED from the profile into the
 * editor ARE the applied values. Reported bug: freshly loaded profile values
 * were colored "saved" (and only the hardcoded defaults "applied"), because
 * the applied baseline was pulled from the live PropertyService — empty until
 * the node starts (falling back to the application defaults) and drifting
 * from the file's string form afterwards.
 * <p>
 * Covered: (1) the {@code valueStatus} SSOT resolves a value equal to the
 * loaded/applied baseline as APPLIED (and the rest of the saved/applied/
 * unsaved semantics), and (2) the panel initializes its applied baseline as
 * the profile's loaded properties, and {@code loadAppliedProperties()} does
 * not disturb it.
 * </p>
 */
@DisplayName("NodeConfigurationPanel applied-baseline tests")
class NodeConfigurationPanelAppliedBaselineTest {

    private static final String PROFILE = "applied-baseline-test";

    private static final Path PROFILE_FILE = Path.of("conf", "node", "profiles", PROFILE + ".properties");
    private static final Path META_FILE = Path.of("conf", "node", "profiles.json");

    @Test
    @DisplayName("valueStatus: a value equal to the loaded (applied) baseline is APPLIED")
    void valueStatus_loadedValueIsApplied() throws Exception {
        // The reported scenario: the profile is loaded into the editor, so
        // current == saved == applied — it must resolve to APPLIED (before the
        // fix the applied baseline was empty/defaults, so this came out
        // SAVED).
        assertEquals("APPLIED", valueStatus(false, "44", "44", "44"),
                "a freshly loaded profile value equals the applied baseline");
    }

    @Test
    @DisplayName("valueStatus: APPLIED takes precedence over SAVED")
    void valueStatus_appliedBeatsSaved() throws Exception {
        assertEquals("APPLIED", valueStatus(false, "99", "44", "99"),
                "a value matching the applied baseline is APPLIED even when it differs from the saved file");
    }

    @Test
    @DisplayName("valueStatus: a value saved to disk but differing from the loaded baseline is SAVED")
    void valueStatus_savedNotYetApplied() throws Exception {
        assertEquals("SAVED", valueStatus(false, "77", "77", "44"),
                "saved (on disk) but differing from the loaded baseline is SAVED");
    }

    @Test
    @DisplayName("valueStatus: a value matching neither baseline is UNSAVED")
    void valueStatus_unsaved() throws Exception {
        assertEquals("UNSAVED", valueStatus(false, "custom", "77", "44"),
                "a value matching neither the saved file nor the loaded baseline is UNSAVED");
    }

    @Test
    @DisplayName("valueStatus: checkbox values compare parsed booleans")
    void valueStatus_checkboxBooleanComparison() throws Exception {
        assertEquals("APPLIED", valueStatus(true, "true", "yes", "1"),
                "boolean spellings of the same on-state all compare equal (loaded=yes, applied=1)");
        assertEquals("UNSAVED", valueStatus(true, "false", "true", "true"),
                "a toggled-off checkbox differs from the on-state baseline");
    }

    @Test
    @DisplayName("the panel initializes its applied baseline with the loaded profile values")
    void constructor_appliedBaselineIsTheLoadedProfile() throws Exception {
        Files.createDirectories(PROFILE_FILE.getParent());
        boolean createdProfileFile = !Files.exists(PROFILE_FILE);
        byte[] originalMeta = Files.exists(META_FILE) ? Files.readAllBytes(META_FILE) : null;

        if (createdProfileFile) {
            // A known value that differs from the application default: if the
            // applied baseline were empty (or defaulted), the loaded value
            // could not resolve against it.
            Files.writeString(PROFILE_FILE, "node.valueSuffix=TESTCOIN\n");
        }

        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    // Same icon-font registration the application performs at
                    // startup (AppearanceModule#init) so IconFontSwing resolves.
                    jiconfont.swing.IconFontSwing.register(
                            jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                    JFrame owner = new JFrame("applied-baseline-test-owner");
                    NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                            null, PROFILE);
                    owner.add(panel);
                    owner.setSize(900, 700);
                    owner.setVisible(true);
                    panelRef[0] = panel;
                    ownerRef[0] = owner;
                } catch (Throwable t) {
                    error.set(t);
                }
            });
            assertNotNull(panelRef[0], "the configuration panel must be constructable: " + error.get());

            final Properties[] savedProps = new Properties[1];
            final Properties[] appliedProps = new Properties[1];
            final Properties[] appliedAfterReloadHook = new Properties[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Object saved = readDeclaredField(panelRef[0], "savedProfile");
                    Object applied = readDeclaredField(panelRef[0], "appliedProfile");
                    savedProps[0] = ((application.module.node.profile.NodeProfile) saved).getProperties();
                    appliedProps[0] = ((application.module.node.profile.NodeProfile) applied).getProperties();

                    // The live-value hook must not disturb the loaded baseline.
                    ((NodeConfigurationPanel) panelRef[0]).loadAppliedProperties();
                    Object appliedAfter = readDeclaredField(panelRef[0], "appliedProfile");
                    appliedAfterReloadHook[0] = ((application.module.node.profile.NodeProfile) appliedAfter)
                            .getProperties();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertNotNull(savedProps[0], "savedProfile must be populated");
            assertNotNull(appliedProps[0], "appliedProfile must be populated");
            assertEquals(savedProps[0], appliedProps[0],
                    "the applied baseline must be the profile's LOADED values");
            assertEquals("TESTCOIN", appliedProps[0].getProperty("node.valueSuffix"),
                    "the applied baseline must carry the profile file's value, not the application default");
            assertEquals(appliedProps[0], appliedAfterReloadHook[0],
                    "loadAppliedProperties() must not overwrite the loaded applied baseline");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (ownerRef[0] instanceof JFrame f) {
                    f.dispose();
                }
            });
            if (createdProfileFile) {
                Files.deleteIfExists(PROFILE_FILE);
            }
            if (originalMeta != null) {
                Files.write(META_FILE, originalMeta);
            }
        }
    }

    /** Invokes the private static valueStatus(boolean, String, String, String) SSOT. */
    private static String valueStatus(boolean isCheckbox, String value, String saved, String applied)
            throws Exception {
        Method m = Arrays.stream(NodeConfigurationPanel.class.getDeclaredMethods())
                .filter(it -> it.getName().equals("valueStatus") && it.getParameterCount() == 4)
                .findFirst()
                .orElseThrow(() -> new AssertionError("valueStatus not found"));
        m.setAccessible(true);
        return String.valueOf(m.invoke(null, isCheckbox, value, saved, applied));
    }

    private static Object readDeclaredField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("field not found: " + name);
    }
}