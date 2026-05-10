package brs.gui.configuration;

import brs.gui.util.HelpButton;
import brs.util.PathUtils;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class DatabaseConfigurationPanel extends JPanel {

    public enum DatabaseEngine {
        MARIADB("MariaDB", "MariaDB", "10.6", "10.11", "11.4"),
        POSTGRESQL("PostgreSQL", "PostgreSQL", "15", "16", "17"),
        SQLITE("SQLite", "SQLite", "3.45.2", "3.46.0");

        private final String displayName;
        private final String folderName;
        private final String[] versions;

        DatabaseEngine(String displayName, String folderName, String... versions) {
            this.displayName = displayName;
            this.folderName = folderName;
            this.versions = versions;
        }

        @Override
        public String toString() {
            return displayName;
        }

        public String getFolderName() {
            return folderName;
        }

        public String[] getVersions() {
            return versions;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfigurationPanel.class);
    private final Runnable restartAction;
    private final String confFolder;
    private final Runnable backAction;

    private JComboBox<DatabaseEngine> dbTypeCombo;
    private JComboBox<String> dbVersionCombo;
    private JButton downloadBtn;

    public DatabaseConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction) {
        super(new BorderLayout());
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.backAction = backAction;

        ensureDirectoryStructure();
        initUI();
    }

    private void ensureDirectoryStructure() {
        try {
            // A PathUtils segítségével meghatározzuk a 'database' mappa helyét
            // a JAR fájlhoz (vagy munkakönyvtárhoz) képest egy szinttel feljebb.
            Path baseDbPath = PathUtils.resolvePath("../database");
            logger.info("Base database path: {}", baseDbPath);

            if (Files.notExists(baseDbPath)) {
                Files.createDirectories(baseDbPath);
                logger.info("Created base database directory: {}", baseDbPath);
            }

            for (DatabaseEngine engine : DatabaseEngine.values()) {
                Path p = baseDbPath.resolve(engine.getFolderName());
                if (Files.notExists(p)) {
                    Files.createDirectories(p);
                    logger.info("Created engine directory: {}", p);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initUI() {
        JPanel contentPanel = new JPanel(new MigLayout("fillx, insets 20, gap 10", "[][grow][]", ""));

        contentPanel.add(new JLabel("Database Engine:"), "align label");
        dbTypeCombo = new JComboBox<>(DatabaseEngine.values());
        contentPanel.add(dbTypeCombo, "growx");
        contentPanel.add(new HelpButton(), "wrap");

        contentPanel.add(new JLabel("Version:"), "align label");
        dbVersionCombo = new JComboBox<>();
        updateVersionCombo();
        contentPanel.add(dbVersionCombo, "growx");
        contentPanel.add(new HelpButton(), "wrap");

        dbTypeCombo.addActionListener(e -> updateVersionCombo());

        downloadBtn = new JButton("Download & Setup");
        downloadBtn.addActionListener(e -> performDownload());
        contentPanel.add(downloadBtn, "span, center, gaptop 20");

        add(new JScrollPane(contentPanel), BorderLayout.CENTER);
    }

    private void updateVersionCombo() {
        DatabaseEngine selected = (DatabaseEngine) dbTypeCombo.getSelectedItem();
        dbVersionCombo.removeAllItems();
        if (selected != null) {
            for (String v : selected.getVersions()) {
                dbVersionCombo.addItem(v);
            }
        }
    }

    private void performDownload() {
        DatabaseEngine selected = (DatabaseEngine) dbTypeCombo.getSelectedItem();
        String version = (String) dbVersionCombo.getSelectedItem();

        int choice = JOptionPane.showConfirmDialog(this,
                "Download and install " + selected + " v" + version + "?",
                "Confirm Download", JOptionPane.YES_NO_OPTION);

        if (choice == JOptionPane.YES_OPTION) {
            JOptionPane.showMessageDialog(this,
                    "Downloading " + selected + " " + version + "... (Feature development ongoing)");
        }
    }

    public boolean checkUnsavedChangesAndProceed(Runnable onProceed, Runnable onCancel) {
        if (onProceed != null)
            onProceed.run();
        return true;
    }

    public void loadAppliedProperties() {
    }
}
