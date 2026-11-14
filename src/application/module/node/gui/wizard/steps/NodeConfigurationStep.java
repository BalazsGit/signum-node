package application.module.node.gui.wizard.steps;

import com.formdev.flatlaf.FlatClientProperties;
import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.gui.configuration.NodeConfigurationPanel;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.gui.wizard.WizardFinish;
import application.module.node.gui.wizard.WizardTexts;
import application.module.node.profile.Network;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.SqliteDatabasePaths;
import application.module.node.props.Prop;
import application.module.node.props.Props;
import application.module.node.Signum;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import application.utils.gui.HelpButton;
import application.utils.gui.IconFontCache;
import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Wizard step — the <b>node profile configuration</b>: the ports, the editable
 * database URL, plus the
 * <b>advanced settings</b>.
 * <p>
 * The profile NAME and the NETWORK (mainnet / testnet — mocknet is not
 * offered for now) live in the first step ({@link ProfileNameStep}). The three
 * port inputs are plain editable fields pre-filled with the property's default
 * port and use the profile editor's property-information help — there is no
 * "use default ports" toggle: the fields always hold a value and that value
 * is what the profile gets.
 * </p>
 * <p>
 * The advanced settings are an icon that opens the <b>full node profile
 * configuration</b> (all category tabs + the live search) in its own modal
 * dialog with a Clone Profile / Save / Cancel button row — the same
 * profile-discovery / copy action the node console offers. The editor works on
 * the wizard's <b>virtual in-memory profile</b>
 * profile {@link WizardContext#getVirtualProfile()}) — the profile FILE is created
 * only by the wizard's finish step. The wizard's port and DB.Url fields mirror
 * their edits into the virtual profile <b>live</b> (keystroke by keystroke), the
 * advanced editor loads the virtual profile on open and its save merges back into
 * it, and the virtual profile is read back into the wizard's fields on close,
 * including dynamic inputs for additional saved overrides — the virtual profile is
 * the authoritative source of the profile's properties (the finish step persists
 * it as the file).
 * </p>
 */
public class NodeConfigurationStep implements WizardStep {

    private static final Map<String, String> PROPERTY_DEFAULTS = loadPropertyDefaults();
    private static final Map<String, Prop<?>> PROPERTIES_BY_NAME = loadPropertiesByName();
    private static final List<String> STEP_MANAGED_PROPERTIES = List.of(
            Props.DB_URL.getName(),
            Props.DB_USERNAME.getName(),
            Props.DB_PASSWORD.getName(),
            Props.API_PORT.getName(),
            Props.P2P_PORT.getName(),
            Props.API_WEBSOCKET_PORT.getName(),
            Props.NETWORK_PARAMETERS.getName());
    private static final int ROW_LABEL_WIDTH = 170;

    // The dialog stretches every step panel to the full available height —
    // NORTH keeps the content top-anchored (the same pattern as the other steps).
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel();
    // Port values have no predefined choice catalog, so they use plain
    // editable inputs rather than editable dropdowns.
    private final JTextField apiPortField = new JTextField(String.valueOf(Props.API_PORT.getDefaultValue()), 8);
    private final JTextField p2pPortField = new JTextField(String.valueOf(Props.P2P_PORT.getDefaultValue()), 8);
    private final JTextField wsPortField = new JTextField(
            String.valueOf(Props.API_WEBSOCKET_PORT.getDefaultValue()), 8);
    // The node's DB.Url — an editable field. It is pre-filled from the database
    // configured in the earlier steps; editing it changes the created profile's
    // database (the value lands in the profile's DB.Url on finish).
    private final JTextField dbUrlField = new JTextField(28);
    private final JLabel dbUrlValidationLabel = new JLabel();
    private final JPanel dbUrlInputPanel = new JPanel(new BorderLayout());
    private Color dbUrlNormalForeground;
    private boolean programmaticDbUrlChange = false;
    private final JPanel configurationRows = new JPanel(new GridBagLayout());
    private final Map<String, JComponent> additionalSettingFields = new HashMap<>();
    private final Map<String, JPanel> portInputPanels = new HashMap<>();
    private final Map<String, JLabel> portValidationLabels = new HashMap<>();
    private final Map<String, JButton> portResetButtons = new HashMap<>();
    private final Map<String, Color> portNormalForegrounds = new HashMap<>();
    private boolean refreshingAdditionalSettings = false;
    // The advanced settings: an icon that opens the full profile editor dialog.
    private final JButton advancedBtn = new JButton();
    private NodeConfigurationPanel advancedPanel; // (re)built on every open
    private JDialog advancedDialog;

    /**
     * The wizard's original values (the virtual in-memory profile as first
     * created), held stable across reopens of the advanced editor — the
     * "applied" baseline of its 3-state coloring (a saved value stays yellow
     * until it is set back to this original baseline). Captured once, on the
     * first open.
     */
    private Properties originalAppliedBaseline = null;
    /** The wizard-managed values pushed into the virtual in-memory profile at the last sync. */
    private final Map<String, String> lastPushedManaged = new HashMap<>();

    /** Live navigation context (bound by the controller; null in standalone use). */
    private WizardContext boundContext = null;
    private final List<Consumer<WizardContext>> changeListeners = new ArrayList<>();
    private boolean programmaticChange = false;

