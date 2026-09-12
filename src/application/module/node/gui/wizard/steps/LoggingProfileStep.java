package application.module.node.gui.wizard.steps;

import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.utils.gui.GuiFontManager;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * Wizard step 3 — logging profile (preset) selection.
 * <p>
 * The assignment is written to the canonical store ({@code LoggingAssignmentStore} →
 * {@code conf/node/profiles.json}) at finish time.
 * </p>
 */
public class LoggingProfileStep implements WizardStep {

    /** The default preset applied when the user keeps the "use default" option. */
    public static final String DEFAULT_PRESET = "standard";

    private static final String[] PRESETS = {"minimal", "standard", "verbose", "debug"};

    private final JPanel panel = new JPanel();
    private final JRadioButton defaultRadio = new JRadioButton("Use default preset (" + DEFAULT_PRESET + ")", true);
    private final JRadioButton customRadio = new JRadioButton("Custom preset");
    private final JComboBox<String> presetBox = new JComboBox<>(PRESETS);

    public LoggingProfileStep() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JLabel title = new JLabel("3. Logging profile");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        GuiFontManager.applyDefaultFont(title);
        panel.add(title);

        ButtonGroup group = new ButtonGroup();
        defaultRadio.addActionListener(e -> presetBox.setEnabled(false));
        customRadio.addActionListener(e -> presetBox.setEnabled(true));
        group.add(defaultRadio);
        group.add(customRadio);
        presetBox.setEnabled(false);
        presetBox.setSelectedItem("verbose");
        panel.add(defaultRadio);
        panel.add(customRadio);
        panel.add(presetBox);

        JLabel hint = new JLabel("The preset can be changed any time from the profile's Logging tab.");
        GuiFontManager.applyDefaultFont(hint);
        panel.add(hint);
    }

    public String getPreset() {
        return defaultRadio.isSelected() ? DEFAULT_PRESET : (String) presetBox.getSelectedItem();
    }

    @Override
    public String getId() {
        return "logging-profile";
    }

    @Override
    public String getTitle() {
        return "Logging";
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        return null; // always valid (default preselected)
    }

    @Override
    public void onExit(WizardContext context) {
        context.setLoggingPreset(getPreset());
    }
}