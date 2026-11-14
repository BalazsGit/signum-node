package application.module.node.gui.wizard.steps;

import application.module.logging.LoggingProfileRepository;
import application.module.node.gui.wizard.NodeLoggingProfileWizard;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.gui.wizard.WizardTexts;
import application.utils.config.ModuleIds;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wizard step (G5) — logging-profile selection for the node being created.
 * <p>
 * The user picks the logging profile the new node will use: an <b>existing</b> profile
 * (combo) or a <b>new</b> one created through the {@link NodeLoggingProfileWizard}
 * (the "New Node Logging Profile" button). "(Default)" leaves the node on the built-in
 * logging defaults (the finish step then seeds the assignment the same way as every
 * other creation path — SSOT {@code ProfileRuntimeService.seedDefaultLoggingAssignment}).
 * </p>
 * <p>
 * A read-only summary below the controls shows the selection <b>against the default</b>
 * (plan §2.5.2 step 4): the chosen profile's root level, and whether it differs from the
 * default profile's. The selected profile is mirrored into the shared
 * {@link WizardContext} on exit; the finish step assigns it to the node
 * (SSOT {@code LoggingAssignmentStore}).
 * </p>
 * <p>
 * The profile listing and the profile-properties loading are behind injectable hooks
 * (the production SSOT is {@link LoggingProfileRepository}) so the headless tests can
 * stub the file I/O and drive the selection without touching the real {@code ./conf}
 * tree.
 * </p>
 */
public class LoggingSelectionStep implements WizardStep {

    /** The combo entry that means "no explicit profile — the built-in defaults apply". */
    public static final String DEFAULT_ENTRY = "(Default)";

    /** The reserved canonical logging profile name (the L1 user-editable default). */
    private static final String DEFAULT_PROFILE_NAME = LoggingProfileRepository.RESERVED_PROFILE_NAME;

    /** The root logger level key compared in the default-against summary. */
    private static final String ROOT_LEVEL_KEY = "rootLogger.level";

    private final JPanel panel = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel();
    private final JComboBox<String> profileCombo = new JComboBox<>();
    private final JButton createButton = new JButton("New logging profile…");
    private final javax.swing.JEditorPane summary = WizardTexts.info("");

    private WizardContext boundContext;
    private final List<Consumer<WizardContext>> changeListeners = new ArrayList<>();
    private boolean rebuilding = false;

    // ── Injectable SSOT hooks (headless-safe: tests stub the file I/O) ──────
    private Supplier<List<String>> profileNames = LoggingSelectionStep::defaultProfileNames;
    private Function<String, Properties> profileProperties = LoggingSelectionStep::defaultProfileProperties;
    private Supplier<String> profileCreator = () -> NodeLoggingProfileWizard.show(null);

    public LoggingSelectionStep() {
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        javax.swing.JEditorPane heading = WizardTexts.heading("Logging");
        heading.setAlignmentX(0f);
        content.add(heading);

        javax.swing.JEditorPane intro = WizardTexts.info(
                "Choose the logging profile this node will use. Leave it on the default, "
                        + "pick an existing profile, or create a new one.");
        intro.setAlignmentX(0f);
        intro.setMaximumSize(new java.awt.Dimension(460, 40));
        content.add(intro);

        JPanel row = new JPanel(new GridLayout(0, 1, 0, 6));
        row.setOpaque(false);
        row.setAlignmentX(0f);
        row.setMaximumSize(new java.awt.Dimension(460, 80));
        JLabel profileLabel = new JLabel("Logging profile");
        GuiFontManager.applyDefaultFont(profileLabel);
        row.add(profileLabel);
        row.add(profileCombo);
        row.add(createButton);
        content.add(row);

        summary.setAlignmentX(0f);
        summary.setMaximumSize(new java.awt.Dimension(460, 60));
        content.add(summary);

        panel.add(content, BorderLayout.CENTER);

        profileCombo.addActionListener(e -> {
            if (!rebuilding) {
                updateSummary();
                notifyChange();
            }
        });
        createButton.addActionListener(e -> onCreateClicked());
        updateSummary();
    }

    // ------------------------------------------------------------------
    // Default SSOT hooks (production)
    // ------------------------------------------------------------------

