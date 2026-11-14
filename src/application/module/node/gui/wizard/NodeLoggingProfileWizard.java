package application.module.node.gui.wizard;

import application.module.logging.LoggingProfileRepository;
import application.utils.config.ModuleIds;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiFontManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/**
 * Self-contained modal wizard that creates a new <b>node logging profile</b> — the
 * wizard form of the Logging module's {@code ModuleLoggingProfilePanel}
 * (plan §2.5.1: "the wizard version of the ModuleLoggingProfilePanel UI").
 * <p>
 * A logging profile is a file in the logging module's convention area
 * ({@code ./conf/node/logging/<name>.properties}); the SSOT
 * {@link LoggingProfileRepository#saveProps} persists it. The wizard is 0..N: the
 * caller may open it repeatedly to create several profiles.
 * </p>
 * <p>
 * The wizard is runnable <b>standalone</b> (from the Logging module) and is also opened
 * as a <b>sub-dialog</b> of the node setup wizard (an icon button in the logging step).
 * The created profile is an independent logging-module entity; the node only references
 * it (its assignment is written by the wizard's finish step).
 * </p>
 * <p>
 * Creation runs through a single headless entry point ({@link #createProfile}) so the
 * logic is unit-testable without the GUI and the file write lives in exactly one place
 * (plan AD-03, the same pattern as the database-profile wizards).
 * </p>
 */
public class NodeLoggingProfileWizard extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLoggingProfileWizard.class);

    /** The dialog's window title (tests locate it by this exact text). */
    public static final String TITLE = "New Node Logging Profile";

    /** The default (root) log level of a new logging profile. */
    public static final String DEFAULT_LEVEL = "INFO";

    /** The log4j2 log levels offered to the user (finest-first). */
    public static final List<String> LOG_LEVELS =
            List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    /** The node profile's root logger level key (log4j2). */
    private static final String ROOT_LEVEL_KEY = "rootLogger.level";

    /** The node profile's file-log level key (log4j2; the node's L1 layering key). */
    private static final String FILE_LEVEL_KEY = "node.fileLog.level";

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<String> rootLevelCombo = new JComboBox<>(LOG_LEVELS.toArray(new String[0]));
    private final JComboBox<String> fileLevelCombo = new JComboBox<>(LOG_LEVELS.toArray(new String[0]));
    private final JLabel status = new JLabel(" ");
    private String createdProfileName;

    public NodeLoggingProfileWizard(Window owner) {
        super(owner);
        setTitle(TITLE);
        setModal(true);
        rootLevelCombo.setSelectedItem(DEFAULT_LEVEL);
        fileLevelCombo.setSelectedItem(DEFAULT_LEVEL);
        buildUi();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });
    }

    // ------------------------------------------------------------------
    // Headless creation core (the single source of the file write)
    // ------------------------------------------------------------------

    /**
     * Validates the (name, root level, file level) triple and persists the new logging
     * profile (SSOT {@link LoggingProfileRepository#saveProps}). This is the only place
     * the profile file is written — the GUI just collects values and calls this.
     *
     * @return the created profile name
     * @throws IllegalArgumentException when the name is blank or reserved
     * @throws IOException              on persistence failure
     */
    static String createProfile(String name, String rootLevel, String fileLevel) throws IOException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Profile name is required");
        }
        String root = (rootLevel == null || rootLevel.isBlank()) ? DEFAULT_LEVEL : rootLevel;
        String file = (fileLevel == null || fileLevel.isBlank()) ? root : fileLevel;
        Properties props = new Properties();
        props.setProperty(ROOT_LEVEL_KEY, root);
        props.setProperty(FILE_LEVEL_KEY, file);
        // saveProps rejects reserved names (the canonical "default" / "logging-default")
        // and persists the profile file for the node module (SSOT: LoggingProfileRepository).
        new LoggingProfileRepository().saveProps(ModuleIds.NODE, trimmed, props);
        return trimmed;
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        getContentPane().add(content);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        JLabel heading = new JLabel(WizardTexts.escape("New Node Logging Profile"));
        heading.setFont(GuiFontManager.getBoldDefaultFont());
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        content.add(heading, gc);
        gc.gridwidth = 1;

        gc.gridx = 0; gc.gridy = 1;
        content.add(new JLabel("Profile name"), gc);
        gc.gridx = 1; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL;
        content.add(nameField, gc);
        gc.weightx = 0; gc.fill = GridBagConstraints.NONE;

        gc.gridx = 0; gc.gridy = 2;
        content.add(new JLabel("Root log level"), gc);
        gc.gridx = 1;
        content.add(rootLevelCombo, gc);

        gc.gridx = 0; gc.gridy = 3;
        content.add(new JLabel("File log level"), gc);
        gc.gridx = 1;
        content.add(fileLevelCombo, gc);

        gc.gridx = 0; gc.gridy = 4; gc.gridwidth = 2;
        content.add(status, gc);
        gc.gridwidth = 1;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton create = new JButton("Create");
        create.addActionListener(e -> onCreate());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> onCancel());
        buttons.add(create);
        buttons.add(cancel);
        gc.gridx = 0; gc.gridy = 5; gc.gridwidth = 2;
        content.add(buttons, gc);
    }

    private void onCreate() {
        String name = nameField.getText();
        try {
            createProfile(name, String.valueOf(rootLevelCombo.getSelectedItem()),
                    String.valueOf(fileLevelCombo.getSelectedItem()));
        } catch (Exception ex) {
            LOGGER.warn("Could not create the logging profile '{}'", name, ex);
            status.setForeground(GuiColors.getContrastRed());
            status.setText(WizardTexts.escape("Could not create the profile: " + ex.getMessage()));
            status.revalidate();
            return;
        }
        createdProfileName = name.trim();
        dispose();
    }

    private void onCancel() {
        createdProfileName = null;
        dispose();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** @return the name of the profile created by "Create", or {@code null} when cancelled. */
    public String getCreatedProfileName() {
        return createdProfileName;
    }

    /**
     * Shows the wizard modally and returns the created profile name (or {@code null} when
     * the user cancelled). Marshals to the event dispatch thread when called from another
     * thread, so hosts may call it from any thread.
     *
     * @param parent the parent component (its owner window is used for modality; may be
     *               {@code null} for a root-level dialog)
     * @return the created profile name, or {@code null} when cancelled
     */
    public static String show(Component parent) {
        Window owner = ownerOf(parent);
        final String[] result = new String[1];
        Runnable task = () -> {
            NodeLoggingProfileWizard wizard = new NodeLoggingProfileWizard(owner);
            wizard.setVisible(true); // modal: blocks until the user finishes
            result[0] = wizard.getCreatedProfileName();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                LOGGER.warn("The node logging profile wizard failed", ex);
            }
        }
        return result[0];
    }

    /** The modal owner window for the parent component (walking up past nested dialogs). */
    private static Window ownerOf(Component parent) {
        if (parent == null) {
            return null;
        }
        Window owner = SwingUtilities.windowForComponent(parent);
        while (owner instanceof JDialog) {
            Window next = ((JDialog) owner).getOwner();
            if (next == null) {
                break;
            }
            owner = next;
        }
        return owner;
    }

    // ------------------------------------------------------------------
    // Test accessors (package-private: the GUI regression tests locate the fields)
    // ------------------------------------------------------------------

    JTextField getNameField() {
        return nameField;
    }

    JComboBox<String> getRootLevelCombo() {
        return rootLevelCombo;
    }

    JComboBox<String> getFileLevelCombo() {
        return fileLevelCombo;
    }

    Component getRootPanel() {
        return getContentPane();
    }
}

