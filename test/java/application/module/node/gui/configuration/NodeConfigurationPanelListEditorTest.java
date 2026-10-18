package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the per-item list editor of {@link NodeConfigurationPanel}:
 * a list-typed property is rendered as one EDITABLE COMBO per stored item,
 * stacked one below the other (each with a trash icon) plus an "add" row
 * (editable combo + flat '+' button). This test drives a real list property
 * on the EDT and verifies: every item renders as its own combo, addItem /
 * removeItem keep the combo list in sync, the value (de)serializes with the
 * property's on-disk delimiter (',' for the Jetty DoS whitelists, ';'
 * otherwise), and each item is colored by its own state (applied / unsaved).
 */
@DisplayName("NodeConfigurationPanel list (per-item) editor tests")
class NodeConfigurationPanelListEditorTest {

    private static final long INIT_TIMEOUT_MS = 60_000;

    /** A list property whose on-disk value is COMMA-separated (Jetty DoS whitelist). */
    private static final String COMMA_PROP = "JETTY.API.DoSFilter.ipWhitelist";
    /** A list property whose on-disk value is SEMICOLON-separated (getStringList). */
    private static final String SEMI_PROP = "API.adminKeyList";

    @Test
    @DisplayName("a list property is one field per item; add/remove work and serialization keeps the delimiter")
    void listEditor_perItemFieldsAddRemoveAndDelimiter() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("list-editor-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "list-editor-test");
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
            SwingUtilities.invokeAndWait(() -> {
                try {
                    runScenario(panelRef[0], result);
                } catch (Throwable t) {
                    result.add(t);
                }
            });
            if (!result.isEmpty()) {
                Object o = result.get(0);
                if (o instanceof Throwable t) {
                    throw new AssertionError("the list-editor scenario failed", t);
                }
                throw new AssertionError("the list-editor scenario failed: " + o);
            }
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
    private void runScenario(Object panel, List<Object> out) throws Exception {
        Map<String, Object> listEditors = (Map<String, Object>) readField(panel, "listEditors");
        assertNotNull(listEditors, "the listEditors map must exist");
        Object commaEditor = listEditors.get(COMMA_PROP);
        Object semiEditor = listEditors.get(SEMI_PROP);
        assertNotNull(commaEditor, "the comma-delimited whitelist editor must exist");
        assertNotNull(semiEditor, "the semicolon-delimited editor must exist");

        // 1. Load items -> one field per item (display order preserved).
        invoke(commaEditor, "setItems", new Class<?>[] { List.class }, List.of("10.0.0.1", "10.0.0.2"));
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), invoke(commaEditor, "getItems", (Class<?>[]) null),
                "setItems must store the items in order");
        assertEquals(2, itemFieldCount(commaEditor), "each item must render as its own field");

        // 2. Add an item -> a new field appears.
        invoke(commaEditor, "addItem", new Class<?>[] { String.class }, "10.0.0.9");
        assertEquals(List.of("10.0.0.1", "10.0.0.2", "10.0.0.9"),
                invoke(commaEditor, "getItems", (Class<?>[]) null), "addItem must append a new item");
        assertEquals(3, itemFieldCount(commaEditor), "addItem must add a field");

        // 3. Remove the middle item -> only that field disappears.
        List<JComboBox<String>> fields = (List<JComboBox<String>>) readField(commaEditor, "itemFields");
        invoke(commaEditor, "removeItem", new Class<?>[] { JComboBox.class }, fields.get(1));
        assertEquals(List.of("10.0.0.1", "10.0.0.9"),
                invoke(commaEditor, "getItems", (Class<?>[]) null), "removeItem must drop that item only");
        assertEquals(2, itemFieldCount(commaEditor), "removeItem must drop a field");

        // 4. The joined value is newline-joined in display order.
        assertEquals("10.0.0.1\n10.0.0.9", invoke(commaEditor, "getJoinedValue", (Class<?>[]) null));

