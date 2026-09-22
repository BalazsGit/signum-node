package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.awt.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;

import application.utils.gui.SearchMatchPanel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the value-status visibility checkboxes of the search
 * panel in {@link NodeConfigurationPanel}: with the "Unsaved values" box
 * selected and the other two not (and an empty search text) the results list
 * must hold EXACTLY the rows that have unsaved changes — a reported bug was
 * that the filter listed every row, not only the dirty ones.
 * <p>
 * The test drives the panel on the EDT: it selects the filter, asserts that
 * every listed row is dirty, then edits one clean text field and asserts the
 * list grows by exactly that row (and shrinks back when the edit is undone).
 * A second test verifies that with all three boxes selected and an empty
 * query nothing is filtered (the tabbed view stays up).
 * </p>
 */
@DisplayName("NodeConfigurationPanel unsaved-changes filter tests")
class NodeConfigurationPanelUnsavedFilterTest {

    private static final long INIT_TIMEOUT_MS = 60_000;

    @Test
    @DisplayName("the filter lists only the rows with unsaved changes")
    void unsavedFilter_listsOnlyDirtyRows() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                // Same icon-font registration the application performs at
                // startup (AppearanceModule#init) so IconFontSwing resolves.
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("unsaved-filter-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "unsaved-filter-test");
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
            // Wait for the async UI construction (initUI on the EDT) to finish.
            final Object[] ready = new Object[1];
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && ready[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Object rows = readDeclaredField(panelRef[0], "allPropertyRows");
                        Object checkbox = readDeclaredField(panelRef[0], "showUnsavedBox");
                        if (rows instanceof List<?> list && !list.isEmpty() && checkbox != null) {
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

            final List<Object> result = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    runFilterScenario(panelRef[0], result);
                } catch (Throwable t) {
                    result.add(t);
                }
            });
            if (!result.isEmpty()) {
                Object o = result.get(0);
                if (o instanceof Throwable t) {
                    throw new AssertionError("the unsaved-filter scenario failed", t);
                }
                throw new AssertionError("the unsaved-filter scenario failed: " + o);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (ownerRef[0] instanceof Frame owner) {
                    owner.dispose();
                }
            });
        }
    }

    /** The EDT scenario; collects violation messages (empty = success). */
    private void runFilterScenario(Object panel, List<Object> out) throws Exception {
        List<Object> matchesBefore = selectFilterAndReadMatches(panel, true);
        for (Object row : matchesBefore) {
            if (!isRowDirty(panel, row)) {
                out.add("with the filter on and no search text, a row WITHOUT unsaved changes "
                        + "is listed: " + labelText(row));
                return;
            }
        }

        // Find a clean row with a plain text field (outside the DB section,
        // whose URL row aggregates the credential rows' dirty state).
        Object cleanRow = null;
        JTextField cleanField = null;
        for (Object row : readRows(panel)) {
            Object input = readField(row, "input");
            if (input instanceof JTextField field && !isRowDirty(panel, row)
                    && !propName(row).toLowerCase(Locale.ROOT).contains("db")) {
                cleanRow = row;
                cleanField = field;
                break;
            }
        }
        if (cleanRow == null) {
            out.add("test premise not met: no clean plain-text row available to edit");
            return;
        }
        String original = cleanField.getText();
        int sizeBefore = matchesBefore.size();

        // Edit the clean row: it must become the ONLY newly listed row.
        cleanField.setText(original + "x");
        List<Object> matchesAfter = readMatches(panel);
        if (!matchesAfter.contains(cleanRow)) {
            out.add("after editing a clean row, the edited row is missing from the filter list");
            return;
        }
        if (matchesAfter.size() != sizeBefore + 1) {
            out.add("after editing exactly one clean row the list must grow by exactly one "
                    + "(was " + sizeBefore + ", now " + matchesAfter.size() + ")");
            return;
        }
        for (Object row : matchesAfter) {
            if (!isRowDirty(panel, row)) {
                out.add("after the edit, a row WITHOUT unsaved changes is listed: " + labelText(row));
                return;
            }
        }

        // Undo the edit: the list must shrink back to the original set.
        cleanField.setText(original);
        List<Object> matchesRestored = readMatches(panel);
        if (matchesRestored.size() != sizeBefore) {
            out.add("after undoing the edit the list must return to " + sizeBefore + " rows, "
                    + "but has " + matchesRestored.size());
            return;
        }
        if (matchesRestored.contains(cleanRow)) {
            out.add("after undoing the edit, the (clean again) row is still listed");
        }
    }

    @Test
    @DisplayName("all boxes selected and no query: nothing is filtered (tabbed view)")
    void allBoxesSelected_noQuery_tabView() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                // Same icon-font registration the application performs at
                // startup (AppearanceModule#init) so IconFontSwing resolves.
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("status-filter-tabview-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "status-filter-tabview");
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
            final Object[] ready = new Object[1];
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && ready[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Object rows = readDeclaredField(panelRef[0], "allPropertyRows");
                        Object checkbox = readDeclaredField(panelRef[0], "showUnsavedBox");
                        if (rows instanceof List<?> list && !list.isEmpty() && checkbox != null) {
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

            final List<Object> result = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JCheckBox unsaved = (JCheckBox) readDeclaredField(panelRef[0], "showUnsavedBox");
                    JCheckBox saved = (JCheckBox) readDeclaredField(panelRef[0], "showSavedBox");
                    JCheckBox applied = (JCheckBox) readDeclaredField(panelRef[0], "showAppliedBox");
                    assertTrue(unsaved.isSelected(), "the unsaved box must be selected by default");
                    assertTrue(saved.isSelected(), "the saved box must be selected by default");
                    assertTrue(applied.isSelected(), "the applied box must be selected by default");
                    List<?> matches = (List<?>) readDeclaredField(panelRef[0], "searchMatches");
                    boolean viewActive = (Boolean) readDeclaredField(panelRef[0], "searchViewActive");
                    if (!matches.isEmpty()) {
                        result.add("with all boxes selected and an empty query nothing must be listed");
                    } else if (viewActive) {
                        result.add("with all boxes selected and an empty query the tabbed view must be up");
                    }
                } catch (Throwable t) {
                    result.add(t);
                }
            });
            if (!result.isEmpty()) {
                Object o = result.get(0);
                if (o instanceof Throwable t) {
                    throw new AssertionError("the tab-view scenario failed", t);
                }
                throw new AssertionError("the tab-view scenario failed: " + o);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = (JFrame) ownerRef[0];
                if (owner != null) {
                    owner.setVisible(false);
                    owner.dispose();
                }
            });
        }
    }

    @Test
    @DisplayName("the status filter is its own titled box before the search box, and is no search (no counter / chevrons)")
    void statusFilter_OwnBoxBeforeSearch_NoSearchUiWithoutQuery() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("status-filter-box-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "status-filter-box-test");
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
            // Wait for the async UI construction (initUI on the EDT) to finish.
            final Object[] ready = new Object[1];
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && ready[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Object rows = readDeclaredField(panelRef[0], "allPropertyRows");
                        Object checkbox = readDeclaredField(panelRef[0], "showUnsavedBox");
                        if (rows instanceof List<?> list && !list.isEmpty() && checkbox != null) {
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

            final List<Object> result = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    runStatusFilterScenario(panelRef[0], result);
                } catch (Throwable t) {
                    result.add(t);
                }
            });
            if (!result.isEmpty()) {
                Object o = result.get(0);
                if (o instanceof Throwable t) {
                    throw new AssertionError("the status-filter-box scenario failed", t);
                }
                throw new AssertionError("the status-filter-box scenario failed: " + o);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (ownerRef[0] instanceof Frame owner) {
                    owner.dispose();
                }
            });
        }
    }

    /** The EDT scenario; collects violation messages (empty = success). */
    private void runStatusFilterScenario(Object panel, List<Object> out) throws Exception {
        // 1. Layout: the checkboxes live in their own "Show values" titled
        //    box which sits BEFORE the "Search" box in the same row (the
        //    console filter header pattern: two compact titled boxes).
        JComponent statusPanel = (JComponent) ((JCheckBox) readDeclaredField(panel, "showUnsavedBox")).getParent();
        Border statusBorder = statusPanel.getBorder();
        if (!(statusBorder instanceof TitledBorder statusTitle) || !"Show values".equals(statusTitle.getTitle())) {
            out.add("the value-status checkboxes must sit in a TitledBorder titled \"Show values\" "
                    + "(got: " + statusBorder + ")");
            return;
        }
        JComponent searchBox = (JComponent) readDeclaredField(panel, "searchMatchPanel");
        Border searchBorder = searchBox.getBorder();
        if (!(searchBorder instanceof TitledBorder searchTitle) || !"Search".equals(searchTitle.getTitle())) {
            out.add("the search field must sit in a TitledBorder titled \"Search\" (got: " + searchBorder + ")");
            return;
        }
        Container row = statusPanel.getParent();
        // (JDK 25 removed Container#getComponentIndex — find the indices manually)
        java.awt.Component[] siblings = row.getComponents();
        int statusIndex = -1;
        int searchIndex = -1;
        for (int i = 0; i < siblings.length; i++) {
            if (siblings[i] == statusPanel) {
                statusIndex = i;
            }
            if (siblings[i] == searchBox) {
                searchIndex = i;
            }
        }
        if (row != searchBox.getParent() || statusIndex < 0 || searchIndex < 0 || statusIndex > searchIndex) {
            out.add("the \"Show values\" box must be a direct neighbor placed BEFORE the \"Search\" box "
                    + "(status parent: " + row + ", search parent: " + searchBox.getParent() + ")");
            return;
        }

        SearchMatchPanel searchPanel = (SearchMatchPanel) readDeclaredField(panel, "searchMatchPanel");
        JLabel indicator = searchPanel.getMatchIndicatorLabel();
        JButton prev = searchPanel.getPreviousMatchButton();
        JButton next = searchPanel.getNextMatchButton();
        JTextField field = searchPanel.getSearchField();

        // 2. Filter-only (a checkbox off, no search text): the flat list is
        //    a filtered view, NOT a search — no active match, no counter,
        //    no chevrons.
        selectFilterAndReadMatches(panel, true);
        if ((Integer) readDeclaredField(panel, "searchActiveIndex") != -1) {
            out.add("filter-only mode (no search text) must not keep an active match index");
            return;
        }
        if (!indicator.getText().isEmpty() || indicator.isVisible() || prev.isVisible() || next.isVisible()) {
            out.add("filter-only mode (no search text) must show neither the match counter nor the chevrons");
            return;
        }

        // 3. A real query IS a search: counter and chevrons come back.
        setCheckboxSelected(panel, "showSavedBox", true);
        setCheckboxSelected(panel, "showAppliedBox", true);
        field.setText("Enable");
        if (indicator.getText().isEmpty() || !indicator.isVisible()) {
            out.add("with a matching search text the match counter must be shown (got: '"
                    + indicator.getText() + "')");
            return;
        }
        if (!prev.isVisible() || !next.isVisible()) {
            out.add("with a matching search text both chevrons must be visible");
            return;
        }

        // 4. Clearing the query (all statuses checked again): back to the
        //    tabbed view, search UI off once more.
        field.setText("");
        if (!indicator.getText().isEmpty() || indicator.isVisible() || prev.isVisible() || next.isVisible()) {
            out.add("after clearing the search text the counter and the chevrons must be hidden again");
        }
    }

    /**
     * Sets the value-status visibility checkboxes the way a user click would
     * (select = only the "Unsaved values" box is left selected) and returns
     * the listed rows.
     */
    private List<Object> selectFilterAndReadMatches(Object panel, boolean select) throws Exception {
        setCheckboxSelected(panel, "showUnsavedBox", select);
        setCheckboxSelected(panel, "showSavedBox", !select);
        setCheckboxSelected(panel, "showAppliedBox", !select);
        return readMatches(panel);
    }

    /** Clicks the checkbox (like a user) until it has the wanted selection state. */
    private void setCheckboxSelected(Object panel, String fieldName, boolean select) throws Exception {
        Object checkbox = readDeclaredField(panel, fieldName);
        if (checkbox instanceof JCheckBox cb && cb.isSelected() != select) {
            cb.doClick();
        }
    }

    private List<Object> readMatches(Object panel) throws Exception {
        return (List<Object>) readDeclaredField(panel, "searchMatches");
    }

    private List<Object> readRows(Object panel) throws Exception {
        return (List<Object>) readDeclaredField(panel, "allPropertyRows");
    }

    private boolean isRowDirty(Object panel, Object row) throws Exception {
        Method m = Arrays.stream(panel.getClass().getDeclaredMethods())
                .filter(it -> it.getName().equals("isRowDirty"))
                .findFirst().orElseThrow(() -> new AssertionError("isRowDirty not found"));
        m.setAccessible(true);
        return (Boolean) m.invoke(panel, row);
    }

    private String propName(Object row) throws Exception {
        Object prop = readField(row, "prop");
        Method m = prop.getClass().getDeclaredMethod("getName");
        m.setAccessible(true);
        return (String) m.invoke(prop);
    }

    private String labelText(Object row) throws Exception {
        Object text = readField(row, "labelText");
        return text == null ? "?" : text.toString();
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object readDeclaredField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("field not found: " + name);
    }
}