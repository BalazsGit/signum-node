package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.gui.MariaDBConfigurationPanel;
import application.module.database.gui.PostgreSQLConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.utils.gui.GuiFontManager;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.util.function.Function;

/**
 * Wizard step 1.1 — database installation ("ha szükséges").
 * <p>
 * For server engines this step embeds the <b>existing</b> Database-module engine panel
 * (SSOT — no duplicated installer UI), which provides the complete flow: version
 * selection, <b>Download</b> (+ progress), extract, <b>Initialize</b> and configuration
 * (my.ini / postgres settings). An installed engine is detected via
 * {@link DatabaseConfigurationUtils#isDatabaseInstalled(DatabaseEngine)}; when the
 * engine is already installed the "Skip" option is pre-selected (the installation is
 * only mandatory when actually needed).
 * </p>
 * <p>
 * Auto-skipped for SQLite (file-based, nothing to install).
 * </p>
 * <p>
 * The embedded panel factory and the installed-check are injectable so headless unit
 * tests can drive the step without building the heavyweight real panels.
 * </p>
 */
public class DatabaseInstallationStep implements WizardStep {

    private final JPanel panel = new JPanel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JCheckBox skipCheck = new JCheckBox("Skip — the database is already installed / I will install it manually");
    private final JPanel embeddedSlot = new JPanel(new BorderLayout());
    private final Function<DatabaseEngine, JComponent> embeddedFactory;
    private final Function<DatabaseEngine, Boolean> installedCheck;

    private DatabaseEngine builtFor = null;
    private DatabaseEngine currentEngine = null;
    private boolean initialized = false;

    /** Production wiring: real Database-module panels + real disk check. */
    public DatabaseInstallationStep() {
        this(DatabaseInstallationStep::defaultEmbeddedPanel, DatabaseConfigurationUtils::isDatabaseInstalled);
    }

    /**
     * @param embeddedFactory builds the installer UI for an engine (SSOT: Database module panels)
     * @param installedCheck  reports whether the engine already has an installed instance
     */
    public DatabaseInstallationStep(Function<DatabaseEngine, JComponent> embeddedFactory,
                                    Function<DatabaseEngine, Boolean> installedCheck) {
        this.embeddedFactory = embeddedFactory;
        this.installedCheck = installedCheck;

        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        statusLabel.setFont(statusLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        GuiFontManager.applyDefaultFont(statusLabel);
        panel.add(statusLabel);

        embeddedSlot.setOpaque(false);
        panel.add(embeddedSlot);

        GuiFontManager.applyDefaultFont(skipCheck);
        skipCheck.addActionListener(e -> {
            embeddedSlot.setVisible(!skipCheck.isSelected());
            if (!skipCheck.isSelected()) {
                ensureEmbeddedPanel(); // lazily build when the user (un)checks "skip"
            }
        });
        panel.add(skipCheck);
    }

    /** Lazily builds the embedded installer panel for the current engine (only once). */
    private void ensureEmbeddedPanel() {
        if (currentEngine == null || builtFor == currentEngine) {
            return;
        }
        embeddedSlot.removeAll();
        JScrollPane scroll = new JScrollPane(embeddedFactory.apply(currentEngine),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(new java.awt.Dimension(520, 300));
        embeddedSlot.add(scroll, BorderLayout.CENTER);
        builtFor = currentEngine;
        panel.revalidate();
        panel.repaint();
    }

    /** The SSOT installer UI for a server engine (the Database module's own panel). */
    public static JComponent defaultEmbeddedPanel(DatabaseEngine engine) {
        if (engine == DatabaseEngine.POSTGRESQL) {
            return new PostgreSQLConfigurationPanel();
        }
        return new MariaDBConfigurationPanel();
    }

    @Override
    public String getId() {
        return "database-installation";
    }

    @Override
    public String getTitle() {
        return "Database installation";
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        DatabaseEngine engine = context.getEngine() == null ? DatabaseEngine.SQLITE : context.getEngine();
        if (engine == DatabaseEngine.SQLITE || skipCheck.isSelected()) {
            return null;
        }
        if (!installedCheck.apply(engine)) {
            return "Complete the database installation below (Download + Initialize), or select 'Skip'.";
        }
        return null;
    }

    @Override
    public void onExit(WizardContext context) {
        DatabaseEngine engine = context.getEngine() == null ? DatabaseEngine.SQLITE : context.getEngine();
        context.setDbInstallSkipped(engine == DatabaseEngine.SQLITE || skipCheck.isSelected());
    }

    @Override
    public void onEnter(WizardContext context) {
        DatabaseEngine engine = context.getEngine() == null ? DatabaseEngine.SQLITE : context.getEngine();
        if (engine == DatabaseEngine.SQLITE) {
            return;
        }
        currentEngine = engine;
        boolean installed = installedCheck.apply(engine);
        if (!initialized) {
            // "ha szükséges": already installed → skip pre-selected; otherwise the installer is shown
            skipCheck.setSelected(installed);
            embeddedSlot.setVisible(!installed);
            initialized = true;
        }
        statusLabel.setText(installed
                ? engine.getDisplayName() + " is already installed — you may review/reconfigure it below."
                : engine.getDisplayName() + " is not installed yet — use the installer below (Download → Initialize).");

        // Lazily build the embedded installer panel only when it is actually shown.
        if (!skipCheck.isSelected()) {
            ensureEmbeddedPanel();
        }
    }

    @Override
    public boolean autoSkip(WizardContext context) {
        return context.getEngine() == DatabaseEngine.SQLITE;
    }
}