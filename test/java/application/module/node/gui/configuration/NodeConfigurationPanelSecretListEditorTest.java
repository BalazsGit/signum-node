package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the SECRET list editors of {@link NodeConfigurationPanel}
 * (the API admin key, the mining passphrases): the per-item dropdowns mask
 * every entry by default (echo dots), and each row carries its own eye toggle
 * that reveals / hides THAT ONE entry — before selecting it and without
 * closing the dropdown (toggling must not move the combo's selection or
 * editor text). A non-secret list editor (the Jetty DoS whitelist) must keep
 * the plain, unmasked label-only dropdown renderer.
 */
@DisplayName("NodeConfigurationPanel secret (masked) list editor tests")
class NodeConfigurationPanelSecretListEditorTest {

    private static final long INIT_TIMEOUT_MS = 60_000;

    /** The secret list property: the API admin key ("Admin Password (API Key)"). */
    private static final String SECRET_PROP = "API.adminKeyList";
    /** A plain (non-secret) list property (the Jetty DoS whitelist). */
    private static final String PLAIN_PROP = "JETTY.API.DoSFilter.ipWhitelist";

    private static final String SECRET_A = "alphaSecretKey-12345";
    private static final String SECRET_B = "betaSecretKey-67890";

    @Test
    @DisplayName("secret list dropdowns mask every entry; the per-entry eye reveals / hides it without selecting or closing")
    void secretListEditor_masksDropdownEntriesWithPerEntryEyeToggle() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                // The app's L&F + its app-wide password-reveal property, so
                // the editor's built-in reveal eye is present (deterministic).
                UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
                UIManager.put("PasswordField.showRevealButton", true);
                JFrame owner = new JFrame("secret-list-editor-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "secret-list-editor-test");
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

        try {
            waitInit(panelRef[0]);

            final List<Object> result = new ArrayList<>();
            final Object[] e2eHolder = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    runScenario(panelRef[0], result, e2eHolder);
                } catch (Throwable t) {
                    result.add(t);
                }
            });
            if (!result.isEmpty()) {
                Object o = result.get(0);
                if (o instanceof Throwable t) {
                    throw new AssertionError("the secret list-editor scenario failed", t);
                }
                throw new AssertionError("the secret list-editor scenario failed: " + o);
            }
            // The row's eye hook end-to-end (runs on the EDT via the
            // system event queue), on a bare list carrying the same
            // renderer the dropdown uses.
            runHookE2e((JComboBox<String>) e2eHolder[0]);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (ownerRef[0] instanceof Frame owner) {
                    owner.dispose();
                }
            });
        }
    }

    private void waitInit(Object panel) throws Exception {
        final Object[] ready = new Object[1];
        long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && ready[0] == null) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Object rows = readField(panel, "allPropertyRows");
                    if (rows instanceof List<?> list && !list.isEmpty()) {
                        ready[0] = true;
                    }
                } catch (Exception ignore) {
                    // fields not ready yet — keep polling
                }
            });
            if (ready[0] == null) {
                Thread.sleep(100);
            }
        }
        assertNotNull(ready[0], "the panel UI (property rows) must finish initializing");
    }
    @SuppressWarnings("unchecked")
    private void runScenario(Object panel, List<Object> out, Object[] e2eHolder) throws Exception {
        Map<String, Object> listEditors = (Map<String, Object>) readField(panel, "listEditors");
        assertNotNull(listEditors, "the listEditors map must exist");
        Object secretEditor = listEditors.get(SECRET_PROP);
        Object plainEditor = listEditors.get(PLAIN_PROP);
        assertNotNull(secretEditor, "the API admin key (secret) editor must exist");
        assertNotNull(plainEditor, "the Jetty DoS whitelist (plain) editor must exist");

        // Load two known secrets into the secret editor.
        invokeSetItems(secretEditor, List.of(SECRET_A, SECRET_B));
        List<JComboBox<String>> combos = (List<JComboBox<String>>) readField(secretEditor, "itemFields");
        assertEquals(2, combos.size(), "one editable combo per stored secret item");
        JComboBox<String> combo = combos.get(0);
        JList<Object> probe = new JList<>(new Object[] { SECRET_A, SECRET_B });
        @SuppressWarnings("unchecked")
        ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) combo.getRenderer();

        // 1. The dropdown entry is MASKED by default and carries its own
        //    (painted) eye icon.
        Component cell = renderer.getListCellRendererComponent(probe, SECRET_A, 0, false, false);
        assertTrue(cell instanceof JPanel, "the secret dropdown row must be a panel with an eye icon");
        JLabel label = findLabel(cell);
        JLabel eye = findEye(cell);
        assertNotNull(label, "the secret dropdown row must carry its text label");
        assertNotNull(eye, "the secret dropdown row must carry its own eye icon");
        assertEquals(maskOf(SECRET_A), label.getText(), "the dropdown entry must be masked by default");
        assertNotEquals(SECRET_A, label.getText());

        // 2. The add row's dropdown is masked with the same per-entry eye.
        JComboBox<String> addField = (JComboBox<String>) readField(secretEditor, "addField");
        @SuppressWarnings("unchecked")
        ListCellRenderer<Object> addRenderer = (ListCellRenderer<Object>) addField.getRenderer();
        Component addCell = addRenderer.getListCellRendererComponent(probe, SECRET_A, 0, false, false);
        assertTrue(addCell instanceof JPanel, "the add row's dropdown row must carry the eye icon too");
        assertNotNull(findEye(addCell), "the add row's dropdown row must carry its own eye icon");
        assertEquals(maskOf(SECRET_A), findLabel(addCell).getText(),
                "the add row's dropdown entries must be masked by default");

        // 3. A NON-secret list editor keeps the plain label-only renderer.
        JComboBox<String> plainAdd = (JComboBox<String>) readField(plainEditor, "addField");
        @SuppressWarnings("unchecked")
        ListCellRenderer<Object> plainRenderer = (ListCellRenderer<Object>) plainAdd.getRenderer();
        Component plainCell = plainRenderer.getListCellRendererComponent(probe, "1.2.3.4", 0, false, false);
        assertTrue(plainCell instanceof JLabel,
                "a plain list editor must keep the label-only (unmasked) dropdown renderer");

        // 4. The EDITABLE input is a (masked) password field: hidden by
        //    default, under every L&F.
        JComponent editorComp = (JComponent) combo.getEditor().getEditorComponent();
        assertTrue(editorComp instanceof JPasswordField,
                "the secret editor must be a masked password field (hidden by default)");
        JPasswordField field = (JPasswordField) editorComp;

        // 5. The L&F's built-in reveal eye (FlatLaf gives every password
        //    field one — see GuiManager#applyDefaultsAfterLaf) toggles the
        //    mask in place and never changes the value or the selection.
        AbstractButton editorEye = findButton(editorComp);
        assertNotNull(editorEye, "the password editor must carry the L&F's reveal eye");
        Object valueBefore = combo.getEditor().getItem();
        int selectionBefore = combo.getSelectedIndex();
        char echoBefore = field.getEchoChar();
        editorEye.doClick();
        assertNotEquals(echoBefore, field.getEchoChar(), "the editor's eye must toggle the mask");
        assertEquals(valueBefore, combo.getEditor().getItem(),
                "the editor's eye must not change the field's value");
        assertEquals(selectionBefore, combo.getSelectedIndex(),
                "the editor's eye must not change the combo's selection");
        editorEye.doClick(); // back to the default (masked) state

        e2eHolder[0] = combo;
    }

    /**
     * End-to-end for the row's eye hook, exercising the EXACT hook code the
     * dropdown installs (see NodeConfigurationPanel.installSecretRowHooks)
     * on a bare list carrying the same renderer: a posted click on a row's
     * painted EYE must toggle only that entry's reveal WITHOUT selecting the
     * row (which is precisely what keeps the real dropdown open), while a
     * posted click on the ROW itself must select the entry (which closes the
     * real dropdown). A bare list is used so the test does not depend on the
     * heavyweight popup being laid out and painted in the test environment.
     */
    @SuppressWarnings("unchecked")
    private void runHookE2e(JComboBox<String> combo) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JList<String> list = new JList<>(new String[] { SECRET_A, SECRET_B });
            list.setFixedCellWidth(240);
            list.setFixedCellHeight(24);
            list.setSize(240, 48);
            // Install the row hooks by invoking the renderer — the same
            // code path the dropdown's painting takes.
            ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) combo.getRenderer();
            for (int i = 0; i < list.getModel().getSize(); i++) {
                renderer.getListCellRendererComponent(list, list.getModel().getElementAt(i), i,
                        i == list.getSelectedIndex(), false);
            }
            list.doLayout();
            assertTrue(Boolean.TRUE.equals(list.getClientProperty("secretRowMouseHook")),
                    "the row's eye hook must be installed by the renderer");
            Set<String> revealed = (Set<String>) list.getClientProperty("secretRowRevealed");
            assertNotNull(revealed, "the revealed set must be stored on the list");
            assertFalse(revealed.contains(SECRET_A), "the entry must start masked");

            // -- click the row's EYE: reveal only — no selection.
            clickPoint(list, 0, true);
            assertTrue(revealed.contains(SECRET_A), "the eye must reveal the entry");
            assertEquals(-1, list.getSelectedIndex(), "the eye must not select the row");

            // -- click the EYE again: hide again, still no selection.
            clickPoint(list, 0, true);
            assertFalse(revealed.contains(SECRET_A), "the eye must hide the entry again");
            assertEquals(-1, list.getSelectedIndex(),
                    "the second eye click must not select the row");

            // -- reveal once more, then click the ROW (text area): selects it.
            clickPoint(list, 0, true);
            assertTrue(revealed.contains(SECRET_A), "re-reveal before selecting");
            clickPoint(list, 0, false);
            assertEquals(0, list.getSelectedIndex(), "clicking the row must select the entry");
        });
    }

    /**
     * Posts a full press/release/click on the given row's eye (eye=true) or
     * text area (eye=false) of the list. The event is dispatched on the list
     * itself, so its listener chain (the row's eye hook included) runs
     * exactly as it would for a routed user click.
     */
    private static void clickPoint(JList<String> list, int index, boolean eye) {
        Rectangle bounds = list.getCellBounds(index, index);
        assertNotNull(bounds, "the row's cell bounds must be available");
        int x = eye ? (int) bounds.getMaxX() - 5 : bounds.x + 10;
        int y = bounds.y + bounds.height / 2;
        long when = System.currentTimeMillis();
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, when, 0, x, y, 1, false, 1));
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, when, 0, x, y, 1, false, 1));
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, when, 0, x, y, 1, false, 1));
    }

    /** The masked form the renderer must show: one echo dot per character. */
    private static String maskOf(String s) {
        StringBuilder dots = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            dots.append('\u2022');
        }
        return dots.toString();
    }

    private static JLabel findLabel(Component cell) {
        for (Component c : ((Container) cell).getComponents()) {
            if (c instanceof JLabel label) {
                return label;
            }
        }
        return null;
    }

    /** The row's painted eye icon (the icon label at the row's right edge). */
    private static JLabel findEye(Component cell) {
        for (Component c : ((Container) cell).getComponents()) {
            if (c instanceof JLabel label && label.getIcon() != null) {
                return label;
            }
        }
        return null;
    }

    /**
     * The first button anywhere below the given component — a
     * {@link AbstractButton}, because the L&F's password reveal control is a
     * JToggleButton (NOT a JButton — that extends JRadioButton).
     */
    private static AbstractButton findButton(JComponent c) {
        if (c instanceof AbstractButton button) {
            return button;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (child instanceof JComponent jc) {
                    AbstractButton found = findButton(jc);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private static void invokeSetItems(Object editor, List<String> values) throws Exception {
        Method m = editor.getClass().getDeclaredMethod("setItems", List.class);
        m.setAccessible(true);
        m.invoke(editor, values);
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignore) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
    }
}
