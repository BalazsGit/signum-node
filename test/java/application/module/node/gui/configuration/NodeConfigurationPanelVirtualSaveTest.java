package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.steps.NodeConfigurationStep;
import application.module.node.profile.NodeProfile;
import application.module.node.props.Props;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wizard's advanced-editor (virtual) save: the full editor persists the
 * profile in its minimal-override form (a key at its property default is
 * absent), so the virtual save must MERGE the editor's overrides into the
 * wizard's virtual in-memory session profile — never replace it. A replace
 * dropped every key the editor omits, and the wizard's read-back saw
 * "absent" where "default" was meant (the reported tryParsePort NPE + the
 * stale step-4 fields after a Save).
 */
@DisplayName("NodeConfigurationPanel virtual (wizard-mode) save")
class NodeConfigurationPanelVirtualSaveTest {

    private static final long INIT_TIMEOUT_MS = 60_000;

    @BeforeAll
    static void registerIconFont() {
        jiconfont.swing.IconFontSwing.register(
                jiconfont.icons.font_awesome.FontAwesome.getIconFont());
    }

    @Test
    @DisplayName("a virtual save merges the editor's overrides: non-default values stay, a default-valued key is cleared, unmanaged keys are kept")
    void virtualSaveMergesIntoTheSessionProfile() throws Exception {
        String name = "virtual-save-test";
        WizardContext context = new WizardContext();
        context.setName(name);
        context.setEngine(DatabaseEngine.SQLITE);
        NodeConfigurationStep step = new NodeConfigurationStep();
        step.bindContext(context);
        step.onEnter(context);
        ((JTextField) readDeclaredField(step, "apiPortField")).setText("8126");
        NodeProfile virtual = context.getVirtualProfile();
        Properties virtualProps = virtual.getProperties();
        assertEquals("8126", virtualProps.getProperty(Props.API_PORT.getName()),
                "step 4's 8125 → 8126 edit is already in the virtual profile");
        virtualProps.setProperty(Props.DB_URL.getName(),
                "jdbc:sqlite:file:./database/SQLite/" + name + "/ledger.db");
        virtualProps.setProperty(Props.P2P_PORT.getName(),
                String.valueOf(Props.P2P_PORT.getDefaultValue())); // at its default: cleared by the merge
        virtualProps.setProperty("some.unmanaged.key", "42"); // a key the editor does not manage
        Properties originalBaseline = new Properties();
        originalBaseline.setProperty(Props.DB_URL.getName(),
                "jdbc:sqlite:file:./database/SQLite/" + name + "/signum.sqlite.db");
        originalBaseline.setProperty(Props.NODE_DEBUG_TRACE_SEPARATOR.getName(), "\t");

        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final JFrame[] ownerRef = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                JFrame owner = new JFrame("virtual-save-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, name, virtual, originalBaseline);
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
            // Wait for the async initUI (the rows / value suppliers must be built).
            Object[] ready = new Object[1];
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && ready[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Object rows = readDeclaredField(panelRef[0], "allPropertyRows");
                        if (rows instanceof List<?> list && !list.isEmpty()) {
                            ready[0] = true;
                        }
                    } catch (Exception ignore) {
                    }
                });
                Thread.sleep(100);
            }
            assertNotNull(ready[0], "the panel UI must finish initializing");

            Object[] jdbcStatus = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    jdbcStatus[0] = invoke(panelRef[0], "jdbcRowStatus");
                } catch (Exception e) {
                    error.set(e);
                }
            });
            assertNull(error.get(), "the JDBC row state must be readable");
            assertEquals("SAVED", jdbcStatus[0].toString(),
                    "the step 4 database URL is already accepted in the wizard's virtual profile");
            Object separatorRow = findPropertyRow(panelRef[0], Props.NODE_DEBUG_TRACE_SEPARATOR.getName());
            assertNotNull(separatorRow, "the debug trace separator row must be present");
            assertEquals("SAVED_APPLIED", invokeRowStatus(panelRef[0], separatorRow).toString(),
                    "the visible \\t escape must match the actual tab baseline and stay out of the unsaved filter");
            Object apiPortRow = findPropertyRow(panelRef[0], Props.API_PORT.getName());
            assertNotNull(apiPortRow, "the API port row must be present");
            assertEquals("SAVED", invokeRowStatus(panelRef[0], apiPortRow).toString(),
                    "the step 4 API port is accepted and displayed as saved in Advanced Settings");

            // Verify the step's API port reaches the editor, then edit it as a
            // user would before driving the virtual save directly.
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Object components = readDeclaredField(panelRef[0], "propertyComponents");
                    JComponent apiPort = (JComponent) ((java.util.Map<?, ?>) components)
                            .get(Props.API_PORT.getName());
                    assertTrue(apiPort instanceof JComboBox<?>);
                    JComboBox<?> apiPortCombo = (JComboBox<?>) apiPort;
                    assertEquals("8126", apiPortCombo.getEditor().getItem(),
                            "the API port entered in step 4 is loaded into Advanced Settings");
                    JComponent separator = (JComponent) ((java.util.Map<?, ?>) components)
                            .get(Props.NODE_DEBUG_TRACE_SEPARATOR.getName());
                    assertTrue(separator instanceof JComboBox<?>);
                    assertEquals("\\t", ((JComboBox<?>) separator).getEditor().getItem(),
                            "the default tab separator is visibly represented as \\t");
                    JTextField separatorEditor = (JTextField) ((JComboBox<?>) separator).getEditor()
                            .getEditorComponent();
                    separatorEditor.setText(" ");
                    assertEquals("\\u0020", separatorEditor.getText(),
                            "a literal space is displayed as an explicit Unicode escape");
                    Object tabActionKey = separatorEditor.getInputMap(JComponent.WHEN_FOCUSED)
                            .get(javax.swing.KeyStroke.getKeyStroke("TAB"));
                    assertEquals("insertVisibleTabEscape", tabActionKey,
                            "Tab is handled as visible text instead of moving focus");
                    separatorEditor.setText("\\t\\u0020");
                    assertEquals("UNSAVED",
                            invokeRowStatus(panelRef[0],
                                    findPropertyRow(panelRef[0], Props.NODE_DEBUG_TRACE_SEPARATOR.getName()))
                                    .toString(),
                            "adding a space to the separator differs from the saved/applied tab");
                    separatorEditor.getActionMap().get(tabActionKey).actionPerformed(
                            new java.awt.event.ActionEvent(separatorEditor,
                                    java.awt.event.ActionEvent.ACTION_PERFORMED, "TAB"));
                    assertEquals("\\t\\u0020\\t", separatorEditor.getText(),
                            "pressing Tab appends a visible escape after the space");
                    JTextField apiPortEditor = (JTextField) apiPortCombo.getEditor().getEditorComponent();
                    apiPortEditor.setText("8124");
                } catch (Exception e) {
                    error.set(e);
                }
            });
            assertNull(error.get(), "the API port editor must be accessible");

            // The end-to-end dialog test below covers the real Save button.
            // Exercise the virtual merge here without opening a second modal
            // dialog so this focused state test stays deterministic.
            Object[] report = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    report[0] = invoke(panelRef[0], "getUnsavedChangesReport");
                } catch (Exception e) {
                    error.set(e);
                }
            });
            assertNull(error.get(), "the dirty report must be readable");
            assertNotNull(report[0], "typing into the API port editor must be detected as unsaved");

            final Object[] saved = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    saved[0] = invoke(panelRef[0], "doSaveCurrentProfile");
                } catch (Throwable t) {
                    error.set(t);
                }
            });
            assertNull(error.get(), "the virtual save must not fail");
            assertEquals(Boolean.TRUE, saved[0], "the virtual save reports success");

            Properties result = virtual.getProperties();
            assertEquals("8124", result.getProperty(Props.API_PORT.getName()),
                    "the edited API port is saved back to the wizard's virtual profile");
            assertEquals("\t \t", result.getProperty(Props.NODE_DEBUG_TRACE_SEPARATOR.getName()),
                    "the visible tab escapes and space are decoded to real whitespace before saving");
            Object[] visibleSeparatorAfterSave = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JComboBox<?> separator = (JComboBox<?>) readComponentMap(
                            (NodeConfigurationPanel) panelRef[0])
                            .get(Props.NODE_DEBUG_TRACE_SEPARATOR.getName());
                    visibleSeparatorAfterSave[0] = separator.getEditor().getItem();
                } catch (Exception e) {
                    error.set(e);
                }
            });
            assertNull(error.get(), "the saved separator editor must be readable");
            assertEquals("\\t\\u0020\\t", visibleSeparatorAfterSave[0],
                    "after saving, tabs and spaces are shown as visible escapes");
            assertEquals("SAVED", invokeRowStatus(panelRef[0],
                    findPropertyRow(panelRef[0], Props.NODE_DEBUG_TRACE_SEPARATOR.getName())).toString(),
                    "the virtual save moves the separator to the saved state");
            assertNull(result.getProperty(Props.P2P_PORT.getName()),
                    "a key at its property default is cleared (the minimal-override file convention)");
            String savedUrl = result.getProperty(Props.DB_URL.getName());
            assertTrue(savedUrl != null && savedUrl.contains("virtual-save-test"),
                    "the session's DB.Url survives the virtual save (was: " + savedUrl + ")");
            assertEquals("42", result.getProperty("some.unmanaged.key"),
                    "a key the editor does not manage is kept (a replace would have dropped it)");
            Method readBack = NodeConfigurationStep.class.getDeclaredMethod(
                    "readBackFromVirtualProfile", WizardContext.class);
            readBack.setAccessible(true);
            readBack.invoke(step, context);
            assertEquals("8124", step.getApiPortText(),
                    "the step 4 field reflects the API port saved from Advanced Settings");
        } finally {
            if (ownerRef[0] != null) {
                ownerRef[0].dispose();
            }
        }
    }

    @Test
    @DisplayName("the real wizard dialog round-trips all step 4 ports through Advanced Save")
    void advancedDialogRoundTripsStepFourPorts() throws Exception {
        WizardContext context = new WizardContext();
        context.setName("virtual-save-dialog-test");
        context.setEngine(DatabaseEngine.SQLITE);
        NodeConfigurationStep[] stepRef = new NodeConfigurationStep[1];
        JFrame[] ownerRef = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            stepRef[0] = new NodeConfigurationStep();
            stepRef[0].bindContext(context);
            stepRef[0].onEnter(context);
            ownerRef[0] = new JFrame("virtual-save-dialog-owner");
            ownerRef[0].setContentPane(stepRef[0].getPanel());
            ownerRef[0].setSize(900, 700);
            ownerRef[0].setVisible(true);
            try {
                setPortText(stepRef[0], "apiPortField", "8126");
                setPortText(stepRef[0], "p2pPortField", "8124");
                setPortText(stepRef[0], "wsPortField", "8127");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Properties livePorts = context.getVirtualProfile().getProperties();
        assertEquals("8126", livePorts.getProperty(Props.API_PORT.getName()));
        assertEquals("8124", livePorts.getProperty(Props.P2P_PORT.getName()));
        assertEquals("8127", livePorts.getProperty(Props.API_WEBSOCKET_PORT.getName()));

        AtomicReference<Throwable> interactionError = new AtomicReference<>();
        Thread interaction = new Thread(() -> {
            try {
                JDialog advanced = waitForDialog("Node Profile Configuration", INIT_TIMEOUT_MS);
                assertNotNull(advanced, "the wizard must open the real Advanced dialog");
                NodeConfigurationPanel panel = waitForConfigurationPanel(advanced, INIT_TIMEOUT_MS);
                assertNotNull(panel, "the Advanced dialog must finish building its configuration editor");
                assertDialogFitsUsableScreen(advanced);
                JComboBox<?> openedApiPort = (JComboBox<?>) readComponentMap(panel).get(Props.API_PORT.getName());
                JComboBox<?> openedP2pPort = (JComboBox<?>) readComponentMap(panel).get(Props.P2P_PORT.getName());
                JComboBox<?> openedWsPort = (JComboBox<?>) readComponentMap(panel)
                        .get(Props.API_WEBSOCKET_PORT.getName());
                assertEquals("8126", openedApiPort.getEditor().getItem(),
                        "the live Step 4 API port must be loaded into the real Advanced dialog");
                assertEquals("8124", openedP2pPort.getEditor().getItem(),
                        "the live Step 4 P2P port must be loaded into the real Advanced dialog");
                assertEquals("8127", openedWsPort.getEditor().getItem(),
                        "the live Step 4 WebSocket port must be loaded into the real Advanced dialog");
                assertEquals("SAVED", invokeRowStatus(panel,
                        findPropertyRow(panel, Props.API_PORT.getName())).toString(),
                        "a live Step 4 API port is already accepted, not unsaved, in Advanced Settings");

                SwingUtilities.invokeAndWait(() -> {
                    try {
                        JComboBox<?> apiPort = (JComboBox<?>) readComponentMap(panel).get(Props.API_PORT.getName());
                        ((JTextField) apiPort.getEditor().getEditorComponent()).setText("8124");
                        assertEquals("UNSAVED", invokeRowStatus(panel,
                                findPropertyRow(panel, Props.API_PORT.getName())).toString(),
                                "editing an accepted Step 4 port in Advanced Settings becomes unsaved");
                        JButton saveButton = findButton(advanced, "Save");
                        assertNotNull(saveButton, "the Advanced dialog's Save button");
                        SwingUtilities.invokeLater(saveButton::doClick);
                    } catch (Throwable t) {
                        interactionError.set(t);
                    }
                });

                JDialog confirmation = waitForDialog("Save Configuration", INIT_TIMEOUT_MS);
                assertNotNull(confirmation, "editing the API port must trigger the save confirmation");
                SwingUtilities.invokeAndWait(() -> {
                    JButton saveButton = findButton(confirmation, "Save");
                    assertNotNull(saveButton, "the save confirmation's Save button");
                    saveButton.doClick();
                });
            } catch (Throwable t) {
                interactionError.set(t);
                SwingUtilities.invokeLater(() -> {
                    for (java.awt.Window window : java.awt.Window.getWindows()) {
                        if (window instanceof JDialog dialog) {
                            dialog.dispose();
                        }
                    }
                });
            }
        }, "wizard-advanced-dialog-interaction");
        interaction.start();

        try {
            Method openAdvanced = NodeConfigurationStep.class.getDeclaredMethod("openAdvanced");
            openAdvanced.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    openAdvanced.invoke(stepRef[0]);
                } catch (Throwable t) {
                    interactionError.compareAndSet(null, t);
                }
            });
            interaction.join(INIT_TIMEOUT_MS);
            assertFalse(interaction.isAlive(), "the modal save flow must complete");
            assertNull(interactionError.get(), "the dialog interaction must succeed");
            String[] apiPortAfterSave = new String[1];
            SwingUtilities.invokeAndWait(() -> apiPortAfterSave[0] = stepRef[0].getApiPortText());
            assertEquals("8124", apiPortAfterSave[0],
                    "the step 4 field must show the value saved in Advanced Settings");
            assertEquals("8124", context.getVirtualProfile().getProperties()
                    .getProperty(Props.API_PORT.getName()),
                    "the wizard's virtual profile must retain the latest Advanced value");
            assertEquals(8124, context.getApiPort());
            assertEquals(8124, context.getP2pPort());
            assertEquals(8127, context.getWsPort());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    if (window instanceof JDialog dialog) {
                        dialog.dispose();
                    }
                }
                ownerRef[0].dispose();
            });
        }
    }

    private static void setPortText(NodeConfigurationStep step, String fieldName, String value) throws Exception {
        JTextField field = (JTextField) readDeclaredField(step, fieldName);
        field.setText(value);
    }

    private static void assertDialogFitsUsableScreen(JDialog dialog) {
        java.awt.GraphicsConfiguration configuration = dialog.getGraphicsConfiguration();
        java.awt.Rectangle usable = new java.awt.Rectangle(configuration.getBounds());
        java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        usable.x += insets.left;
        usable.y += insets.top;
        usable.width -= insets.left + insets.right;
        usable.height -= insets.top + insets.bottom;
        assertTrue(usable.contains(dialog.getBounds()),
                "Advanced Configuration must stay inside the usable screen bounds when the panel scrolls");
    }

    @Test
    @DisplayName("the unsaved-changes prompt is centered on the Advanced Configuration window")
    void unsavedPromptIsCenteredOnAdvancedWindow() throws Exception {
        String name = "virtual-save-center-test";
        Properties profileProperties = new Properties();
        profileProperties.setProperty(Props.API_PORT.getName(), "8126");
        NodeProfile virtual = new NodeProfile(name);
        virtual.setProperties(profileProperties);
        NodeConfigurationPanel[] panelRef = new NodeConfigurationPanel[1];
        JDialog[] ownerRef = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("wizard-test-frame");
            ownerRef[0] = new JDialog(frame, "Node Profile Configuration");
            panelRef[0] = new NodeConfigurationPanel(null, "./conf", null, null, name,
                    virtual, profileProperties);
            ownerRef[0].add(panelRef[0]);
            ownerRef[0].setBounds(120, 100, 900, 700);
            ownerRef[0].setVisible(true);
        });

        try {
            NodeConfigurationPanel panel = waitForConfigurationPanel(ownerRef[0], INIT_TIMEOUT_MS);
            assertNotNull(panel, "the Advanced Configuration panel must finish initializing");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JComboBox<?> apiPort = (JComboBox<?>) readComponentMap(panel).get(Props.API_PORT.getName());
                    ((JTextField) apiPort.getEditor().getEditorComponent()).setText("8124");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            AtomicReference<Throwable> interactionError = new AtomicReference<>();
            AtomicReference<Boolean> proceeded = new AtomicReference<>();
            Thread interaction = new Thread(() -> {
                try {
                    JDialog prompt = waitForDialog("Unsaved Changes", INIT_TIMEOUT_MS);
                    assertNotNull(prompt, "the close guard must show its unsaved-changes prompt");
                    assertEquals(ownerRef[0], prompt.getOwner(),
                            "the prompt must be owned by the Advanced Configuration window");
                    java.awt.Point promptLocation = prompt.getLocationOnScreen();
                    java.awt.Point ownerLocation = ownerRef[0].getLocationOnScreen();
                    assertEquals(ownerLocation.x + ownerRef[0].getWidth() / 2,
                            promptLocation.x + prompt.getWidth() / 2, 2,
                            "the prompt must be horizontally centered on the Advanced window");
                    assertEquals(ownerLocation.y + ownerRef[0].getHeight() / 2,
                            promptLocation.y + prompt.getHeight() / 2, 2,
                            "the prompt must be vertically centered on the Advanced window");
                    SwingUtilities.invokeAndWait(() -> {
                        JButton cancel = findButton(prompt, "Cancel");
                        assertNotNull(cancel, "the prompt's Cancel button");
                        cancel.doClick();
                    });
                } catch (Throwable t) {
                    interactionError.set(t);
                    SwingUtilities.invokeLater(() -> {
                        for (java.awt.Window window : java.awt.Window.getWindows()) {
                            if (window instanceof JDialog dialog && "Unsaved Changes".equals(dialog.getTitle())) {
                                dialog.dispose();
                            }
                        }
                    });
                }
            }, "unsaved-prompt-position-check");
            interaction.start();
            SwingUtilities.invokeAndWait(() ->
                    proceeded.set(panel.checkUnsavedChangesAndProceed(null, null)));
            interaction.join(INIT_TIMEOUT_MS);
            assertFalse(interaction.isAlive(), "the modal close guard must finish");
            assertNull(interactionError.get(), "the prompt positioning check must pass");
            assertEquals(Boolean.FALSE, proceeded.get(), "Cancel keeps the Advanced window open");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                ownerRef[0].dispose();
                if (ownerRef[0].getOwner() != null) {
                    ownerRef[0].getOwner().dispose();
                }
            });
        }
    }

    private static Object readDeclaredField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, JComponent> readComponentMap(NodeConfigurationPanel panel) throws Exception {
        return (java.util.Map<String, JComponent>) readDeclaredField(panel, "propertyComponents");
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object findPropertyRow(Object panel, String propertyName) throws Exception {
        for (Object row : (List<?>) readDeclaredField(panel, "allPropertyRows")) {
            Object prop = readDeclaredField(row, "prop");
            if (propertyName.equals(prop.getClass().getMethod("getName").invoke(prop))) {
                return row;
            }
        }
        return null;
    }

    private static Object invokeRowStatus(Object panel, Object row) throws Exception {
        Method method = java.util.Arrays.stream(panel.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("rowStatus"))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        if (SwingUtilities.isEventDispatchThread()) {
            return method.invoke(panel, row);
        }
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = method.invoke(panel, row);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return result[0];
    }

    private static JDialog waitForDialog(String title, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing() && title.equals(dialog.getTitle())) {
                    return dialog;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static JButton findButton(java.awt.Container container, String text) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof java.awt.Container child) {
                JButton result = findButton(child, text);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static NodeConfigurationPanel waitForConfigurationPanel(JDialog dialog, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            NodeConfigurationPanel panel = findConfigurationPanel(dialog);
            if (panel != null) {
                try {
                    if (readComponentMap(panel).containsKey(Props.API_PORT.getName())) {
                        return panel;
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static NodeConfigurationPanel findConfigurationPanel(java.awt.Container container) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof NodeConfigurationPanel panel) {
                return panel;
            }
            if (component instanceof java.awt.Container child) {
                NodeConfigurationPanel result = findConfigurationPanel(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}