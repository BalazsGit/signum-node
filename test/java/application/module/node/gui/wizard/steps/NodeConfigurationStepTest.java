package application.module.node.gui.wizard.steps;

import com.formdev.flatlaf.FlatClientProperties;
import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.profile.Network;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.HelpButton;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NodeConfigurationStep")
class NodeConfigurationStepTest {

    @BeforeAll
    static void registerIconFont() {
        // The application registers the icon font at startup (AppearanceModule#init);
        // the step builds its advanced-settings icon and the port-row help buttons
        // in the constructor.
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    private final WizardContext context = new WizardContext();

    @Test
    @DisplayName("id / title / header icon")
    void identity() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        assertEquals("node-configuration", step.getId());
        assertEquals("Node Profile configuration", step.getTitle());
        assertEquals(FontAwesome.COG, step.getHeaderIcon());
    }

    @Test
    @DisplayName("port values use plain editable fields pre-filled with defaults and a help button each")
    void portInputsUseTheConfigurationFormat() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        assertNotNull(portField(step, "8125"), "the API port field is pre-filled with the default");
        assertNotNull(portField(step, "8123"), "the P2P port field is pre-filled with the default");
        assertNotNull(portField(step, "8126"), "the WebSocket port field is pre-filled with the default");
        assertEquals(0, count(step, JComboBox.class), "there are no port choice catalogs");
        assertEquals(4, count(step, HelpButton.class),
                "the database URL and each port row have a property-information button");
    }

    @Test
    @DisplayName("validate accepts the pre-filled defaults and rejects a non-numeric custom API port")
    void validate() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        assertNull(step.validate(context), "the pre-filled defaults are always valid");
        JTextField apiPort = portField(step, "8125");
        apiPort.setText("not-a-port");
        String error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("api port"));
        apiPort.setText("8125");
        dbUrlField(step).setText("jdbc:sqlite:file:./database/SQLite/test/ledger.data");
        error = step.validate(context);
        assertNotNull(error);
        assertTrue(error.contains(".db"), "SQLite URLs with another extension are rejected");
        dbUrlField(step).setText("jdbc:sqlite:file:./database/SQLite/test/ledger.db");
        assertNull(step.validate(context), "a .db SQLite URL is valid");
    }

    @Test
    @DisplayName("onExit always collects the port values (there is no use-default toggle)")
    void onExitCollects() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        portField(step, "8125").setText("11111");
        portField(step, "8123").setText("11112");
        portField(step, "8126").setText("11113");
        step.onExit(context);
        assertEquals(Network.MAINNET, context.getNetwork(), "the network is owned by the name step");
        assertEquals(11111, context.getApiPort());
        assertEquals(11112, context.getP2pPort());
        assertEquals(11113, context.getWsPort());
        assertNull(context.getName(), "the name is owned by the profile-name step");
    }

    @Test
    @DisplayName("confirmExit passes (the full editor runs in its own modal dialog)")
    void confirmExitClosed() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        assertTrue(step.confirmExit(null, context));
    }

    @Test
    @DisplayName("the advanced settings are an icon button that opens the full editor dialog")
    void advancedSettingsIsAnIconButton() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        // The advanced-settings entry is the only text-less icon JButton in the step.
        JButton btn = findByClass(step.getPanel(), JButton.class, b ->
                b.getIcon() != null
                        && (b.getText() == null || b.getText().isEmpty()));
        assertNotNull(btn, "the advanced-settings entry button");
        assertNotNull(btn.getIcon(), "the entry is an icon (the COGS glyph)");
    }

    @Test
    @DisplayName("G4: the editable DB.Url field reflects the selected database profile (G3)")
    void dbUrlFieldShowsSelectedProfile() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_node");
        ctx.setEngine(DatabaseEngine.SQLITE);
        ctx.setSelectedDbProfileName("g4_db_profile");
        step.bindContext(ctx);
        assertTrue(dbUrlField(step).getText().contains("g4_db_profile"),
                "the DB.Url field reflects the selected database profile");
    }

    @Test
    @DisplayName("entering node configuration leaves the DB.Url value unselected")
    void dbUrlFieldIsNotSelectedOnEnter() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("wizard_node");
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);

        JTextField field = dbUrlField(step);
        field.selectAll();
        step.onEnter(ctx);

        assertEquals(field.getDocument().getLength(), field.getCaretPosition(),
                "the caret is placed at the end of the URL");
        assertEquals(field.getCaretPosition(), field.getSelectionStart(),
                "the URL has no selected text");
        assertEquals(field.getCaretPosition(), field.getSelectionEnd(),
                "the URL has no selected text");
    }

    @Test
    @DisplayName("the DB.Url SSOT: editing a server URL re-decides the engine + decomposes host/port/db, and re-composes on read")
    void dbUrlContextRoundTrip() {
        // Editing a server DB.Url in the step re-decides the engine + the connection:
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.SQLITE);
        NodeConfigurationStep.applyDbUrlToContext(ctx, "jdbc:mariadb://db.example.com:3306/signum_prod?useSSL=false");
        assertEquals(DatabaseEngine.MARIADB, ctx.getEngine(), "the engine follows the URL protocol");
        assertFalse(ctx.isSkipDbSetup(), "a configured server connection sets the database up");
        assertEquals("db.example.com", ctx.getDbHost());
        assertEquals(3306, ctx.getDbPort());
        assertEquals("signum_prod", ctx.getDbName());
        assertEquals("?useSSL=false", ctx.getDbUrlSuffix(), "the query string is the URL suffix");

        // The same SSOT re-composes the URL the field shows for that context:
        assertEquals("jdbc:mariadb://db.example.com:3306/signum_prod?useSSL=false",
                NodeConfigurationStep.dbUrlFromContext(ctx));

        // A PostgreSQL URL re-decides to that engine; a SQLite URL re-decides back:
        NodeConfigurationStep.applyDbUrlToContext(ctx, "jdbc:postgresql://host:5432/signum");
        assertEquals(DatabaseEngine.POSTGRESQL, ctx.getEngine());
        assertEquals("host", ctx.getDbHost());
        assertEquals(5432, ctx.getDbPort());
        NodeConfigurationStep.applyDbUrlToContext(ctx, "jdbc:sqlite:file:./database/SQLite/node1/node1.sqlite.db");
        assertEquals(DatabaseEngine.SQLITE, ctx.getEngine());
        assertEquals("jdbc:sqlite:file:./database/SQLite/node1/node1.sqlite.db", ctx.getSqliteDbUrl());
        assertEquals("node1", ctx.getSqliteDbProfileName());
    }

    @Test
    @DisplayName("entering the step creates the virtual in-memory session profile (no profile file on disk)")
    void sessionVirtualProfileOnEnter() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        String name = "g4_virtual_node_" + System.nanoTime();
        ctx.setName(name);
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.onEnter(ctx);
        assertNotNull(ctx.getVirtualProfile(), "the virtual in-memory session profile is created on entry");
        assertEquals(name, ctx.getVirtualProfile().getName(), "the virtual profile carries the session's name");
        assertEquals("8125", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.API_PORT.getName()),
                "the virtual profile starts with the API port shown in step 4");
        assertEquals("8123", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.P2P_PORT.getName()),
                "the virtual profile starts with the P2P port shown in step 4");
        assertEquals("8126", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.API_WEBSOCKET_PORT.getName()),
                "the virtual profile starts with the WebSocket port shown in step 4");
        assertFalse(Files.exists(ConfigurationUtils.resolveNodeProfilePath(name)),
                "no profile file is written before the wizard finishes");
    }

    @Test
    @DisplayName("no virtual profile is created while the profile name is undecided")
    void sessionVirtualProfileSkippedWithoutName() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext(); // no name yet
        step.onEnter(ctx);
        assertNull(ctx.getVirtualProfile(), "no name yet → no virtual session profile");
    }

    @Test
    @DisplayName("onExit pushes the edited ports into the virtual in-memory session profile")
    void onExitSyncsVirtualProfile() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_virtual_sync_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.onEnter(ctx);
        portField(step, "8125").setText("11111");
        portField(step, "8123").setText("11112");
        portField(step, "8126").setText("11113");
        step.onExit(ctx);
        assertNotNull(ctx.getVirtualProfile());
        java.util.Properties props = ctx.getVirtualProfile().getProperties();
        assertEquals("11111", props.getProperty(application.module.node.props.Props.API_PORT.getName()));
        assertEquals("11112", props.getProperty(application.module.node.props.Props.P2P_PORT.getName()));
        assertEquals("11113", props.getProperty(application.module.node.props.Props.API_WEBSOCKET_PORT.getName()));
        assertFalse(Files.exists(ConfigurationUtils.resolveNodeProfilePath(ctx.getName())),
                "the profile file still does not exist after the step exits");
    }

    @Test
    @DisplayName("changing the API port from 8125 to 8126 updates the virtual profile immediately")
    void liveApiPortEditMirrorsImmediately() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_live_port_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);
        assertNotNull(ctx.getVirtualProfile(), "the virtual session profile exists after entry");

        portField(step, "8125").setText("8126");
        assertEquals("8126", step.getApiPortText(), "step 4 shows the edited API port");
        assertEquals("8126", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.API_PORT.getName()),
                "the exact edit is committed immediately, before opening Advanced Settings");

        ctx.setApiPort(8125);
        ctx.getVirtualProfile().getProperties().setProperty(
                application.module.node.props.Props.API_PORT.getName(), "8125");
        step.syncStepFourValuesToVirtualProfile(ctx);
        assertEquals(8126, ctx.getApiPort(),
                "opening Advanced Settings treats the currently visible step 4 value as authoritative");
        assertEquals("8126", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.API_PORT.getName()),
                "an outdated profile snapshot cannot replace the visible step 4 value");
    }

    @Test
    @DisplayName("the API port uses a directly editable text field")
    void apiPortUsesPlainTextField() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_wrapped_port_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);

        JTextField apiPort = portField(step, "8125");
        apiPort.setText("8126");
        assertEquals("8126", step.getApiPortText(),
                "the direct input exposes its typed value");
        assertEquals("8126", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.API_PORT.getName()),
                "the edit is immediately mirrored into the virtual profile");
    }

    @Test
    @DisplayName("all three port edits are mirrored into the virtual profile immediately")
    void livePortEditMirrorsToVirtualProfile() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_live_all_ports_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);
        assertNotNull(ctx.getVirtualProfile(), "the virtual session profile exists after entry");

        portField(step, "8125").setText("11111");
        portField(step, "8123").setText("11112");
        portField(step, "8126").setText("11113");
        java.util.Properties props = ctx.getVirtualProfile().getProperties();
        assertEquals("11111",
                props.getProperty(application.module.node.props.Props.API_PORT.getName()),
                "the live API port edit is mirrored into the virtual session profile immediately");
        assertEquals("11112",
                props.getProperty(application.module.node.props.Props.P2P_PORT.getName()),
                "the live P2P port edit is mirrored into the virtual session profile immediately");
        assertEquals("11113",
                props.getProperty(application.module.node.props.Props.API_WEBSOCKET_PORT.getName()),
                "the live WebSocket port edit is mirrored into the virtual session profile immediately");
        assertEquals(11111, ctx.getApiPort(), "the context carries the live API port");
        assertEquals(11112, ctx.getP2pPort(), "the context carries the live P2P port");
        assertEquals(11113, ctx.getWsPort(), "the context carries the live WebSocket port");
    }

    @Test
    @DisplayName("advanced port values are shown on read-back even when invalid and are rejected at validation")
    void advancedInvalidPortIsNotSilentlyReplacedByPreviousValue() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_invalid_advanced_port_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);

        String apiPortKey = application.module.node.props.Props.API_PORT.getName();
        ctx.getVirtualProfile().getProperties().setProperty(apiPortKey, "81256");
        step.readBackFromVirtualProfile(ctx);

        JTextField apiPort = portField(step, "81256");
        assertEquals("81256", step.getApiPortText(),
                "read-back must not disguise a saved invalid value as the previous port");
        assertTrue(step.validate(ctx).contains("between 1 and 65535"),
                "81256 is outside the valid TCP port range and must block wizard navigation");
        assertEquals(FlatClientProperties.OUTLINE_ERROR,
                apiPort.getClientProperty(FlatClientProperties.OUTLINE),
                "an invalid port gets the standard error outline");
        JLabel rangeError = findByClass(step.getPanel(), JLabel.class,
                label -> label.isVisible() && label.getText().contains("between 1 and 65535"));
        assertEquals(application.utils.gui.GuiColors.getContrastRed(), rangeError.getForeground(),
                "the inline range error is shown in the shared error color");
        JButton resetButton = findByClass(step.getPanel(), JButton.class,
                button -> button.isVisible()
                        && "Set to default".equals(button.getToolTipText()));

        apiPort.setText("not-a-number");
        assertTrue(step.validate(ctx).contains("whole number from 1 to 65535"),
                "non-numeric values explain the accepted format and range");
        apiPort.setText("0");
        assertTrue(step.validate(ctx).contains("between 1 and 65535"),
                "values below the TCP port range are rejected with the valid range");

        resetButton.doClick();
        assertEquals("8125", step.getApiPortText(), "reset restores the API port default");
        assertNull(step.validate(ctx), "reset clears the validation error");
        assertFalse(rangeError.isVisible(), "the inline error disappears after reset");
        assertEquals("8125", ctx.getVirtualProfile().getProperties().getProperty(apiPortKey),
                "reset immediately synchronizes the default to the virtual profile");
        assertEquals(8125, ctx.getApiPort());

        ctx.getVirtualProfile().getProperties().setProperty(apiPortKey, "8124");
        step.readBackFromVirtualProfile(ctx);
        assertEquals("8124", step.getApiPortText());
        assertEquals(8124, ctx.getApiPort(),
                "a valid advanced port is synchronized into the context and the visible field");
    }

    @Test
    @DisplayName("ports stay synchronized through real Swing document events and every help icon shares one column")
    void livePortEditAndHelpColumnStayConsistent() throws Exception {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_edt_port_sync_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            portField(step, "8125").setText("8124");
            portField(step, "8123").setText("8122");
            portField(step, "8126").setText("8127");
            step.syncStepFourValuesToVirtualProfile(ctx);
            step.readBackFromVirtualProfile(ctx);
        });

        assertEquals("8124", step.getApiPortText());
        assertEquals("8122", step.getP2pPortText());
        assertEquals("8127", step.getWsPortText());
        assertEquals("8124", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.API_PORT.getName()));
        assertEquals("8122", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.P2P_PORT.getName()));
        assertEquals("8127", ctx.getVirtualProfile().getProperties().getProperty(
                application.module.node.props.Props.API_WEBSOCKET_PORT.getName()));

        String additionalKey = application.module.node.props.Props.NODE_SHUTDOWN_TIMEOUT.getName();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ctx.getVirtualProfile().getProperties().setProperty(additionalKey, "240");
            step.readBackFromVirtualProfile(ctx);
        });

        java.lang.reflect.Field rowsField = NodeConfigurationStep.class.getDeclaredField("configurationRows");
        rowsField.setAccessible(true);
        JPanel rows = (JPanel) rowsField.get(step);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            rows.setSize(700, 500);
            layoutRecursively(rows);
        });
        List<HelpButton> helpButtons = java.util.Arrays.stream(rows.getComponents())
                .filter(HelpButton.class::isInstance)
                .map(HelpButton.class::cast)
                .toList();
        assertEquals(5, helpButtons.size(),
                "Database URL, three ports, and the additional setting each have one help button");
        int helpX = helpButtons.get(0).getX();
        assertTrue(helpButtons.stream().allMatch(button -> button.getX() == helpX),
                "all property-information icons use the same grid column");

        java.lang.reflect.Field resetButtonsField = NodeConfigurationStep.class
                .getDeclaredField("portResetButtons");
        resetButtonsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, JButton> resetButtons =
                (java.util.Map<String, JButton>) resetButtonsField.get(step);
        JButton apiReset = resetButtons.get(application.module.node.props.Props.API_PORT.getName());
        JTextField apiPort = portField(step, "8124");
        javax.swing.SwingUtilities.invokeAndWait(() -> apiPort.setText("81256"));
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            rows.setSize(700, 500);
            layoutRecursively(rows);
        });
        java.awt.Point fieldPosition = javax.swing.SwingUtilities.convertPoint(apiPort, 0, 0, rows);
        int fieldCenterY = fieldPosition.y + apiPort.getHeight() / 2;
        java.awt.Point resetPosition = javax.swing.SwingUtilities.convertPoint(apiReset, 0, 0, rows);
        HelpButton apiHelp = helpButtons.stream()
                .filter(button -> Math.abs(button.getY() + button.getHeight() / 2 - fieldCenterY) <= 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("API port help icon is not centered with the input row; "
                        + "field=" + fieldPosition.y + "/" + apiPort.getHeight()
                        + ", reset=" + resetPosition.y + "/" + apiReset.getHeight()
                        + ", help centers="
                        + helpButtons.stream()
                                .map(button -> button.getY() + "/" + button.getHeight())
                                .toList()));
        assertTrue(apiReset.isVisible(),
                "the invalid port shows the restore action in the same row as its input");
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    @Test
    @DisplayName("the last edit is the current one: a live DB.Url keystroke mirrors into the virtual session profile immediately")
    void liveDbUrlEditMirrorsToVirtualProfile() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_live_dburl_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);
        assertNotNull(ctx.getVirtualProfile(), "the virtual session profile exists after entry");

        dbUrlField(step).setText("jdbc:sqlite:file:./database/SQLite/g4_live_edit/g4_live_edit.sqlite.db");
        java.util.Properties props = ctx.getVirtualProfile().getProperties();
        assertEquals("jdbc:sqlite:file:./database/SQLite/g4_live_edit/g4_live_edit.sqlite.db",
                props.getProperty(application.module.node.props.Props.DB_URL.getName()),
                "the live DB.Url edit is mirrored into the virtual session profile");
    }

    @Test
    @DisplayName("read-back tolerates the profile's minimal-override form: a missing port key means the property default (no NPE)")
    void readBackToleratesMinimalOverrideProfile() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_readback_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);
        application.module.node.profile.NodeProfile virtual = ctx.getVirtualProfile();
        assertNotNull(virtual, "the virtual session profile exists after entry");

        // The full editor's save persists the MINIMAL overrides (a key at its
        // property default is absent) — simulate a saved session profile of
        // which only a non-default P2P port and the DB.Url are left.
        java.util.Properties minimal = new java.util.Properties();
        minimal.setProperty(application.module.node.props.Props.DB_URL.getName(),
                virtual.getProperties().getProperty(application.module.node.props.Props.DB_URL.getName()));
        minimal.setProperty(application.module.node.props.Props.P2P_PORT.getName(), "11112");
        virtual.setProperties(minimal);

        // Must not throw (the former tryParsePort(null) NPE) and must resolve
        // the missing keys to the property's defaults:
        step.readBackFromVirtualProfile(ctx);
        assertEquals(11112, ctx.getP2pPort(), "the saved override is read back");
        assertEquals(application.module.node.props.Props.API_PORT.getDefaultValue(), ctx.getApiPort(),
                "a missing API port key means the property default");
        assertEquals(application.module.node.props.Props.API_WEBSOCKET_PORT.getDefaultValue(), ctx.getWsPort(),
                "a missing WebSocket port key means the property default");
    }

    @Test
    @DisplayName("a saved advanced API port is reflected in the step 4 field")
    void readBackUpdatesApiPortField() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_api_port_readback_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);

        String apiPortKey = application.module.node.props.Props.API_PORT.getName();
        ctx.getVirtualProfile().getProperties().setProperty(apiPortKey, "8124");
        step.readBackFromVirtualProfile(ctx);

        assertEquals(8124, ctx.getApiPort(), "the saved advanced value updates the wizard context");
        assertEquals("8124", step.getApiPortText(), "the visible step 4 API port field is refreshed");
    }

    @Test
    @DisplayName("a saved advanced database URL is reflected exactly in the step 4 field")
    void readBackUpdatesDatabaseUrlField() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_db_url_readback_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        ctx.setSelectedDbProfileName("old-profile");
        step.bindContext(ctx);
        step.onEnter(ctx);

        String advancedUrl = "jdbc:sqlite:file:./database/SQLite/advanced/advanced.sqlite.db";
        ctx.getVirtualProfile().getProperties().setProperty(
                application.module.node.props.Props.DB_URL.getName(), advancedUrl);
        step.readBackFromVirtualProfile(ctx);

        assertEquals(advancedUrl, dbUrlField(step).getText(),
                "step 4 shows the exact URL saved through Advanced Settings");
    }

    @Test
    @DisplayName("saved advanced overrides appear as editable step fields and sync back to the virtual profile")
    void advancedOverridesAppearAsEditableFields() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_advanced_overrides_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);

        String propertyKey = application.module.node.props.Props.NODE_SHUTDOWN_TIMEOUT.getName();
        ctx.getVirtualProfile().getProperties().setProperty(propertyKey, "240");
        step.readBackFromVirtualProfile(ctx);

        JTextField additionalField = step.getAdditionalSettingField(propertyKey);
        assertNotNull(additionalField, "a saved advanced override is shown on step 4");
        assertTrue(additionalField.isEditable(), "the additional setting can be edited directly");
        assertEquals("240", additionalField.getText());

        additionalField.setText("300");
        assertEquals("300", ctx.getVirtualProfile().getProperties().getProperty(propertyKey),
                "step 4 edits immediately update the virtual profile");

        ctx.getVirtualProfile().getProperties().remove(propertyKey);
        step.readBackFromVirtualProfile(ctx);
        assertNull(step.getAdditionalSettingField(propertyKey),
                "a setting reset/removed in advanced configuration disappears from step 4");
    }

    @Test
    @DisplayName("additional settings preserve boolean and fixed-select input types")
    void additionalSettingsPreservePropertyInputTypes() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_advanced_typed_overrides_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        step.onEnter(ctx);

        String booleanKey = application.module.node.props.Props.API_WEBSOCKET_ENABLE.getName();
        String selectKey = application.module.node.props.Props.API_DOC_MODE.getName();
        String textKey = application.module.node.props.Props.NODE_SHUTDOWN_TIMEOUT.getName();
        ctx.getVirtualProfile().getProperties().setProperty(booleanKey, "false");
        ctx.getVirtualProfile().getProperties().setProperty(selectKey, "legacy");
        ctx.getVirtualProfile().getProperties().setProperty(textKey, "240");
        step.readBackFromVirtualProfile(ctx);

        assertEquals(7, count(step, HelpButton.class),
                "each database, port and additional-setting row has an info button");
        assertTrue(step.getAdditionalSettingComponent(booleanKey) instanceof JCheckBox,
                "boolean settings use a checkbox");
        JCheckBox checkbox = (JCheckBox) step.getAdditionalSettingComponent(booleanKey);
        assertFalse(checkbox.isSelected());
        checkbox.doClick();
        assertNull(ctx.getVirtualProfile().getProperties().getProperty(booleanKey),
                "selecting the default removes the minimal-override property");
        checkbox.doClick();
        assertEquals("false", ctx.getVirtualProfile().getProperties().getProperty(booleanKey),
                "checkbox edits are mirrored to the virtual profile");

        assertTrue(step.getAdditionalSettingComponent(selectKey) instanceof JComboBox<?>);
        JComboBox<?> select = (JComboBox<?>) step.getAdditionalSettingComponent(selectKey);
        assertFalse(select.isEditable(), "a recognized fixed-option value uses a non-editable select");
        select.setSelectedItem("off");
        assertEquals("off", ctx.getVirtualProfile().getProperties().getProperty(selectKey),
                "select edits are mirrored to the virtual profile");

        assertTrue(step.getAdditionalSettingComponent(textKey) instanceof JTextField,
                "a free-form value without predefined options uses a plain editable field");
    }

    @Test
    @DisplayName("additional secret overrides are masked")
    void additionalSecretOverridesAreMasked() {
        NodeConfigurationStep step = new NodeConfigurationStep();
        WizardContext ctx = new WizardContext();
        ctx.setName("g4_advanced_secret_" + System.nanoTime());
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.onEnter(ctx);

        String key = application.module.node.props.Props.API_SSL_KEY_STORE_PASSWORD.getName();
        ctx.getVirtualProfile().getProperties().setProperty(key, "not-shown-plain-text");
        step.readBackFromVirtualProfile(ctx);

        assertTrue(step.getAdditionalSettingField(key) instanceof javax.swing.JPasswordField,
                "secret-valued settings use a masked editor");
    }

    // ── helpers: navigate the Swing tree by class + predicate ─────────────

    /** The port input whose current value is {@code value}. */
    private static JTextField portField(NodeConfigurationStep step, String value) {
        return findByClass(step.getPanel(), JTextField.class, field -> value.equals(field.getText().trim()));
    }

    private static int count(NodeConfigurationStep step, Class<?> type) {
        List<Component> children = new ArrayList<>();
        collectChildren(step.getPanel(), children);
        int n = 0;
        for (Component c : children) {
            if (type.isInstance(c)) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static <T extends JComponent> T findByClass(
            JComponent root, Class<T> type, Predicate<T> filter) {
        List<Component> children = new ArrayList<>();
        collectChildren(root, children);
        for (Component c : children) {
            if (type.isInstance(c) && filter.test((T) c)) {
                return (T) c;
            }
        }
        throw new IllegalStateException("component not found: " + type.getName());
    }

    private static void collectChildren(JComponent root, List<Component> out) {
        for (Component c : root.getComponents()) {
            if (c instanceof JComponent) {
                out.add(c);
                collectChildren((JComponent) c, out);
            }
        }
    }

    /** The editable "Database URL" field (the DB.Url of the node being created). */
    private static JTextField dbUrlField(NodeConfigurationStep step) {
        List<Component> all = new ArrayList<>();
        collectChildren(step.getPanel(), all);
        for (Component c : all) {
            if (c instanceof JLabel lbl
                    && String.valueOf(lbl.getText()).startsWith("Database URL")
                    && lbl.getParent() instanceof JPanel row) {
                Component[] siblings = row.getComponents();
                for (int i = 0; i < siblings.length - 1; i++) {
                    if (siblings[i] == lbl) {
                        if (siblings[i + 1] instanceof JTextField field) {
                            return field;
                        }
                        if (siblings[i + 1] instanceof JPanel inputPanel) {
                            List<Component> inputs = new ArrayList<>();
                            collectChildren(inputPanel, inputs);
                            for (Component input : inputs) {
                                if (input instanceof JTextField field) {
                                    return field;
                                }
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("DB.Url field not found");
    }
}