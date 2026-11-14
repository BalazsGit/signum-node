package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.SqliteDatabasePaths;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Self-contained modal wizard that creates a new <b>SQLite database</b> — either a
 * brand-new <b>database profile</b> or a new <b>database file placed into an
 * EXISTING profile</b> (the profile's own settings stay untouched; the new file
 * becomes selectable from the profile in the linked-mode file dropdown).
 * The wizard form of the configuration panel's "Linked Database Profile"
 * selection (the same Profile / DB-file trio the linked SQLite row uses).
 * <p>
 * A SQLite database profile is a file-based profile in the database module's
 * convention area ({@code ./database/SQLite/<name>/profile.json}); the SSOT
 * {@link ProfileCreateDefaults#ensureSqliteDatabaseProfile} creates (or updates) it
 * together with its data directory. Both input rows are EDITABLE dropdowns: the
 * profile select lists every discovered SQLite database profile (picking an
 * existing one names the target of a new database file), the file select lists
 * the selected profile's {@code *.db} files — arbitrary (new) values can be
 * typed into either. The wizard is therefore 0..N: the caller may open
 * it repeatedly to create several profiles / database files.
 * </p>
 * <p>
 * The wizard is runnable <b>standalone</b> (from the Database module) and is also
 * opened as a <b>sub-dialog</b> of the node setup wizard (an icon button in the
 * database / node-configuration step). The created profile is an independent
 * database-module entity; the node only references it (plan §2.5.1).
 * </p>
 * <p>
 * Creation runs through a single headless entry point
 * ({@link #createProfile(String, String)}) so the logic is unit-testable without the
 * GUI and the file write lives in exactly one place (plan AD-03). A conflicting
 * input (an existing profile paired with an ALREADY EXISTING database file) is
 * reported by {@link #checkConflict(String, String)} with a red notification and
 * blocks the create.
 * </p>
 */
public class SqliteDatabaseProfileWizard extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteDatabaseProfileWizard.class);

    /** The dialog's window title (tests locate it by this exact text). */
    public static final String TITLE = "New SQLite Database, or Database Profile";

    /** The default database file of a per-profile SQLite database (SSOT). */
    private static final String DEFAULT_DB_FILE = ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME;

    /** The maximum length of a new database profile name (kept consistent with node profiles). */
    private static final int MAX_NAME_LENGTH = 32;

    /** The wrap width of the dialog's body/preview text panes (selectable, wraps inside the dialog). */
    private static final int TEXT_WRAP_WIDTH = 560;

    // ------------------------------------------------------------------
    // Headless creation core (the single source of the file write)
    // ------------------------------------------------------------------

    /**
     * Validates the (name, database file) input of a new SQLite database profile.
     *
     * @return {@code null} when the input is acceptable, otherwise a user-facing
     *         error message
     */
    static String validateInput(String rawName, String rawDbFile) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return "The profile name must not be empty.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "The profile name must not exceed " + MAX_NAME_LENGTH + " characters.";
        }
        if (name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            return "The profile name must not contain path separators.";
        }
        String file = rawDbFile == null ? "" : rawDbFile.trim();
        if (file.isEmpty()) {
            return "The database file name must not be empty.";
        }
        if (!SqliteDatabasePaths.isDbFile(file)) {
            return "The database file must have a \".db\" extension.";
        }
        return null;
    }

    /**
     * Composes the canonical JDBC URL of the new profile (SSOT:
     * {@link SqliteDatabasePaths#composeUrl}):
     * {@code jdbc:sqlite:file:./database/SQLite/<name>/<file>}. For the default file
     * name this is identical to {@link ProfileCreateDefaults#sqliteDbUrl(String)}.
     */
    static String composeUrl(String name, String dbFile) {
        return SqliteDatabasePaths.composeUrl(
                SqliteDatabasePaths.profileDir(name == null ? "" : name.trim()),
                dbFile == null ? "" : dbFile.trim());
    }

    /**
     * Whether a database profile with the given name already exists (discovery SSOT:
     * {@link DatabaseConfigurationUtils#getProfileNames}).
     */
    static boolean profileExists(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        return DatabaseConfigurationUtils.getProfileNames(null, DatabaseEngine.SQLITE.toString())
                .contains(name.trim());
    }

    /**
     * The (profile, database file) conflict check: a not-yet-existing database
     * file may be placed into an EXISTING profile, but the pair itself must not
     * already exist — a file the profile directory already carries is a conflict.
     *
     * @param name the profile name
     * @param file the database file name
     * @return a user-facing conflict message, or {@code null} when the pair is
     *         free (blank inputs are reported by {@link #validateInput})
     */
    static String checkConflict(String name, String file) {
        if (name == null || name.trim().isEmpty() || file == null || file.trim().isEmpty()) {
            return null;
        }
        if (profileExists(name)
                && Files.exists(SqliteDatabasePaths.profileDir(name.trim()).resolve(file.trim()))) {
            return "The database file '" + file.trim() + "' already exists in profile '" + name.trim()
                    + "' — please change the database profile name or the database file name.";
        }
        return null;
    }

    /**
     * Creates the named SQLite database profile — or places a new database file
     * into an EXISTING profile — the single file-write entry point:
     * <ul>
     *   <li>NEW profile name: writes {@code profile.json} with the composed
     *       convention URL and ensures the data directory (SSOT:
     *       {@link ProfileCreateDefaults#ensureSqliteDatabaseProfile});</li>
     *   <li>EXISTING profile name: only ensures the file's directory — the
     *       profile's settings (profile.json) are left untouched; the new file
     *       becomes visible from the linked-mode file dropdown (it lists the
     *       profile directory's {@code *.db} files).</li>
     * </ul>
     *
     * @return the normalized profile name
     * @throws IllegalArgumentException when the input fails {@link #validateInput}
     */
    static String createProfile(String rawName, String rawDbFile) {
        String error = validateInput(rawName, rawDbFile);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        String name = rawName.trim();
        String file = rawDbFile.trim();
        if (profileExists(name)) {
            // An existing profile: place the (new) database file into the
            // profile's directory without touching profile.json.
            try {
                Path target = SqliteDatabasePaths.profileDir(name).resolve(file);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not ensure the directory of the existing profile '"
                        + name + "': " + e.getMessage());
            }
            LOGGER.info("Placed a new database file '{}' into the existing SQLite database profile '{}'",
                    file, name);
            return name;
        }
        String url = composeUrl(name, file);
        ProfileCreateDefaults.ensureSqliteDatabaseProfile(name, url);
        LOGGER.info("Created the SQLite database profile '{}' -> {}", name, url);
        return name;
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    /**
     * The profile name select — an EDITABLE dropdown listing every discovered
     * SQLite database profile (picking an existing one places the new database
     * file into that profile); arbitrary new names can be typed.
     */
    private final JComboBox<String> nameField = new JComboBox<>();
    /**
     * The database file select — an EDITABLE dropdown listing the selected
     * profile's {@code *.db} files (plus the default file name); arbitrary new
     * file names can be typed.
     */
    private final JComboBox<String> dbFileField = new JComboBox<>();
    private final javax.swing.JEditorPane preview = WizardTexts.info("", TEXT_WRAP_WIDTH);
    private final javax.swing.JEditorPane status = WizardTexts.info(" ", TEXT_WRAP_WIDTH);
    private final boolean allowExistingProfileTarget;

    /** The profile name created by a successful "Create" (null until then / on cancel). */
    private String createdProfileName = null;

    /**
     * @param owner the parent window (may be {@code null} for a root-level dialog)
     */
    public SqliteDatabaseProfileWizard(Window owner) {
        this(owner, true);
    }

    private SqliteDatabaseProfileWizard(Window owner, boolean allowExistingProfileTarget) {
        super(owner);
        this.allowExistingProfileTarget = allowExistingProfileTarget;
        setTitle(TITLE);
        setModal(true);
        // Defensive icon-font registration (the app registers it at startup; this
        // covers standalone/test construction).
        IconFontSwing.register(FontAwesome.getIconFont());
        buildUi();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });
    }

    private void buildUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setResizable(false);

        // GridBagLayout (not BoxLayout): every row is anchored WEST and fills the
        // width, so the fixed-width display panes sit on the left edge, aligned with
        // the form labels. BoxLayout mis-positions a narrow (max-width-capped) pane
        // toward the right regardless of its alignmentX.
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(18, 22, 14, 22));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        // Header: the database icon (the same glyph the profile toolbars use, at the
        // dialog icon size) + the bold title.
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        header.setOpaque(false);
        header.add(new JLabel(IconFontSwing.buildIcon(FontAwesome.DATABASE,
                GuiConstants.ICON_SIZE_DIALOG, GuiColors.getButtonIcon())));
        header.add(titleLabel());
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 0);
        content.add(header, c);

        // Description: left-aligned on the form's left edge.
        c.gridy = 1;
        c.insets = new Insets(10, 0, 0, 0);
        String description = allowExistingProfileTarget
                ? "Create a new SQLite database profile — or add a new database file to an EXISTING profile: "
                        + "pick the existing profile from the list and enter a database file that does not "
                        + "exist yet. The profile is stored in the database module's convention area "
                        + "(./database/SQLite/&lt;name&gt;/) and can be used by one or more node profiles."
                : "Create a new SQLite database profile. Existing profiles cannot be selected here; use the "
                        + "Database File + button in the profile editor to add a file to an existing profile.";
        content.add(WizardTexts.info(description, TEXT_WRAP_WIDTH), c);

        // The input form: Profile name + Database file.
        c.gridy = 2;
        c.insets = new Insets(14, 0, 0, 0);
        content.add(buildForm(), c);

        // The live preview (profile directory + composed URL) and the status line use
        // fixed-height rows in a stable-height block. Without reserved row heights,
        // GridBagLayout vertically centers their combined preferred height, so a
        // multi-line warning moves the preview upward.
        int lineH = preview.getFontMetrics(preview.getFont()).getHeight();
        int previewH = 3 * lineH;
        int statusH = 3 * lineH;
        int blockH = previewH + 6 + statusH;
        JPanel previewBlock = new JPanel(new GridBagLayout());
        previewBlock.setOpaque(false);
        previewBlock.setPreferredSize(new Dimension(TEXT_WRAP_WIDTH, blockH));
        previewBlock.setMaximumSize(new Dimension(Integer.MAX_VALUE, blockH));
        GridBagConstraints pc = new GridBagConstraints();
        pc.gridx = 0;
        pc.anchor = GridBagConstraints.NORTHWEST;
        pc.fill = GridBagConstraints.HORIZONTAL;
        pc.weightx = 1;
        pc.gridy = 0;
        pc.insets = new Insets(0, 0, 0, 0);
        JPanel previewSlot = new JPanel(new BorderLayout());
        previewSlot.setOpaque(false);
        previewSlot.setPreferredSize(new Dimension(TEXT_WRAP_WIDTH, previewH));
        previewSlot.add(preview, BorderLayout.NORTH);
        previewBlock.add(previewSlot, pc);
        pc.gridy = 1;
        pc.insets = new Insets(6, 0, 0, 0);
        JPanel statusSlot = new JPanel(new BorderLayout());
        statusSlot.setOpaque(false);
        statusSlot.setPreferredSize(new Dimension(TEXT_WRAP_WIDTH, statusH));
        statusSlot.add(status, BorderLayout.NORTH);
        previewBlock.add(statusSlot, pc);
        c.gridy = 3;
        c.insets = new Insets(12, 0, 0, 0);
        content.add(previewBlock, c);

        // The action row.
        c.gridy = 4;
        c.insets = new Insets(16, 0, 0, 0);
        content.add(buildButtons(), c);

        setContentPane(content);
        GuiFontManager.applyFontToTree(this, UIManager.getFont("Label.font"));
        // applyFontToTree re-asserts the plain label font on every component —
        // re-apply the bold title font afterwards so the heading keeps its weight.
        ((JLabel) header.getComponent(1)).setFont(
                GuiFontManager.getBoldScaledDefaultFont(WizardStep.KEYWORD_FONT_SCALE));

        updatePreviewAndStatus(); // size the dialog for the initial content first
        pack();
        setLocationRelativeTo(getOwner());

        installListeners();
    }

    private static JLabel titleLabel() {
        JLabel title = new JLabel(TITLE);
        title.setFont(GuiFontManager.getBoldScaledDefaultFont(WizardStep.KEYWORD_FONT_SCALE));
        return title;
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);

        // Both input rows are editable dropdowns: the profile select lists every
        // discovered SQLite database profile, the file select the selected
        // profile's *.db files (plus the default name) — both accept typed new
        // values (a new profile name / a new database file name).
        nameField.setEditable(true);
        ConfigurationUtils.styleComboEditor(nameField);
        ConfigurationUtils.fixComponentSize(nameField);
        refreshProfileOptions();

        dbFileField.setEditable(true);
        ConfigurationUtils.styleComboEditor(dbFileField);
        ConfigurationUtils.fixComponentSize(dbFileField);
        refreshDbFileOptions();

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        form.add(formLabel("Profile name:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(nameField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        form.add(formLabel("Database file:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(dbFileField, c);
        return form;
    }

    /** Repopulates the profile select with every discovered SQLite database profile. */
    private void refreshProfileOptions() {
        String current = comboEditorText(nameField);
        nameField.removeAllItems();
        if (allowExistingProfileTarget) {
            for (String name : DatabaseConfigurationUtils.getProfileNames(null, DatabaseEngine.SQLITE.toString())) {
                nameField.addItem(name);
            }
        }
        nameField.getEditor().setItem(current);
    }

    /**
     * Repopulates the database file select from the selected profile's directory
     * (its {@code *.db} files), always offers the default file name, keeps a
     * typed (not yet listed) value visible and prefills the default file for a
     * fresh selection.
     */
    private void refreshDbFileOptions() {
        String name = comboEditorText(nameField).trim();
        String current = comboEditorText(dbFileField).trim();
        dbFileField.removeAllItems();
        if (!name.isEmpty() && Files.isDirectory(SqliteDatabasePaths.profileDir(name))) {
            for (String file : SqliteDatabasePaths.discoverDbFiles(SqliteDatabasePaths.profileDir(name))) {
                dbFileField.addItem(file);
            }
        }
        if (!comboContains(dbFileField, DEFAULT_DB_FILE)) {
            dbFileField.addItem(DEFAULT_DB_FILE);
        }
        if (!current.isEmpty() && !comboContains(dbFileField, current)) {
            dbFileField.addItem(current);
        }
        dbFileField.getEditor().setItem(current.isEmpty() ? DEFAULT_DB_FILE : current);
    }

    /** The visible (typed or selected) text of an editable combo. */
    private static String comboEditorText(JComboBox<String> combo) {
        if (combo.getEditor() != null && combo.getEditor().getEditorComponent() instanceof JTextField field) {
            return field.getText() == null ? "" : field.getText();
        }
        String selected = (String) combo.getSelectedItem();
        return selected == null ? "" : selected;
    }

    private static boolean comboContains(JComboBox<String> combo, String item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (item.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static JLabel formLabel(String text) {
        JLabel l = new JLabel(text);
        GuiFontManager.applyDefaultFont(l);
        return l;
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton create = new JButton("Create");
        JButton cancel = new JButton("Cancel");
        create.setFocusable(false);
        cancel.setFocusable(false);
        create.addActionListener(e -> onCreate());
        cancel.addActionListener(e -> onCancel());
        buttons.add(create);
        buttons.add(cancel);
        getRootPane().setDefaultButton(create);
        return buttons;
    }

    private void installListeners() {
        // The editable combos' visible text lives in the editor's text field —
        // the document listeners track both typed and dropdown-selected values.
        JTextField nameEditor = (JTextField) nameField.getEditor().getEditorComponent();
        nameEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshDbFileOptions(); // the file list follows the selected profile
                updatePreviewAndStatus();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshDbFileOptions();
                updatePreviewAndStatus();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshDbFileOptions();
                updatePreviewAndStatus();
            }
        });
        JTextField fileEditor = (JTextField) dbFileField.getEditor().getEditorComponent();
        DocumentListener fileDl = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePreviewAndStatus();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePreviewAndStatus();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePreviewAndStatus();
            }
        };
        fileEditor.getDocument().addDocumentListener(fileDl);
    }

    /**
     * Refreshes the live preview (profile directory + composed URL) and the status
     * line (validation error / red conflict notification / "already exists" note)
     * from the current field values.
     */
    private void updatePreviewAndStatus() {
        String name = comboEditorText(nameField).trim();
        String file = comboEditorText(dbFileField).trim();

        if (!name.isEmpty()) {
            String url = composeUrl(name, file);
            String dir = SqliteDatabasePaths.profileDir(name).toString().replace('\\', '/');
            preview.setText(WizardTexts.wrap(
                    "Profile directory: <b>" + WizardTexts.escape(dir) + "</b><br>"
                            + "Database URL: " + WizardTexts.escape(url)));
        } else {
            preview.setText(WizardTexts.wrap(" "));
        }

        String error = validateInput(name, file);
        String conflict = checkConflict(name, file);
        if (error != null) {
            status.setForeground(GuiColors.getContrastRed());
            status.setText(WizardTexts.wrap(WizardTexts.escape(error)));
        } else if (conflict != null) {
            // The (profile, file) pair already exists — red notification: the
            // user must change the profile name or the database file name.
            status.setForeground(GuiColors.getContrastRed());
            status.setText(WizardTexts.wrap(WizardTexts.escape(conflict)));
        } else if (!allowExistingProfileTarget && profileExists(name)) {
            status.setForeground(GuiColors.getContrastRed());
            status.setText(WizardTexts.wrap(WizardTexts.escape(
                    "Profile '" + name + "' already exists — choose a new profile name.")));
        } else if (!name.isEmpty() && profileExists(name)) {
            status.setForeground(GuiColors.getUnsaved());
            status.setText(WizardTexts.wrap(WizardTexts.escape(
                    "Profile '" + name + "' already exists — the new database file will be added to it.")));
        } else {
            status.setForeground(GuiColors.getFaintText());
            status.setText(WizardTexts.wrap(
                    name.isEmpty() ? "Enter a profile name to continue." : "Ready to create."));
        }
        preview.revalidate();
        status.revalidate();
    }

    private void onCreate() {
        String name = comboEditorText(nameField);
        String file = comboEditorText(dbFileField);
        if (validateInput(name, file) != null) {
            updatePreviewAndStatus();
            return;
        }
        if (!allowExistingProfileTarget && profileExists(name)) {
            updatePreviewAndStatus();
            return;
        }
        if (checkConflict(name, file) != null) {
            // The pair already exists — keep the red conflict notification and
            // create nothing (no overwrite, no duplicate).
            updatePreviewAndStatus();
            return;
        }
        try {
            createdProfileName = createProfile(name, file);
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not create the SQLite database profile '{}'", name, ex);
            status.setForeground(GuiColors.getContrastRed());
            status.setText(WizardTexts.wrap(
                    WizardTexts.escape("Could not create the profile: " + ex.getMessage())));
            status.revalidate();
            return;
        }
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

    /** Shows the (already constructed) wizard modally. Must run on the event dispatch thread. */
    public void showAndWait() {
        setVisible(true);
    }

    /**
     * Shows the wizard modally and returns the created profile name (or {@code null}
     * when the user cancelled). Marshals to the event dispatch thread when called from
     * another thread, so hosts may call it from any thread.
     *
     * @param parent the parent component (its owner window is used for modality; may
     *               be {@code null} for a root-level dialog)
     * @return the created profile name, or {@code null} when cancelled
     */
    public static String show(Component parent) {
        return show(parent, null);
    }

    /**
     * Shows the wizard modally, prefilling the profile name field with the given
     * suggested name ({@code null}/blank: no prefill), and returns the created
     * profile name (or {@code null} when the user cancelled). Marshals to the event
     * dispatch thread when called from another thread, so hosts may call it from any
     * thread.
     *
     * @param parent          the parent component (its owner window is used for
     *                        modality; may be {@code null} for a root-level dialog)
     * @param suggestedName   the profile name to prefill, or {@code null}
     * @return the created profile name, or {@code null} when cancelled
     */
    public static String show(Component parent, String suggestedName) {
        return show(parent, suggestedName, true);
    }

    /** Opens the wizard in new-profile-only mode. */
    public static String showForNewProfile(Component parent) {
        return show(parent, null, false);
    }

    private static String show(Component parent, String suggestedName, boolean allowExistingProfileTarget) {
        Window owner = ownerOf(parent);
        final String[] result = new String[1];
        Runnable task = () -> {
            SqliteDatabaseProfileWizard wizard = new SqliteDatabaseProfileWizard(owner, allowExistingProfileTarget);
            if (suggestedName != null && !suggestedName.isBlank()) {
                JTextField nameEditor = (JTextField) wizard.getNameField().getEditor().getEditorComponent();
                nameEditor.setText(suggestedName.trim());
            }
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
                LOGGER.warn("The SQLite database profile wizard failed", ex);
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

    JComboBox<String> getNameField() {
        return nameField;
    }

    JComboBox<String> getDbFileField() {
        return dbFileField;
    }

    Component getRootPanel() {
        return getContentPane();
    }
}
