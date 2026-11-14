package application.module.node.gui.configuration;

import com.formdev.flatlaf.FlatClientProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import application.module.node.profile.NodeProfile;
import application.module.node.props.Props;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G0 regression test for the {@link NodeConfigurationPanel} "wizard mode"
 * (opened from the node setup wizard's advanced settings): the same 4-state
 * value model and "Show values" filter are available as in normal mode, while
 * wizard-specific profile actions remain hidden.
 */
@DisplayName("NodeConfigurationPanel wizard-mode tests")
class NodeConfigurationPanelWizardModeTest {

    private static final String PROFILE = "wizard-mode-test";
    private static final Path PROFILE_FILE = Path.of("conf", "node", "profiles", PROFILE + ".properties");

    private static final AtomicReference<Window> windowRef = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(PROFILE_FILE.getParent());
        if (!Files.exists(PROFILE_FILE)) {
            Files.writeString(PROFILE_FILE, "node.valueSuffix=TESTCOIN\n");
        }
    }

    private static Object readDeclaredField(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invokeRowStatus(NodeConfigurationPanel panel, Object row) throws Exception {
        Method method = Arrays.stream(NodeConfigurationPanel.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("rowStatus"))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(panel, row);
    }

    @AfterEach
    void cleanup() {
        disposeWindow();
        try {
            Files.deleteIfExists(PROFILE_FILE);
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Test
    @DisplayName("wizard mode keeps all four value states and filter boxes")
    void wizardModeFourState() throws Exception {
        NodeConfigurationPanel panel = buildPanel(true);

        assertTrue(boxVisible(panel, "showUnsavedAppliedBox"),
                "wizard mode exposes the 'unsaved applied' filter box");
        assertTrue(boxVisible(panel, "showUnsavedBox"));
        assertTrue(boxVisible(panel, "showSavedBox"));
        assertTrue(boxVisible(panel, "showAppliedBox"));
        // The wizard mode's top toolbar row carries ONLY the Copy
        // Configuration icon button (the node console tab's toolbar icon) —
        // the other profile actions do not apply while the node is created.
        assertNotNull(findButtonByTooltip(panel, "Copy Existing Configuration to This Profile"),
                "wizard mode keeps the Copy Configuration icon on the top toolbar row");
        assertNull(findButtonByTooltip(panel, "New Profile"),
                "wizard mode removes the New Profile toolbar button");
        assertNull(findButtonByTooltip(panel, "Clone Configuration to New Profile"),
                "wizard mode removes the Clone toolbar button");
        assertNull(findButtonByTooltip(panel, "Rename Profile"),
                "wizard mode removes the Rename toolbar button");
        assertNull(findButtonByTooltip(panel, "Saves all unsaved changes"),
                "wizard mode removes the Save toolbar button");
        assertNull(findButtonByTooltip(panel, "Applies the saved changes"),
                "wizard mode removes the Apply toolbar button");
        assertNull(findButtonByTooltip(panel, "Reload Configuration"),
                "wizard mode removes the Reload toolbar button");
        assertNull(findButtonByTooltip(panel, "Delete Profile"),
                "wizard mode removes the Delete toolbar button");
    }

    @Test
    @DisplayName("invalid ports show inline errors and restore saved or applied values")
    void portValidationAndRestore() throws Exception {
        Properties appliedBaseline = new Properties();
        appliedBaseline.setProperty(Props.API_PORT.getName(), "8125");
        NodeConfigurationPanel panel = buildPanel(true, appliedBaseline);
        @SuppressWarnings("unchecked")
        Map<String, JComponent> components =
                (Map<String, JComponent>) readDeclaredField(panel, "propertyComponents");
        JComboBox<?> apiPort = (JComboBox<?>) components.get(Props.API_PORT.getName());
        JTextField editor = (JTextField) apiPort.getEditor().getEditorComponent();

        SwingUtilities.invokeAndWait(() -> editor.setText("81256"));
        assertPortInvalid(panel, apiPort, editor);

        Object apiRow = ((List<?>) readDeclaredField(panel, "allPropertyRows")).stream()
                .filter(row -> {
                    try {
                        Object prop = readDeclaredField(row, "prop");
                        return Props.API_PORT.getName().equals(
                                prop.getClass().getMethod("getName").invoke(prop));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .findFirst().orElseThrow();
        JButton help = (JButton) readDeclaredField(apiRow, "help");
        assertSame(apiPort.getParent(), help.getParent(),
                "the property help icon remains on the input row");

        @SuppressWarnings("unchecked")
        Map<String, JButton> restoreButtons =
                (Map<String, JButton>) readDeclaredField(panel, "portRestoreButtons");
        JButton restore = restoreButtons.get(Props.API_PORT.getName());
        assertEquals("Set back to saved", restore.getToolTipText());
        SwingUtilities.invokeAndWait(restore::doClick);
        assertEquals("8126", editor.getText(),
                "restore uses the accepted value in the virtual profile");
        assertFalse(restore.isVisible());

        Properties saved = ((NodeProfile) readDeclaredField(panel, "savedProfile")).getProperties();
        saved.remove(Props.API_PORT.getName());
        SwingUtilities.invokeAndWait(() -> editor.setText("0"));
        assertPortInvalid(panel, apiPort, editor);
        SwingUtilities.invokeAndWait(restore::doClick);
        assertEquals("8125", editor.getText(),
                "when the saved profile has no explicit port, restore uses the applied baseline");
    }

    @Test
    @DisplayName("port validation remains attached and recovers when editing from search results")
    void portValidationSurvivesSearchAndRestore() throws Exception {
        Properties appliedBaseline = new Properties();
        appliedBaseline.setProperty(Props.API_PORT.getName(), "8125");
        NodeConfigurationPanel panel = buildPanel(true, appliedBaseline);
        @SuppressWarnings("unchecked")
        Map<String, JComponent> components =
                (Map<String, JComponent>) readDeclaredField(panel, "propertyComponents");
        JComboBox<?> apiPort = (JComboBox<?>) components.get(Props.API_PORT.getName());
        JTextField editor = (JTextField) apiPort.getEditor().getEditorComponent();
        application.utils.gui.SearchMatchPanel search =
                (application.utils.gui.SearchMatchPanel) readDeclaredField(panel, "searchMatchPanel");
        JPanel searchResults = (JPanel) readDeclaredField(panel, "searchResultsPanel");
        @SuppressWarnings("unchecked")
        Map<String, JTextArea> validationLabels =
                (Map<String, JTextArea>) readDeclaredField(panel, "portValidationLabels");
        JTextArea validation = validationLabels.get(Props.API_PORT.getName());
        @SuppressWarnings("unchecked")
        Map<String, JButton> restoreButtons =
                (Map<String, JButton>) readDeclaredField(panel, "portRestoreButtons");
        JButton restore = restoreButtons.get(Props.API_PORT.getName());

        SwingUtilities.invokeAndWait(() -> {
            search.setSearchText("API Port");
            editor.setText("8125344");
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(searchResults.getComponentCount() > 0, "the search results view is active");
        assertSame(searchResults, validation.getParent(),
                "the multiline validation status moves together with its searched property row");
        assertPortInvalid(panel, apiPort, editor);
        assertTrue(validation.getLineWrap(), "long validation text wraps instead of being clipped");

        SwingUtilities.invokeAndWait(() -> search.setSearchText(""));
        Object apiRow = ((List<?>) readDeclaredField(panel, "allPropertyRows")).stream()
                        .filter(row -> {
                            try {
                                Object prop = readDeclaredField(row, "prop");
                                return Props.API_PORT.getName().equals(
                                        prop.getClass().getMethod("getName").invoke(prop));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }).findFirst().orElseThrow();
        Object categoryParent = readDeclaredField(apiRow, "originalParent");
        assertSame(categoryParent, validation.getParent(),
                "clearing search returns the validation with its property row");
        assertSame(categoryParent, restore.getParent(),
                "clearing search returns the restore icon with its property row");
        JButton help = (JButton) readDeclaredField(apiRow, "help");
        assertSame(categoryParent, help.getParent(),
                "clearing search returns the information icon with its property row");

        JTabbedPane tabs = (JTabbedPane) readDeclaredField(panel, "categoryTabbedPane");
        JScrollPane apiSettings = (JScrollPane) tabs.getComponentAt(0);
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                apiSettings.getHorizontalScrollBarPolicy(),
                "wizard tabs allow horizontal scrolling when their minimum content width requires it");

        SwingUtilities.invokeAndWait(() -> editor.setText("8125"));
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(restore.isVisible(), "a valid value hides the restore button");
        assertFalse(validation.isVisible(), "a valid value hides the validation message");
        assertNull(editor.getClientProperty(FlatClientProperties.OUTLINE),
                "a valid value restores the standard input outline");
    }

    private static void assertPortInvalid(NodeConfigurationPanel panel, JComboBox<?> combo, JTextField editor)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        @SuppressWarnings("unchecked")
        Map<String, JTextArea> validationLabels =
                (Map<String, JTextArea>) readDeclaredField(panel, "portValidationLabels");
        JTextArea label = validationLabels.get(Props.API_PORT.getName());
        assertTrue(label.isVisible());
        assertTrue(label.getText().contains("between 1 and 65535"));
        assertEquals(FlatClientProperties.OUTLINE_ERROR,
                editor.getClientProperty(FlatClientProperties.OUTLINE));
        @SuppressWarnings("unchecked")
        Map<String, JButton> restoreButtons =
                (Map<String, JButton>) readDeclaredField(panel, "portRestoreButtons");
        assertTrue(restoreButtons.get(Props.API_PORT.getName()).isVisible());
        assertSame(combo.getParent(), label.getParent(),
                "the error message stays within the port property's row container");
    }

    @Test
    @DisplayName("wizard Show values filter includes only UNSAVED_APPLIED rows when selected")
    void wizardUnsavedAppliedFilter() throws Exception {
        Properties appliedBaseline = new Properties();
        appliedBaseline.setProperty(Props.API_PORT.getName(),
                String.valueOf(Props.API_PORT.getDefaultValue()));
        NodeConfigurationPanel panel = buildPanel(true, appliedBaseline);

        SwingUtilities.invokeAndWait(() -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, JComponent> components =
                        (Map<String, JComponent>) readDeclaredField(panel, "propertyComponents");
                JComboBox<?> apiPort = (JComboBox<?>) components.get(Props.API_PORT.getName());
                JTextField editor = (JTextField) apiPort.getEditor().getEditorComponent();
                editor.setText(String.valueOf(Props.API_PORT.getDefaultValue()));

                Object apiRow = ((List<?>) readDeclaredField(panel, "allPropertyRows")).stream()
                        .filter(row -> {
                            try {
                                Object prop = readDeclaredField(row, "prop");
                                return Props.API_PORT.getName().equals(
                                        prop.getClass().getMethod("getName").invoke(prop));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .findFirst().orElseThrow();
                assertEquals("UNSAVED_APPLIED", invokeRowStatus(panel, apiRow).toString());

                for (String checkboxName : List.of("showUnsavedBox", "showSavedBox", "showAppliedBox")) {
                    JCheckBox checkbox = (JCheckBox) readDeclaredField(panel, checkboxName);
                    if (checkbox.isSelected()) {
                        checkbox.doClick();
                    }
                }
                JCheckBox unsavedApplied = (JCheckBox) readDeclaredField(panel, "showUnsavedAppliedBox");
                assertTrue(unsavedApplied.isVisible());
                assertTrue(unsavedApplied.isSelected());
                assertTrue(((List<?>) readDeclaredField(panel, "searchMatches")).contains(apiRow),
                        "the selected UNSAVED_APPLIED filter lists the matching row");

                unsavedApplied.doClick();
                assertFalse(((List<?>) readDeclaredField(panel, "searchMatches")).contains(apiRow),
                        "unselecting UNSAVED_APPLIED filters the row out");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("normal (console) mode keeps UNSAVED_APPLIED and its box (4-state model)")
    void normalModeFourState() throws Exception {
        NodeConfigurationPanel panel = buildPanel(false);

        assertTrue(boxVisible(panel, "showUnsavedAppliedBox"),
                "normal mode keeps the 4th ('unsaved applied') box");
        assertNotNull(findButtonByTooltip(panel, "New Profile"),
                "normal mode keeps the profile-action toolbar");
    }

    @Test
    @DisplayName("the regular profile editor displays separator whitespace escapes and marks edits unsaved")
    void regularProfileEditorEncodesDebugTraceWhitespace() throws Exception {
        NodeConfigurationPanel panel = buildPanel(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, JComponent> components =
                        (Map<String, JComponent>) readDeclaredField(panel, "propertyComponents");
                JComboBox<?> separator = (JComboBox<?>) components.get(
                        Props.NODE_DEBUG_TRACE_SEPARATOR.getName());
                JTextField editor = (JTextField) separator.getEditor().getEditorComponent();

                editor.setText(" ");
                assertEquals("\\u0020", editor.getText());
                Object tabAction = editor.getInputMap(JComponent.WHEN_FOCUSED)
                        .get(javax.swing.KeyStroke.getKeyStroke("TAB"));
                assertEquals("insertVisibleTabEscape", tabAction);
                editor.getActionMap().get(tabAction).actionPerformed(
                        new java.awt.event.ActionEvent(editor,
                                java.awt.event.ActionEvent.ACTION_PERFORMED, "TAB"));
                assertEquals("\\u0020\\t", editor.getText());

                Object row = ((List<?>) readDeclaredField(panel, "allPropertyRows")).stream()
                        .filter(candidate -> {
                            try {
                                Object prop = readDeclaredField(candidate, "prop");
                                return Props.NODE_DEBUG_TRACE_SEPARATOR.getName()
                                        .equals(prop.getClass().getMethod("getName").invoke(prop));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .findFirst().orElseThrow();
                assertEquals("UNSAVED", invokeRowStatus(panel, row).toString());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNull(error.get(), "the regular profile editor must apply the separator's visible escape behavior");
    }

    @Test
    @DisplayName("wizard mode's editor fits the advanced dialog (no horizontal scrollbar)")
    void wizardModeFitsDialogWidth() throws Exception {
        NodeConfigurationPanel panel = buildPanel(true);
        int min = panel.getMinimumSize().width;
        int pref = panel.getPreferredSize().width;
        // A JViewport shows its AS_NEEDED horizontal scrollbar when the view's
        // PREFERRED width exceeds the viewport (900px dialog) — the minimum is
        // only relevant for how far the content can shrink. Both must fit.
        assertTrue(min <= 900,
                "the wizard editor's minimum width must fit the 900px advanced dialog (min=" + min + ")");
        assertTrue(pref <= 900,
                "the wizard editor's PREFERRED width must fit the 900px advanced dialog, "
                        + "or the dialog would show a horizontal scrollbar (preferred=" + pref + ")");

        JTabbedPane tabs = (JTabbedPane) readDeclaredField(panel, "categoryTabbedPane");
        JScrollPane apiSettings = (JScrollPane) tabs.getComponentAt(0);
        apiSettings.setSize(900, 600);
        apiSettings.doLayout();
        assertFalse(apiSettings.getHorizontalScrollBar().isVisible(),
                "the tab content tracks the viewport width when its minimum width fits");

        int contentMinWidth = apiSettings.getViewport().getView().getMinimumSize().width;
        apiSettings.setSize(Math.max(80, contentMinWidth - 40), 600);
        apiSettings.doLayout();
        assertTrue(apiSettings.getHorizontalScrollBar().isVisible(),
                "a horizontal scrollbar appears only when the tab's minimum width cannot fit; min="
                        + contentMinWidth + ", viewport=" + apiSettings.getViewport().getWidth()
                        + ", view=" + apiSettings.getViewport().getView().getWidth());
    }

    /** Constructs a (wizard / normal) panel on the EDT and returns it. */
    private static NodeConfigurationPanel buildPanel(boolean wizard) throws Exception {
        return buildPanel(wizard, null);
    }

    private static NodeConfigurationPanel buildPanel(boolean wizard, Properties originalAppliedBaseline)
            throws Exception {
        // Wizard mode loads from (and saves to) the virtual in-memory session
        // profile — the profile file is not read or written by the panel.
        final NodeProfile virtual = new NodeProfile.Builder(PROFILE).build();
        virtual.getProperties().setProperty("node.valueSuffix", "TESTCOIN");
        if (wizard && originalAppliedBaseline != null) {
            virtual.getProperties().setProperty(Props.API_PORT.getName(), "8126");
        }
        final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                // Same icon-font registration the application performs at startup.
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("wizard-mode-test-owner");
                NodeConfigurationPanel panel = wizard
                        ? new NodeConfigurationPanel(null, "./conf", null, null, PROFILE, virtual,
                                originalAppliedBaseline)
                        : new NodeConfigurationPanel(null, "./conf", null, null, PROFILE);
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                windowRef.set(owner);
                panelRef.set(panel);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNotNull(panelRef.get(), "the configuration panel must be constructable: " + error.get());
        // Pump the EDT so the async initUI (queued via invokeLater in the
        // constructor) has run before the test reads the panel's fields.
        SwingUtilities.invokeAndWait(() -> {
            // no-op — yields the EDT to run the pending initUI
        });
        return panelRef.get();
    }

    private static void disposeWindow() {
        Window w = windowRef.getAndSet(null);
        if (w != null) {
            SwingUtilities.invokeLater(w::dispose);
        }
    }

    private static boolean boxVisible(NodeConfigurationPanel panel, String fieldName) throws Exception {
        Class<?> c = panel.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object box = f.get(panel);
                assertNotNull(box, fieldName + " must exist");
                return ((java.awt.Component) box).isVisible();
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("field not found: " + fieldName);
    }

    /** The first {@link JButton} in the component tree whose tooltip contains the fragment (or null). */
    private static JButton findButtonByTooltip(JComponent root, String fragment) {
        ArrayDeque<Component> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Component c = queue.poll();
            if (c instanceof JButton btn && btn.getToolTipText() != null
                    && btn.getToolTipText().contains(fragment)) {
                return btn;
            }
            if (c instanceof Container container) {
                for (Component child : container.getComponents()) {
                    queue.add(child);
                }
            }
        }
        return null;
    }
}