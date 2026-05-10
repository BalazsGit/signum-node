package brs.gui.configuration;

import brs.gui.GuiConstants;
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
    private final LookAndFeelPanel lafConfig;
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
        loggerConfig = new LoggerConfigurationPanel(restartAction, confFolder, backAction, null);
        dbConfig = new DatabaseConfigurationPanel(restartAction, confFolder, backAction);
        lafConfig = new LookAndFeelPanel(restartAction, backAction);

        tabbedPane.addTab("Node", nodeConfig);
        tabbedPane.addTab("Logger", loggerConfig);
        tabbedPane.addTab("Database", dbConfig);
        tabbedPane.addTab("Look & Feel", lafConfig);

        add(tabbedPane, BorderLayout.CENTER);
        updateUI(); // Apply initial styles
    }

    public void setSelectedTab(ConfigTab tab) {
        tabbedPane.setSelectedIndex(tab.ordinal());
    }

    public boolean checkUnsavedChanges() {
        // Orchestrate unsaved changes check across all tabs
        if (!nodeConfig.checkUnsavedChangesAndProceed(null, null))
            return false;
        if (!loggerConfig.checkUnsavedChangesAndProceed(null, null))
            return false;
        return lafConfig.checkUnsavedChangesAndProceed(true, null, null);
    }

    public void loadAppliedProperties() {
        nodeConfig.loadAppliedProperties();
        loggerConfig.loadAppliedProperties();
    }

    public enum ConfigTab {
        NODE, LOGGER, DATABASE, LAF
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