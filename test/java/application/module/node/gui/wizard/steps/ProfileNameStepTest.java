package application.module.node.gui.wizard.steps;

import application.module.node.gui.wizard.WizardContext;
import application.module.node.profile.Network;
import jiconfont.icons.font_awesome.FontAwesome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProfileNameStep")
class ProfileNameStepTest {

    private final WizardContext context = new WizardContext();

    @Test
    @DisplayName("id / title / header icon")
    void identity() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        assertEquals("profile-name", step.getId());
        assertEquals("Profile name", step.getTitle());
        assertEquals(FontAwesome.TAG, step.getHeaderIcon());
    }

    @Test
    @DisplayName("suggested name is resolved by the SSOT with an _NN suffix when taken")
    void suggestedName() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>(List.of("mainnet", "mainnet_01")));
        assertEquals("mainnet_02", step.resolveSuggested(context));
    }

    @Test
    @DisplayName("validate accepts the suggested name")
    void validateSuggestedOk() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        assertNull(step.validate(context));
    }

    @Test
    @DisplayName("validate rejects a custom name that is already taken")
    void validateRejectsTakenCustomName() {
        ProfileNameStep step = new ProfileNameStep(() -> Set.of("my-node"));
        selectCustom(step);
        nameField(step).setText("my-node");
        String error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.contains("already exists"));
    }

    @Test
    @DisplayName("validate rejects a reserved custom name")
    void validateRejectsReservedName() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        selectCustom(step);
        nameField(step).setText("node-default");
        String error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("reserved"));
    }

    @Test
    @DisplayName("validate rejects an empty custom name")
    void validateRejectsEmptyCustomName() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        selectCustom(step);
        nameField(step).setText("   ");
        String error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.contains("required"));
    }

    @Test
    @DisplayName("resolveName returns the custom name when selected, the suggested one otherwise")
    void resolveNameFollowsTheSelection() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        assertFalse(step.isCustomName(), "the suggested option is preselected");
        assertEquals("mainnet", step.resolveName(context));
        selectCustom(step);
        nameField(step).setText("my-node");
        assertTrue(step.isCustomName());
        assertEquals("my-node", step.resolveName(context));
        assertEquals("my-node", step.getCustomName());
    }

    @Test
    @DisplayName("onExit stores the custom name (null for the suggested one) and the resolved name")
    void onExitStoresTheNameChoice() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        step.onExit(context); // suggested
        assertNull(context.getCustomName());
        assertEquals("mainnet", context.getName());

        WizardContext custom = new WizardContext();
        selectCustom(step);
        nameField(step).setText("my-node");
        step.onExit(custom);
        assertEquals("my-node", custom.getCustomName());
        assertEquals("my-node", custom.getName());
    }

    @Test
    @DisplayName("network radios: the context and the suggested name follow the choice live")
    void networkSelectionDrivesSuggestedName() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        WizardContext ctx = new WizardContext();
        step.bindContext(ctx);
        assertEquals(Network.MAINNET, ctx.getNetwork(), "mainnet is the default network");
        assertEquals("mainnet", step.resolveSuggested(ctx));

        findByClass(step.getPanel(), javax.swing.JRadioButton.class,
                rb -> "Testnet".equals(rb.getText())).doClick();
        assertEquals(Network.TESTNET, ctx.getNetwork(), "the radio mirrors the choice into the context live");
        assertEquals("testnet", step.resolveSuggested(ctx));

        // Mocknet is intentionally not offered (yet): only mainnet / testnet radios exist.
        List<Component> children = new ArrayList<>();
        collectChildren(step.getPanel(), children);
        List<String> networkRadios = new ArrayList<>();
        for (Component c : children) {
            if (c instanceof javax.swing.JRadioButton rb
                    && (rb.getText().startsWith("Mainnet") || rb.getText().startsWith("Testnet")
                        || rb.getText().startsWith("Mocknet"))) {
                networkRadios.add(rb.getText());
            }
        }
        assertEquals(List.of("Mainnet (default)", "Testnet"), networkRadios,
                "only mainnet / testnet are offered (mocknet removed for now)");
        // Back to mainnet: the suggested name follows again.
        findByClass(step.getPanel(), javax.swing.JRadioButton.class,
                rb -> rb.getText().startsWith("Mainnet")).doClick();
        assertEquals(Network.MAINNET, ctx.getNetwork());
        assertEquals("mainnet", step.resolveSuggested(ctx));
    }

    @Test
    @DisplayName("the name input is pre-filled with the suggested name (read-only), then editable for a custom choice")
    void nameFieldFollowsTheChoice() {
        ProfileNameStep step = new ProfileNameStep(() -> new HashSet<>());
        WizardContext ctx = new WizardContext();
        step.bindContext(ctx);
        javax.swing.JTextField field = nameField(step);
        assertEquals("mainnet", field.getText(),
                "the suggested name is pre-filled while the suggested option is selected");
        assertFalse(field.isEnabled(), "the field is read-only while the suggested option is selected");

        selectCustom(step);
        assertTrue(field.isEnabled(), "the field is editable for the custom choice");
        field.setText("my-node");
        assertEquals("my-node", field.getText(), "the typed name is shown for the custom choice");
    }

    // ── helpers: navigate the Swing tree by class + predicate ─────────────

    private static void selectCustom(ProfileNameStep step) {
        findByClass(step.getPanel(), javax.swing.JRadioButton.class,
                rb -> "Custom name".equals(rb.getText())).doClick();
    }

    private static javax.swing.JTextField nameField(ProfileNameStep step) {
        // the name field is the 24-column, standard-styled field
        return findByClass(step.getPanel(), javax.swing.JTextField.class,
                f -> f.getColumns() == 24);
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