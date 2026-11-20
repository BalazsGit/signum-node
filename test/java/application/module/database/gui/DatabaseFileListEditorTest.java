package application.module.database.gui;

import application.utils.gui.FlatPlusButton;
import application.utils.gui.GuiColors;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SQLite database-file list editor")
class DatabaseFileListEditorTest {

    private DatabaseFileListEditor editor;

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            editor = new DatabaseFileListEditor();
        });
    }

    @Test
    @DisplayName("adds, lists and removes database files in order, firing the change hook")
    void addListAndRemove() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<List<String>> afterAdd = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            editor.onChange = () -> changes.incrementAndGet();
            editor.addItem("alpha.db");
            editor.addItem("beta.db");
            editor.addItem("   ");
            afterAdd.set(editor.getItems());
        });
        assertEquals(List.of("alpha.db", "beta.db"), afterAdd.get());
        assertTrue(changes.get() >= 2, "adding files must fire the change hook");

        AtomicReference<List<String>> afterRemove = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> first = editor.itemCombos().get(0);
            editor.removeItem(first);
            afterRemove.set(editor.getItems());
        });
        assertEquals(List.of("beta.db"), afterRemove.get());
    }

    @Test
    @DisplayName("retargets the active (first) file")
    void setActiveFile() throws Exception {
        AtomicReference<List<String>> items = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            editor.addItem("first.db");
            editor.setActiveFileText("primary.db");
            items.set(editor.getItems());
        });
        assertEquals(List.of("primary.db"), items.get());
    }

    @Test
    @DisplayName("carries the flat, non-growing '+' on its add row")
    void addRowHasFlatPlus() throws Exception {
        AtomicReference<Boolean> hasPlus = new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(() -> hasPlus.set(containsFlatPlus(editor)));
        assertTrue(hasPlus.get(), "the add row must carry the flat '+' button");
    }

    @Test
    @DisplayName("rebuilds the item dropdown with the saved and the unsaved value, colored by state")
    void itemDropdownListsAndColorsSavedAndCurrentValues() throws Exception {
        AtomicReference<List<String>> items = new AtomicReference<>();
        AtomicReference<Color> savedItemColor = new AtomicReference<>();
        AtomicReference<Color> currentItemColor = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            editor.addItem("alpha.db");
            editor.setItemSavedValues(List.of("alpha"));
            // rename the active file to an unsaved value WITHOUT passing through
            // the empty state (clearing the field removes the row — and a
            // whole-text replacement would fire the remove event first)
            JComboBox<String> combo = editor.itemCombos().get(0);
            javax.swing.text.JTextComponent field =
                    (javax.swing.text.JTextComponent) combo.getEditor().getEditorComponent();
            try {
                field.getDocument().insertString(0, "beta", null);
                field.getDocument().remove(4, 5);
            } catch (javax.swing.text.BadLocationException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        // Let the deferred state-aware refresh (the model rebuild) settle.
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> combo = editor.itemCombos().get(0);
            List<String> model = new ArrayList<>();
            for (int i = 0; i < combo.getModel().getSize(); i++) {
                model.add(combo.getModel().getElementAt(i));
            }
            items.set(model);
            JList<Object> list = new JList<>(new String[0]);
            @SuppressWarnings("unchecked")
            javax.swing.ListCellRenderer<Object> renderer = (javax.swing.ListCellRenderer<Object>) combo.getRenderer();
            savedItemColor.set(((JComponent) renderer
                    .getListCellRendererComponent(list, "alpha", 0, false, false)).getForeground());
            currentItemColor.set(((JComponent) renderer
                    .getListCellRendererComponent(list, "beta", 1, false, false)).getForeground());
        });
        assertEquals(List.of("alpha", "beta"), items.get(),
                "the item dropdown lists the saved and the unsaved value");
        assertEquals(GuiColors.getSaved(), savedItemColor.get(),
                "the saved baseline entry is saved-colored");
        assertEquals(GuiColors.getUnsaved(), currentItemColor.get(),
                "the unsaved current entry is unsaved-colored");
    }

    @Test
    @DisplayName("the trash asks confirmation only for saved files (by the saved name); new rows remove at once")
    void trashConfirmationTargetsTheSavedFileOnly() throws Exception {
        AtomicInteger confirmations = new AtomicInteger();
        AtomicReference<String> confirmedName = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            editor.removeConfirmation = name -> {
                confirmedName.set(name);
                confirmations.incrementAndGet();
                return true;
            };
            editor.addItem("alpha.db");
            editor.setItemSavedValues(List.of("alpha"));
            // Rename the row to an unsaved value (WITHOUT passing through the
            // empty state — clearing the field removes the row).
            JComboBox<String> combo = editor.itemCombos().get(0);
            javax.swing.text.JTextComponent field =
                    (javax.swing.text.JTextComponent) combo.getEditor().getEditorComponent();
            try {
                field.getDocument().insertString(0, "beta", null);
                field.getDocument().remove(4, 5);
            } catch (javax.swing.text.BadLocationException impossible) {
                throw new IllegalStateException(impossible);
            }
            editor.removeItem(combo);
        });
        assertEquals(1, confirmations.get(), "a saved row asks confirmation exactly once");
        assertEquals("alpha.db", confirmedName.get(),
                "the trash targets the saved file, not the not-yet-existing new name");
        assertEquals(0, editor.getItems().size(), "the confirmed row is removed");

        SwingUtilities.invokeAndWait(() -> {
            editor.addItem("gamma.db");
            editor.removeItem(editor.itemCombos().get(0));
        });
        assertEquals(1, confirmations.get(),
                "a not-yet-created row is removed without any confirmation");
        assertEquals(0, editor.getItems().size(), "the new row is removed");
    }

    private static boolean containsFlatPlus(JPanel panel) {
        for (java.awt.Component child : panel.getComponents()) {
            if (child instanceof FlatPlusButton) {
                return true;
            }
            if (child instanceof JPanel sub) {
                if (containsFlatPlus(sub)) {
                    return true;
                }
            }
        }
        return false;
    }
}
