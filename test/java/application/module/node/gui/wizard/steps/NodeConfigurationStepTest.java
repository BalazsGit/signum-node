package application.module.node.gui.wizard.steps;

import application.module.node.gui.wizard.WizardContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NodeConfigurationStep")
class NodeConfigurationStepTest {

    private final WizardContext context = new WizardContext();

    @Test
    @DisplayName("suggested name is resolved by the SSOT with _NN suffix when taken")
    void suggestedName() {
        NodeConfigurationStep step = new NodeConfigurationStep(() -> new java.util.HashSet<>(List.of("mainnet", "mainnet_01")));
        assertEquals("mainnet_02", step.resolveName(context));
    }

    @Test
    @DisplayName("validate accepts a valid suggested name")
    void validateSuggestedOk() {
        NodeConfigurationStep step = new NodeConfigurationStep(() -> new java.util.HashSet<>());
        assertNull(step.validate(context));
    }

    @Test
    @DisplayName("validate rejects a custom name that is already taken")
    void validateRejectsTakenCustomName() {
        NodeConfigurationStep step = new NodeConfigurationStep(() -> Set.of("my-node"));
        selectCustom(step);
        nameField(step).setText("my-node");
        String error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.contains("already exists"));
    }

    @Test
    @DisplayName("validate rejects a reserved custom name")
    void validateRejectsReservedName() {
        NodeConfigurationStep step = new NodeConfigurationStep(() -> new java.util.HashSet<>());
        selectCustom(step);
        nameField(step).setText("node-default");
        String error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("reserved"));
    }

    @Test
    @DisplayName("validate rejects a non-numeric custom API port")
    void validateRejectsBadPort() {
        NodeConfigurationStep step = new NodeConfigurationStep(() -> new java.util.HashSet<>());
        selectCustom(step);
        nameField(step).setText("good-name");
        useDefaultPorts(step, false);
        portField(step, "8125").setText("not-a-port");
        String error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("api port"));
    }

    @Test
    @DisplayName("onExit collects name and custom ports into the context")
    void onExitCollects() {
        NodeConfigurationStep step = new NodeConfigurationStep(() -> new java.util.HashSet<>());
        selectCustom(step);
        nameField(step).setText("collected");
        useDefaultPorts(step, false);
        portField(step, "8125").setText("11111");
        portField(step, "8123").setText("11112");
        portField(step, "8126").setText("11113");
        step.onExit(context);
        assertEquals("collected", context.getName());
        assertEquals(false, context.isUseDefaultPorts());
        assertEquals(11111, context.getApiPort());
        assertEquals(11112, context.getP2pPort());
        assertEquals(11113, context.getWsPort());
    }

    // ── helpers: navigate the Swing tree by class + predicate ─────────────

    private static void selectCustom(NodeConfigurationStep step) {
        findByClass(step.getPanel(), javax.swing.JRadioButton.class,
                rb -> "Custom name".equals(rb.getText())).doClick();
    }

    private static javax.swing.JTextField nameField(NodeConfigurationStep step) {
        // the name field is the 28-column field (port fields are 8 columns)
        return findByClass(step.getPanel(), javax.swing.JTextField.class,
                f -> f.getColumns() == 28);
    }

    private static void useDefaultPorts(NodeConfigurationStep step, boolean on) {
        javax.swing.JCheckBox box = findByClass(step.getPanel(), javax.swing.JCheckBox.class,
                cb -> cb.getText().startsWith("Use default ports"));
        if (box.isSelected() != on) {
            box.doClick();
        }
        assertEquals(on, box.isSelected());
    }

    private static javax.swing.JTextField portField(NodeConfigurationStep step, String defaultValue) {
        return findByClass(step.getPanel(), javax.swing.JTextField.class,
                f -> f.isEnabled() && defaultValue.equals(f.getText()));
    }

    private static <T extends javax.swing.JComponent> T findByClass(
            javax.swing.JComponent root, Class<T> type, Predicate<T> filter) {
        List<Component> children = new ArrayList<>();
        collectChildren(root, children);
        for (Component c : children) {
            if (type.isInstance(c) && filter.test(type.cast(c))) {
                return type.cast(c);
            }
        }
        throw new IllegalStateException("component not found: " + type.getName());
    }

    private static void collectChildren(javax.swing.JComponent root, List<Component> out) {
        for (Component c : root.getComponents()) {
            if (c instanceof javax.swing.JComponent) {
                out.add(c);
                collectChildren((javax.swing.JComponent) c, out);
            }
        }
    }
}