    public NodeConfigurationStep() {
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        panel.setOpaque(false);

        // The step title (bold 1.2× keyword style, selectable like every other
        // display text of the wizard) + a normal empty line + the description.
        JEditorPane title = WizardTexts.heading("Node Profile configuration");
        title.setAlignmentX(0f);
        content.add(title);
        JEditorPane blankLine = WizardTexts.info("");
        blankLine.setAlignmentX(0f);
        content.add(blankLine);
        JEditorPane info = WizardTexts.info(
                "This is the node profile being created — the network and the database were decided in the previous steps.<br>"
                        + "The ports are pre-filled with their defaults; change them only when you need different ones.<br>"
                        + "The gear icon opens the full profile editor (all categories, with search) for everything else.");
        info.setAlignmentX(0f);
        content.add(info);
        content.add(Box.createVerticalStrut(8));

        // The advanced settings: an icon that opens the full profile editor in
        // its own modal dialog (Clone Profile / Save / Cancel button row).
        advancedBtn.setMargin(new Insets(0, 0, 0, 0));
        advancedBtn.setBorderPainted(false);
        advancedBtn.setFocusPainted(false);
        advancedBtn.setContentAreaFilled(false);
        advancedBtn.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        advancedBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        advancedBtn.setIcon(IconFontCache.buildIcon(FontAwesome.COGS, GuiConstants.getButtonIconSize(),
                GuiColors.getButtonIcon()));
        advancedBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Open the full node profile configuration (all categories, with search). "
                        + "The database from the previous step is pre-filled; anything you set here becomes part of the created profile."));
        advancedBtn.setAlignmentX(0f);
        advancedBtn.addActionListener(e -> openAdvanced());

        configurationRows.setOpaque(false);
        configurationRows.setAlignmentX(0f);
        ConfigurationUtils.styleInputComponent(dbUrlField);
        dbUrlNormalForeground = dbUrlField.getForeground();
        dbUrlValidationLabel.setForeground(GuiColors.getContrastRed());
        dbUrlValidationLabel.setVisible(false);
        dbUrlField.getDocument().addDocumentListener(docListener(this::onDbUrlChanged));
        dbUrlInputPanel.setOpaque(false);
        dbUrlInputPanel.add(dbUrlField, BorderLayout.CENTER);
        dbUrlInputPanel.add(dbUrlValidationLabel, BorderLayout.SOUTH);
        content.add(Box.createVerticalStrut(8));
        content.add(configurationRows);

        // Top-anchored: the dialog stretches the step's root panel to the full
        // available height — NORTH keeps the content at its preferred height
        // and leaves the extra space below.
        panel.add(content, BorderLayout.NORTH);

        // There are no predefined port choices. The direct text fields mirror
        // keystrokes live into the virtual in-memory profile and show inline
        // validation with a one-click reset when the value is invalid.
        portInputPanels.put(Props.API_PORT.getName(), createPortInput(apiPortField, Props.API_PORT,
                value -> {
                    if (boundContext != null) {
                        boundContext.setApiPort(value);
                    }
                }));
        portInputPanels.put(Props.P2P_PORT.getName(), createPortInput(p2pPortField, Props.P2P_PORT,
                value -> {
                    if (boundContext != null) {
                        boundContext.setP2pPort(value);
                    }
                }));
        portInputPanels.put(Props.API_WEBSOCKET_PORT.getName(), createPortInput(wsPortField,
                Props.API_WEBSOCKET_PORT, value -> {
                    if (boundContext != null) {
                        boundContext.setWsPort(value);
                    }
                }));
        refreshAdditionalSettings(new Properties());
    }

    private JPanel createPortInput(JTextField field, Prop<Integer> prop,
            Consumer<Integer> updateContext) {
        ConfigurationUtils.styleInputComponent(field);
        Color normalForeground = field.getForeground();
        portNormalForegrounds.put(prop.getName(), normalForeground);

        JLabel validationLabel = new JLabel();
        validationLabel.setForeground(GuiColors.getContrastRed());
        validationLabel.setVisible(false);
        portValidationLabels.put(prop.getName(), validationLabel);

        JButton resetButton = new JButton();
        resetButton.setToolTipText("Set to default");
        ConfigurationUtils.styleProfileIconButton(resetButton, FontAwesome.UNDO,
                GuiColors.getButtonIcon(), GuiConstants.getToolBarIconSize());
        resetButton.setVisible(false);
        resetButton.addActionListener(event -> field.setText(String.valueOf(prop.getDefaultValue())));
        portResetButtons.put(prop.getName(), resetButton);

        JPanel fieldRow = new JPanel(new BorderLayout(ConfigurationUtils.INPUT_ACTION_GAP, 0));
        fieldRow.setOpaque(false);
        fieldRow.add(field, BorderLayout.CENTER);
        fieldRow.add(resetButton, BorderLayout.EAST);

        JPanel inputPanel = new JPanel(new BorderLayout(0, 2));
        inputPanel.setOpaque(false);
        inputPanel.add(fieldRow, BorderLayout.NORTH);
        inputPanel.add(validationLabel, BorderLayout.SOUTH);
        field.getDocument().addDocumentListener(docListener(() -> {
            updatePortValidation(field, prop);
            firePortFieldChange(field, prop, updateContext);
        }));
        updatePortValidation(field, prop);
        return inputPanel;
    }

    private void updatePortValidation(JTextField field, Prop<Integer> prop) {
        String error = portValidationError(prop.getName(), fieldText(field));
        boolean invalid = error != null;
        JLabel validationLabel = portValidationLabels.get(prop.getName());
        validationLabel.setText(invalid ? error : "");
        validationLabel.setVisible(invalid);
        field.setForeground(invalid ? GuiColors.getContrastRed() : portNormalForegrounds.get(prop.getName()));
        field.setToolTipText(error);
        field.putClientProperty(FlatClientProperties.OUTLINE,
                invalid ? FlatClientProperties.OUTLINE_ERROR : null);
        JButton resetButton = portResetButtons.get(prop.getName());
        resetButton.setVisible(invalid);
        if (validationLabel.getParent() != null) {
            validationLabel.getParent().revalidate();
        }
    }

    private static String portValidationError(String what, String text) {
        return ConfigurationUtils.portValidationError(what, text);
    }

    private static Map<String, String> loadPropertyDefaults() {
        Map<String, String> defaults = new TreeMap<>();
        for (Field field : Props.class.getFields()) {
            if (!Prop.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Prop<?> prop = (Prop<?>) field.get(null);
                Object value = prop.getDefaultValue();
                if (value != null) {
                    defaults.put(prop.getName(), String.valueOf(value));
                }
            } catch (IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        return Map.copyOf(defaults);
    }

    private static Map<String, Prop<?>> loadPropertiesByName() {
        Map<String, Prop<?>> props = new HashMap<>();
        for (Field field : Props.class.getFields()) {
            if (!Prop.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Prop<?> prop = (Prop<?>) field.get(null);
                props.put(prop.getName(), prop);
            } catch (IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        return Map.copyOf(props);
    }

    /**
     * Shows every saved profile override that does not already have a dedicated
     * wizard input. These fields are a view over the virtual profile, not a second
     * source of truth.
     */
    private void refreshAdditionalSettings(Properties properties) {
        refreshingAdditionalSettings = true;
        try {
            configurationRows.removeAll();
            additionalSettingFields.clear();
            int row = addSectionHeading("Database", 0);
            row = addConfigurationRow("Database URL:", dbUrlInputPanel, Props.DB_URL.getName(),
                    Props.DB_URL.getDefaultValue(), "Database URL", row);
            row = addSectionHeading("Ports", row);
            row = addConfigurationRow("API port", portInputPanels.get(Props.API_PORT.getName()),
                    Props.API_PORT.getName(),
                    Props.API_PORT.getDefaultValue(), "API port", row);
            row = addConfigurationRow("P2P port", portInputPanels.get(Props.P2P_PORT.getName()),
                    Props.P2P_PORT.getName(),
                    Props.P2P_PORT.getDefaultValue(), "P2P port", row);
            row = addConfigurationRow("WebSocket port",
                    portInputPanels.get(Props.API_WEBSOCKET_PORT.getName()), Props.API_WEBSOCKET_PORT.getName(),
                    Props.API_WEBSOCKET_PORT.getDefaultValue(), "WebSocket port", row);

            List<String> keys = properties.stringPropertyNames().stream()
                    .filter(key -> !STEP_MANAGED_PROPERTIES.contains(key))
                    .sorted()
                    .toList();
            if (!keys.isEmpty()) {
                row = addSectionHeading("Additional settings", row);
            }
            for (String key : keys) {
                JComponent input = createAdditionalSettingInput(key, properties.getProperty(key, ""));
                additionalSettingFields.put(key, input);
                Prop<?> prop = PROPERTIES_BY_NAME.get(key);
                Object defaultValue = prop == null ? null : prop.getDefaultValue();
                row = addConfigurationRow(key + ":", input, key, defaultValue, key, row);
            }

            GridBagConstraints advanced = new GridBagConstraints();
            advanced.gridx = 0;
            advanced.gridy = row;
            advanced.gridwidth = 3;
            advanced.anchor = GridBagConstraints.WEST;
            advanced.insets = new Insets(12, 0, 2, 0);
            configurationRows.add(advancedBtn, advanced);
            configurationRows.revalidate();
            configurationRows.repaint();
            content.revalidate();
        } finally {
            refreshingAdditionalSettings = false;
        }
    }

    private int addSectionHeading(String title, int row) {
        JEditorPane heading = WizardTexts.heading(title);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(row == 0 ? 0 : 8, 0, 3, 0);
        configurationRows.add(heading, c);
        return row + 1;
    }

    private int addConfigurationRow(String labelText, JComponent input, String propertyName,
            Object defaultValue, String helpLabel, int row) {
        JLabel label = new JLabel(labelText);
        label.setToolTipText(propertyName);
        label.setPreferredSize(new Dimension(ROW_LABEL_WIDTH, 24));
        GuiFontManager.applyDefaultFont(label);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(2, 0, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        configurationRows.add(label, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        configurationRows.add(input, c);

        HelpButton help = new HelpButton();
        help.setToolTipText("Click for more information about " + helpLabel.toLowerCase(java.util.Locale.ROOT));
        help.addActionListener(e -> NodeConfigurationPanel.showPropertyInfo(
                panel, propertyName, defaultValue, helpLabel));
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTHWEST;
        Component alignedInput = input;
        if (input.getLayout() instanceof BorderLayout borderLayout) {
            Component fieldRow = borderLayout.getLayoutComponent(BorderLayout.NORTH);
            if (fieldRow == null) {
                fieldRow = borderLayout.getLayoutComponent(BorderLayout.CENTER);
            }
            if (fieldRow != null) {
                alignedInput = fieldRow;
            }
        }
        int verticalOffset = Math.max(0,
                (alignedInput.getPreferredSize().height - help.getPreferredSize().height) / 2);
        c.insets = new Insets(2 + verticalOffset, ConfigurationUtils.INPUT_ACTION_GAP, 2, 0);
        configurationRows.add(help, c);
        return row + 1;
    }

    private static boolean isSensitiveProperty(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("passphrase")
                || normalized.contains("secret")
                || normalized.contains("adminkey")
                || normalized.contains("token")
                || normalized.contains("privatekey");
    }

    private JComponent createAdditionalSettingInput(String key, String value) {
        Prop<?> prop = PROPERTIES_BY_NAME.get(key);
        if (prop != null && prop.getDefaultValue() instanceof Boolean) {
            JCheckBox checkbox = new JCheckBox();
            checkbox.setSelected("true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value)
                    || "1".equals(value)
                    || "on".equalsIgnoreCase(value));
            checkbox.addActionListener(e -> onAdditionalSettingChanged(key, String.valueOf(checkbox.isSelected())));
            return checkbox;
        }
        if (isSensitiveProperty(key)) {
            JPasswordField field = new JPasswordField(24);
            ConfigurationUtils.styleInputComponent(field);
            field.setText(ConfigurationUtils.propertyValueForEditing(value));
            field.getDocument().addDocumentListener(docListener(
                    () -> onAdditionalSettingChanged(key,
                            ConfigurationUtils.propertyValueFromEditing(new String(field.getPassword())))));
            return field;
        }

        String[] options = additionalSettingOptions(key);
        if (options == null) {
            JTextField field = new JTextField(24);
            ConfigurationUtils.styleInputComponent(field);
            field.setText(ConfigurationUtils.propertyValueForEditing(value));
            field.getDocument().addDocumentListener(docListener(
                    () -> onAdditionalSettingChanged(key,
                            ConfigurationUtils.propertyValueFromEditing(field.getText()))));
            return field;
        }

        String[] choices = options;
        if (!java.util.Arrays.asList(options).contains(value)) {
            choices = java.util.Arrays.copyOf(options, options.length + 1);
            choices[options.length] = value;
        }
        JComboBox<String> combo = new JComboBox<>(choices);
        combo.setEditable(false);
        ConfigurationUtils.fixComponentSize(combo);
        combo.setSelectedItem(value);
        combo.addActionListener(e -> {
            onAdditionalSettingChanged(key, String.valueOf(combo.getSelectedItem()));
        });
        return combo;
    }

    private static String[] additionalSettingOptions(String key) {
        if (Props.API_DOC_MODE.getName().equals(key)) {
            return new String[]{"modern", "legacy", "off"};
        }
        if (Props.DB_ARCHIVAL_MODE.getName().equals(key)) {
            return new String[]{"ARCHIVE", "TRIM", "PRUNE"};
        }
        if (Props.DB_SQLITE_JOURNAL_MODE.getName().equals(key)) {
            return new String[]{"WAL", "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "OFF"};
        }
        if (Props.DB_SQLITE_SYNCHRONOUS.getName().equals(key)) {
            return new String[]{"NORMAL", "FULL", "OFF"};
        }
        return null;
    }

    private void onAdditionalSettingChanged(String key, String value) {
        if (refreshingAdditionalSettings || boundContext == null || boundContext.getVirtualProfile() == null) {
            return;
        }
        String defaultValue = PROPERTY_DEFAULTS.get(key);
        Properties properties = boundContext.getVirtualProfile().getProperties();
        if (Objects.equals(value, defaultValue) || (defaultValue == null && value.isEmpty())) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    JTextField getAdditionalSettingField(String key) {
        JComponent input = additionalSettingFields.get(key);
        if (input instanceof JTextField field) {
            return field;
        }
        if (input instanceof JComboBox<?> combo && combo.isEditable()) {
            return ConfigurationUtils.findEditorTextField(combo.getEditor().getEditorComponent());
        }
        return null;
    }

    JComponent getAdditionalSettingComponent(String key) {
        return additionalSettingFields.get(key);
    }

    /** The current text of a port input. */
    private static String fieldText(JTextField field) {
        return field.getText().trim();
    }

    /** A document listener collapsing the three events into one callback. */
    private static DocumentListener docListener(Runnable onTextChange) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onTextChange.run(); }
            @Override public void removeUpdate(DocumentEvent e) { onTextChange.run(); }
            @Override public void changedUpdate(DocumentEvent e) { onTextChange.run(); }
        };
    }

    // ── Accessors (also used by tests) ────────────────────────────────────

    public String getApiPortText() {
        return fieldText(apiPortField);
    }

    public String getP2pPortText() {
        return fieldText(p2pPortField);
    }

    public String getWsPortText() {
        return fieldText(wsPortField);
    }

    private int parsePort(String what, String text) {
        String error = portValidationError(what, text);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return Integer.parseInt(text.trim());
    }

    /** Defensive parse (live mirroring never throws for transient states). */
    private static Integer tryParsePort(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int p = Integer.parseInt(text.trim());
            return (p >= 1 && p <= 65535) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }


    // ── Advanced settings: the full profile editor ────────────────────────

    /**
     * Opens the full node profile configuration ({@link NodeConfigurationPanel}
     * — all category tabs + the live search, with the Copy Configuration icon
     * on the editor's top toolbar row) in its own modal dialog with the
     * Save / Cancel button row, for the profile being created.
     * The editor works on the wizard's <b>virtual in-memory profile</b> (the
     * profile FILE is created only by the wizard's finish step) and is
     * (re)built on every open so it always loads the virtual profile's current
     * state. When the modal dialog closes (Save or Cancel), the virtual
     * profile — the authoritative source — is read back into the context and
     * the wizard's port fields.
     */
    private void openAdvanced() {
        WizardContext ctx = boundContext;
        String name = ctx == null ? null : ctx.getName();
        if (name == null || name.isBlank()) {
            return; // cannot happen: step 1 validated the name
        }
        try {
            syncStepFourValuesToVirtualProfile(ctx);
            // Capture the wizard's original values (the virtual profile as first
            // created) once — the stable "applied" baseline of the advanced
            // editor's 3-state coloring.
            if (originalAppliedBaseline == null) {
                originalAppliedBaseline = new Properties();
                originalAppliedBaseline.putAll(ctx.getVirtualProfile().getProperties());
            }
            if (advancedDialog != null) {
                advancedDialog.dispose();
            }
            advancedPanel = new NodeConfigurationPanel(
                    () -> {
                        // no-op: the node is not started until the wizard finishes
                    },
                    Signum.CONF_FOLDER, null, null, name,
                    ctx.getVirtualProfile(), originalAppliedBaseline);
            buildAdvancedDialog();
            advancedDialog.setVisible(true); // modal: blocks until Save / Cancel
            // The dialog is closed: the virtual (in-memory) session profile is
            // authoritative for the values it holds (wizard values + the user's
            // own full-editor edits).
            readBackFromVirtualProfile(ctx);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(NodeConfigurationStep.class)
                    .error("Could not open the advanced settings", e);
            JOptionPane.showMessageDialog(panel,
                    "Could not open the advanced settings:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * The modal advanced-settings dialog: the full editor (tabs + search) with
     * the button row at the bottom — Save (a plain button before Cancel that
     * writes the editor's values to the wizard profile) and Cancel (closes and
     * returns to the wizard). The copy action is NOT a text button here: it is
     * the Copy Configuration icon on the editor's top toolbar row (the same
     * icon as the node console tab's toolbar). The profile itself is only
     * created on Finish — until then the wizard just collects the data.
     */
    private void buildAdvancedDialog() {
        // The full editor dialog: modal over the wizard window (the same
        // JDialog + Dialog$ModalityType pattern every other profile dialog uses).
        Window parent = SwingUtilities.getWindowAncestor(panel);
        advancedDialog = new JDialog(parent, "Node Profile Configuration",
                JDialog.ModalityType.APPLICATION_MODAL);
        advancedDialog.setLayout(new BorderLayout());

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton("Save");
        saveBtn.setToolTipText("Save the editor's values to the wizard profile and return to the wizard.");
        saveBtn.addActionListener(e -> advancedPanel.saveOrDiscardThen(this::closeAdvancedDialog));
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setToolTipText("Close the editor and return to the wizard.");
        cancelBtn.addActionListener(e -> advancedPanel.checkUnsavedChangesAndProceed(this::closeAdvancedDialog, null));
        buttonBar.add(saveBtn);
        buttonBar.add(cancelBtn);
        advancedDialog.add(buttonBar, BorderLayout.SOUTH);

        Rectangle usableBounds = usableScreenBounds(parent);
        int width = Math.min(900, usableBounds.width);
        int height = Math.min(560, Math.max(160,
                usableBounds.height - buttonBar.getPreferredSize().height - 80));
        // The configuration panel already scrolls each category internally
        // and pins its profile-file path in its SOUTH region. Wrapping the
        // whole panel here nests scroll panes and makes both behaviors fail.
        advancedPanel.setPreferredSize(new Dimension(width, height));
        advancedDialog.add(advancedPanel, BorderLayout.CENTER);

        // The Copy Configuration action is the icon on the editor's top
        // toolbar row (the node console tab's toolbar counterpart) — the
        // bottom row keeps only Save / Cancel.
        advancedDialog.setMinimumSize(new Dimension(
                Math.min(780, usableBounds.width), Math.min(480, usableBounds.height)));
        advancedDialog.pack();
        advancedDialog.setLocationRelativeTo(panel);
        Dimension packedSize = advancedDialog.getSize();
        advancedDialog.setSize(Math.min(packedSize.width, usableBounds.width),
                Math.min(packedSize.height, usableBounds.height));
        int x = Math.max(usableBounds.x,
                Math.min(advancedDialog.getX(), usableBounds.x + usableBounds.width - advancedDialog.getWidth()));
        int y = Math.max(usableBounds.y,
                Math.min(advancedDialog.getY(), usableBounds.y + usableBounds.height - advancedDialog.getHeight()));
        advancedDialog.setLocation(x, y);
        advancedDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    }

    private static Rectangle usableScreenBounds(Window parent) {
        GraphicsConfiguration configuration = parent == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                        .getDefaultConfiguration()
                : parent.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    /** Hides the modal advanced-settings dialog (the wizard step resumes). */
    private void closeAdvancedDialog() {
        if (advancedDialog != null) {
            advancedDialog.setVisible(false);
        }
    }

    /**
     * Creates (on the first visit) or refreshes the wizard's <b>virtual
     * in-memory session profile</b>: the wizard-managed values (the database of
     * the previous step, the ports, the network) are pushed into it, while the
     * user's own full-editor edits are never overwritten (a managed value is
     * re-pushed only when it changed since the last push; a managed value the
     * wizard no longer manages is removed when the editor left it untouched).
     * Pure memory — no file I/O: the profile FILE is created only by the
     * wizard's finish step, so the session's own profile can never count as a
     * "taken" name while the wizard runs (and a cancel leaves nothing behind).
     */
    private void syncVirtualProfile(WizardContext ctx) {
        String name = ctx == null ? null : ctx.getName();
        if (name == null || name.isBlank()) {
            return; // the profile name is decided in the first step
        }
        Properties managed = WizardFinish.buildProperties(ctx);
        NodeProfile virtual = ctx.getVirtualProfile();
        if (virtual == null) {
            virtual = new NodeProfile.Builder(name).properties(new Properties(managed)).build();
            ctx.setVirtualProfile(virtual);
        }
        Properties current = virtual.getProperties();
        for (String key : managed.stringPropertyNames()) {
            if (!managed.getProperty(key).equals(lastPushedManaged.get(key))) {
                current.setProperty(key, managed.getProperty(key));
            }
        }
        for (String key : lastPushedManaged.keySet()) {
            if (!managed.containsKey(key) && lastPushedManaged.get(key).equals(current.getProperty(key))) {
                current.remove(key);
            }
        }
        pushSnapshot(managed);
    }

    /** Replaces the "last pushed" snapshot with the given (string) properties. */
    private void pushSnapshot(Properties managed) {
        lastPushedManaged.clear();
        for (String key : managed.stringPropertyNames()) {
            lastPushedManaged.put(key, managed.getProperty(key));
        }
    }

    /**
     * Commits the step's current port fields before another editor reads the
     * virtual profile. While this step is active, its visible port fields are
     * authoritative and must win over any older managed-property snapshot.
     */
    void syncStepFourValuesToVirtualProfile(WizardContext ctx) {
        collectPorts(ctx);
        syncVirtualProfile(ctx);
        NodeProfile virtual = ctx.getVirtualProfile();
        if (virtual == null) {
            return;
        }
        // The visible step fields win immediately before Advanced opens.
        // The reconciliation snapshot can equal these fields even when an
        // Advanced edit has since changed the shared virtual profile.
        Properties properties = virtual.getProperties();
        syncPortProperty(properties, Props.API_PORT, getApiPortText());
        syncPortProperty(properties, Props.P2P_PORT, getP2pPortText());
        syncPortProperty(properties, Props.API_WEBSOCKET_PORT, getWsPortText());
        pushSnapshot(WizardFinish.buildProperties(ctx));
    }

    private static void syncPortProperty(Properties properties, Prop<Integer> prop, String text) {
        Integer port = tryParsePort(text);
        if (port != null) {
            properties.setProperty(prop.getName(), String.valueOf(port));
        }
    }

    /**
     * Reads the (authoritative) virtual in-memory session profile back into the
     * context and visible fields: the database (engine + URL + connection), the
     * network, explicit port values and any additional saved property overrides.
     * <p>
     * The profile holds <b>minimal overrides</b> (the profile-file convention:
     * a key whose value equals the property's default is absent) — a missing
     * port key therefore falls back to the property's default, never to "no
     * value". Package-private for the unit tests (the dialog itself is not
     * driven headlessly).
     * </p>
     */
    void readBackFromVirtualProfile(WizardContext ctx) {
        NodeProfile profile = ctx.getVirtualProfile();
        if (profile == null) {
            return;
        }
        Properties props = profile.getProperties();
        String dbUrl = props.getProperty(Props.DB_URL.getName());
        if (dbUrl != null && !dbUrl.isBlank()) {
            // The file's DB.Url is the source of truth — resolve it (engine + the
            // connection) the same way the editable field's value is (the SSOT).
            applyDbUrlToContext(ctx, dbUrl);
            if (!dbUrl.startsWith("jdbc:sqlite:")) {
                ctx.setDbUser(props.getProperty(Props.DB_USERNAME.getName()));
                ctx.setDbPassword(props.getProperty(Props.DB_PASSWORD.getName()));
            }
        }
        if (dbUrl != null) {
            setDbUrlField(dbUrl);
        } else {
            updateDbUrlField(ctx);
        }
        ctx.setNetwork(Network.fromNetworkParameter(props.getProperty(Props.NETWORK_PARAMETERS.getName())));
        // Each port independently — the former all-three-present gate dropped
        // the whole read-back (and the step 4 refresh) whenever a save left
        // even one port at its default (its key then absent from the profile).
        Integer apiPort = tryParsePort(props.getProperty(Props.API_PORT.getName(),
                String.valueOf(Props.API_PORT.getDefaultValue())));
        if (apiPort != null) {
            ctx.setApiPort(apiPort);
        }
        Integer p2pPort = tryParsePort(props.getProperty(Props.P2P_PORT.getName(),
                String.valueOf(Props.P2P_PORT.getDefaultValue())));
        if (p2pPort != null) {
            ctx.setP2pPort(p2pPort);
        }
        Integer wsPort = tryParsePort(props.getProperty(Props.API_WEBSOCKET_PORT.getName(),
                String.valueOf(Props.API_WEBSOCKET_PORT.getDefaultValue())));
        if (wsPort != null) {
            ctx.setWsPort(wsPort);
        }
        syncFieldsFromContext(ctx);
        syncPortFieldsFromProfile(ctx, props);
        refreshAdditionalSettings(props);
    }

    private void syncPortFieldsFromProfile(WizardContext ctx, Properties properties) {
        programmaticChange = true;
        try {
            apiPortField.setText(portValue(properties, Props.API_PORT, ctx.getApiPort()));
            p2pPortField.setText(portValue(properties, Props.P2P_PORT, ctx.getP2pPort()));
            wsPortField.setText(portValue(properties, Props.API_WEBSOCKET_PORT, ctx.getWsPort()));
        } finally {
            programmaticChange = false;
        }
        updatePortValidation(apiPortField, Props.API_PORT);
        updatePortValidation(p2pPortField, Props.P2P_PORT);
        updatePortValidation(wsPortField, Props.API_WEBSOCKET_PORT);
    }

    private static String portValue(Properties properties, Prop<Integer> prop, Integer contextValue) {
        int fallback = contextValue == null ? prop.getDefaultValue() : contextValue;
        return properties.getProperty(prop.getName(), String.valueOf(fallback));
    }

    /**
     * Mirrors the port fields' current values into the context (defensive
     * parse — a transient invalid value is kept out, {@code validate} reports
     * it at navigation time). The suggested name no longer includes the ports.
     */
    private void collectPorts(WizardContext ctx) {
        Integer api = tryParsePort(getApiPortText());
        if (api != null) {
            ctx.setApiPort(api);
        }
        Integer p2p = tryParsePort(getP2pPortText());
        if (p2p != null) {
            ctx.setP2pPort(p2p);
        }
        Integer ws = tryParsePort(getWsPortText());
        if (ws != null) {
            ctx.setWsPort(ws);
        }
    }

    /**
     * Mirrors the live port edits into the shared context and notifies the
     * controller (it re-resolves the suggested profile name — network and
     * engine only, the ports no longer rename the profile — and the wizard's
     * title bar follows).
     */
    private void firePortFieldChange(JTextField field, Prop<Integer> prop,
            Consumer<Integer> updateContext) {
        WizardContext context = boundContext;
        if (programmaticChange || context == null || updateContext == null) {
            return;
        }
        Integer value = tryParsePort(fieldText(field));
        if (value == null) {
            return;
        }

        updateContext.accept(value);
        syncVirtualProfile(context);
        NodeProfile virtual = context.getVirtualProfile();
        if (virtual != null) {
            syncPortProperty(virtual.getProperties(), prop, fieldText(field));
            pushSnapshot(WizardFinish.buildProperties(context));
        }
        for (Consumer<WizardContext> listener : changeListeners) {
            listener.accept(context);
        }
    }

    // ── WizardStep contract ───────────────────────────────────────────────

    @Override
    public String getId() {
        return "node-configuration";
    }

    @Override
    public String getTitle() {
        return "Node Profile configuration";
    }

    @Override
    public FontAwesome getHeaderIcon() {
        return FontAwesome.COG;
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        String databaseError = dbUrlValidationError();
        if (databaseError != null) {
            return databaseError;
        }
        try {
            parsePort("API port", getApiPortText());
            parsePort("P2P port", getP2pPortText());
            parsePort("WebSocket port", getWsPortText());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return null;
    }

    /**
     * The full editor runs in its own modal dialog, so while the wizard's
     * Back / Next / Finish buttons are active it is necessarily closed — no
     * unsaved-changes guard is needed here (the dialog's Save / Cancel row
     * owns that interaction).
     */
    @Override
    public boolean confirmExit(java.awt.Window parent, WizardContext context) {
        return true;
    }

    @Override
    public void onExit(WizardContext context) {
        context.setApiPort(parsePort("API port", getApiPortText()));
        context.setP2pPort(parsePort("P2P port", getP2pPortText()));
        context.setWsPort(parsePort("WebSocket port", getWsPortText()));
        // The step's fields hold the user's latest (edited) values — push them
        // into the virtual in-memory session profile (the full editor's saved
        // edits are already in it; the profile file is created only on finish).
        syncStepFourValuesToVirtualProfile(context);
    }

    /**
     * Entering the step (including BACK navigation) shows the already
     * configured values — the fields are re-synced from the context and the
     * read-only database summary is refreshed. The profile name is NOT
     * re-derived from the ports (it is decided by the network and the engine
     * only), so going back never renames the profile.
     */
    @Override
    public void onEnter(WizardContext context) {
        if (context == null) {
            return;
        }
        updateDbUrlField(context);
        syncFieldsFromContext(context);
        // The virtual in-memory session profile tracks the wizard's managed
        // values (created on the first visit — pure memory, no file: the
        // profile file is created only by the wizard's finish step).
        syncStepFourValuesToVirtualProfile(context);
        NodeProfile virtualProfile = context.getVirtualProfile();
        if (originalAppliedBaseline == null && virtualProfile != null) {
            originalAppliedBaseline = new Properties();
            originalAppliedBaseline.putAll(virtualProfile.getProperties());
        }
        refreshAdditionalSettings(virtualProfile == null ? new Properties() : virtualProfile.getProperties());
    }

    @Override
    public void bindContext(WizardContext context) {
        this.boundContext = context;
        updateDbUrlField(context);
    }

    @Override
    public void addChangeListener(Consumer<WizardContext> listener) {
        changeListeners.add(listener);
    }

    // ── Read-only database summary + field synchronization ─────────────────

    /**
     * Reflects the node's database in the editable DB.Url field (the value of
     * {@link #dbUrlFromContext}). Programmatic — no live-change loop.
     */
    private void updateDbUrlField(WizardContext ctx) {
        if (ctx == null) {
            return;
        }
        setDbUrlField(dbUrlFromContext(ctx));
    }

    private void setDbUrlField(String dbUrl) {
        programmaticDbUrlChange = true;
        try {
            dbUrlField.setText(dbUrl == null ? "" : dbUrl);
            dbUrlField.setCaretPosition(dbUrlField.getDocument().getLength());
        } finally {
            programmaticDbUrlChange = false;
        }
        updateDbUrlValidationDisplay();
    }

    private String dbUrlValidationError() {
        String url = dbUrlField.getText().trim();
        if (!url.startsWith("jdbc:sqlite:")) {
            return null;
        }
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        return parts != null && SqliteDatabasePaths.isDbFile(parts.fileName())
                ? null
                : "Only files with the .db extension are allowed.";
    }

    private void updateDbUrlValidationDisplay() {
        String error = dbUrlValidationError();
        boolean invalid = error != null;
        dbUrlValidationLabel.setText(invalid ? error : "");
        dbUrlValidationLabel.setVisible(invalid);
        dbUrlField.setForeground(invalid ? GuiColors.getContrastRed() : dbUrlNormalForeground);
        panel.revalidate();
    }

    /**
     * The DB.Url the shared context currently implies — the single source of truth
     * for the editable field (and the inverse of {@link #applyDbUrlToContext}). A
     * linked database profile (selected as the database source) resolves to its URL
     * (best-effort); otherwise SQLite yields the configured URL, or the per-profile
     * SSOT default (from the node profile name) while untouched, and a server engine
     * yields the composed connection URL (empty while the connection is not set yet).
     */
    static String dbUrlFromContext(WizardContext ctx) {
        DatabaseEngine engine = ctx.getEngine() == null ? DatabaseEngine.SQLITE : ctx.getEngine();
        String selectedProfile = ctx.getSelectedDbProfileName();
        if (selectedProfile != null && !selectedProfile.isBlank()) {
            try {
                DatabaseConfigurationUtils.DbProfile p =
                        DatabaseConfigurationUtils.loadProfile(null, engine.toString(), selectedProfile);
                if (p != null && !p.databases.isEmpty()) {
                    return p.databases.get(0).url;
                }
            } catch (RuntimeException e) {
                // fall through: best-effort resolution of the profile name
            }
            return selectedProfile;
        }
        if (engine == DatabaseEngine.SQLITE) {
            String url = ctx.getSqliteDbUrl();
            if (url != null && !url.isBlank()) {
                return url;
            }
            String name = ctx.getName() == null || ctx.getName().isBlank() ? "<name>" : ctx.getName();
            return ProfileCreateDefaults.sqliteDbUrl(name);
        }
        if (ctx.isSkipDbSetup()) {
            return ""; // the connection is not configured yet
        }
        String protocol = engine == DatabaseEngine.POSTGRESQL ? "postgresql" : "mariadb";
        int port = ctx.getDbPort() > 0 ? ctx.getDbPort() : engine.getDefaultPort();
        String host = ctx.getDbHost() == null ? "" : ctx.getDbHost();
        String db = ctx.getDbName() == null ? "" : ctx.getDbName();
        String suffix = ctx.getDbUrlSuffix() == null ? "" : ctx.getDbUrlSuffix();
        return "jdbc:" + protocol + "://" + host + ":" + port + "/" + db + suffix;
    }

    /**
     * Mirrors a DB.Url into the shared context — the exact inverse of
     * {@link #dbUrlFromContext}: the engine follows the URL's protocol, a SQLite URL
     * sets the SQLite DB.Url (+ the companion database-profile name from its folder),
     * a server URL decomposes into host/port/db (the username/password are not part
     * of the URL and keep their current values). This is how the editable field
     * re-decides the database — and the engine — from the node-configuration step.
     */
    static void applyDbUrlToContext(WizardContext ctx, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String u = url.trim();
        if (u.startsWith("jdbc:sqlite:")) {
            ctx.setEngine(DatabaseEngine.SQLITE);
            ctx.setSkipDbSetup(true);
            ctx.setSqliteDbUrl(u);
            SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(u);
            ctx.setSqliteDbProfileName(parts == null || !SqliteDatabasePaths.isDbFile(parts.fileName()) ? null
                    : SqliteDatabasePaths.deriveProfileName(parts.dir()));
        } else if (u.startsWith("jdbc:postgresql://")) {
            ctx.setEngine(DatabaseEngine.POSTGRESQL);
            ctx.setSkipDbSetup(false);
            applyServerConnection(ctx, u);
        } else if (u.startsWith("jdbc:mariadb://")) {
            ctx.setEngine(DatabaseEngine.MARIADB);
            ctx.setSkipDbSetup(false);
            applyServerConnection(ctx, u);
        }
    }

    /**
     * Parses host/port/db out of a server JDBC URL ({@code jdbc:engine://host:port/db})
     * into the shared context. An invalid or absent port keeps the previous value (the
     * engine default is resolved at finish).
     */
    static void applyServerConnection(WizardContext ctx, String url) {
        int sep = url.indexOf("://");
        if (sep < 0) {
            return;
        }
        String rest = url.substring(sep + 3);
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        String db = slash >= 0 ? rest.substring(slash + 1) : "";
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            ctx.setDbHost(hostPort.substring(0, colon));
            Integer port = tryParsePort(hostPort.substring(colon + 1));
            if (port != null) {
                ctx.setDbPort(port);
            }
        } else {
            ctx.setDbHost(hostPort);
        }
        // The query string (with its leading '?', e.g. "?useSSL=false") is the URL
        // suffix — the editor's Suffix field — kept separate from the database name.
        int q = db.indexOf('?');
        ctx.setDbUrlSuffix(q >= 0 ? db.substring(q) : null);
        ctx.setDbName(q >= 0 ? db.substring(0, q) : db);
    }

    /**
     * Mirrors a live DB.Url edit into the shared context (the inverse of
     * {@link #dbUrlFromContext}) — the engine and the connection follow the URL, so
     * the node's database can be re-decided (even re-engine'd) from this step.
     * Notifies the controller (the title-bar / name preview follows).
     */
    private void onDbUrlChanged() {
        if (programmaticDbUrlChange || boundContext == null) {
            return;
        }
        String url = dbUrlField.getText().trim();
        updateDbUrlValidationDisplay();
        if (url.isBlank()) {
            return;
        }
        if (dbUrlValidationError() != null) {
            return;
        }
        applyDbUrlToContext(boundContext, url);
        // The last edit is the current one (same rule as the port fields): the
        // virtual in-memory session profile tracks the URL's keystrokes.
        syncVirtualProfile(boundContext);
        for (Consumer<WizardContext> listener : changeListeners) {
            listener.accept(boundContext);
        }
    }

    /**
     * Re-syncs the port fields from the context (going back shows the already
     * configured values; programmatic — no live-change feedback loop).
     */
    private void syncFieldsFromContext(WizardContext ctx) {
        programmaticChange = true;
        try {
            if (ctx.getApiPort() != null) {
                apiPortField.setText(String.valueOf(ctx.getApiPort()));
            }
            if (ctx.getP2pPort() != null) {
                p2pPortField.setText(String.valueOf(ctx.getP2pPort()));
            }
            if (ctx.getWsPort() != null) {
                wsPortField.setText(String.valueOf(ctx.getWsPort()));
            }
        } finally {
            programmaticChange = false;
        }
        updatePortValidation(apiPortField, Props.API_PORT);
        updatePortValidation(p2pPortField, Props.P2P_PORT);
        updatePortValidation(wsPortField, Props.API_WEBSOCKET_PORT);
    }
}