        // 5. Serialization keeps each property's on-disk delimiter.
        Map<String, Supplier<String>> suppliers = (Map<String, Supplier<String>>) readField(panel, "valueSuppliers");
        invoke(commaEditor, "setItems", new Class<?>[] { List.class }, List.of("a1", "b2"));
        assertEquals("a1,b2", suppliers.get(COMMA_PROP).get(),
                "the Jetty DoS whitelist must serialize comma-separated");
        invoke(semiEditor, "setItems", new Class<?>[] { List.class }, List.of("a1", "b2"));
        assertEquals("a1;b2", suppliers.get(SEMI_PROP).get(),
                "every other list property must serialize semicolon-separated");

        // 6. Per-item coloring: a saved item differs from an added (unsaved)
        //    one (the color lives on the combo's EDITOR text component).
        invoke(commaEditor, "setItems", new Class<?>[] { List.class }, List.of("127.0.0.1", "9.9.9.9"));
        List<JComboBox<String>> colored = (List<JComboBox<String>>) readField(commaEditor, "itemFields");
        invoke(commaEditor, "refreshItemColors", new Class<?>[] { List.class, List.class },
                List.of("127.0.0.1"), List.of("127.0.0.1"));
        assertEquals(GuiColors.getApplied(), editorText(colored.get(0)).getForeground(),
                "an item present in saved+applied must be colored APPLIED");
        assertEquals(GuiColors.getUnsaved(), editorText(colored.get(1)).getForeground(),
                "an item absent from saved must be colored UNSAVED");

        // 7. The per-item dropdowns hold ONLY the row's OWN baseline values:
        //    the saved and the applied items the profiles list at this row's
        //    POSITION (row i <-> the i-th saved / applied item) — never the
        //    whole lists (every dropdown used to list every item of the
        //    saved / applied sets). Row 2 was edited from its saved value
        //    "10.0.0.2" to "9.9.9.9": its dropdown offers the current value
        //    plus its OWN saved baseline ("10.0.0.2") so the item can be
        //    reverted by selecting it; its own applied baseline equals the
        //    current value and is not listed twice.
        invoke(commaEditor, "setItems", new Class<?>[] { List.class }, List.of("10.0.0.1", "9.9.9.9"));
        List<JComboBox<String>> rows = (List<JComboBox<String>>) readField(commaEditor, "itemFields");
        invoke(commaEditor, "refreshItemColors", new Class<?>[] { List.class, List.class },
                List.of("10.0.0.1", "10.0.0.2"), List.of("10.0.0.1", "9.9.9.9"));
        JComboBox<String> editedRow = rows.get(1); // "9.9.9.9": edited, applied, not saved
        assertEquals(GuiColors.getUnsavedApplied(), editorText(editedRow).getForeground(),
                "an applied-but-not-saved item must be colored UNSAVED_APPLIED");
        assertEquals(List.of("9.9.9.9", "10.0.0.2"), comboModelItems(editedRow),
                "row 2's dropdown must hold the current value plus row 2's OWN saved baseline only "
                        + "(not the whole saved / applied lists)");
        JComboBox<String> savedRow = rows.get(0); // "10.0.0.1": saved + applied
        assertEquals(List.of("10.0.0.1"), comboModelItems(savedRow),
                "row 1's dropdown must not list row 2's values (only row 1's own baselines)");
        // Every dropdown entry is painted by the state of its OWN value: the
        // row's current value keeps its own gray unsaved-applied state (never
        // applied-green, even though it equals the applied one) and the
        // saved baseline entry is painted SAVED.
        JList<String> dummyList = new JList<>(new String[] { "9.9.9.9", "10.0.0.2" });
        java.awt.Component ownEntry = editedRow.getRenderer()
                .getListCellRendererComponent(dummyList, "9.9.9.9", 0, false, false);
        assertEquals(GuiColors.getUnsavedApplied(), ownEntry.getForeground(),
                "the dropdown entry equal to an unsaved-applied item's own value must be gray, "
                        + "not applied-green");
        java.awt.Component savedEntry = editedRow.getRenderer()
                .getListCellRendererComponent(dummyList, "10.0.0.2", 1, false, false);
        assertEquals(GuiColors.getSaved(), savedEntry.getForeground(),
                "the row's saved baseline entry must be painted SAVED");

