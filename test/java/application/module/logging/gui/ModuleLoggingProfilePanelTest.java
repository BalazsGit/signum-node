package application.module.logging.gui;

import application.module.logging.LoggingProfileRepository;
import application.utils.gui.GuiColors;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    @DisplayName("the profile combo lists the virtual Default entry, NOT the reserved sample config")
    void defaultEntryListedInsteadOfSampleConfig() {
        ModuleLoggingProfilePanel panel = newPanel();
        JComboBox<String> combo = panel.getProfileCombo();
        assertEquals(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY, combo.getItemAt(0),
                "Default is the first (built-in) profile entry");
        for (int i = 0; i < combo.getItemCount(); i++) {
            org.junit.jupiter.api.Assertions.assertNotEquals(LoggingProfileRepository.RESERVED_PROFILE_NAME,
                    combo.getItemAt(i), "the on-disk sample config must not be listed");
        }
    }

    @Test
    @DisplayName("toolbar buttons carry FontAwesome icons (node-configuration look)")
    void toolbarButtonsHaveIcons() {
        ModuleLoggingProfilePanel panel = newPanel();
        assertNotNull(findButton(panel, "New").getIcon(), "New icon present");
        assertNotNull(findButton(panel, "Save").getIcon(), "Save icon present");
        assertNotNull(findButton(panel, "Apply").getIcon(), "Apply icon present");
        assertNotNull(findButton(panel, "Rename").getIcon(), "Rename icon present");
        assertNotNull(findButton(panel, "Delete").getIcon(), "Delete icon present");
        assertNotNull(findButton(panel, "Refresh").getIcon(), "Refresh icon present");
        assertNotNull(findButton(panel, "Reset to Defaults").getIcon(), "Reset icon present");
        assertNotNull(findButton(panel, "Reload").getIcon(), "Reload icon present");
    }

    @Test
    @DisplayName("combo renderer: the applied profile gets the applied color + check icon, others do not")
    void comboRendererMarksAppliedProfile() {
        ModuleLoggingProfilePanel panel = newPanel();
        JComboBox<String> combo = panel.getProfileCombo();
        @SuppressWarnings("unchecked")
        ListCellRenderer<String> renderer = (ListCellRenderer<String>) combo.getRenderer();
        JList<String> list = new JList<>();

        String notApplied = "definitely-not-applied-" + System.nanoTime();
        JLabel other = (JLabel) renderer.getListCellRendererComponent(list, notApplied, -1, false, false);
        assertNull(other.getIcon(), "a non-applied profile has no check icon");

        // Mirror the panel's own lookup: the applied marker of the test module.
        String marker;
        try {
            marker = new LoggingProfileRepository().getApplied("test-mod");
        } catch (Exception e) {
            marker = null;
        }
        boolean defaultApplied = marker == null || marker.isBlank()
                || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(marker);
        String appliedEntry = defaultApplied ? ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY : marker;
        JLabel applied = (JLabel) renderer.getListCellRendererComponent(list, appliedEntry, -1, false, false);
        assertNotNull(applied.getIcon(), "the applied profile shows the check icon");
        assertEquals(GuiColors.getApplied(), applied.getForeground(), "the applied profile is painted green");
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

    @Test
    @DisplayName("typing and deleting keep the keyboard focus in the row editor")
    void typingKeepsFocusInRowEditor() {
        // Repro of the reported bug: in the handlers field only the first
        // character landed (later: after one backspace the field dead-locked).
        // Cause: the per-keystroke update re-rendered the row grid
        // (removeAll + re-add) and detaching the focused field dropped the AWT
        // keyboard focus — the follow-up focus request races the AWT focus
        // machinery and silently loses. The per-keystroke path must update the
        // rows in place and never detach the focused editor.
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        final Object[] refs = new Object[2]; // [0]=JFrame, [1]=JTextField
        final AtomicReference<Throwable> error = new AtomicReference<>();
        onEdt(() -> {
            try {
                ModuleLoggingProfilePanel panel = new ModuleLoggingProfilePanel(new TestProvider());
                JComponent handler = editorAfterLabel(panel, "test.handler");
                assertInstanceOf(JTextField.class, handler, "the handler row is a text field");
                JFrame frame = new JFrame("logging-focus-test-owner");
                frame.setLayout(new BorderLayout());
                frame.add(panel);
                frame.setSize(700, 600);
                frame.setVisible(true);
                refs[0] = frame;
                refs[1] = handler;
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNull(error.get(), "the panel must be constructable in a frame: " + error.get());
        JFrame frame = (JFrame) refs[0];
        assertTrue(awaitActive(frame), "the test window could not be activated");
        JTextField field = (JTextField) refs[1];
        try {
            onEdt(() -> field.requestFocusInWindow());
            assertTrue(awaitFocusOwner(field), "the field gains focus on click");

            // Type "abc" without re-clicking — exactly the user's repro.
            for (char c : "abc".toCharArray()) {
                onEdt(() -> {
                    field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_PRESSED,
                            System.nanoTime(), 0, KeyEvent.VK_UNDEFINED, KeyEvent.CHAR_UNDEFINED));
                    field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_TYPED,
                            System.nanoTime(), 0, 0, c));
                });
            }
            assertTrue(field.getText().endsWith("abc"),
                    "all three characters must land in the field, got: " + field.getText());

            // Backspace once, then type again — the follow-up regression:
            // after one deletion the field dead-locked (no further typing or
            // deleting was possible at all).
            onEdt(() -> field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    System.nanoTime(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED)));
            assertTrue(field.getText().endsWith("ab"),
                    "the backspace must delete the last character, got: " + field.getText());
            onEdt(() -> {
                field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_PRESSED,
                        System.nanoTime(), 0, KeyEvent.VK_UNDEFINED, KeyEvent.CHAR_UNDEFINED));
                field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_TYPED,
                        System.nanoTime(), 0, 0, 'x'));
            });
            assertTrue(field.getText().endsWith("abx"),
                    "typing must still work after a deletion, got: " + field.getText());
            assertTrue(awaitFocusOwner(field),
                    "the row editor must keep the keyboard focus across typing "
                            + "and deletion — otherwise every further keystroke "
                            + "is lost (the reported '1 character' bug)");
        } finally {
            onEdt(() -> ((JFrame) refs[0]).dispose());
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Component focusOwner() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    }

    /** Waits up to 3 s for the window to become active (requestFocusInWindow needs it). */
    private static boolean awaitActive(JFrame frame) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (onEdtBoolean(frame::isActive)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return onEdtBoolean(frame::isActive);
    }

    private static boolean onEdtBoolean(java.util.function.BooleanSupplier action) {
        final boolean[] result = new boolean[1];
        onEdt(() -> result[0] = action.getAsBoolean());
        return result[0];
    }

    /** Waits (polling the EDT) until the given component is the focus owner. */
    private static boolean awaitFocusOwner(Component expected) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (onEdtBoolean(() -> expected == focusOwner())) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
