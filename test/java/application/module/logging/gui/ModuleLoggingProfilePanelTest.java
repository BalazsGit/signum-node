package application.module.logging.gui;

import application.module.logging.LoggingProfileRepository;
import application.utils.gui.ComboSearchHighlightRenderer;
import application.utils.gui.GuiColors;
import application.utils.gui.HelpDialog;
import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchPanel;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Verifies the icon-only CRUD toolbar (top, no header text), the search row (profile
 * selector left of the search, above the "Logger levels" frame), the per-key value-typed
 * editor rows, and the host extension points ({@code setLinkControl}, {@code setApplyHook}).
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
                JComponent next = (JComponent) root.getComponent(i + 1);
                // The editor now sits inside the row's value cell (editor +
                // per-row "Remove key" trash icon) â€” unwrap the cell.
                if (next instanceof Container cell) {
                    for (Component inner : cell.getComponents()) {
                        if (inner instanceof JComboBox || inner instanceof JTextField) {
                            return (JComponent) inner;
                        }
                    }
                }
                return next;
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

    /**
     * Lays out the (detached) tree recursively: {@code doLayout()} on the
     * component and, recursively, on every child (JDK 25's {@code validate()}
     * does not lay out a detached subtree â€” the BrowserTabViewTest pattern).
     */
    private static void layoutRecursively(Component c) {
        c.doLayout();
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                layoutRecursively(child);
            }
        }
    }

    /** The first JButton whose tooltip contains the given text (may be null). */
    private static JButton findButtonByTooltip(Container root, String part) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JButton button) {
                if (button.getToolTipText() != null && button.getToolTipText().contains(part)) {
                    return button;
                }
            }
            if (child instanceof Container) {
                JButton found = findButtonByTooltip((Container) child, part);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The first JLabel with exactly the given text (may be null). */
    private static JLabel findLabel(Container root, String text) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (child instanceof Container) {
                JLabel found = findLabel((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The texts of every JButton in the tree (depth-first). */
    private static List<String> allButtonTexts(Container root) {
        List<String> out = new ArrayList<>();
        collectButtonTexts(root, out);
        return out;
    }

    private static void collectButtonTexts(Container c, List<String> out) {
        for (int i = 0; i < c.getComponentCount(); i++) {
            Component child = c.getComponent(i);
            if (child instanceof JButton button) {
                out.add(button.getText());
            } else if (child instanceof Container container) {
                collectButtonTexts(container, out);
            }
        }
    }

    /** The first container carrying a TitledBorder with exactly the given title (may be null). */
    private static Container findTitledContainer(Container root, String title) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (!(child instanceof Container container)) {
                continue;
            }
            if (container instanceof JComponent jComponent
                    && jComponent.getBorder() instanceof TitledBorder titled
                    && title.equals(titled.getTitle())) {
                return container;
            }
            Container found = findTitledContainer(container, title);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The first component of the given type anywhere under {@code root} (may be null). */
    private static <T extends Component> T findFirst(Container root, Class<T> type) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T found = findFirst((Container) child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Every component of the given type anywhere under {@code root}. */
    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectAll(root, type, out);
        return out;
    }

    private static <T extends Component> void collectAll(Container root, Class<T> type, List<T> out) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            if (child instanceof Container) {
                collectAll((Container) child, type, out);
            }
        }
    }

    /** The y position of {@code c} relative to {@code root} (walking up the parents). */
    private static int verticalPosition(Container root, Component c) {
        int y = c.getY();
        Component p = c.getParent();
        while (p != null && p != root) {
            y += p.getY();
            p = p.getParent();
        }
        return y;
    }

    /** The x position of {@code c} relative to {@code root} (walking up the parents). */
    private static int horizontalPosition(Container root, Component c) {
        int x = c.getX();
        Component p = c.getParent();
        while (p != null && p != root) {
            x += p.getX();
            p = p.getParent();
        }
        return x;
    }

    @Test
    @DisplayName("constructs without a host context; the provider header text is NOT painted")
    void constructsAndShowsNoHeaderText() {
        List<String> labels = allLabels(newPanel());
        assertFalse(labels.contains("Test Module"),
                "the provider display name must not be painted as a header; labels=" + labels);
        assertFalse(labels.contains("test provider"),
                "the provider description must not be painted as a header; labels=" + labels);
        assertTrue(labels.contains("test.level"), "the editor rows are present; labels=" + labels);
    }

    @Test
    @DisplayName("renders an editor row for each default key")
    void rendersDefaultRows() {
        List<String> labels = allLabels(newPanel());
        assertTrue(labels.contains("test.level"), "level row present; labels=" + labels);
        assertTrue(labels.contains("test.handler"), "handler row present; labels=" + labels);
    }

    @Test
    @DisplayName("value-typed editors: log level â†’ combo, other â†’ text field")
    void valueTypedEditors() {
        ModuleLoggingProfilePanel panel = newPanel();
        assertInstanceOf(JComboBox.class, editorAfterLabel(panel, "test.level"),
                "a log-level key must use a combo editor");
        assertInstanceOf(JTextField.class, editorAfterLabel(panel, "test.handler"),
                "a non-level key must use a text-field editor");
    }

    @Test
    @DisplayName("the action toolbar offers Apply and Refresh (icon-only, tooltip-driven)")
    void toolbarButtons() {
        ModuleLoggingProfilePanel panel = newPanel();
        JButton apply = findButtonByTooltip(panel, "Apply");
        assertNotNull(apply, "the Apply button (by tooltip) is present");
        assertEquals("", apply.getText(),
                "the function buttons are icon-only (no text label; the tooltip carries the description)");
        assertNotNull(findButtonByTooltip(panel, "Refresh"), "the Refresh button (by tooltip) is present");
    }

    @Test
    @DisplayName("the top area is laid out: icon toolbar on top, profile selector left of the search")
    void topAreaIsLaidOut() {
        ModuleLoggingProfilePanel panel = newPanel();
        onEdt(() -> {
            panel.enableSearch();
            panel.setSize(900, 600);
            layoutRecursively(panel);
        });
        // The provider title/description text used to sit on top â€” it must be
        // GONE, replaced by the icon-only action toolbar.
        assertNull(findLabel(panel, "Test Module"),
                "the provider title must not be painted (the icon toolbar takes its place)");
        assertNull(findLabel(panel, "test provider"),
                "the provider description must not be painted (the icon toolbar takes its place)");
        // The whole function-button toolbar must actually occupy space.
        JButton apply = findButtonByTooltip(panel, "Apply");
        assertNotNull(apply, "the Apply button is present");
        assertTrue(apply.getWidth() > 0 && apply.getHeight() > 0,
                "the Apply button must be laid out, got " + apply.getWidth() + "x" + apply.getHeight());
        // The profile selector sits on the SAME row, to the LEFT of the search
        // box, at its natural width (it must not stretch across the row).
        JComboBox<String> profile = panel.getProfileCombo();
        assertTrue(profile.getWidth() > 0 && profile.getHeight() > 0,
                "the profile combo must be laid out, got " + profile.getWidth() + "x" + profile.getHeight());
        Component search = findFirst(panel, SearchMatchPanel.class);
        assertNotNull(search, "the search box is present after enableSearch");
        int profileX = horizontalPosition(panel, profile);
        int searchX = horizontalPosition(panel, search);
        assertTrue(profileX + profile.getWidth() <= searchX,
                "the profile selector must sit to the LEFT of the search box (profile x=" + profileX
                        + " width=" + profile.getWidth() + ", search x=" + searchX + ")");
        assertTrue(Math.abs(verticalPosition(panel, profile) - verticalPosition(panel, search)) <= 24,
                "the profile selector must sit on the SAME row as the search box (profile y="
                        + verticalPosition(panel, profile) + ", search y=" + verticalPosition(panel, search) + ")");

        // The search row carries the node configuration panel's search-row
        // left margin: the profile box starts 10px right of the "Logger
        // levels" frame (the frame runs flush, like the configuration tab's
        // tab area).
        Container frame = findTitledContainer(panel, "Logger levels");
        assertNotNull(frame, "the 'Logger levels' titled frame is present");
        Container profileBox = findTitledContainer(panel, "Profile");
        assertNotNull(profileBox, "the 'Profile' titled box is present");
        assertEquals(horizontalPosition(panel, profileBox) - horizontalPosition(panel, frame), 10,
                "the profile box must start 10px right of the 'Logger levels' frame (the configuration panel's search-row left margin)");
        assertEquals(verticalPosition(panel, profileBox), verticalPosition(panel, search),
                "the profile box and the search box must top-align on the same row");
        // ONE uniform gap between the profile box and the search box.
        int gap = horizontalPosition(panel, search)
                - (horizontalPosition(panel, profileBox) + profileBox.getWidth());
        assertEquals(ModuleLoggingProfilePanel.ROW_GAP, gap,
                "the gap between the profile box and the search box must be the uniform ROW_GAP");
        // Uniform heights: every box of the row is stretched to the same height.
        assertEquals(search.getHeight(), profileBox.getHeight(),
                "the profile box and the search box must have the SAME height");
    }

    @Test
    @DisplayName("the live row search is ON by default (no enableSearch call needed)")
    void searchIsOnByDefault() {
        ModuleLoggingProfilePanel panel = newPanel();
        assertNotNull(findFirst(panel, SearchMatchPanel.class),
                "the 'Search' box must be present straight after construction (the row search is a core feature)");
        panel.enableSearch(); // idempotent
        assertEquals(1, findAll(panel, SearchMatchPanel.class).size(),
                "enableSearch must not add a second search box (the search is on by default)");
    }

    @Test
    @DisplayName("the 'Show values' row-state filter (Unsaved/Saved/Applied) is on by default, all boxes selected")
    void showValuesFilterIsOnByDefault() {
        ModuleLoggingProfilePanel panel = newPanel();
        Container box = findTitledContainer(panel, "Show values");
        assertNotNull(box, "the 'Show values' titled box is present");
        List<JCheckBox> checks = findAll(box, JCheckBox.class);
        assertEquals(3, checks.size(), "one checkbox per row state; got=" + checks.size());
        assertEquals("Unsaved values", checks.get(0).getText());
        assertEquals("Saved values", checks.get(1).getText());
        assertEquals("Applied values", checks.get(2).getText());
        for (JCheckBox check : checks) {
            assertTrue(check.isSelected(), "every state filter is selected by default: " + check.getText());
        }
        assertEquals(GuiColors.getUnsaved(), checks.get(0).getForeground(), "the unsaved box is the unsaved color");
        assertEquals(GuiColors.getSaved(), checks.get(1).getForeground(), "the saved box is the saved color");
        assertEquals(GuiColors.getApplied(), checks.get(2).getForeground(), "the applied box is the applied color");
    }

    @Test
    @DisplayName("the search sits ABOVE the 'Logger levels' frame; the add-key row is at the top inside it")
    void searchAboveLoggerLevelsFrame() {
        ModuleLoggingProfilePanel panel = newPanel();
        onEdt(() -> {
            panel.enableSearch();
            panel.setSize(900, 600);
            layoutRecursively(panel);
        });
        Container titled = findTitledContainer(panel, "Logger levels");
        assertNotNull(titled, "the 'Logger levels' titled frame is present");
        // The add-key row sits at the TOP INSIDE the frame, the level rows below.
        JButton addKey = findButton(panel, "Add key");
        assertNotNull(addKey, "the 'Add key' button is present");
        assertTrue(titled.isAncestorOf(addKey),
                "the add-key row must sit INSIDE the 'Logger levels' frame");
        JScrollPane gridScroll = findFirst(titled, JScrollPane.class);
        assertNotNull(gridScroll, "the row grid scroll is inside the frame");
        assertNull(gridScroll.getBorder(),
                "the grid scroll must not add its own inner frame (the outer 'Logger levels' border is the only frame)");
        assertTrue(addKey.getY() < gridScroll.getY(),
                "the add-key row must be at the TOP of the frame (add-key y=" + addKey.getY()
                        + ", grid y=" + gridScroll.getY() + ")");
        // The search box is NOT inside the frame â€” it sits on the search row ABOVE it.
        Component search = findFirst(panel, SearchMatchPanel.class);
        assertNotNull(search, "the search box is present after enableSearch");
        assertNull(findFirst(titled, SearchMatchPanel.class),
                "the search box must NOT sit inside the 'Logger levels' frame");
        assertTrue(verticalPosition(panel, search) < verticalPosition(panel, titled),
                "the search row must sit ABOVE the 'Logger levels' frame (search y="
                        + verticalPosition(panel, search) + ", frame y=" + verticalPosition(panel, titled) + ")");
    }

    @Test
    @DisplayName("every row carries a 'Remove key' trash icon; user-added rows are removable, built-in rows are not")
    void removeKeyIcon() {
        ModuleLoggingProfilePanel panel = newPanel();

        // Add a user key through the "Add key" row.
        final String userKey = "com.example.MyApp.level";
        onEdt(() -> {
            JTextField keyInput = findAddKeyInput(panel);
            assertNotNull(keyInput, "the add-key input is present");
            keyInput.setText(userKey);
            findButton(panel, "Add key").doClick();
        });

        // The user-added row: enabled trash icon, and clicking it removes the row.
        JButton userRemove = findButtonByTooltip(panel, "Remove key '" + userKey + "'");
        assertNotNull(userRemove, "the user-added row carries its 'Remove key' trash icon");
        assertTrue(userRemove.isEnabled(), "a user-added row is removable");
        onEdt(userRemove::doClick);
        assertNull(panel.editorValueOf(userKey), "the removed row is gone from the editor");
        assertFalse(allLabels(panel).contains(userKey), "the removed row is not rendered");

        // The common-logger template rows are removable as well.
        JButton commonRemove = findButtonByTooltip(panel, "Remove key 'com.zaxxer.hikari.level'");
        assertNotNull(commonRemove, "the common-logger template row carries its trash icon");
        assertTrue(commonRemove.isEnabled(), "a common-logger template row is removable");

        // The provider's default rows are built-in: disabled trash icon.
        JButton builtInRemove = findButtonByTooltip(panel, "Built-in row");
        assertNotNull(builtInRemove, "the built-in rows carry the (disabled) trash icon");
        assertFalse(builtInRemove.isEnabled(), "a provider default row cannot be removed");
    }

    /** The "Add key" row's key input (the JTextField sibling of the "Add key" button). */
    private static JTextField findAddKeyInput(Container root) {
        JButton addBtn = findButton(root, "Add key");
        if (addBtn == null) {
            return null;
        }
        for (Component child : addBtn.getParent().getComponents()) {
            if (child instanceof JTextField) {
                return (JTextField) child;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a search hit inside an editable text value is banded in the field (the node configuration behavior)")
    void searchMatchInTextFieldValueIsBanded() throws Exception {
        ModuleLoggingProfilePanel panel = newPanel();
        onEdt(panel::enableSearch);
        List<SearchMatchPanel> searches = findAll(panel, SearchMatchPanel.class);
        assertFalse(searches.isEmpty(), "the search box is present after enableSearch");
        // "Console" occurs ONLY in the test.handler value (not in any label or key).
        onEdt(() -> searches.get(0).setSearchText("Console"));

        final JTextField[] fieldHolder = new JTextField[1];
        onEdt(() -> {
            panel.setSize(panel.getPreferredSize());
            layoutRecursively(panel);
            fieldHolder[0] = findTextFieldWithText(panel, "java.util.logging.ConsoleHandler");
        });
        JTextField field = fieldHolder[0];
        assertNotNull(field, "the value text field is present in the tree");
        assertTrue(field.getWidth() > 0 && field.getHeight() > 0,
                "the value field is laid out: " + field.getSize());

        // The document highlight is registered on the value field itself.
        javax.swing.text.Highlighter.Highlight[] highlights = field.getHighlighter().getHighlights();
        assertEquals(1, highlights.length,
                "the matched occurrence in the value field carries a highlight (got "
                        + highlights.length + ")");

        // The band is actually PAINTED behind the matched text (pixel check,
        // the node configuration panel's regression-test policy).
        java.awt.image.BufferedImage img = renderComponent(field);
        java.awt.Color bandActive = blendedOver(field.getBackground(), GuiColors.getSearchActiveMatch());
        java.awt.Color bandSoft = blendedOver(field.getBackground(), GuiColors.getSearchMatch());
        int idx = field.getText().indexOf("Console");
        assertTrue(idx >= 0);
        java.awt.geom.Rectangle2D start = field.modelToView2D(idx);
        java.awt.geom.Rectangle2D end = field.modelToView2D(idx + "Console".length());
        int width = Math.max(1, (int) end.getX() - (int) start.getX());
        assertTrue(regionContainsColor(img, (int) start.getX(), (int) start.getY(),
                width, (int) start.getHeight(), bandActive)
                || regionContainsColor(img, (int) start.getX(), (int) start.getY(),
                        width, (int) start.getHeight(), bandSoft),
                "the matched text in the value field must carry the search band");
    }

    @Test
    @DisplayName("a search hit inside a combo's selected value is banded via the swapped renderer, restored on clear")
    void searchMatchInComboSelectedValueIsBanded() {
        ModuleLoggingProfilePanel panel = newPanel();
        onEdt(panel::enableSearch);
        List<SearchMatchPanel> searches = findAll(panel, SearchMatchPanel.class);
        assertFalse(searches.isEmpty(), "the search box is present after enableSearch");

        // The level rows are non-editable combos; "warn" occurs only in the
        // SELECTED value ("WARNING") of the common-logger template rows.
        JComponent hikariEditor = editorAfterLabel(panel, "com.zaxxer.hikari.level");
        JComboBox<?> hikariCombo = assertInstanceOf(JComboBox.class, hikariEditor, "the hikari row is a combo");
        javax.swing.ListCellRenderer<?> originalRenderer = hikariCombo.getRenderer();

        onEdt(() -> searches.get(0).setSearchText("warn"));
        assertInstanceOf(ComboSearchHighlightRenderer.class, hikariCombo.getRenderer(),
                "the matched combo's selected value is rendered by the band-painting renderer");
        ComboSearchHighlightRenderer bandRenderer = (ComboSearchHighlightRenderer) hikariCombo.getRenderer();
        // The band is re-resolved per item: the selected value is banded, the
        // non-matching dropdown items are not (they are not visible).
        JList<String> popup = new JList<>(new String[] {"WARNING", "INFO"});
        bandRenderer.getListCellRendererComponent(popup, "WARNING", 0, false, false);
        assertTrue(bandRenderer.hasHighlight(), "the selected value carries the band range");
        bandRenderer.getListCellRendererComponent(popup, "INFO", 1, false, false);
        assertFalse(bandRenderer.hasHighlight(), "a non-matching dropdown item is not banded");

        // Clearing the search restores the combo's original renderer.
        onEdt(() -> searches.get(0).setSearchText(""));
        assertSame(originalRenderer, hikariCombo.getRenderer(),
                "the combo's original renderer is restored when the search clears");
    }

    /** The first JTextField whose text equals the given text (may be null). */
    private static JTextField findTextFieldWithText(Container root, String text) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JTextField field && text.equals(field.getText())) {
                return field;
            }
            if (child instanceof Container) {
                JTextField found = findTextFieldWithText((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Paints the (visible, sized) component into an opaque white image. */
    private static java.awt.image.BufferedImage renderComponent(JComponent component) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(component.getWidth(),
                component.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        component.paint(g);
        g.dispose();
        return img;
    }

    /** The band color after blending over the field's (opaque) background. */
    private static java.awt.Color blendedOver(java.awt.Color background, java.awt.Color band) {
        float a = band.getAlpha() / 255f;
        int r = Math.round(a * band.getRed() + (1f - a) * background.getRed());
        int gr = Math.round(a * band.getGreen() + (1f - a) * background.getGreen());
        int b = Math.round(a * band.getBlue() + (1f - a) * background.getBlue());
        return new java.awt.Color(r, gr, b);
    }

    /** Whether any pixel of the given region equals the given color. */
    private static boolean regionContainsColor(java.awt.image.BufferedImage img, int x, int y,
            int width, int height, java.awt.Color color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(img.getWidth(), x + Math.max(0, width));
        int y1 = Math.min(img.getHeight(), y + Math.max(0, height));
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                if (new java.awt.Color(img.getRGB(xx, yy)).equals(color)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("row labels do not paint the bracketed <key> text (the key stays in the tooltip)")
    void rowLabelsDoNotPaintBracketedKeys() {
        ModuleLoggingProfilePanel panel = newPanel();
        List<SearchMatchLabel> labels = findAll(panel, SearchMatchLabel.class);
        assertFalse(labels.isEmpty(), "the row labels are SearchMatchLabels");
        for (SearchMatchLabel label : labels) {
            assertNull(label.getKeyText(),
                    "no row may paint the bracketed <key> text (label=\"" + label.getText() + "\")");
        }
        SearchMatchLabel first = labels.get(0);
        assertNotNull(first.getToolTipText(), "the raw key stays available via the tooltip");
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
        assertNotNull(findButtonByTooltip(panel, "New Profile<br>").getIcon(), "New icon present");
        assertNotNull(findButtonByTooltip(panel, "Save<br>").getIcon(), "Save icon present");
        assertNotNull(findButtonByTooltip(panel, "Apply<br>").getIcon(), "Apply icon present");
        assertNotNull(findButtonByTooltip(panel, "Rename<br>").getIcon(), "Rename icon present");
        assertNotNull(findButtonByTooltip(panel, "Delete<br>").getIcon(), "Delete icon present");
        assertNotNull(findButtonByTooltip(panel, "Refresh<br>").getIcon(), "Refresh icon present");
        assertNotNull(findButtonByTooltip(panel, "Reset to Defaults<br>").getIcon(), "Reset icon present");
        assertNotNull(findButtonByTooltip(panel, "Reload<br>").getIcon(), "Reload icon present");
        // The help (question mark) is an icon in the SAME style/size as the others.
        JButton help = findButtonByTooltip(panel, "Help<br>");
        assertNotNull(help, "the Help button is present");
        assertNotNull(help.getIcon(), "the Help glyph is an icon (no text)");
        assertEquals(findButtonByTooltip(panel, "Apply<br>").getIcon().getIconWidth(),
                help.getIcon().getIconWidth(),
                "the Help glyph uses the same icon size as the other toolbar glyphs");
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
        // keyboard focus â€” the follow-up focus request races the AWT focus
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

            // Type "abc" without re-clicking â€” exactly the user's repro.
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

            // Backspace once, then type again â€” the follow-up regression:
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
                            + "and deletion â€” otherwise every further keystroke "
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

    @Test
    @DisplayName("Save/Rename/Delete are disabled while the virtual 'Default' profile is selected")
    void profileActionsDisabledForDefaultProfile() {
        ModuleLoggingProfilePanel panel = newPanel();

        // The first combo entry (selected on construction) is the virtual "Default".
        JComboBox<String> combo = panel.getProfileCombo();
        assertEquals(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY, combo.getSelectedItem());

        assertFalse(findButtonByTooltip(panel, "Save<br>").isEnabled(),
                "Save is impossible for the built-in 'Default' profile â€” the button must not stay active");
        assertFalse(findButtonByTooltip(panel, "Rename<br>").isEnabled(),
                "Rename is impossible for the built-in 'Default' profile â€” the button must not stay active");
        assertFalse(findButtonByTooltip(panel, "Delete<br>").isEnabled(),
                "Delete is impossible for the built-in 'Default' profile â€” the button must not stay active");

        // The actions that ARE meaningful with 'Default' selected stay enabled.
        assertTrue(findButtonByTooltip(panel, "New Profile<br>").isEnabled());
        assertTrue(findButtonByTooltip(panel, "Apply<br>").isEnabled());
        assertTrue(findButtonByTooltip(panel, "Refresh<br>").isEnabled());
        assertTrue(findButtonByTooltip(panel, "Reload<br>").isEnabled());
        assertTrue(findButtonByTooltip(panel, "Reset to Defaults<br>").isEnabled());

        // The Help button shows the core's generic help out of the box (no host supplier).
        assertTrue(findButtonByTooltip(panel, "Help<br>").isEnabled(),
                "Help is enabled by default — hosts without a supplier (e.g. the Logging "
                        + "module's 'Logging Profiles' tab) still get help");
    }

    @Test
    @DisplayName("setHelpSupplier overrides the default help; null hides the Help button")
    void setHelpSupplierTogglesHelpButton() {
        ModuleLoggingProfilePanel panel = newPanel();
        JButton help = findButtonByTooltip(panel, "Help<br>");
        assertTrue(help.isEnabled() && help.isVisible(),
                "the Help button is enabled out of the box (core default help)");

        onEdt(() -> panel.setHelpSupplier(null));
        assertFalse(help.isEnabled(), "an explicit null supplier hides the Help button");
        assertFalse(help.isVisible(), "an explicit null supplier hides the Help button");

        onEdt(() -> panel.setHelpSupplier(() -> "<html>Custom host help</html>"));
        assertTrue(help.isEnabled() && help.isVisible(),
                "a host supplier re-enables (and overrides) the Help button");
    }

    @Test
    @DisplayName("clicking Help shows the compact structured default help dialog (no host supplier registered)")
    void helpShowsDefaultHelpDialog() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display (modal dialog)");
        AtomicReference<String> captured = new AtomicReference<>();
        AtomicInteger capturedWidth = new AtomicInteger(-1);
        AtomicReference<JDialog> dialogRef = new AtomicReference<>();
        CountDownLatch shown = new CountDownLatch(1);
        onEdt(() -> {
            JFrame owner = new JFrame("help-test-owner");
            ModuleLoggingProfilePanel panel = new ModuleLoggingProfilePanel(new TestProvider());
            owner.add(panel);
            owner.setSize(900, 700);
            owner.setVisible(true);
            // Auto-close the modal help dialog as soon as it appears.
            Timer closer = new Timer(300, e -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog dialog && dialog.isShowing()) {
                        dialogRef.set(dialog);
                        // Capture: dialog title + every label text of the structured content
                        // + the Close button text.
                        String text = dialog.getTitle();
                        text = text + " | " + String.join(" ", allButtonTexts(dialog));
                        text = text + " | " + String.join(" ", allLabels(dialog));
                        captured.set(text);
                        capturedWidth.set(dialog.getWidth());
                        dialog.dispose();
                        shown.countDown();
                        ((Timer) e.getSource()).stop();
                        return;
                    }
                }
            });
            closer.setRepeats(true);
            closer.start();
            findButtonByTooltip(panel, "Help<br>").doClick(); // blocks until the dialog closes
        });
        assertTrue(shown.await(10, TimeUnit.SECONDS), "the Help dialog appeared");
        String text = captured.get();
        assertNotNull(text, "the Help dialog was captured");
        assertTrue(text.contains("Logging Profile Help"), "the dialog title (got: " + text + ")");
        assertTrue(text.contains("Row colors"), "the default help explains the row colors");
        assertTrue(text.contains("Toolbar actions"), "the default help lists the toolbar actions");
        assertTrue(text.contains("Apply"), "the default help explains Apply");
        assertTrue(text.contains("Delete"), "the default help explains Delete");
        assertTrue(text.contains("Close"), "the dialog has a Close button");
        assertTrue(capturedWidth.get() <= HelpDialog.CONTENT_WIDTH + 120,
                "the dialog stays compact instead of a full-width banner (width=" + capturedWidth.get() + ")");
        // The legend names are painted with the palette's real state colors (GuiColors SSOT).
        JDialog dialog = dialogRef.get();
        assertNotNull(dialog, "the help dialog reference was captured");
        JLabel appliedLabel = findLabel(dialog, "Applied");
        assertNotNull(appliedLabel, "the legend has an 'Applied' row");
        assertEquals(GuiColors.getApplied(), appliedLabel.getForeground(),
                "the 'Applied' legend name uses the palette's applied color");
        onEdt(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JFrame f && "help-test-owner".equals(f.getTitle())) {
                    f.dispose();
                }
            }
        });
    }

    @Test
    @DisplayName("Save/Rename/Delete enable for a real profile and disable again when 'Default' is reselected")
    void profileActionsToggleWithSelection() {
        ModuleLoggingProfilePanel panel = newPanel();
        JComboBox<String> combo = panel.getProfileCombo();

        String name = "toggle-btns-" + System.nanoTime();
        onEdt(() -> panel.createProfileFromCurrentState(name));
        try {
            assertEquals(name, combo.getSelectedItem(), "the new profile is selected after creation");
            // The Save/Apply split: right after creation the editor mirrors the
            // saved profile exactly, so there is nothing to save yet — Save
            // stays disabled until a row actually changes.
            assertFalse(findButtonByTooltip(panel, "Save<br>").isEnabled(),
                    "Save is not valid while there are no unsaved changes");
            // An unsaved change lights Save up (it is valid for an on-disk profile now).
            JComboBox<?> level = assertInstanceOf(JComboBox.class, editorAfterLabel(panel, "test.level"));
            onEdt(() -> level.setSelectedItem("FINE"));
            assertTrue(findButtonByTooltip(panel, "Save<br>").isEnabled(),
                    "Save is valid while the profile has unsaved changes");
            assertTrue(findButtonByTooltip(panel, "Rename<br>").isEnabled(), "Rename is valid for an on-disk profile");
            assertTrue(findButtonByTooltip(panel, "Delete<br>").isEnabled(), "Delete is valid for an on-disk profile");

            onEdt(() -> combo.setSelectedItem(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY));
            assertFalse(findButtonByTooltip(panel, "Save<br>").isEnabled(),
                    "back to 'Default' â€” Save is disabled again");
            assertFalse(findButtonByTooltip(panel, "Rename<br>").isEnabled(),
                    "back to 'Default' â€” Rename is disabled again");
            assertFalse(findButtonByTooltip(panel, "Delete<br>").isEnabled(),
                    "back to 'Default' â€” Delete is disabled again");
        } finally {
            // Self-cleaning: never leave a test profile in the runtime conf root.
            try {
                new LoggingProfileRepository().delete("test-mod", name);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("selectProfile picks a listed profile and reloads the editor; unknown names are a no-op")
    void selectProfileSelectsAndLoads() {
        ModuleLoggingProfilePanel panel = newPanel();
        JComboBox<String> combo = panel.getProfileCombo();

        String name = "select-profile-" + System.nanoTime();
        onEdt(() -> panel.createProfileFromCurrentState(name));
        try {
            // Back to the virtual 'Default' entry, then programmatically re-select the profile.
            onEdt(() -> combo.setSelectedItem(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY));

            // An unsaved change is discarded by the reload.
            JComboBox<?> level = assertInstanceOf(JComboBox.class, editorAfterLabel(panel, "test.level"));
            onEdt(() -> level.setSelectedItem("FINE"));

            final boolean[] ok = {false};
            onEdt(() -> ok[0] = panel.selectProfile(name));
            assertTrue(ok[0], "selectProfile reports success for a listed profile");
            assertEquals(name, combo.getSelectedItem(), "selectProfile selects the listed profile");
            assertEquals("INFO", String.valueOf(level.getSelectedItem()),
                    "selectProfile reloads the editor from the saved profile (the unsaved change is discarded)");

            final boolean[] no = {true};
            onEdt(() -> no[0] = panel.selectProfile("no-such-profile"));
            assertFalse(no[0], "an unknown entry is a no-op");
            assertEquals(name, combo.getSelectedItem(), "an unknown entry leaves the selection untouched");
        } finally {
            // Self-cleaning: never leave a test profile in the runtime conf root.
            try {
                new LoggingProfileRepository().delete("test-mod", name);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }


    // â”€â”€ Test fixture â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("a clean profile switch shows no unsaved rows (no stale values, no false dirty rows)")
    void cleanProfileSwitchShowsNoUnsavedRows() {
        ModuleLoggingProfilePanel panel = newPanel();
        String a = "clean-switch-a-" + System.nanoTime();
        String b = "clean-switch-b-" + System.nanoTime();
        onEdt(() -> panel.createProfileFromDefaults(a));
        try {
            onEdt(() -> panel.createProfileFromDefaults(b));
            // Switch B → A (the user combo action path).
            onEdt(() -> panel.getProfileCombo().setSelectedItem(a));
            SearchMatchLabel label = labelWithText(panel, "test.handler");
            assertNotNull(label, "the test.handler row label is present: " + allLabels(panel));
            assertEquals(GuiColors.getApplied(), label.getForeground(),
                    "a clean switch leaves the row applied (green)");
            assertFalse(allLabels(panel).stream().anyMatch(l -> l.endsWith(" *")),
                    "no row may carry the unsaved star after a clean switch; " + allLabels(panel));
        } finally {
            cleanupProfiles(a, b);
        }
    }

    @Test
    @DisplayName("unsaved edits survive a profile switch (per-profile workspace) and the combo marks the profile dirty")
    void dirtyEditSurvivesProfileSwitch() {
        ModuleLoggingProfilePanel panel = newPanel();
        String a = "dirty-switch-a-" + System.nanoTime();
        String b = "dirty-switch-b-" + System.nanoTime();
        onEdt(() -> panel.createProfileFromDefaults(a));
        try {
            JTextField handler = assertInstanceOf(JTextField.class, editorAfterLabel(panel, "test.handler"));
            onEdt(() -> handler.setText("unsaved.handler.value"));
            // Switch away: the editor state of 'a' must be kept in memory.
            onEdt(() -> panel.createProfileFromDefaults(b));
            // 'a' is dirty: its combo entry carries the trailing star.
            onEdt(() -> panel.getProfileCombo().setSelectedItem(a));
            // The unsaved edit is restored on the switch back (the row is starred).
            SearchMatchLabel starred = labelWithText(panel, "test.handler *");
            assertNotNull(starred, "the restored row is unsaved (starred): " + allLabels(panel));
            assertEquals(GuiColors.getUnsaved(), starred.getForeground(),
                    "the restored row is colored unsaved");
            assertEquals("unsaved.handler.value",
                    ((JTextField) editorAfterLabel(panel, "test.handler *")).getText(),
                    "the unsaved edit is restored on the switch back");
            // The combo renderer marks the dirty profile with the trailing star.
            ListCellRenderer<? super String> renderer = panel.getProfileCombo().getRenderer();
            JLabel cell = (JLabel) renderer.getListCellRendererComponent(
                    new JList<>(new String[]{a, b}), a, 0, false, false);
            assertEquals(a + " *", cell.getText(),
                    "the dirty profile carries the trailing star in the combo");
        } finally {
            cleanupProfiles(a, b);
        }
    }

    @Test
    @DisplayName("loading a profile sets EVERY row (missing keys get the default, not the previous profile's value)")
    void missingKeysLoadDefaultsNotStaleValues() {
        ModuleLoggingProfilePanel panel = newPanel();
        String a = "stale-keys-a-" + System.nanoTime();
        String b = "stale-keys-b-" + System.nanoTime();
        onEdt(() -> panel.createProfileFromDefaults(a));
        try {
            // Give 'a' a distinctive value on one row and save it.
            onEdt(() -> ((JTextField) editorAfterLabel(panel, "test.handler")).setText("custom.handler.a"));
            onEdt(findButtonByTooltip(panel, "Save<br>")::doClick);
            // Profile 'b' on disk defines ONLY test.level (no test.handler at all).
            Properties partial = new Properties();
            partial.setProperty("test.level", "FINE");
            try {
                new LoggingProfileRepository().saveProps("test-mod", b, partial);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            // Re-scan the profiles (the Refresh button).
            onEdt(findButtonByTooltip(panel, "Refresh<br>")::doClick);
            onEdt(() -> panel.getProfileCombo().setSelectedItem(b));
            assertEquals("java.util.logging.ConsoleHandler",
                    ((JTextField) editorAfterLabel(panel, "test.handler")).getText(),
                    "a key missing from the profile loads the application default, not the previous profile's value");
            assertEquals("FINE",
                    ((JComboBox<?>) editorAfterLabel(panel, "test.level")).getSelectedItem(),
                    "a key present in the profile loads its value");
            // Switch back: 'a's saved value comes back.
            onEdt(() -> panel.getProfileCombo().setSelectedItem(a));
            assertEquals("custom.handler.a",
                    ((JTextField) editorAfterLabel(panel, "test.handler")).getText(),
                    "switching back restores 'a's saved value");
        } finally {
            cleanupProfiles(a, b);
        }
    }

    @Test
    @DisplayName("saved-but-not-applied rows stay saved (yellow) across a profile switch round-trip")
    void savedProfileStaysSavedUntilApplied() {
        LoggingProfileRepository repo = new LoggingProfileRepository();
        try {
            repo.setApplied("test-mod", null); // deterministic start: nothing applied
            ModuleLoggingProfilePanel panel = newPanel();
            String a = "saved-not-applied-a-" + System.nanoTime();
            String b = "saved-not-applied-b-" + System.nanoTime();
            onEdt(() -> panel.createProfileFromDefaults(a));
            try {
                // Save a custom value in 'a' (nothing is applied: baseline = defaults).
                onEdt(() -> ((JTextField) editorAfterLabel(panel, "test.handler")).setText("custom.handler.a"));
                onEdt(findButtonByTooltip(panel, "Save<br>")::doClick);
                assertEquals(GuiColors.getSaved(), labelWithText(panel, "test.handler").getForeground(),
                        "a saved value that is not applied is colored saved (yellow)");

                // Switch away and back: the row must STAY saved (yellow), not flip to applied.
                onEdt(() -> panel.createProfileFromDefaults(b));
                onEdt(() -> panel.getProfileCombo().setSelectedItem(a));
                assertEquals(GuiColors.getSaved(),
                        labelWithText(panel, "test.handler").getForeground(),
                        "the row stays saved (yellow) after the switch round-trip — it is not applied");
            } finally {
                cleanupProfiles(a, b);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            try {
                repo.setApplied("test-mod", null);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("the applied-state snapshot drives the applied baseline (applied = snapshot values)")
    void appliedSnapshotDrivesRowStates() {
        LoggingProfileRepository repo = new LoggingProfileRepository();
        String a = "applied-snapshot-a-" + System.nanoTime();
        try {
            repo.setApplied("test-mod", null); // deterministic start: nothing applied
            ModuleLoggingProfilePanel panel = newPanel();
            onEdt(() -> panel.createProfileFromDefaults(a));
            // Save a custom value, then apply 'a' WITH a snapshot of the pre-edit
            // values (a runtime that still uses the old values).
            onEdt(() -> ((JTextField) editorAfterLabel(panel, "test.handler")).setText("custom.handler.a"));
            onEdt(findButtonByTooltip(panel, "Save<br>")::doClick);
            Properties snapshot = new Properties();
            snapshot.setProperty("test.handler", "java.util.logging.ConsoleHandler");
            snapshot.setProperty("test.level", "INFO");
            repo.saveAppliedSnapshot("test-mod", a, snapshot);
            repo.setApplied("test-mod", a);

            // A fresh panel re-reads the marker: the snapshot is the applied baseline.
            ModuleLoggingProfilePanel fresh = newPanel();
            onEdt(() -> fresh.getProfileCombo().setSelectedItem(a));
            assertEquals(GuiColors.getSaved(),
                    labelWithText(fresh, "test.handler").getForeground(),
                    "a saved value that differs from the applied snapshot is colored saved (yellow)");
            assertEquals(GuiColors.getApplied(),
                    labelWithText(fresh, "test.level").getForeground(),
                    "a value matching the applied snapshot is colored applied (green)");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            try {
                repo.setApplied("test-mod", null); // also drops the snapshot
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            cleanupProfiles(a);
        }
    }

    @Test
    @DisplayName("Apply is enabled for an on-disk profile that is not the applied one (unassigned profiles)")
    void applyEnabledForUnassignedProfile() {
        LoggingProfileRepository repo = new LoggingProfileRepository();
        try {
            repo.setApplied("test-mod", null); // nothing applied
            ModuleLoggingProfilePanel panel = newPanel();
            String a = "apply-unassigned-" + System.nanoTime();
            onEdt(() -> panel.createProfileFromDefaults(a));
            try {
                assertTrue(findButtonByTooltip(panel, "Apply<br>").isEnabled(),
                        "Apply is enabled for an on-disk profile while nothing is applied (it can be assigned)");
            } finally {
                cleanupProfiles(a);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            try {
                repo.setApplied("test-mod", null);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("Apply is disabled when the selected profile IS the applied one and nothing waits")
    void applyDisabledWhenAppliedAndClean() {
        LoggingProfileRepository repo = new LoggingProfileRepository();
        String a = "apply-applied-" + System.nanoTime();
        try {
            repo.setApplied("test-mod", null); // deterministic start
            ModuleLoggingProfilePanel panel = newPanel();
            onEdt(() -> panel.createProfileFromDefaults(a));
            // Mark 'a' applied with a snapshot of its current (default) values:
            // selected == applied and every row matches the baseline.
            repo.saveAppliedSnapshot("test-mod", a, repo.loadProps("test-mod", a));
            repo.setApplied("test-mod", a);

            ModuleLoggingProfilePanel fresh = newPanel();
            onEdt(() -> fresh.getProfileCombo().setSelectedItem(a));
            assertFalse(findButtonByTooltip(fresh, "Apply<br>").isEnabled(),
                    "Apply is disabled when the selected profile is the applied one and no change waits");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            try {
                repo.setApplied("test-mod", null); // also drops the snapshot
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            cleanupProfiles(a);
        }
    }

    @Test
    @DisplayName("the New Profile button creates a profile initialized with the application defaults (the editor state is NOT carried over)")
    void newProfileInitializedWithApplicationDefaults() {
        ModuleLoggingProfilePanel panel = newPanel();

        // The hover text carries the promise: the new profile's values WILL BE
        // the application default values. Carrying over the editor's current
        // state is what Clone Configuration is for — New Profile never does it.
        JButton newBtn = findButtonByTooltip(panel, "New Profile<br>");
        assertTrue(newBtn.getToolTipText().contains("initialized with"),
                "the tooltip must say the profile is initialized with the defaults: "
                        + newBtn.getToolTipText());
        assertTrue(newBtn.getToolTipText().contains("application default values"),
                "the tooltip must say the profile is initialized with the application defaults: "
                        + newBtn.getToolTipText());

        // Change a value in the editor — the new profile must NOT carry THAT.
        JTextField handler = assertInstanceOf(JTextField.class, editorAfterLabel(panel, "test.handler"));
        onEdt(() -> handler.setText("custom.handler"));

        String name = "created-from-defaults-" + System.nanoTime();
        onEdt(() -> panel.createProfileFromDefaults(name));
        try {
            assertEquals(name, panel.getProfileCombo().getSelectedItem(), "the new profile is selected after creation");
            // The panel's repository is bound to the runtime conf root; verify
            // through a fresh instance of the same root.
            Properties loaded = new LoggingProfileRepository().loadProps("test-mod", name);
            assertEquals("java.util.logging.ConsoleHandler", loaded.getProperty("test.handler"),
                    "the new profile carries the application default, NOT the editor's changed value");
            assertEquals("INFO", loaded.getProperty("test.level"),
                    "an untouched row keeps its application default value");
            assertFalse(allLabels(panel).stream().anyMatch(l -> l.endsWith(" *")),
                    "no row may carry the unsaved star right after creation; " + allLabels(panel));
        } finally {
            // Self-cleaning: never leave a test profile in the runtime conf root.
            try {
                new LoggingProfileRepository().delete("test-mod", name);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("rows with pending changes carry the trailing unsaved star (node configuration convention); Save clears it")
    void unsavedRowIsStarredAndClearsOnSave() {
        ModuleLoggingProfilePanel panel = newPanel();
        String name = "unsaved-star-" + System.nanoTime();
        onEdt(() -> panel.createProfileFromCurrentState(name));
        try {
            // The new profile holds exactly the editor state: nothing is unsaved.
            assertFalse(allLabels(panel).stream().anyMatch(l -> l.endsWith(" *")),
                    "no row may carry the unsaved star right after seeding; " + allLabels(panel));

            // Change a value: the row is starred in place and its tooltip says so.
            JComboBox<?> level = assertInstanceOf(JComboBox.class, editorAfterLabel(panel, "test.level"));
            onEdt(() -> level.setSelectedItem("FINE"));
            List<String> labels = allLabels(panel);
            assertTrue(labels.contains("test.level *"),
                    "the changed row carries the trailing star; labels=" + labels);
            SearchMatchLabel starred = labelWithText(panel, "test.level *");
            assertNotNull(starred, "the starred row label is present");
            assertTrue(starred.getToolTipText() != null && starred.getToolTipText().contains("Unsaved change"),
                    "the tooltip marks the unsaved change: " + starred.getToolTipText());

            // Save: the editor now mirrors the saved content — the star clears.
            onEdt(() -> findButtonByTooltip(panel, "Save<br>").doClick());
            assertFalse(allLabels(panel).stream().anyMatch(l -> l.endsWith(" *")),
                    "Save must clear the unsaved star; " + allLabels(panel));
        } finally {
            // Self-cleaning: never leave a test profile in the runtime conf root.
            try {
                new LoggingProfileRepository().delete("test-mod", name);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("editing against the virtual 'Default' profile is starred too (it cannot be saved)")
    void unsavedRowIsStarredUnderDefaultProfile() {
        ModuleLoggingProfilePanel panel = newPanel();
        // The Default entry is selected on construction; any change is unsaved.
        JComboBox<?> level = assertInstanceOf(JComboBox.class, editorAfterLabel(panel, "test.level"));
        onEdt(() -> level.setSelectedItem("FINE"));
        List<String> labels = allLabels(panel);
        assertTrue(labels.contains("test.level *"),
                "the changed row carries the trailing star; labels=" + labels);
    }

    /** The first {@link SearchMatchLabel} whose text equals the given text (may be null). */
    private static SearchMatchLabel labelWithText(Container root, String text) {
        return findAll(root, SearchMatchLabel.class).stream()
                .filter(l -> text.equals(l.getText()))
                .findFirst()
                .orElse(null);
    }

    private static void cleanupProfiles(String... names) {
        for (String name : names) {
            try {
                new LoggingProfileRepository().delete("test-mod", name);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

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
            };
        }
    }
}
