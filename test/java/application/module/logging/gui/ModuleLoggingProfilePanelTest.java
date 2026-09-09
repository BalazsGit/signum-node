package application.module.logging.gui;

import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the core {@link ModuleLoggingProfilePanel} (headless-safe: construction and
 * inspection are pumped on the EDT, mirroring {@code LoggingPanelTest}/{@code NodeLoggingPanelTest}).
 * <p>
 * Verifies the provider-driven header, the per-key value-typed editor rows, the CRUD toolbar,
 * and the host extension points ({@code setLinkControl}, {@code setApplyHook}).
 * </p>
 */
@DisplayName("ModuleLoggingProfilePanel Tests")
class ModuleLoggingProfilePanelTest {

    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ModuleLoggingProfilePanel newPanel() {
        final ModuleLoggingProfilePanel[] holder = new ModuleLoggingProfilePanel[1];
        onEdt(() -> holder[0] = new ModuleLoggingProfilePanel(new TestProvider()));
        return holder[0];
    }

    private static List<String> allLabels(Container root) {
        List<String> out = new ArrayList<>();
        collectLabels(root, out);
        return out;
    }

    private static void collectLabels(Container c, List<String> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel) {
                out.add(((JLabel) child).getText());
            }
            if (child instanceof Container) {
                collectLabels((Container) child, out);
            }
        }
    }

    private static JComponent editorAfterLabel(Container root, String label) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JLabel && label.equals(((JLabel) child).getText())
                    && i + 1 < root.getComponentCount()) {
                return (JComponent) root.getComponent(i + 1);
            }
            if (child instanceof Container) {
                JComponent found = editorAfterLabel((Container) child, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String text) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JButton && text.equals(((JButton) child).getText())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton found = findButton((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean hasComponent(Container root, Class<?> type) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (type.isInstance(child)) {
                return true;
            }
            if (child instanceof Container && hasComponent((Container) child, type)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("constructs without a host context and shows the provider header")
    void constructsAndShowsHeader() {
        List<String> labels = allLabels(newPanel());
        assertTrue(labels.contains("Test Module"), "display name shown; labels=" + labels);
        assertTrue(labels.contains("test provider"), "description shown; labels=" + labels);
    }

    @Test
    @DisplayName("renders an editor row for each default key")
    void rendersDefaultRows() {
        List<String> labels = allLabels(newPanel());
        assertTrue(labels.contains("test.level"), "level row present; labels=" + labels);
        assertTrue(labels.contains("test.handler"), "handler row present; labels=" + labels);
    }

    @Test
    @DisplayName("value-typed editors: log level → combo, other → text field")
    void valueTypedEditors() {
        ModuleLoggingProfilePanel panel = newPanel();
        assertInstanceOf(JComboBox.class, editorAfterLabel(panel, "test.level"),
                "a log-level key must use a combo editor");
        assertInstanceOf(JTextField.class, editorAfterLabel(panel, "test.handler"),
                "a non-level key must use a text-field editor");
    }

    @Test
    @DisplayName("the CRUD toolbar offers Apply and Refresh")
    void toolbarButtons() {
        ModuleLoggingProfilePanel panel = newPanel();
        assertNotNull(findButton(panel, "Apply"), "Apply button present");
        assertNotNull(findButton(panel, "Refresh"), "Refresh button present");
    }

    @Test
    @DisplayName("setLinkControl adds a host control to the component tree")
    void setLinkControl() {
        final ModuleLoggingProfilePanel[] holder = new ModuleLoggingProfilePanel[1];
        onEdt(() -> {
            ModuleLoggingProfilePanel p = new ModuleLoggingProfilePanel(new TestProvider());
            p.setLinkControl(new JCheckBox("Link to node profile"));
            holder[0] = p;
        });
        assertTrue(hasComponent(holder[0], JCheckBox.class), "link control present after setLinkControl");
    }

    @Test
    @DisplayName("setApplyHook is chainable (returns the same panel instance)")
    void applyHookChainable() {
        final ModuleLoggingProfilePanel[] before = new ModuleLoggingProfilePanel[1];
        final ModuleLoggingProfilePanel[] after = new ModuleLoggingProfilePanel[1];
        onEdt(() -> {
            ModuleLoggingProfilePanel p = new ModuleLoggingProfilePanel(new TestProvider());
            before[0] = p;
            after[0] = p.setApplyHook(name -> { });
        });
        assertSame(before[0], after[0], "setApplyHook returns the same instance");
    }

    // ── Test fixture ───────────────────────────────────────────────────

    static final class TestProvider extends ModuleLoggingProvider {
        @Override
        public ModuleLoggingProfile getProfile() {
            return new ModuleLoggingProfile() {
                @Override
                public String getModuleId() {
                    return "test-mod";
                }

                @Override
                public String getDisplayName() {
                    return "Test Module";
                }

                @Override
                public String getDescription() {
                    return "test provider";
                }

                @Override
                public Map<String, String> getDefaults() {
                    return Map.of(
                            "test.level", "INFO",
                            "test.handler", "java.util.logging.ConsoleHandler");
                }

                @Override
                public Map<String, Map<String, String>> getPresetOverrides() {
                    return Collections.emptyMap();
                }
            };
        }
    }
}
