package brs.gui.configuration.databaseConfiguration;

import brs.gui.GuiColors;
import brs.gui.GuiConstants;
import brs.gui.configuration.ConfigurationUtils;
import brs.gui.util.HelpButton;
import brs.util.PathUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class SQLiteConfigurationPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(SQLiteConfigurationPanel.class);

    private JsonObject profileSettings = new JsonObject();
    private JsonObject appliedProfileSettings = new JsonObject();
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final List<DatabaseConfigurationPanel.PropertyRow> allPropertyRows = new ArrayList<>();

    private String confFolder;
    private JsonObject globalSettings;
    private Runnable restartAction;
    private Runnable backAction;

    private String loadedProfileName;
    private String runningProfileName;
    private String activeProfileName;
    private Path activeProfilePath;

    private JLabel step1StatusIcon;
    private JLabel step2StatusIcon;
    private JLabel downloadStatusLabel;
    private JLabel pathLabel;
    private JButton downloadDatabaseBtn;

    private Runnable onDirtyStatusChanged;
    private Runnable onProfileSelectionChanged;

    private final Icon checkIcon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE,
            GuiConstants.getHelpIconSize(),
            GuiColors.getApplied());
    private final Icon errorIcon = IconFontSwing.buildIcon(FontAwesome.TIMES_CIRCLE, GuiConstants.getHelpIconSize(),
            GuiColors.getContrastRed());

    public SQLiteConfigurationPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(new JLabel("SQLite Configuration"));

        JButton loadProfileButton = new JButton("Load SQLite Profile");
        loadProfileButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "SQLite profile loading is not yet implemented.");
        });

        add(loadProfileButton);

        // Step 1: Download and Install Database (for SQLite, this is mostly N/A or
        // embedded)
        addSectionHeader(this, "Step 1: Download and Install Database", true);
        JPanel step1ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5", "[][grow]", ""));
        step1ContentPanel.setOpaque(false);

        step1ContentPanel.add(
                new JLabel("SQLite is typically embedded and does not require external download/installation."),
                "span, wrap");

        downloadDatabaseBtn = new JButton("N/A");
        downloadDatabaseBtn.setEnabled(false); // SQLite doesn't need external download
        ConfigurationUtils.fixComponentSize(downloadDatabaseBtn);
        step1ContentPanel.add(downloadDatabaseBtn, "split 2, growx, height pref!, gaptop 5");

        step1StatusIcon = new JLabel();
        step1StatusIcon.setVisible(false);
        step1ContentPanel.add(step1StatusIcon, "wrap");

        downloadStatusLabel = new JLabel("");
        downloadStatusLabel.setForeground(GuiColors.getFaintText());
        step1ContentPanel.add(downloadStatusLabel, "span, wrap");

        this.add(step1ContentPanel, "span, growx, wrap");

        // Step 2: Run and Set up Database
        addSectionHeader(this, "Step 2: Run and Set up Database", false);
        JPanel step2ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5", "[][grow]", ""));
        step2ContentPanel.setOpaque(false);

        // addProperty(step2ContentPanel, "databaseName", "Database File", "DB.Url",
        // "jdbc:sqlite:file:./db/signum.sqlite.db");
        // addProperty(step2ContentPanel, "sqliteJournalMode", "Journal Mode",
        // "DB.SqliteJournalMode", "WAL", new String[]{"WAL", "DELETE", "TRUNCATE",
        // "PERSIST"});
        // addProperty(step2ContentPanel, "sqliteCacheSize", "Cache Size (KB)",
        // "DB.SqliteCacheSize", "-131072");

        // SQLite doesn't typically have admin/app users in the same way as
        // client-server DBs
        // So, we'll omit those sections for simplicity or add placeholders if needed.

        step2StatusIcon = new JLabel();
        step2StatusIcon.setIcon(IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE, GuiConstants.getHelpIconSize(),
                GuiColors.getApplied()));
        step2StatusIcon.setVisible(false);
        step2ContentPanel.add(step2StatusIcon, "span, align right, wrap");

        JButton runSetupBtn = new JButton("Run Database Setup");
        ConfigurationUtils.fixComponentSize(runSetupBtn);
        runSetupBtn.setToolTipText("Perform database creation and user setup based on the configured settings.");
        // runSetupBtn.addActionListener(e -> runDatabaseSetup());
        step2ContentPanel.add(runSetupBtn, "gaptop 5, wrap");

        this.add(step2ContentPanel, "span, growx, wrap");
    }

    private void addSectionHeader(JPanel panel, String title, boolean isFirst) {
        // Replicating the logic from MariaDBConfigurationPanel's addSectionHeader
        // This assumes PropertyRow is accessible from DatabaseConfigurationPanel
        DatabaseConfigurationPanel.PropertyRow row = new DatabaseConfigurationPanel.PropertyRow(null, title, panel);
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()));
        row.label = label;
        row.labelConstraints = "span, growx, " + (isFirst ? "" : "gaptop 15, ") + "gapbottom 5, wrap";
        panel.add(label, row.labelConstraints);
        allPropertyRows.add(row);
    }

}