        // 8. An EMPTY list leaves no blank strip above the add row: the
        //    property row is pinned to the editor's own preferred height
        //    (like the regular inputs' "height pref!"), not to a fixed 80px
        //    floor (a reported bug: the big empty area above the add field).
        Map<String, JComponent> propertyComponents =
                (Map<String, JComponent>) readField(panel, "propertyComponents");
        JComponent adminKeyComp = propertyComponents.get(SEMI_PROP);
        assertNotNull(adminKeyComp, "the admin-key list property component must be registered");
        invoke(semiEditor, "setItems", new Class<?>[] { List.class }, List.of());
        adminKeyComp.revalidate();
        adminKeyComp.validate();
        int laidOut = adminKeyComp.getHeight();
        int preferred = adminKeyComp.getPreferredSize().height;
        assertTrue(laidOut <= preferred + 2,
                "an EMPTY list row must not carry a blank strip above the add row "
                        + "(laid out " + laidOut + "px, editor preferred " + preferred + "px)");

        // 9. The item rows stack VERTICALLY, one below the other (a reported
        //    regression: the rows appeared side by side).
        invoke(commaEditor, "setItems", new Class<?>[] { List.class }, List.of("a1", "b2", "c3"));
        JPanel itemsPanel = (JPanel) readField(commaEditor, "itemsPanel");
        // Lay the items panel out explicitly (its preferred size is not
        // resolvable off-screen): give it a generous size and run the layout
        // manager directly.
        itemsPanel.setSize(600, 200);
        itemsPanel.doLayout();
        assertTrue(itemsPanel.getComponentCount() >= 12, "three item rows and the add row must be rendered");
        java.awt.Rectangle row0 = itemsPanel.getComponent(0).getBounds();
        java.awt.Rectangle row1 = itemsPanel.getComponent(3).getBounds();
        java.awt.Rectangle row2 = itemsPanel.getComponent(6).getBounds();
        assertTrue(row1.y > row0.y && row2.y > row1.y,
                "the item rows must stack one below the other (y: "
                        + row0.y + ", " + row1.y + ", " + row2.y + ")");
        JComboBox<String> addField = (JComboBox<String>) readField(commaEditor, "addField");
        List<JComboBox<String>> itemCombos = (List<JComboBox<String>>) readField(commaEditor, "itemFields");
        assertEquals(itemCombos.get(0).getPreferredSize().height, addField.getPreferredSize().height,
                "the add-row input and existing item inputs must have identical preferred heights");
        assertEquals(row0.height, addField.getBounds().height,
                "the add-row input and existing item inputs must have identical laid-out heights");
        for (int i = 0; i < itemCombos.size(); i++) {
            java.awt.Rectangle itemBounds = itemCombos.get(i).getBounds();
            assertEquals(itemBounds.x, addField.getX(),
                    "the add-row input must start at the same x as item row " + i);
            assertEquals(itemBounds.width, addField.getWidth(),
                    "the add-row input must have the same width as item row " + i);
            java.awt.Component itemAction = itemsPanel.getComponent(i * 3 + 1);
            assertEquals(itemAction.getX(), itemsPanel.getComponent(10).getX(),
                    "the add '+' must align with the trash icon in item row " + i);
        }
        // No scroll pane of their own: the items live directly in the list
        // editor (which then takes exactly as much width as it needs).
        assertTrue(!(itemsPanel.getParent() instanceof javax.swing.JViewport),
                "the item rows must not be wrapped in their own scroll pane");

