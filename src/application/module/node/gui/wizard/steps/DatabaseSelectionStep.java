package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.utils.gui.GuiFontManager;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wizard step 1 — database engine selection (SSOT: {@link DatabaseEngine}).
 */
public class DatabaseSelectionStep implements WizardStep {

    private final JPanel panel = new JPanel();
    private final ButtonGroup group = new ButtonGroup();
    private final Map<DatabaseEngine, JRadioButton> buttons = new LinkedHashMap<>();

    public DatabaseSelectionStep() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JLabel title = new JLabel("1. Select the database engine for this node");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        GuiFontManager.applyDefaultFont(title);
        panel.add(title);

        for (DatabaseEngine engine : DatabaseEngine.values()) {
            JRadioButton button = new JRadioButton(engine.getDisplayName());
            if (engine == DatabaseEngine.SQLITE) {
                button.setSelected(true);
            }
            button.addActionListener(e ->
                    buttons.forEach((engineKey, b) -> b.setSelected(b == button)));
            group.add(button);
            buttons.put(engine, button);
            panel.add(button);
        }

        JLabel hint = new JLabel("SQLite is file-based (no server needed); MariaDB/PostgreSQL use a running database server.");
        GuiFontManager.applyDefaultFont(hint);
        panel.add(hint);
    }

    /** @return the currently selected engine (never null; default SQLite). */
    public DatabaseEngine getSelectedEngine() {
        for (Map.Entry<DatabaseEngine, JRadioButton> e : buttons.entrySet()) {
            if (e.getValue().isSelected()) {
                return e.getKey();
            }
        }
        return DatabaseEngine.SQLITE;
    }

    @Override
    public String getId() {
        return "database-selection";
    }

    @Override
    public String getTitle() {
        return "Database";
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        return null; // engine is always chosen (radio group, SQLite preselected)
    }

    @Override
    public void onExit(WizardContext context) {
        context.setEngine(getSelectedEngine());
    }
}