package application.module.database.gui;

import application.utils.gui.FlatPlusButton;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
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