        // 10. The "?" help button of a list property sits INSIDE the FIRST
        //     item row, right of that row's trash icon (the uniform gap) —
        //     instead of a separate help column of the property row (the
        //     reported blemish: the ? floated off the first item row's line).
        List<?> allRows = (List<?>) readField(panel, "allPropertyRows");
        Object adminKeyRow = null;
        for (Object r : allRows) {
            if ("Admin Password (API Key)".equals(readField(r, "labelText"))) {
                adminKeyRow = r;
                break;
            }
        }
        assertNotNull(adminKeyRow,
                "the admin key list property row must exist (labelText \"Admin Password (API Key)\")");
        assertNull(readField(adminKeyRow, "help"),
                "the list property must NOT carry a separate help-column button (the ? travels in the first item row)");
        // The ? button lives in the editor, on the FIRST item row — in the
        // SAME grid as that row's trash icon.
        invoke(semiEditor, "setItems", new Class<?>[] { List.class }, List.of("probe.key"));
        JPanel adminItemsPanel = (JPanel) readField(semiEditor, "itemsPanel");
        adminItemsPanel.setSize(600, 80);
        adminItemsPanel.doLayout();
        assertNotNull(adminItemsPanel.getComponentCount() > 0 ? adminItemsPanel.getComponent(0) : null,
                "the first item row is rendered");
        javax.swing.JButton firstRowHelp = null;
        javax.swing.JButton firstRowTrash = null;
        for (java.awt.Component c : adminItemsPanel.getComponents()) {
            if (c instanceof application.utils.gui.HelpButton) {
                firstRowHelp = (javax.swing.JButton) c;
            }
            if (c instanceof javax.swing.JButton button && button != firstRowHelp
                    && button.getToolTipText() != null && button.getToolTipText().contains("Remove this item")) {
                firstRowTrash = button;
            }
        }
        assertNotNull(firstRowHelp, "the first item row carries the list's ? help button");
        assertNotNull(firstRowTrash, "the first item row carries its trash icon");
        assertTrue(firstRowHelp.getParent() == firstRowTrash.getParent(),
                "the ? help button must sit in the first item row, next to that row's trash icon");
        // The ? is to the RIGHT of the trash icon, on the same row.
        java.awt.Point trashP = new java.awt.Point();
        java.awt.Point helpP = new java.awt.Point();
        firstRowTrash.getLocation(trashP);
        firstRowHelp.getLocation(helpP);
        assertTrue(helpP.x > trashP.x + firstRowTrash.getWidth(),
                "the ? help button must sit to the RIGHT of the trash icon (trash x=" + trashP.x
                        + " w=" + firstRowTrash.getWidth() + ", ? x=" + helpP.x + ")");
        assertTrue(Math.abs((trashP.y + firstRowTrash.getHeight() / 2)
                - (helpP.y + firstRowHelp.getHeight() / 2)) <= 2,
                "the ? help button must be vertically centered on the first item row's line "
                        + "(trash centerY=" + (trashP.y + firstRowTrash.getHeight() / 2)
                        + ", ? centerY=" + (helpP.y + firstRowHelp.getHeight() / 2) + ")");

        // 11. The trash and "+" buttons use the same flat-button style and
        //     occupy the same centered action column in their respective rows.
        JPanel listEditorPanel = (JPanel) commaEditor;
        listEditorPanel.setSize(600, 200);
        listEditorPanel.doLayout();
        JPanel listItemsPanel = (JPanel) readField(commaEditor, "itemsPanel");
        listItemsPanel.doLayout();
        javax.swing.JButton rowTrash = (javax.swing.JButton) listItemsPanel.getComponent(1);
        javax.swing.JButton rowPlus = (javax.swing.JButton) listItemsPanel
                .getComponent(listItemsPanel.getComponentCount() - 2);
        assertTrue(rowTrash instanceof application.utils.gui.FlatIconButton,
                "the trash button must use the shared flat icon-button style");
        assertTrue(rowPlus instanceof application.utils.gui.FlatIconButton,
                "the add button must use the shared flat icon-button style");
        assertEquals(rowTrash.getX(), rowPlus.getX(),
                "the trash and plus buttons must be horizontally aligned (trash " + rowTrash.getBounds()
                        + ", plus " + rowPlus.getBounds() + ")");
        assertEquals(rowTrash.getWidth(), rowPlus.getWidth(),
                "the trash and plus buttons must fill the same-width action column");

