package application.module.node.gui.configuration;

import application.module.database.databaseConfiguration.DatabaseConfigurationPanel;
import application.module.database.databaseConfiguration.DatabaseEnginePanel;
import application.utils.gui.GuiConstants;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

/**
 * Unified configuration container using tabs to group Node, Logger, and Look
 * and Feel settings.
 */
public class ConfigurationPanel extends JPanel {

    private final JTabbedPane tabbedPane;
    private final NodeConfigurationPanel nodeConfig;
    private final LoggerConfigurationPanel loggerConfig;
    private final DatabaseConfigurationPanel dbConfig;
    private final Runnable backAction;
    private final JButton backButton;
    private final JLabel titleLabel;

    public ConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction) {
        super(new BorderLayout());
        this.backAction = backAction;

        // Global Header
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(5, 5, 5, 5));

        backButton = new JButton("Back to Console");
        backButton.addActionListener(e -> {
            if (checkUnsavedChanges()) {
                backAction.run();
            }
        });
        header.add(backButton, BorderLayout.WEST);

        titleLabel = new JLabel("Configuration", SwingConstants.CENTER);
        header.add(titleLabel, BorderLayout.CENTER);

        JPanel headerContainer = new JPanel(new BorderLayout());
        headerContainer.add(header, BorderLayout.CENTER);
        headerContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH);

        add(headerContainer, BorderLayout.NORTH);

        tabbedPane = new JTabbedPane();

        // Initialize sub-panels
        // We pass null for the internal 'switch' actions as they are now handled by
        // tabs
        nodeConfig = new NodeConfigurationPanel(restartAction, confFolder, backAction, null);
        loggerConfig = new LoggerConfigurationPanel(restartAction, confFolder, backAction, null,
                name -> nodeConfig.setLinkedLoggingProfile(name),
                () -> nodeConfig.getLoadedProfileName(),
                () -> nodeConfig.getLinkedLoggingProfile());
        dbConfig = new DatabaseConfigurationPanel(restartAction, confFolder, backAction);

        tabbedPane.addTab("Node", nodeConfig);
        tabbedPane.addTab("Logger", loggerConfig);
        tabbedPane.addTab("Database", dbConfig);

        tabbedPane.addChangeListener(e -> {
            int index = tabbedPane.getSelectedIndex();

            if (index == 1) { // Logger
                loggerConfig.updateLinkCheckbox();
                String linked = nodeConfig.getLinkedLoggingProfile();
                if (linked != null && !linked.isEmpty()) {
                    loggerConfig.loadProfile(linked);
                }
            } else if (index == 2) { // Database
                String linked = nodeConfig.getLinkedDbProfile();
                if (linked != null && linked.contains(":")) {
                    String[] parts = linked.split(":");
                    DatabaseConfigurationPanel.DatabaseEngine engine = DatabaseConfigurationPanel.DatabaseEngine
                            .fromDisplayName(parts[0]);
                    if (engine != null) {
                        // Notify internal logic of the Database tab
                        for (Component c : dbConfig.getComponents()) {
                            if (c instanceof JTabbedPane) {
                                JTabbedPane dbTabs = (JTabbedPane) c;
                                for (int i = 0; i < dbTabs.getTabCount(); i++) {
                                    if (dbTabs.getTitleAt(i).equalsIgnoreCase(engine.getDisplayName())) {
                                        dbTabs.setSelectedIndex(i);
                                        Component enginePanel = dbTabs.getComponentAt(i);
                                        if (enginePanel instanceof DatabaseEnginePanel) {
                                            ((DatabaseEnginePanel) enginePanel).loadProfile(parts[1], null);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        add(tabbedPane, BorderLayout.CENTER);
        updateUI(); // Apply initial styles
    }

    public void setSelectedTab(ConfigTab tab) {
        tabbedPane.setSelectedIndex(tab.ordinal());
    }

    public boolean checkUnsavedChanges() {
        // Orchestrate unsaved changes check across all tabs
        if (!nodeConfig.checkUnsavedChangesAndProceed(null, null)) {
            return false;
        }
        if (!loggerConfig.checkUnsavedChangesAndProceed(null, null)) {
            return false;
        }
        // if (!dbConfig.checkUnsavedChangesAndProceed(null, null))
        // return false;
        return true;
    }

    public void loadAppliedProperties() {
        nodeConfig.loadAppliedProperties();
        loggerConfig.loadAppliedProperties();
    }

    public enum ConfigTab {
        NODE, LOGGER, DATABASE
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (backButton != null) {
            backButton.setIcon(IconFontSwing.buildIcon(FontAwesome.ARROW_LEFT, GuiConstants.getHelpIconSize(),
                    UIManager.getColor("Label.foreground")));
        }
        if (titleLabel != null) {
            titleLabel.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 16f));
        }
    }
}