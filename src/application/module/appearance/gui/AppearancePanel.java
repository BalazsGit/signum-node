package application.module.appearance.gui;

import application.module.node.props.Props;
import application.module.appearance.AppearanceDefaults;
import application.module.appearance.AppearanceModule;
import application.module.appearance.AppearanceProfile;
import application.module.appearance.AppearanceProfileRepository;
import application.module.appearance.gui.laf.FlatLafPanel;
import application.module.node.gui.ColorSettingsPanel;
import application.utils.gui.ColorPaletteManager;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.DialogUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.gui.ProfileCopyDialog;
import application.utils.io.PathUtils;

import com.formdev.flatlaf.*;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.LoggingFacade;
import com.formdev.flatlaf.util.SystemInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import javax.swing.*;
import javax.swing.UIManager.LookAndFeelInfo;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppearancePanel extends JPanel {

    private static AppearancePanel instance;
    private final Runnable backAction;
    private JComboBox<String> profileComboBox;
    private JButton newProfileBtn;
    private JButton cloneProfileBtn;
    private JButton copyProfileBtn;
    private JButton resetToDefaultBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private ColorSettingsPanel colorSettingsPanel;
    private JButton saveProfileBtn;
    private JButton reloadProfileBtn;
    private JButton helpBtn;
    private JTabbedPane tabbedPane;

    public static final String DEFAULT_PROFILE_NAME = "Default";
    private static final String LEGACY_DEFAULT_PROFILE_NAME = "gui";

    private AppearanceProfile savedProfile;
    private boolean isProgrammaticChange = false;
    private boolean lastUnsavedStatus = false;
    private final AppearanceProfileRepository profileRepository;

    // GPU acceleration checkbox (requires restart to take effect)
    private JCheckBox gpuAccelerationCheckBox;

    private static final Logger LOGGER = LoggerFactory.getLogger(AppearancePanel.class);

    public static AppearancePanel getInstance() {
        return instance;
    }

    public ColorSettingsPanel getColorSettingsPanel() {
        return colorSettingsPanel;
    }

    public AppearancePanel(Runnable restartAction, Runnable backAction) {
        super(new BorderLayout());
        this.backAction = backAction;
        instance = this;
        this.profileRepository = new AppearanceProfileRepository("conf");
        profileRepository.ensureDirExists();

        // Determine initial state from file BEFORE the UI is created
        String lastProfile = DEFAULT_PROFILE_NAME;
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lastSelectedLafProfile")) {
                        String candidate = settings.get("lastSelectedLafProfile").getAsString();
                        if (!LEGACY_DEFAULT_PROFILE_NAME.equals(candidate)
                                && profileRepository.getProfileDir().resolve(candidate + ".properties").toFile().exists()) {
                            lastProfile = candidate;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read the last selected appearance profile; using '{}'",
                    DEFAULT_PROFILE_NAME, e);
        }
        updateLoadedStateFromProfile(lastProfile);

        isProgrammaticChange = true;
        initUI();
        isProgrammaticChange = false;
        updateProfileButtonStates();

        // Listen for LookAndFeel changes globally
        UIManager.addPropertyChangeListener(e -> {
            if ("lookAndFeel".equals(e.getPropertyName()) && !isProgrammaticChange) {
                // Only trigger dirty status update if the change was manual (not during profile
                // load)
                updateDirtyStatus();
            }
        });
    }

    private void initUI() {
        // --- Profile Panel ---
        JPanel profilePanel = ConfigurationUtils.createProfileToolbarRow();
        profilePanel.setBorder(BorderFactory.createEmptyBorder()); // ResponsiveToolbarScrollPane handles padding

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setRenderer(new ProfileCellRenderer());
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        ConfigurationUtils.fixComponentSize(profileComboBox);
        profileComboBox.setToolTipText(ConfigurationUtils.profileTooltip("Select the appearance profile to edit.<br>"
                + "Default is the built-in baseline."));

        newProfileBtn = new JButton();
        newProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("New Profile<br><br>"
                + "Creates a new appearance profile initialized with the application default appearance."));
        newProfileBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newProfileBtn);

        cloneProfileBtn = new JButton();
        cloneProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Clone Configuration to New Profile<br><br>Creates a new profile from the current appearance, "
                + "including unsaved changes, and selects the new profile."));
        cloneProfileBtn.addActionListener(e -> cloneProfile());
        profilePanel.add(cloneProfileBtn);

        copyProfileBtn = new JButton();
        copyProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Copy Existing Configuration to This Profile<br><br>Loads another profile's appearance into "
                + "the current editor as unsaved changes."));
        copyProfileBtn.addActionListener(e -> copyProfile());
        profilePanel.add(copyProfileBtn);

        resetToDefaultBtn = new JButton();
        resetToDefaultBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Reset to Default<br><br>Copies the application's default appearance "
                        + "values into the current editor (unsaved). Press Save to persist."));
        resetToDefaultBtn.addActionListener(e -> resetToDefault());
        profilePanel.add(resetToDefaultBtn);

        renameProfileBtn = new JButton();
        renameProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Rename Profile<br><br>"
                + "Renames the selected profile."));
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        saveProfileBtn = new JButton();
        saveProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Save Profile<br><br>"
                + "Saves the current appearance to the selected profile."));
        saveProfileBtn.addActionListener(e -> saveCurrentProfile());
        profilePanel.add(saveProfileBtn);

        reloadProfileBtn = new JButton();
        reloadProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Refresh<br><br>"
                + "Refreshes the available profile list and reloads the selected profile from disk. "
                + "Any unsaved changes are discarded only after confirmation."));
        reloadProfileBtn.addActionListener(e -> refreshAndReloadProfile());
        profilePanel.add(reloadProfileBtn);

        deleteProfileBtn = new JButton();
        deleteProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Delete Profile<br><br>"
                + "Permanently deletes the selected profile."));
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        helpBtn = new HelpButton();
        helpBtn.setToolTipText(ConfigurationUtils.profileTooltip("Help<br><br>"
                + "Explains appearance profiles and their actions."));
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn);

        // The profile selector at the row's RIGHT end (after the buttons):
        // the SAME geometry the module logging profile panels use — the
        // shared row gap (ConfigurationUtils.PROFILE_TOOLBAR_GAP) and the
        // same combo sizing (prototype display value + fixComponentSize).
        profilePanel.add(profileComboBox);

        updateProfileButtonsUI();
        profileComboBox.addActionListener(e -> {
            if (isProgrammaticChange) {
                return;
            }
            String selected = (String) profileComboBox.getSelectedItem();
            if (selected != null) {
                loadProfile(selected);
            }
            updateProfileButtonStates();
        });

        JScrollPane profileScrollPane = ConfigurationUtils.wrapProfileToolbarRow(profilePanel);

        JPanel northWrapper = new JPanel(new BorderLayout());
        northWrapper.add(profileScrollPane, BorderLayout.CENTER);
        northWrapper.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH);

        add(northWrapper, BorderLayout.NORTH);

        tabbedPane = new JTabbedPane();
        GuiUtils.applyDefaultTabLayoutPolicy(tabbedPane);

        FlatLafPanel themePanel = new FlatLafPanel();
        themePanel.setOnChangeListener(this::updateDirtyStatus);
        themePanel.setCloseAction(() -> checkUnsavedChangesAndProceed(true, backAction, null));
        tabbedPane.addTab("Themes & Fonts", themePanel);

        colorSettingsPanel = new ColorSettingsPanel();
        // Sync the color settings panel with the overrides loaded from the profile
        // to prevent false "unsaved changes" detection on startup.
        colorSettingsPanel.setProfileOverrides(savedProfile != null ? savedProfile.getColorOverrides() : null);
        colorSettingsPanel.setOnChangeListener(this::updateDirtyStatus);
        tabbedPane.addTab("Color Settings", colorSettingsPanel);

        add(tabbedPane, BorderLayout.CENTER);

        // GPU Acceleration section at the bottom
        JPanel gpuPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        gpuPanel.setOpaque(false);
        gpuPanel.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));

        gpuAccelerationCheckBox = new JCheckBox("GPU Acceleration (sun.java2d.opengl)");
        gpuAccelerationCheckBox.setToolTipText("<html>Enable GPU acceleration for Java 2D rendering.<br>"
                + "Requires an application restart to take effect.<br>"
                + "When enabled, sets sun.java2d.opengl=true at startup.</html>");
        GuiFontManager.applyDefaultFont(gpuAccelerationCheckBox);

        gpuAccelerationCheckBox.addItemListener(e -> {
            saveGpuAccelerationSetting(gpuAccelerationCheckBox.isSelected());
        });

        // Load current GPU acceleration setting
        gpuAccelerationCheckBox.setSelected(loadGpuAccelerationSetting());

        gpuPanel.add(gpuAccelerationCheckBox);

        // Add separator and GPU panel at SOUTH
        JPanel southWrapper = new JPanel(new BorderLayout());
        southWrapper.setOpaque(false);
        southWrapper.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);
        southWrapper.add(gpuPanel, BorderLayout.CENTER);
        add(southWrapper, BorderLayout.SOUTH);

        loadProfiles(profileComboBox);
    }

    private void updateProfileButtonStates() {
        if (profileComboBox == null || renameProfileBtn == null || deleteProfileBtn == null || saveProfileBtn == null) {
            return;
        }
        boolean hasSelection = profileComboBox.getSelectedItem() != null;
        renameProfileBtn.setEnabled(hasSelection);
        deleteProfileBtn.setEnabled(hasSelection);
        saveProfileBtn.setEnabled(hasSelection && hasUnsavedChanges());
    }

    private boolean hasUnsavedChanges() {
        if (savedProfile == null)
            return false;

        AppearanceProfile currentUI = new AppearanceProfile(savedProfile.getName());
        currentUI.setThemeClassName(UIManager.getLookAndFeel().getClass().getName());

        currentUI.setGlobalFont(UIManager.getFont("Label.font"));
        // Fallback for console font if not explicitly set in UIManager
        currentUI.setConsoleFont(UIManager.getFont("TextArea.font"));
        currentUI.setColorOverrides(
                colorSettingsPanel != null ? colorSettingsPanel.getCurrentOverrides() : new HashMap<>());

        return !currentUI.equals(savedProfile);
    }

    private void updateDirtyStatus() {
        if (isProgrammaticChange || savedProfile == null)
            return;
        boolean dirty = hasUnsavedChanges();
        saveProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Save Profile" + (dirty ? " *" : "")
                + "<br><br>Saves the current appearance to the selected profile."));
        saveProfileBtn.setEnabled(dirty);

        // Mark tabs with an asterisk if there are unsaved changes
        boolean colorsDirty = colorSettingsPanel != null
                && !colorSettingsPanel.getCurrentOverrides().equals(savedProfile.getColorOverrides());
        boolean themeDirty = !UIManager.getLookAndFeel().getClass().getName().equals(savedProfile.getThemeClassName())
                || !fontsMatch(UIManager.getFont("Label.font"), savedProfile.getGlobalFont());

        updateTabTitle(0, "Themes & Fonts", themeDirty);
        updateTabTitle(1, "Color Settings", colorsDirty);
        // The loaded profile's cell carries the unsaved star — repaint the
        // CLOSED combo so its selected cell picks the star up (the module
        // logging profile combo's refresh pattern).
        profileComboBox.repaint();
    }

    private void updateTabTitle(int index, String baseTitle, boolean dirty) {
        String currentTitle = tabbedPane.getTitleAt(index);
        String newTitle = dirty ? baseTitle + " *" : baseTitle;
        if (!currentTitle.equals(newTitle)) {
            tabbedPane.setTitleAt(index, newTitle);
        }
    }

    private void createNewProfile() {
        checkUnsavedChangesAndProceed(false, () -> { // allowKeepUnsaved = false
            String name = (String) JOptionPane.showInputDialog(this, "Enter new profile name:", "New Profile",
                    JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (name == null || name.trim().isEmpty())
                return;
            String profileName = name.trim();
            if (containsProfile(profileName) || profileExistsInSettings(profileName)) {
                JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' already exists.",
                        "Profile Already Exists", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Create new profile with default values
            SwingUtilities.invokeLater(() -> {
                resetToDefaultNow();
                saveProfileInternal(profileName, false);
            });
        }, null);
    }

    private void cloneProfile() {
        String name = (String) JOptionPane.showInputDialog(this, "Name for the new profile:",
                "Clone Configuration to New Profile", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        name = name.trim();
        if (containsProfile(name) || profileExistsInSettings(name)) {
            JOptionPane.showMessageDialog(this, "Profile '" + name + "' already exists.",
                    "Profile Already Exists", JOptionPane.WARNING_MESSAGE);
            return;
        }
        saveProfileInternal(name, false);
    }

    private void copyProfile() {
        java.util.List<String> profiles = new java.util.ArrayList<>();
        for (int i = 0; i < profileComboBox.getItemCount(); i++) {
            String profile = profileComboBox.getItemAt(i);
            if (!DEFAULT_PROFILE_NAME.equals(profile)) {
                profiles.add(profile);
            }
        }
        String current = (String) profileComboBox.getSelectedItem();
        String source = ProfileCopyDialog.show(this, profiles, current);
        if (source == null) {
            return;
        }
        if (hasUnsavedChanges()) {
            int confirm = DialogUtils.confirm(this,
                    DialogUtils.actionIcon(FontAwesome.CLIPBOARD, GuiColors.getButtonIcon()),
                    "Copy Configuration",
                    "The current appearance has unsaved changes that will be replaced. Continue?",
                    JOptionPane.YES_NO_OPTION, null, null);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        AppearanceProfile sourceProfile = profileRepository.loadProfile(source);
        if (sourceProfile == null || !profileExistsInSettings(source)) {
            JOptionPane.showMessageDialog(this, "Failed to load profile '" + source + "'.",
                    "Copy Configuration", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            applyAppearanceProfile(sourceProfile);
            updateDirtyStatus();
        });
    }

    private void applyAppearanceProfile(AppearanceProfile profile) {
        boolean snapshotShown = false;
        try {
            FlatAnimatedLafChange.showSnapshot();
            snapshotShown = true;
            String theme = profile.getThemeClassName() != null
                    ? profile.getThemeClassName()
                    : FlatDarkLaf.class.getName();
            UIManager.setLookAndFeel(theme);
            AppearanceModule.updateCommonFontKeys(profile.getGlobalFont());
            AppearanceModule.updateCommonConsoleFontKeys(profile.getConsoleFont());
            ColorPaletteManager.updatePalette(profile.getColorOverrides());
            colorSettingsPanel.setProfileOverrides(profile.getColorOverrides());
            AppearanceModule.updateAllUIs();
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
            snapshotShown = false;
        } catch (Exception e) {
            LOGGER.error("Failed to copy appearance profile '{}'", profile.getName(), e);
            JOptionPane.showMessageDialog(this, "Failed to copy profile '" + profile.getName()
                    + "': " + e.getMessage(), "Copy Configuration", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (snapshotShown) {
                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            }
        }
    }

    private void saveCurrentProfile() {
        String selected = (String) profileComboBox.getSelectedItem();
        if (selected == null || DEFAULT_PROFILE_NAME.equals(selected)) {
            JOptionPane.showMessageDialog(this, "The built-in Default profile cannot be overwritten. "
                    + "Use Clone Configuration to New Profile to save a copy.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!hasUnsavedChanges()) {
            return;
        }
        saveProfileInternal(selected, false);
    }

    private void refreshProfileList() {
        boolean wasProgrammatic = isProgrammaticChange;
        try {
            isProgrammaticChange = true;
            String currentSelection = (String) profileComboBox.getSelectedItem();
            profileComboBox.removeAllItems();
            loadProfiles(profileComboBox);
            if (containsProfile(currentSelection)) {
                profileComboBox.setSelectedItem(currentSelection);
            } else {
                profileComboBox.setSelectedItem(DEFAULT_PROFILE_NAME);
            }
        } finally {
            isProgrammaticChange = wasProgrammatic;
        }
        updateProfileButtonStates();
    }

    private void refreshAndReloadProfile() {
        if (hasUnsavedChanges()) {
            int result = DialogUtils.confirm(this,
                    DialogUtils.actionIcon(FontAwesome.REFRESH, GuiColors.getButtonIcon()),
                    "Refresh Appearance Profiles",
                    "Refresh the profile list and reload the selected profile from disk? "
                            + "Unsaved changes will be discarded.",
                    JOptionPane.YES_NO_OPTION, null, null);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }
        String selected = (String) profileComboBox.getSelectedItem();
        refreshProfileList();
        if (containsProfile(selected)) {
            profileComboBox.setSelectedItem(selected);
        }
        String profileToReload = (String) profileComboBox.getSelectedItem();
        if (profileToReload != null) {
            performLoadProfile(profileToReload);
        }
    }

    private boolean containsProfile(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < profileComboBox.getItemCount(); i++) {
            if (name.equals(profileComboBox.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean profileExistsInSettings(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return profileRepository.getProfileDir().resolve(name + ".properties").toFile().exists();
    }

    private void updateLoadedStateFromProfile(String profileName) {
        this.savedProfile = DEFAULT_PROFILE_NAME.equals(profileName)
                ? AppearanceDefaults.createDefaultProfile()
                : profileRepository.loadProfile(profileName);
        if (this.savedProfile != null && this.savedProfile.getThemeClassName() == null) {
            this.savedProfile.setThemeClassName(FlatDarkLaf.class.getName());
        }
        if (colorSettingsPanel != null) {
            colorSettingsPanel.setProfileOverrides(savedProfile.getColorOverrides());
        }
        lastUnsavedStatus = false;
        // The applied marker (check icon / color / star) moves with the
        // loaded profile — repaint the CLOSED combo so its selected cell
        // reflects the new profile.
        if (profileComboBox != null) {
            profileComboBox.repaint();
        }
    }

    private void updateProfileButtonsUI() {
        if (profileComboBox != null) {
            ConfigurationUtils.fixComponentSize(profileComboBox);
            // The applied (check) marker and the unsaved star are recomputed
            // on every L&F change — repaint the CLOSED combo so its selected
            // cell reflects them.
            profileComboBox.repaint();
        }
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, saveProfileBtn, null, renameProfileBtn,
                deleteProfileBtn, null, reloadProfileBtn, resetToDefaultBtn, copyProfileBtn, cloneProfileBtn);
        // The toolbar's help icon follows the SAME flat icon-button look as
        // every other toolbar button (the module logging profile toolbar's
        // pattern): the button-icon color, the toolbar icon size, the uniform
        // 2/6/2/6 border and the standard button margin — so the question mark
        // sits at the same distance from the profile selector as in the
        // logging module.
        // Re-applied here because HelpButton.updateUI restores its own
        // palette-colored, borderless icon after every L&F change.
        ConfigurationUtils.styleProfileIconButton(helpBtn, FontAwesome.QUESTION_CIRCLE,
                GuiColors.getButtonIcon(), GuiConstants.getToolBarIconSize());
        updateProfileButtonStates();
    }

    /**
     * Profile combo renderer: the profile currently LOADED (applied) for this
     * appearance is painted in the applied (green) color with a leading check
     * icon — the SAME color scheme the module logging profile combo uses —
     * so the active profile is recognizable at a glance.
     * <p>
     * Extends {@link DefaultListCellRenderer} (the L&F-standard cell renderer)
     * rather than a plain {@link JLabel}: the combo measures its size through
     * this renderer, and only the standard renderer measures the L&F-standard
     * cell size — the same reason the module logging profile combo's renderer
     * does, so the two profile selectors keep identical sizing.
     */
    private final class ProfileCellRenderer extends DefaultListCellRenderer {
        private final Icon appliedIcon = IconFontSwing.buildIcon(FontAwesome.CHECK,
                GuiConstants.getHelpIconSize(), GuiColors.getApplied());

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            String name = (String) value;
            setIcon(null);
            setText(name);
            boolean applied = name != null && savedProfile != null && name.equals(savedProfile.getName());
            // The currently loaded profile with unsaved (editor) edits carries
            // the trailing star — the node configuration panel's convention
            // (the same the module logging profile combo uses).
            if (applied && hasUnsavedChanges()) {
                setText(name + " *");
            }
            if (applied) {
                setIcon(appliedIcon);
                setIconTextGap(4);
                setForeground(isSelected ? list.getSelectionForeground() : GuiColors.getApplied());
            } else {
                setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            }
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            return this;
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-apply icons and recalculate sizes based on new font metrics
        updateProfileButtonsUI();
    }

    public JComboBox<String> getProfileComboBox() {
        return profileComboBox;
    }

    public boolean saveProfile() {
        String currentProfile = (String) profileComboBox.getSelectedItem();
        String suggestedName = currentProfile != null ? currentProfile : "";

        JTextField nameField = new JTextField(suggestedName);
        // Reserved names are rejected by saveProfileInternal().

        JPanel panel = new JPanel(new MigLayout("wrap 1, fillx, insets 0", "[grow]", "[]5[]5[]"));
        panel.add(new JLabel("Enter profile name:"));
        panel.add(nameField, "growx");

        JButton saveBtn = new JButton("Save");
        JButton discardBtn = new JButton("Discard");
        JButton cancelBtn = new JButton("Cancel");

        String report = getUnsavedChangesReport();
        if (report != null) {
            JLabel reportLabel = new JLabel(report);
            JScrollPane scroll = new JScrollPane(reportLabel);
            scroll.setPreferredSize(new Dimension(450, 150));
            scroll.setBorder(BorderFactory.createTitledBorder("Changes to be saved"));
            panel.add(scroll, "growx, gaptop 10");
        }

        Object[] options = { saveBtn, discardBtn, cancelBtn };

        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.YES_NO_CANCEL_OPTION, null,
                options, saveBtn);
        JDialog dialog = pane.createDialog(this, "Save Profile As");

        saveBtn.addActionListener(e -> {
            pane.setValue(saveBtn);
            dialog.setVisible(false);
        });
        discardBtn.addActionListener(e -> {
            pane.setValue(discardBtn);
            dialog.setVisible(false);
        });
        cancelBtn.addActionListener(e -> {
            pane.setValue(cancelBtn);
            dialog.setVisible(false);
        });

        Runnable validate = () -> {
            String text = nameField.getText().trim();
            saveBtn.setEnabled(!text.isEmpty());
            dialog.pack();
        };

        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validate.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validate.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validate.run();
            }
        });

        validate.run();

        try {
            while (true) {
                pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                dialog.setVisible(true);
                Object value = pane.getValue();

                if (value == saveBtn) {
                    String name = nameField.getText().trim();
                    if (saveProfileInternal(name, true)) {
                        return true;
                    }
                } else if (value == discardBtn) {
                    performLoadProfile(savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME);
                    return false;
                } else {
                    return false;
                }
            }
        } finally {
            dialog.dispose();
        }
    }

    private boolean saveProfileInternal(String name, boolean confirmOverwrite) {
        if (name == null || name.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Profile name must not be empty.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        name = name.trim();
        try {
            if (confirmOverwrite && profileRepository.getProfileDir().resolve(name + ".properties").toFile().exists()) {
                int choice = DialogUtils.confirm(this,
                        DialogUtils.actionIcon(FontAwesome.FLOPPY_O, GuiColors.getButtonIcon()),
                        "Override profile settings",
                        "Profile '" + name + "' already exists. Do you want to overwrite it?",
                        JOptionPane.YES_NO_OPTION, null, null);
                if (choice != JOptionPane.YES_OPTION) {
                    return false;
                }
            }

            // Build the profile from current UI state
            AppearanceProfile profileToSave = new AppearanceProfile(name);
            profileToSave.setThemeClassName(UIManager.getLookAndFeel().getClass().getName());
            Font font = UIManager.getFont("Label.font");
            profileToSave.setGlobalFont(font);
            Font consoleFont = UIManager.getFont("TextArea.font");
            profileToSave.setConsoleFont(consoleFont);
            Map<String, Color> overrides = colorSettingsPanel.getCurrentOverrides();
            profileToSave.setColorOverrides(overrides);

            profileRepository.saveProfile(name, profileToSave);

            // Persist lastSelectedLafProfile in gui-settings.json (app-level state)
            saveLastSelectedProfile(name);

            isProgrammaticChange = true;
            try {
                updateLoadedStateFromProfile(name);

                // Update combobox
                boolean exists = false;
                for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                    if (profileComboBox.getItemAt(i).equals(name)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    profileComboBox.addItem(name);
                }
                profileComboBox.setSelectedItem(name);
            } finally {
                isProgrammaticChange = false;
            }

            JOptionPane.showMessageDialog(this, "Profile '" + name + "' saved successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
            return true;

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            LOGGER.error("Failed to save appearance profile '{}'", name, e);
            return false;
        }
    }

    private void saveLastSelectedProfile(String name) {
        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }
            settings.addProperty("lastSelectedLafProfile", name);
            if (Files.notExists(settingsPath.getParent())) {
                Files.createDirectories(settingsPath.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to persist lastSelectedLafProfile", e);
        }
    }

    private void loadProfiles(JComboBox<String> comboBox) {
        try {
            for (String profileName : profileRepository.discoverProfiles()) {
                comboBox.addItem(profileName);
            }
            // Select the last profile from gui-settings.json (app-level state)
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lastSelectedLafProfile")) {
                        String lastSelected = settings.get("lastSelectedLafProfile").getAsString();
                        if (LEGACY_DEFAULT_PROFILE_NAME.equals(lastSelected)
                                && !comboHasItem(comboBox, LEGACY_DEFAULT_PROFILE_NAME)) {
                            lastSelected = DEFAULT_PROFILE_NAME;
                        }
                        if (comboHasItem(comboBox, lastSelected)) {
                            comboBox.setSelectedItem(lastSelected);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load appearance profiles from disk", e);
        }
    }

    private static boolean comboHasItem(JComboBox<String> comboBox, String name) {
        for (int i = 0; i < comboBox.getItemCount(); i++) {
            if (name.equals(comboBox.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    public void renameProfile(String oldProfileName) {
        if (oldProfileName == null || oldProfileName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No profile selected to rename.", "Rename Profile",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String newProfileName = (String) JOptionPane.showInputDialog(
                this,
                "Enter new name for profile '" + oldProfileName + "':",
                "Rename Profile",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                oldProfileName);

        if (newProfileName == null || newProfileName.trim().isEmpty()
                || newProfileName.trim().equals(oldProfileName)) {
            return; // User cancelled or entered the same name
        }
        newProfileName = newProfileName.trim();

        try {
            if (profileRepository.getProfileDir().resolve(newProfileName + ".properties").toFile().exists()) {
                JOptionPane.showMessageDialog(this, "A profile with the name '" + newProfileName + "' already exists.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!profileRepository.getProfileDir().resolve(oldProfileName + ".properties").toFile().exists()) {
                JOptionPane.showMessageDialog(this, "Profile '" + oldProfileName + "' not found.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            profileRepository.renameProfile(oldProfileName, newProfileName);

            // Update lastSelectedLafProfile if it was pointing to the old name
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lastSelectedLafProfile")
                            && settings.get("lastSelectedLafProfile").getAsString().equals(oldProfileName)) {
                        settings.addProperty("lastSelectedLafProfile", newProfileName);
                        try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            writer.write(gson.toJson(settings));
                        }
                    }
                }
            }

            updateLoadedStateFromProfile(newProfileName);
            // Update combobox
            profileComboBox.removeItem(oldProfileName);
            profileComboBox.addItem(newProfileName);
            profileComboBox.setSelectedItem(newProfileName);
            updateProfileButtonStates();

            JOptionPane.showMessageDialog(this,
                    "Profile '" + oldProfileName + "' renamed to '" + newProfileName + "' successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error renaming profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            LOGGER.error("Failed to rename appearance profile '{}' to '{}'", oldProfileName, newProfileName, e);
        }
    }

    public void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()
                || (savedProfile != null && profileName.equals(savedProfile.getName())))
            return;

        // reloadCurrentOnDiscard = false: on "Discard" the current profile must
        // NOT be reloaded first — the target profile's load fully re-specifies
        // the appearance (theme, fonts and colors are always applied or
        // explicitly reset), so the switch goes DIRECTLY to the selected
        // profile instead of briefly showing the current profile's saved
        // state in between.
        checkUnsavedChangesAndProceed(false, false,
                () -> performLoadProfile(profileName),
                () -> profileComboBox
                        .setSelectedItem(savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME));
    }

    public boolean checkUnsavedChangesAndProceed(boolean allowKeepUnsaved, Runnable onProceed, Runnable onCancel) {
        return checkUnsavedChangesAndProceed(allowKeepUnsaved, true, onProceed, onCancel);
    }

    /**
     * Like {@link #checkUnsavedChangesAndProceed(boolean, Runnable, Runnable)}, but with control over
     * what "Discard" does: when {@code reloadCurrentOnDiscard} is {@code true} (the default) the
     * current profile is reloaded from disk first (restoring its saved state); when {@code false}
     * the discard is implicit — the caller's {@code onProceed} continuation is expected to fully
     * re-specify the appearance (e.g. by loading another profile), so no intermediate reload
     * of the current profile happens.
     */
    private boolean checkUnsavedChangesAndProceed(boolean allowKeepUnsaved, boolean reloadCurrentOnDiscard,
            Runnable onProceed, Runnable onCancel) {
        String report = getUnsavedChangesReport();
        if (report == null) {
            if (onProceed != null)
                onProceed.run();
            return true;
        }

        Object[] message = {
                "You have unsaved changes in profile '"
                        + (savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME) + "'.",
                report,
                "What would you like to do?"
        };
        Object[] options;
        if (allowKeepUnsaved) {
            options = new Object[] { "Save Profile As", "Keep Unsaved", "Discard", "Cancel" };
        } else {
            options = new Object[] { "Save Profile As", "Discard", "Cancel" };
        }

        int result = JOptionPane.showOptionDialog(this, message, "Unsaved Changes",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);

        if (result == 0) { // Save Profile As
            if (saveProfile()) {
                if (onProceed != null)
                    onProceed.run();
                return true;
            }
            return false;
        }

        int discardIndex = allowKeepUnsaved ? 2 : 1;
        int keepUnsavedIndex = 1;

        if (allowKeepUnsaved && result == keepUnsavedIndex) {
            if (onProceed != null)
                onProceed.run();
            return true;
        } else if (result == discardIndex) {
            if (reloadCurrentOnDiscard) {
                // "Discard" restores the current profile's saved state in the UI.
                // (When false, the onProceed continuation fully re-specifies the
                // appearance — see the 4-arg overload's javadoc.)
                performLoadProfile(savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME);
            }
            if (onProceed != null) {
                SwingUtilities.invokeLater(onProceed);
            }
            return true;
        } else { // Cancel
            if (onCancel != null)
                onCancel.run();
            return false;
        }
    }

    private String getUnsavedChangesReport() {
        if (savedProfile == null)
            return null;
        StringBuilder report = new StringBuilder(
                "<html><b>Unsaved changes in Look and Feel Settings (Profile: '" + savedProfile.getName()
                        + "'):</b><ul>");
        boolean changesFound = false;

        // Check theme
        String currentTheme = UIManager.getLookAndFeel().getClass().getName();
        if (!currentTheme.equals(savedProfile.getThemeClassName())) {
            changesFound = true;
            String oldName = getLookAndFeelName(savedProfile.getThemeClassName());
            String newName = UIManager.getLookAndFeel().getName();
            report.append("<li>Theme: '").append(oldName).append("' &rarr; '").append(newName).append("'</li>");
        }

        // Check global font
        Font currentGlobal = AppearanceModule.getActiveCustomFont();
        if (!fontsMatch(currentGlobal, savedProfile.getGlobalFont())) {
            changesFound = true;
            report.append("<li>Global font: '").append(formatFont(savedProfile.getGlobalFont())).append("' &rarr; '")
                    .append(formatFont(currentGlobal)).append("'</li>");
        }

        // Check console font
        Font currentConsole = AppearanceModule.getActiveConsoleFont();
        if (!fontsMatch(currentConsole, savedProfile.getConsoleFont())) {
            changesFound = true;
            report.append("<li>Console font: '").append(formatFont(savedProfile.getConsoleFont())).append("' &rarr; '")
                    .append(formatFont(currentConsole)).append("'</li>");
        }

        // Check colors
        Map<String, Color> currentColors = colorSettingsPanel != null ? colorSettingsPanel.getCurrentOverrides()
                : Collections.emptyMap();
        if (!currentColors.equals(savedProfile.getColorOverrides())) {
            changesFound = true;
            report.append("<li>Color modifications:<ul>");

            // Find added or modified overrides
            for (Map.Entry<String, Color> entry : currentColors.entrySet()) {
                String key = entry.getKey();
                Color newVal = entry.getValue();
                Color oldVal = savedProfile.getColorOverrides().get(key);

                if (oldVal == null) {
                    Color themeColor = ColorPaletteManager.getThemeColor(key);
                    report.append("<li>").append(key).append(": default ").append(toHexString(themeColor))
                            .append(" &rarr; ").append(toHexString(newVal)).append("</li>");
                } else if (!oldVal.equals(newVal)) {
                    report.append("<li>").append(key).append(": '").append(toHexString(oldVal)).append("' &rarr; '")
                            .append(toHexString(newVal)).append("'</li>");
                }
            }

            // Find removed overrides (reverted to palette default)
            for (String key : savedProfile.getColorOverrides().keySet()) {
                if (!currentColors.containsKey(key)) {
                    Color themeColor = ColorPaletteManager.getThemeColor(key);
                    report.append("<li>").append(key).append(": '")
                            .append(toHexString(savedProfile.getColorOverrides().get(key)))
                            .append("' &rarr; default ").append(toHexString(themeColor)).append("</li>");
                }
            }
            report.append("</ul></li>");
        }

        report.append("</ul></html>");
        return changesFound ? report.toString() : null;
    }

    private String getLookAndFeelName(String className) {
        if (className == null) {
            className = FlatDarkLaf.class.getName();
        }
        for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
            if (laf.getClassName().equals(className)) {
                return laf.getName();
            }
        }
        // Fallback to simple class name if not found in installed LAFs
        int lastDot = className.lastIndexOf('.');
        return lastDot != -1 ? className.substring(lastDot + 1) : className;
    }

    private String formatFont(Font f) {
        if (f == null)
            return "Default";
        return f.getFamily() + " " + f.getSize();
    }

    private void performLoadProfile(String profileName) {
        // Let the current selector/button event finish before FlatLaf captures
        // the visible windows for its animated theme transition.
        SwingUtilities.invokeLater(() -> performLoadProfileNow(profileName));
    }

    private void performLoadProfileNow(String profileName) {
        LOGGER.info("Loading Look and Feel profile: '{}'", profileName);
        isProgrammaticChange = true;
        boolean snapshotShown = false;
        try {
            if (DEFAULT_PROFILE_NAME.equals(profileName)) {
                resetToDefaultInternal();
                saveLastSelectedProfile(DEFAULT_PROFILE_NAME);
                profileComboBox.setSelectedItem(DEFAULT_PROFILE_NAME);
                updateLoadedStateFromProfile(DEFAULT_PROFILE_NAME);
                return;
            }

            AppearanceProfile profile = profileRepository.loadProfile(profileName);
            if (profile == null) {
                throw new IllegalArgumentException("Appearance profile '" + profileName + "' was not found.");
            }

            FlatAnimatedLafChange.showSnapshot();
            snapshotShown = true;

            String themeClassName = profile.getThemeClassName();
            if (themeClassName != null) {
                UIManager.setLookAndFeel(themeClassName);
            }

            AppearanceModule.updateCommonFontKeys(profile.getGlobalFont());
            AppearanceModule.updateCommonConsoleFontKeys(profile.getConsoleFont());

            AppearanceModule.updateAllUIs();
            colorSettingsPanel.setProfileOverrides(profile.getColorOverrides());
            updateLoadedStateFromProfile(profileName);
            profileComboBox.setSelectedItem(profileName);

            // Persist lastSelectedLafProfile before hiding the snapshot so
            // the selector and all other visible state belong to the same
            // frame as the newly applied theme.
            saveLastSelectedProfile(profileName);

            FlatAnimatedLafChange.hideSnapshotWithAnimation();
            snapshotShown = false;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to load appearance profile '{}'", profileName, e);
            JOptionPane.showMessageDialog(this, "Error loading profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            if (snapshotShown) {
                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            }
            isProgrammaticChange = false;
            updateDirtyStatus();
        }
    }

    private void resetToDefault() {
        SwingUtilities.invokeLater(this::resetToDefaultNow);
    }

    private void resetToDefaultNow() {
        isProgrammaticChange = true;
        try {
            resetToDefaultInternal();
        } finally {
            isProgrammaticChange = false;
            updateDirtyStatus();
        }
    }

    private void resetToDefaultInternal() {
        boolean snapshotShown = false;
        try {
            FlatAnimatedLafChange.showSnapshot();
            snapshotShown = true;
            // Reset to a known default theme, e.g., FlatDarkLaf
            UIManager.setLookAndFeel(FlatDarkLaf.class.getName());
            // Reset font
            AppearanceModule.updateCommonFontKeys(null);
            AppearanceModule.updateCommonConsoleFontKeys(null);
            // Reset colors
            ColorPaletteManager.updatePalette(null);
            colorSettingsPanel.setProfileOverrides(null);
            AppearanceModule.updateAllUIs();
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
            snapshotShown = false;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error resetting to default: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            if (snapshotShown) {
                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            }
        }

    }

    private static String toHexString(Color color) {
        if (color.getAlpha() == 255) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        } else {
            return String.format("#%02X%02X%02X%02X", color.getAlpha(), color.getRed(), color.getGreen(),
                    color.getBlue());
        }
    }

    private void showProfileHelp() {
        JPanel help = new JPanel(new MigLayout("wrap 2, insets 8, gapx 10, gapy 6", "[][grow]", ""));
        help.add(new JLabel("<html><h2>Look and Feel Profiles</h2>"
                + "Appearance profiles save and load themes, fonts and color settings.</html>"), "span 2, growx, wrap");

        addProfileHelpRow(help, newProfileBtn, "New Profile",
                "Creates a new profile initialized with the application default appearance.");
        addProfileHelpRow(help, cloneProfileBtn, "Clone to New Profile",
                "Creates a new profile from the current appearance, including unsaved changes.");
        addProfileHelpRow(help, copyProfileBtn, "Copy Configuration",
                "Loads another profile into the current editor as unsaved changes.");
        addProfileHelpRow(help, resetToDefaultBtn, "Reset to Defaults",
                "Copies the application's hardcoded default values into the current editor (unsaved). "
                        + "Press Save to persist.");
        addProfileHelpRow(help, renameProfileBtn, "Rename Profile",
                "Renames the selected profile.");
        addProfileHelpRow(help, saveProfileBtn, "Save Profile",
                "Saves the current appearance to the selected profile.");
        addProfileHelpRow(help, reloadProfileBtn, "Refresh",
                "Refreshes the profile list and reloads the selected profile from disk.");
        addProfileHelpRow(help, deleteProfileBtn, "Delete Profile",
                "Permanently deletes the selected profile.");

        help.add(new JLabel("<html>Profiles are stored as <code>.properties</code> files in "
                + "<code>conf/appearance/profiles/</code>.</html>"), "span 2, gaptop 4, wrap");
        JScrollPane scroll = new JScrollPane(help);
        scroll.setPreferredSize(new Dimension(480, 420));
        JOptionPane.showOptionDialog(this, scroll, "About Look and Feel Profiles",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                new String[] { "OK" }, "OK");
    }

    private void addProfileHelpRow(JPanel help, JButton actionButton, String title, String description) {
        addProfileHelpRow(help, actionButton.getIcon(), title, description);
    }

    private void addProfileHelpRow(JPanel help, Icon icon, String title, String description) {
        JLabel iconLabel = new JLabel(icon);
        iconLabel.setVerticalAlignment(SwingConstants.TOP);
        help.add(iconLabel, "aligny top");
        help.add(new JLabel("<html><b>" + title + "</b><br>" + description + "</html>"), "growx, wrap");
    }

    public void deleteProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }

        int choice = DialogUtils.confirm(this,
                DialogUtils.actionIcon(FontAwesome.TRASH_O, GuiColors.getContrastRed()),
                "Confirm Deletion",
                "Are you sure you want to delete profile '" + profileName + "'?",
                JOptionPane.YES_NO_OPTION, null, null);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            profileRepository.deleteProfile(profileName);

            // Update lastSelectedLafProfile if it was pointing to the deleted profile
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lastSelectedLafProfile")
                            && settings.get("lastSelectedLafProfile").getAsString().equals(profileName)) {
                        settings.addProperty("lastSelectedLafProfile", DEFAULT_PROFILE_NAME);
                        try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            writer.write(gson.toJson(settings));
                        }
                    }
                }
            }

            String selectedBeforeDelete = (String) profileComboBox.getSelectedItem();
            isProgrammaticChange = true;
            try {
                profileComboBox.removeItem(profileName);
                if (profileName.equals(selectedBeforeDelete)) {
                    profileComboBox.setSelectedItem(DEFAULT_PROFILE_NAME);
                }
            } finally {
                isProgrammaticChange = false;
            }
            if (profileName.equals(selectedBeforeDelete)) {
                performLoadProfile(DEFAULT_PROFILE_NAME);
            }
            updateProfileButtonStates();
            JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' deleted successfully.",
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error deleting profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            LOGGER.error("Failed to delete appearance profile '{}'", profileName, e);
        }
    }

    private Path getGuiSettingsPath() {
        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        return PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
    }

    // ====================================================================
    // GPU Acceleration setting persistence
    // ====================================================================

    /**
     * Loads the GPU acceleration setting from gui-settings.json.
     * Reads from nested "appearance.enableGPU" or root-level "enableGPU".
     */
    private boolean loadGpuAccelerationSetting() {
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    // Try nested "appearance" object first
                    if (settings.has("appearance") && settings.get("appearance").isJsonObject()) {
                        JsonObject appearanceObj = settings.getAsJsonObject("appearance");
                        if (appearanceObj.has("enableGPU")) {
                            return appearanceObj.get("enableGPU").getAsBoolean();
                        }
                    }
                    // Fallback to root level for backward compatibility
                    if (settings.has("enableGPU")) {
                        return settings.get("enableGPU").getAsBoolean();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load GPU acceleration setting", e);
        }
        return false; // Default: disabled
    }

    /**
     * Saves the GPU acceleration setting to gui-settings.json.
     * Stores in nested "appearance.enableGPU" for organization.
     */
    private void saveGpuAccelerationSetting(boolean enabled) {
        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }

            // Ensure nested "appearance" object exists
            if (!settings.has("appearance") || !settings.get("appearance").isJsonObject()) {
                settings.add("appearance", new JsonObject());
            }
            settings.getAsJsonObject("appearance").addProperty("enableGPU", enabled);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                writer.write(gson.toJson(settings));
            }

            LOGGER.info("GPU acceleration setting saved: {}", enabled);
        } catch (Exception e) {
            LOGGER.error("Failed to save GPU acceleration setting", e);
            JOptionPane.showMessageDialog(this,
                    "Error saving GPU acceleration setting: " + e.getMessage(),
                    "Save Error", JOptionPane.ERROR_MESSAGE);
            // Restore checkbox state on failure
            gpuAccelerationCheckBox.setSelected(!enabled);
        }
    }

    private boolean fontsMatch(Font a, Font b) {
        if (a == b)
            return true;
        if (a == null || b == null) {
            // If one is null, consider them matching if the other is the system default
            // font
            Font nonNull = (a != null) ? a : b;
            Font systemDefault = UIManager.getFont("Label.font");
            if (systemDefault == null)
                return false;
            return nonNull.getFamily().equals(systemDefault.getFamily()) &&
                    nonNull.getSize() == systemDefault.getSize() &&
                    nonNull.getStyle() == systemDefault.getStyle();
        }
        return a.getFamily().equals(b.getFamily()) && a.getSize() == b.getSize() && a.getStyle() == b.getStyle();
    }
}