    private static List<String> defaultProfileNames() {
        try {
            return new LoggingProfileRepository().listProfiles(ModuleIds.NODE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Properties defaultProfileProperties(String name) {
        try {
            return new LoggingProfileRepository().loadProps(ModuleIds.NODE, name);
        } catch (Exception e) {
            return new Properties();
        }
    }

    // ------------------------------------------------------------------
    // Selection + summary
    // ------------------------------------------------------------------

    private void onCreateClicked() {
        String created = profileCreator.get();
        if (created == null || created.isBlank()) {
            return; // the user cancelled the wizard
        }
        if (indexOf(created) < 0) {
            profileCombo.addItem(created);
        }
        profileCombo.setSelectedItem(created);
        updateSummary();
        notifyChange();
    }

    /** @return the selected logging-profile name, or {@code null} for "(Default)". */
    String selectedLoggingProfileName() {
        int i = profileCombo.getSelectedIndex();
        return i <= 0 ? null : (String) profileCombo.getSelectedItem();
    }

    private int indexOf(String item) {
        for (int i = 0; i < profileCombo.getItemCount(); i++) {
            if (item.equals(profileCombo.getItemAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** (Re)populates the combo from the profile-name SSOT and restores the selection. */
    private void repopulate(WizardContext context) {
        rebuilding = true;
        try {
            String selected = context == null ? null : context.getSelectedLoggingProfileName();
            profileCombo.removeAllItems();
            profileCombo.addItem(DEFAULT_ENTRY);
            for (String name : profileNames.get()) {
                profileCombo.addItem(name);
            }
            if (selected != null && indexOf(selected) >= 0) {
                profileCombo.setSelectedItem(selected);
            } else {
                profileCombo.setSelectedIndex(0);
            }
        } finally {
            rebuilding = false;
        }
        updateSummary();
    }

    /** Renders the default-against summary (plan §2.5.2 step 4). */
    private void updateSummary() {
        String name = selectedLoggingProfileName();
        if (name == null) {
            summary.setText(WizardTexts.wrap("Logging: <b>default</b> — the node's built-in logging defaults apply."));
            return;
        }
        String selectedRoot = rootLevel(profileProperties.apply(name));
        String defaultRoot = rootLevel(profileProperties.apply(DEFAULT_PROFILE_NAME));
        if (selectedRoot.equalsIgnoreCase(defaultRoot)) {
            summary.setText(WizardTexts.wrap(
                    "Logging: <b>" + WizardTexts.escape(name) + "</b> (no differences from the default)."));
        } else {
            summary.setText(WizardTexts.wrap(
                    "Logging: <b>" + WizardTexts.escape(name)
                            + "</b> — differs from the default: root level " + WizardTexts.escape(selectedRoot)
                            + " (default is " + WizardTexts.escape(defaultRoot) + ")."));
        }
    }

    private static String rootLevel(Properties props) {
        String v = props == null ? null : props.getProperty(ROOT_LEVEL_KEY);
        return v == null || v.isBlank() ? "INFO" : v;
    }

    private void notifyChange() {
        if (boundContext == null) {
            return;
        }
        for (Consumer<WizardContext> listener : changeListeners) {
            if (listener != null) {
                listener.accept(boundContext);
            }
        }
    }

    // ------------------------------------------------------------------
    // WizardStep
    // ------------------------------------------------------------------

    @Override
    public String getId() {
        return "logging-selection";
    }

    @Override
    public String getTitle() {
        return "Logging";
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
        return null; // the logging choice is always valid (default is a fine selection)
    }

    @Override
    public boolean autoSkip(WizardContext context) {
        return false; // logging is always a step (the node always gets a logging assignment)
    }

    @Override
    public void bindContext(WizardContext context) {
        this.boundContext = context;
    }

    @Override
    public void addChangeListener(Consumer<WizardContext> listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    @Override
    public void onEnter(WizardContext context) {
        repopulate(context);
    }

    @Override
    public void onExit(WizardContext context) {
        context.setSelectedLoggingProfileName(selectedLoggingProfileName());
    }

    // ------------------------------------------------------------------
    // Test hooks / accessors (package-private)
    // ------------------------------------------------------------------

    void setProfileNames(Supplier<List<String>> supplier) {
        this.profileNames = supplier != null ? supplier : LoggingSelectionStep::defaultProfileNames;
    }

    void setProfileProperties(Function<String, Properties> loader) {
        this.profileProperties = loader != null ? loader : LoggingSelectionStep::defaultProfileProperties;
    }

    void setProfileCreator(Supplier<String> creator) {
        this.profileCreator = creator != null ? creator : () -> NodeLoggingProfileWizard.show(null);
    }

    JComboBox<String> getProfileCombo() {
        return profileCombo;
    }

    JButton getCreateButton() {
        return createButton;
    }

    javax.swing.JEditorPane getSummary() {
        return summary;
    }

    String getSelectedLoggingProfileName() {
        return selectedLoggingProfileName();
    }
}