        // 14. The profile-toolbar Help button uses the same flat, hover-scaled
        //     icon-button interaction as the logging profile toolbar.
        javax.swing.JButton toolbarHelp = (javax.swing.JButton) readField(panel, "helpBtn");
        javax.swing.JButton toolbarSave = (javax.swing.JButton) readField(panel, "saveBtn");
        assertTrue(!(toolbarHelp instanceof application.utils.gui.HelpButton),
                "the profile-toolbar Help button must not carry the separate HelpButton context-menu behavior");
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, toolbarHelp.getCursor().getType(),
                "the profile-toolbar Help button must use the standard arrow cursor (not a hand)");
        assertNotNull(toolbarHelp.getRolloverIcon(),
                "the profile-toolbar Help button must use the shared hover-scale interaction");
        assertEquals(toolbarSave.getIcon().getIconWidth(), toolbarHelp.getIcon().getIconWidth(),
                "the Help glyph must match the other profile-toolbar glyph sizes");

        // 12. Separate list properties keep the same field-column width,
        //     regardless of whether the current values are long or empty.
        invoke(commaEditor, "setItems", new Class<?>[] { List.class },
                List.of("a-very-long-list-entry-that-should-not-expand-the-field"));
        invoke(semiEditor, "setItems", new Class<?>[] { List.class }, List.of());
        assertEquals(((JComponent) commaEditor).getPreferredSize().width,
                ((JComponent) semiEditor).getPreferredSize().width,
                "list editors must have a stable preferred width independent of their item values");

        // 13. Selecting an add-row dropdown value only fills the editor.
        //     The '+' button or Enter is the explicit add action.
        invoke(commaEditor, "setItems", new Class<?>[] { List.class }, List.of("existing"));
        invoke(commaEditor, "refreshItemColors", new Class<?>[] { List.class, List.class },
                List.of("candidate"), List.of());
        JComboBox<String> addCombo = (JComboBox<String>) readField(commaEditor, "addField");
        assertEquals(List.of("candidate"), comboModelItems(addCombo),
                "a removed saved value must be available in the add-row dropdown");
        addCombo.setSelectedItem("candidate");
        assertEquals(1, itemFieldCount(commaEditor),
                "choosing an add-row dropdown entry must not add it automatically");
        JTextField addEditor = editorText(addCombo);
        addCombo.getEditor().setItem("candidate");
        assertEquals(1, itemFieldCount(commaEditor),
                "entering or selecting text must not add it without an explicit action");
        Object enterActionKey = addEditor.getInputMap(JComponent.WHEN_FOCUSED)
                .get(javax.swing.KeyStroke.getKeyStroke("ENTER"));
        assertNotNull(enterActionKey, "the add-row editor must map Enter to an explicit add action");
        addEditor.getActionMap().get(enterActionKey).actionPerformed(
                new java.awt.event.ActionEvent(addEditor, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
        assertEquals(List.of("existing", "candidate"),
                invoke(commaEditor, "getItems", (Class<?>[]) null),
                "pressing Enter in the add-row editor must add its value");
        addCombo.getEditor().setItem("third");
        ((javax.swing.JButton) readField(commaEditor, "addButton")).doClick();
        assertEquals(List.of("existing", "candidate", "third"),
                invoke(commaEditor, "getItems", (Class<?>[]) null),
                "the '+' button must add the value currently in the editor");
    }

    /** The dropdown entries of a combo, in order. */
    private static List<String> comboModelItems(JComboBox<String> combo) {
        javax.swing.ComboBoxModel<String> model = combo.getModel();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < model.getSize(); i++) {
            items.add(model.getElementAt(i));
        }
        return items;
    }

    /** Invokes a (possibly private/package-private) method on the target. */
    private static Object invoke(Object target, String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = findMethod(target.getClass(), name, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @SuppressWarnings("unchecked")
    private static int itemFieldCount(Object editor) throws Exception {
        return ((List<JComboBox<String>>) readField(editor, "itemFields")).size();
    }

    /** The editable text component inside a combo's editor (Basic L&F: the
     *  editor component itself, Metal: a child text field). */
    private static JTextField editorText(JComboBox<String> combo) throws Exception {
        JComponent editor = (JComponent) combo.getEditor().getEditorComponent();
        if (editor instanceof JTextField tf) {
            return tf;
        }
        for (java.awt.Component child : editor.getComponents()) {
            if (child instanceof JTextField tf) {
                return tf;
            }
        }
        throw new AssertionError("no editor text component in the combo");
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] types) throws Exception {
        Class<?>[] t = types == null ? new Class<?>[0] : types;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, t);
            } catch (NoSuchMethodException ignore) {
                // try the superclass
            }
        }
        throw new AssertionError("method not found: " + name);
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignore) {
                // try the superclass
            }
        }
        throw new AssertionError("field not found: " + name);
    }
}