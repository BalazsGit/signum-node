package application.module.node.gui.configuration;

import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.database.utils.DatabaseConfigurationUtils.DbInstance;
import application.module.database.utils.DatabaseConfigurationUtils.DbProfile;
import application.module.database.utils.DatabaseConfigurationUtils.DbUser;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.SqliteDatabasePaths;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.ComboBoxItemToolTips;
import application.utils.gui.GuiColors;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/**
 * The unified JDBC database configuration editor of a node profile's
 * {@code DB.Url} row — a single engine- and link-mode-aware panel that
 * replaces the former CardLayout-based manual / profile cards.
 * <p>
 * One set of rows, one URL-composition SSOT, one value-state model:
 * </p>
 * <ul>
 *   <li><b>Manual mode</b> (no database-profile link): SQLite composes the URL
 *       from an editable <i>Path</i> + <i>DB file</i> (discovered {@code *.db}
 *       files of the path); server engines compose it from <i>Host</i>/
 *       <i>Port</i>/<i>Database</i>/<i>Suffix</i> + credentials. The Profile row
 *       does not exist.</li>
 *   <li><b>Linked mode</b> (a database profile is linked,
 *       {@code "Engine:Profile"}): the profile selects the configuration —
 *       Path is read-only (the convention directory), the DB file is picked
 *       from the profile's {@code *.db} files (SQLite) or the database
 *       instance is picked from the profile's instance list (server engines),
 *       and the host/port/suffix/user/password rows are derived, read-only,
 *       from the instance.</li>
 *   <li><b>Color coding</b>: every row is colored against the (saved, applied)
 *       {@link JdbcState} baselines — the Profile row by its own
 *       link-comparison, every other row by the full tuple. The former
 *       URL-segment comparison is gone.</li>
 * </ul>
 * <p>
 * The panel is presentation + effective-state only: it never persists
 * anything. The host persists via the {@link LinkChangeListener} callback
 * (fired after every user- or auto-initiated link change) and reads the
 * effective state via {@link #getState()} / the individual getters.
 * </p>
 */
public class JdbcConfigurationPanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcConfigurationPanel.class);

    /**
     * The value-state of a configuration row against the saved (profile-on-disk)
     * and applied (the loaded baseline the node runs with) values.
     */
    public enum ValueState {
        APPLIED, SAVED, UNSAVED
    }

    /**
     * The effective JDBC connection state — the single comparison subject of
     * the row color coding: {@code (link, url, username, password)}, every
     * element trimmed and null-safe.
     */
    public record JdbcState(String link, String url, String username, String password) {
        public JdbcState {
            link = link == null ? "" : link.trim();
            url = url == null ? "" : url.trim();
            username = username == null ? "" : username.trim();
            password = password == null ? "" : password.trim();
        }

        /**
         * The {@code "Engine:Profile"} link value, or the empty string when
         * there is no profile (manual mode).
         */
        public static String linkOf(DatabaseConfigurationPanel.DatabaseEngine engine, String profileName) {
            if (engine == null || profileName == null || profileName.trim().isEmpty()) {
                return "";
            }
            return engine.toString() + ":" + profileName.trim();
        }
    }

    /**
     * Notified after every effective (user- or auto-initiated) link change
     * with the panel's current state. The host persists {@code state.link()}
     * and syncs its "Linked Database Profile" checkbox from
     * {@link #isLinkedMode()}.
     */
    public interface LinkChangeListener {
        void linkChanged(JdbcConfigurationPanel panel, JdbcState state);
    }

    private final String confFolder;
    private final Runnable onChange;
    private final LinkChangeListener onLinkChange;

    // --- Row components (layout order) ---
    private final JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo;
    /** The linked database profile (declared field — part of the test contract). */
    private final JComboBox<String> profileCombo;
    private final JComboBox<String> pathCombo;
    /** The DB file (SQLite) / database instance (linked server) select. */
    private final JComboBox<Object> dbCombo;
    private final JTextField databaseField;
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField suffixField;
    private final JTextField userField;
    private final JComboBox<DbUser> userCombo;
    private final JPasswordField passField;
    private final JTextField previewField;

    // --- Row labels ---
    private final JLabel engineLabel, profileLabel, pathLabel, dbLabel;
    private final JLabel hostLabel, portLabel, suffixLabel, userLabel, passLabel;

    // --- Slot overlays (one visual row, two implementations) ---
    private final JPanel dbSlot;      // "FILE" = dbCombo, "TEXT" = databaseField
    private final JPanel userSlot;    // "FIELD" = userField, "COMBO" = userCombo

    // --- State ---
    private boolean linked = false;
    private int programmaticDepth = 0;
    private String originalUrl = "";
    private String originalUsername = "";
    private String originalPassword = "";
    private boolean manualFieldsTouched = false;
    /** Property-derived baseline retained for untouched manual configurations. */
    private String savedUrl = "";
    private String savedUsername = "";
    private String savedPassword = "";
    private DbProfile currentProfile;
    private JdbcState savedState = new JdbcState("", "", "", "");
    private JdbcState appliedState = new JdbcState("", "", "", "");

    /**
     * The last effective state the panel showed for an engine: mode + link +
     * URL + credentials.
     */
    private record EngineSnapshot(boolean linked, String link, String url, String username,
            String password) {
    }

    /**
     * The per-engine working state remembered across engine switches, so
     * switching away and back restores the user's selections — including
     * changes that were never saved — instead of re-deriving them from the
     * baseline (the baseline is only the first-visit fallback).
     */
    private final Map<DatabaseConfigurationPanel.DatabaseEngine, EngineSnapshot> engineSnapshots =
            new EnumMap<>(DatabaseConfigurationPanel.DatabaseEngine.class);
    /** The engine the panel showed before the most recent engine change. */
    private DatabaseConfigurationPanel.DatabaseEngine lastEngine = DatabaseConfigurationPanel.DatabaseEngine.SQLITE;

    /**
     * The last effective (file/instance, user, password) selection the panel
     * showed for a profile, keyed by its link ({@code "Engine:Profile"}).
     */
    private record ProfileSnapshot(String url, String username, String password) {
    }

    /**
     * The per-profile working state remembered across profile and engine
     * switches: the DB file / instance and the credentials the user left on
     * that profile (an unsaved file pick must survive a profile toggle or an
     * engine round trip, like the per-engine state).
     */
    private final Map<String, ProfileSnapshot> profileSnapshots = new HashMap<>();

    /** The link the panel showed before the most recent profile change. */
    private String lastProfileLink = "";

    /**
     * @param confFolder   the base configuration folder (discovery goes
     *                     through {@link DatabaseConfigurationUtils}, which
     *                     resolves the portable {@code ./database/<Engine>}/
     *                     area itself)
     * @param onChange     notified after every effective field/URL change
     *                     (the host refreshes colors/dirty state); may be
     *                     {@code null}
     * @param onLinkChange notified after every effective link change (see
     *                     {@link LinkChangeListener}); may be {@code null}
     */
    public JdbcConfigurationPanel(String confFolder, Runnable onChange, LinkChangeListener onLinkChange) {
        super(new MigLayout("insets 0, fillx, gap 0 2, hidemode 3", "[][grow]", ""));
        this.confFolder = confFolder;
        this.onChange = onChange;
        this.onLinkChange = onLinkChange;
        setOpaque(false);

        engineCombo = new JComboBox<>(DatabaseConfigurationPanel.DatabaseEngine.values());
        engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
        profileCombo = new JComboBox<>();
        profileCombo.setEditable(true);
        pathCombo = new JComboBox<>();
        pathCombo.setEditable(true);
        pathCombo.getEditor().setItem("./database/SQLite");
        dbCombo = new JComboBox<>();
        dbCombo.setEditable(true);
        databaseField = new JTextField();
        hostField = new JTextField("localhost");
        portField = new JTextField();
        suffixField = new JTextField();
        userField = new JTextField();
        userCombo = new JComboBox<>();
        // The FlatLaf "reveal password" eye button comes from the global
        // UIManager setting (GuiManager#applyDefaultsAfterLaf) — no show/hide
        // checkbox.
        passField = new JPasswordField();
        // The composed-URL preview: selectable/copyable but read-only — a
        // non-editable text field styled exactly like the other rows.
        JTextField preview = new JTextField();
        preview.setEditable(false);
        previewField = preview;

        engineLabel = label("Engine:");
        profileLabel = label("Profile:");
        pathLabel = label("Path:");
        // One shared row for the SQLite file select and the server database
        // text field (dbSlot cards); the label text follows the engine. The
        // slot must live in a SINGLE grid row — adding the same component to
        // two rows skews MigLayout's preferred-height calculation (dead space
        // under the preview; guarded by NodeConfigurationPanelJdbcCardHeightTest).
        dbLabel = label("DB file:");
        hostLabel = label("Host:");
        portLabel = label("Port:");
        suffixLabel = label("Suffix:");
        userLabel = label("Username:");
        passLabel = label("Password:");

        dbSlot = slot("FILE", dbCombo, "TEXT", databaseField);
        userSlot = slot("FIELD", userField, "COMBO", userCombo);

        style(engineCombo);
        style(profileCombo);
        style(pathCombo);
        style(dbCombo);
        style(databaseField);
        style(hostField);
        style(portField);
        style(suffixField);
        style(userField);
        style(userCombo);
        style(passField);
        stylePreviewText(previewField);
        // The EDITABLE combos get the same editor unification (no inner L&F
        // field border, plain-combo text margin and preferred height) as the
        // configuration's state-aware dropdowns — so the db.url section's
        // dropdowns behave exactly like every other one. (engineCombo and
        // userCombo are non-editable and keep the L&F's own chrome.)
        ConfigurationUtils.styleComboEditor(profileCombo);
        ConfigurationUtils.styleComboEditor(pathCombo);
        ConfigurationUtils.styleComboEditor(dbCombo);

        addRow(engineLabel, engineCombo);
        addRow(profileLabel, profileCombo);
        addRow(pathLabel, pathCombo);
        addRow(dbLabel, dbSlot);
        addRow(hostLabel, hostField);
        addRow(portLabel, portField);
        addRow(suffixLabel, suffixField);
        addRow(userLabel, userSlot);
        addRow(passLabel, passField);
        addRow(label("JDBC URL:"), previewField);

        installListeners();
        installStateRenderers();
        refreshSqlitePathOptions();
        applyModeVisibility();
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setOpaque(false);
        return l;
    }

    private int rowCount = 0;

    private void addRow(JLabel label, JComponent field) {
        // The 2 px row gap goes on each row's TOP (not a layout-level default
        // gap, which MigLayout would also count 2 px of before the first row
        // into the preferred height — dead space under the preview; guarded
        // by NodeConfigurationPanelJdbcCardHeightTest).
        add(label, rowCount++ == 0 ? "align label" : "align label, gaptop 2");
        // The explicit wrap is load-bearing: without it MigLayout opens a new
        // COLUMN for the next label instead of a new row (all fields end up in
        // a single horizontal line).
        add(field, "growx, wrap");
    }

    /** A CardLayout slot: the named cards share one layout cell. */
    private static JPanel slot(String cardA, JComponent a, String cardB, JComponent b) {
        JPanel slot = new JPanel(new CardLayout());
        slot.setOpaque(false);
        slot.add(a, cardA);
        slot.add(b, cardB);
        return slot;
    }

    private static void style(JComponent c) {
        ConfigurationUtils.styleInputComponent(c);
    }

    /**
     * The composed-URL preview: selectable/copyable but read-only — NO
     * text-box frame around it (the requirement: the URL must stay
     * selectable WITHOUT looking like an input field). It keeps the unified
     * input metrics (the same font, the same one-line height and the same
     * left text margin as the other rows, so the preview text aligns exactly
     * with the fields' text), but its border is EMPTY — there is no L&F
     * field border to draw a box around the URL. The field stays
     * non-editable (so it can never be typed into) while remaining fully
     * selectable and copyable, and transparent (no field background).
     */
    private static void stylePreviewText(JTextField field) {
        // Unified font + one-line height + left text margin — derived the
        // SAME way as the real text inputs, but with an EMPTY border: the
        // L&F border is only used to MEASURE the natural height (and to
        // align the left inset with the plain-combo text x), never to paint.
        // Its vertical insets are CARRIED INTO the empty border, so the
        // frameless preview keeps exactly the unified one-line height.
        field.setFont(UIManager.getFont("Label.font"));
        Border fieldBorder = UIManager.getBorder("TextField.border");
        field.setBorder(fieldBorder);
        int naturalH = field.getPreferredSize().height;
        int extra = Math.max(0, ConfigurationUtils.standardControlHeight() - naturalH);
        int top = extra / 2;
        int bottom = extra - top;
        java.awt.Insets bi = fieldBorder == null ? new java.awt.Insets(0, 0, 0, 0) : fieldBorder.getBorderInsets(field);
        int fieldLeft = bi.left;
        int left = Math.max(0, ConfigurationUtils.plainComboTextX() - fieldLeft);
        field.setBorder(BorderFactory.createEmptyBorder(bi.top + top, fieldLeft + left, bi.bottom + bottom, 6));
        field.setEditable(false);
        field.setOpaque(false);
    }

    /**
     * Re-applies the one-line control unification (the unified border /
     * height of every text input and the editor padding of the editable
     * selects) from the LIVE metrics — the call the host's
     * {@code updateUI()} makes after an L&F / font change, so the section's
     * controls re-derive their heights from the NEW font exactly like the
     * rest of the configuration.
     */
    public void reunifyControlStyles() {
        style(engineCombo);
        style(profileCombo);
        style(pathCombo);
        style(dbCombo);
        style(databaseField);
        style(hostField);
        style(portField);
        style(suffixField);
        style(userField);
        style(userCombo);
        style(passField);
        stylePreviewText(previewField);
        ConfigurationUtils.styleComboEditor(profileCombo);
        ConfigurationUtils.styleComboEditor(pathCombo);
        ConfigurationUtils.styleComboEditor(dbCombo);
        revalidate();
    }

    private void showSlot(JPanel slot, String card) {
        ((CardLayout) slot.getLayout()).show(slot, card);
    }

    private boolean isProgrammatic() {
        return programmaticDepth > 0;
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    private void installListeners() {
        engineCombo.addActionListener(e -> {
            if (isProgrammatic()) {
                // Track programmatic engine changes (applyLink, loadInitial)
                // too, so a later user switch snapshots the outgoing state
                // under the right key.
                lastEngine = getEngine();
                return;
            }
            // Remember the state we are leaving NOW (synchronously): the
            // combo already holds the NEW engine, so evaluate the effective
            // state with the previous one — every other widget (profile,
            // file/instance, fields) still holds the outgoing engine's
            // values. A later switch back restores these selections, saved
            // or not; the profile we are leaving remembers its own
            // file/instance + user too (per-profile granularity).
            Object incoming = engineCombo.getSelectedItem();
            programmaticDepth++;
            try {
                engineCombo.setSelectedItem(lastEngine);
                engineSnapshots.put(lastEngine,
                        new EngineSnapshot(linked, getLink(), getJdbcUrl(), getUsername(), getPassword()));
                if (!lastProfileLink.isEmpty()) {
                    profileSnapshots.put(lastProfileLink,
                            new ProfileSnapshot(getJdbcUrl(), getUsername(), getPassword()));
                }
            } finally {
                engineCombo.setSelectedItem(incoming);
                programmaticDepth--;
            }
            // Defer the (heavier) follow-up work until after this event returns:
            // doing discovery/repaint while the combo's popup is still closing
            // leaves the popup open (e.g. SQLite -> MariaDB -> SQLite).
            SwingUtilities.invokeLater(() -> {
                EngineSnapshot restore = engineSnapshots.get(getEngine());
                programmaticDepth++;
                try {
                    if (restore != null && restore.linked() != linked) {
                        // The link mode the user left on this engine may differ
                        // from the current one (the host's checkbox was
                        // toggled in between) — restore it (the link change
                        // below syncs the host's checkbox).
                        setLinkedInternal(restore.linked());
                    }
                    if (linked) {
                        // The link survives an engine switch: the profile list
                        // is re-derived for the new engine. The remembered
                        // profile of THAT engine wins (the user's last
                        // selection, saved or unsaved); without one, the
                        // baseline link of that engine (saved first, then
                        // applied) restores the recorded profile — an engine
                        // toggle that changed nothing must not drift to the
                        // first folder entry.
                        refreshProfileList();
                        String profile = profileOfLink(restore != null ? restore.link() : "");
                        if (profile == null || !comboContains(profileCombo, profile)) {
                            profile = recordedProfileForEngine(getEngine(), savedState);
                            if (profile == null) {
                                profile = recordedProfileForEngine(getEngine(), appliedState);
                            }
                            if (profile != null && !comboContains(profileCombo, profile)) {
                                profile = null;
                            }
                        }
                        if (profile != null) {
                            profileCombo.setSelectedItem(profile);
                        } else if (profileCombo.getSelectedItem() == null && profileCombo.getItemCount() > 0) {
                            profileCombo.setSelectedItem(profileCombo.getItemAt(0));
                        }
                        refreshInstanceList();
                        // The file/instance (and user) to select: the one
                        // remembered for the (restored) profile, else the
                        // remembered URL/username of this engine, else the
                        // baseline recorded with the (restored) link, else the
                        // current values.
                        String preferredUrl = getJdbcUrl();
                        String preferredUser = getUsername();
                        ProfileSnapshot remembered = profileSnapshots.get(getLink());
                        if (remembered != null) {
                            preferredUrl = remembered.url();
                            preferredUser = remembered.username();
                        } else if (restore != null) {
                            preferredUrl = restore.url();
                            preferredUser = restore.username();
                        } else {
                            JdbcState matching = baselineOfLink(getLink());
                            if (matching != null) {
                                preferredUrl = matching.url();
                                preferredUser = matching.username();
                            }
                        }
                        selectInstanceUser(preferredUrl, preferredUser);
                        lastProfileLink = getLink();
                    } else if (restore != null) {
                        // The manual values of this engine (typed or committed)
                        // come back with the engine.
                        loadManual(restore.url(), restore.username(), restore.password());
                    }
                } finally {
                    programmaticDepth--;
                }
                fireLinkChanged();
                applyModeVisibility();
                // The manual Path's *.db files — manual SQLite only (in linked
                // mode the DB file select belongs to the profile).
                if (isSqlite() && !linked) {
                    refreshSqliteFiles(Path.of(pathSelection()),
                            (String) dbCombo.getSelectedItem(), true);
                }
                fireChange();
                lastEngine = getEngine();
            });
        });

        profileCombo.addActionListener(e -> {
            if (isProgrammatic() || !linked) {
                return;
            }
            // Remember the file/instance + user of the profile we are leaving,
            // so a later revisit (a profile toggle or an engine round trip)
            // restores the user's choice — saved or not.
            if (!lastProfileLink.isEmpty()) {
                profileSnapshots.put(lastProfileLink,
                        new ProfileSnapshot(getJdbcUrl(), getUsername(), getPassword()));
            }
            refreshInstanceList();
            // The file/instance (and user) to select: the one remembered for
            // this profile, else the baseline recorded with its link, else
            // the current values.
            String preferredUrl = getJdbcUrl();
            String preferredUser = getUsername();
            ProfileSnapshot remembered = profileSnapshots.get(getLink());
            if (remembered != null) {
                preferredUrl = remembered.url();
                preferredUser = remembered.username();
            } else {
                JdbcState matching = baselineOfLink(getLink());
                if (matching != null) {
                    preferredUrl = matching.url();
                    preferredUser = matching.username();
                }
            }
            selectInstanceUser(preferredUrl, preferredUser);
            lastProfileLink = getLink();
            applyModeVisibility();
            fireLinkChanged();
        });

        textChanges((JTextField) pathCombo.getEditor().getEditorComponent(), () -> {
            if (isProgrammatic()) {
                return;
            }
            manualFieldsTouched = true;
            refreshSqliteFiles(Path.of(pathSelection()),
                    (String) dbCombo.getSelectedItem(), true);
            fireChange();
        });

        textChanges(databaseField, this::markManualEdit);
        textChanges(hostField, this::markManualEdit);
        textChanges(portField, this::markManualEdit);
        textChanges(suffixField, this::markManualEdit);
        textChanges(userField, this::markManualEdit);
        textChanges(passField, this::markManualEdit);
        textChanges((JTextField) profileCombo.getEditor().getEditorComponent(), () -> {
            if (isProgrammatic() || !linked) {
                return;
            }
            // Live recolor/dirty signal only (like the other text fields):
            // firing a LINK change here would commit (persist) the in-flight
            // half-typed name and overwrite the saved baseline. The link
            // itself commits on Enter/selection (the Action listener), which
            // refreshes the instance list and lets the host persist it.
            fireChange();
        });
        textChanges((JTextField) dbCombo.getEditor().getEditorComponent(), () -> {
            if (isProgrammatic()) {
                return;
            }
            if (isSqlite()) {
                if (!linked) {
                    manualFieldsTouched = true;
                }
            }
            fireChange();
        });

        dbCombo.addActionListener(e -> {
            if (isProgrammatic()) {
                return;
            }
            if (linked && !isSqlite()) {
                refreshUserList();
            }
            if (isSqlite()) {
                if (!linked) {
                    manualFieldsTouched = true;
                }
            }
            fireChange();
        });

        pathCombo.addActionListener(e -> {
            if (isProgrammatic() || linked || !isSqlite()) {
                return;
            }
            manualFieldsTouched = true;
            refreshSqliteFiles(Path.of(pathSelection()),
                    sqliteFileSelection(), true);
            fireChange();
        });

        userCombo.addActionListener(e -> {
            if (isProgrammatic()) {
                return;
            }
            syncPasswordFromUser();
            fireChange();
        });
    }

    private void textChanges(JTextField field, Runnable onUserChange) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onUserChange.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onUserChange.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onUserChange.run();
            }
        });
    }

    /** Refreshes the preview text and notifies the host of an effective change. */
    private void fireChange() {
        updatePreviewText();
        if (onChange != null) {
            onChange.run();
        }
    }

    /**
     * Marks a manual-mode field as user-edited (so a later link toggle
     * preserves its unsaved value) and notifies the host of the change. The
     * host notification still fires during programmatic updates (as before);
     * only the "edited since load" mark is user-only.
     */
    private void markManualEdit() {
        fireChange();
    }

    /** Refreshes the preview text and notifies the host of an effective link change. */
    private void fireLinkChanged() {
        updatePreviewText();
        if (onLinkChange != null) {
            onLinkChange.linkChanged(this, getState());
        }
        fireChange();
    }

    private void updatePreviewText() {
        String url = getJdbcUrl();
        previewField.setText(url.isEmpty() ? "(no database URL)" : url);
    }

    private boolean isSqlite() {
        return getEngine() == DatabaseConfigurationPanel.DatabaseEngine.SQLITE;
    }

    // ------------------------------------------------------------------
    // Mode visibility — the single function of the (linked, engine) pair
    // ------------------------------------------------------------------

    /**
     * Applies the row visibility/editability for the current
     * {@code (linked, engine)} state: one function drives every row (no
     * CardLayout switching).
     */
    private void applyModeVisibility() {
        boolean sqlite = isSqlite();
        boolean server = !sqlite;

        engineLabel.setVisible(true);
        engineCombo.setVisible(true);

        // Profile: linked mode only (server: read-only — the profile exists in
        // the database module; SQLite: editable — a new profile name may be
        // typed).
        profileLabel.setVisible(linked);
        profileCombo.setVisible(linked);
        profileCombo.setEditable(linked && sqlite);

        // Path: manual SQLite only.
        pathLabel.setVisible(!linked && sqlite);
        pathCombo.setVisible(!linked && sqlite);

        // DB file (SQLite) vs. Database text field (manual server): one row
        // that exists in EVERY mode — the select (FILE card) whenever the
        // profile (or the path) supplies the list: linked mode (the profile's
        // *.db files / instance list) and manual SQLite (the path's *.db
        // files); the free-form text field (TEXT card) only for a manual
        // server URL.
        dbLabel.setVisible(true);
        dbLabel.setText(sqlite ? "DB file:" : "Database:");
        showSlot(dbSlot, (sqlite || linked) ? "FILE" : "TEXT");

        // Host/Port/Suffix: server engines only; read-only when linked
        // (derived from the instance URL — AD-03).
        hostLabel.setVisible(server);
        hostField.setVisible(server);
        hostField.setEditable(server && !linked);
        portLabel.setVisible(server);
        portField.setVisible(server);
        portField.setEditable(server && !linked);
        suffixLabel.setVisible(server);
        suffixField.setVisible(server);
        suffixField.setEditable(server && !linked);

        // Username: server only; a text field in manual mode, the instance's
        // user select in linked mode.
        userLabel.setVisible(server);
        userSlot.setVisible(server);
        showSlot(userSlot, server ? (linked ? "COMBO" : "FIELD") : "FIELD");

        // Password: server only.
        passLabel.setVisible(server);
        passField.setVisible(server);

        revalidate();
        repaint();
    }

    /** Whether the panel is in linked (database-profile) mode. */
    public boolean isLinkedMode() {
        return linked;
    }

    private void setLinkedInternal(boolean value) {
        this.linked = value;
        if (!value) {
            currentProfile = null;
        }
    }

    /**
     * Requests a link-mode toggle (the host's "Linked Database Profile"
     * checkbox). Turning the link on materializes it from the current engine +
     * profile selection (a server engine without any database profile rejects
     * the link — a link exists only with a profile). Turning it off changes
     * only the URL composition mode: the manual Path, Profile and DB-file
     * inputs remain independent and keep their current values. Re-enabling
     * the link restores the linked profile path while retaining the selected
     * profile and DB file.
     */
    public void requestLinkMode(boolean newLinked) {
        if (newLinked == linked) {
            applyModeVisibility();
            return;
        }
        boolean enableLink = newLinked;
        String selectedProfile = getProfileName();
        String selectedSqliteFile = isSqlite() ? sqliteFileSelection() : "";
        programmaticDepth++;
        try {
            if (enableLink && !isSqlite() && profileCombo.getItemCount() == 0) {
                refreshProfileList();
                if (profileCombo.getItemCount() == 0) {
                    LOGGER.warn("No {} database profile available — the link stays off", getEngine());
                    enableLink = false;
                }
            }
            if (enableLink) {
                refreshProfileList();
                if (profileCombo.getSelectedItem() == null && profileCombo.getItemCount() > 0) {
                    profileCombo.setSelectedItem(profileCombo.getItemAt(0));
                }
                setLinkedInternal(true);
                if (isSqlite() && !selectedProfile.isEmpty()) {
                    profileCombo.setEditable(true);
                    if (!comboContains(profileCombo, selectedProfile)) {
                        profileCombo.addItem(selectedProfile);
                    }
                    profileCombo.setSelectedItem(selectedProfile);
                }
                refreshInstanceList();
                if (isSqlite() && !selectedSqliteFile.isEmpty()) {
                    selectSqliteFile(selectedSqliteFile);
                }
            } else if (!newLinked) {
                if (isSqlite() && !selectedProfile.isEmpty()
                        && !comboContains(profileCombo, selectedProfile)) {
                    profileCombo.addItem(selectedProfile);
                    profileCombo.setSelectedItem(selectedProfile);
                }
                setLinkedInternal(false);
                if (isSqlite()) {
                    // The mode toggle changes only the URL's directory source:
                    // manual mode always uses the independent Path input, never
                    // the hidden linked-profile directory.
                    manualFieldsTouched = true;
                    refreshSqliteFiles(Path.of(pathSelection()), selectedSqliteFile, false);
                }
            }
        } finally {
            programmaticDepth--;
        }
        applyModeVisibility();
        lastProfileLink = getLink();
        fireLinkChanged();
    }

    /**
     * Materializes an {@code "Engine:Profile"} database link in the panel:
     * selects the engine, selects the profile and picks the database file /
     * instance the {@code preferredUrl} points at (falling back to the first
     * one) and the user the {@code preferredUsername} names (falling back to
     * the first one). An unmaterializable link (unknown engine, missing
     * profile, malformed value) falls back to manual mode with the given
     * values — never throws.
     *
     * @param value           the link value, or {@code null}/empty for no link
     * @param preferredUrl    the URL to prefer (e.g. the node profile's saved
     *                        {@code DB.Url}), or {@code null}
     * @param preferredUser   the user to prefer, or {@code null}
     */
    public void applyLink(String value, String preferredUrl, String preferredUser) {
        programmaticDepth++;
        try {
            if (value == null || value.isEmpty() || !value.contains(":")) {
                setLinkedInternal(false);
                loadManual(preferredUrl, preferredUser, null);
                applyModeVisibility();
                return;
            }
            int idx = value.indexOf(':');
            String engineName = value.substring(0, idx).trim();
            String profileName = value.substring(idx + 1).trim();
            DatabaseConfigurationPanel.DatabaseEngine engine = parseEngine(engineName);
            if (engine == null || !profileMaterializes(confFolder, engine, profileName)) {
                LOGGER.warn("Database link '{}' cannot be materialized (unknown engine or missing "
                        + "profile) — falling back to manual mode", value);
                setLinkedInternal(false);
                loadManual(preferredUrl, preferredUser, null);
                applyModeVisibility();
                return;
            }
            engineCombo.setSelectedItem(engine);
            refreshProfileList();
            profileCombo.setSelectedItem(profileName);
            setLinkedInternal(true);
            refreshInstanceList();
            selectInstanceUser(preferredUrl, preferredUser);
            applyModeVisibility();
        } finally {
            programmaticDepth--;
        }
        lastProfileLink = getLink();
        updatePreviewText();
    }

    /**
     * The deterministic initial-link resolution of the row load (called by the
     * host during profile load), in a fixed order:
     * <ol>
     *   <li>an <b>explicit link</b> that materializes (known engine, profile
     *       exists) wins — linked mode (server engines: the recorded
     *       assignment; stored in {@code profiles.json});</li>
     *   <li>otherwise, a SQLite URL that follows the per-profile convention
     *       ({@code ./database/SQLite/<profile>}) with an existing database
     *       profile links to it — the file-based assignment is derivable from
     *       the properties alone, so the checkbox follows them automatically;</li>
     *   <li>otherwise the row stays in manual mode with the given values.</li>
     * </ol>
     * <p>
     * The given (property-derived) values are also remembered as the
     * untouched manual-mode baseline.
     * </p>
     *
     * @param explicitLink the recorded {@code "Engine:Profile"} link (server
     *                     engines), or {@code null} when none is recorded
     * @param url          the saved {@code DB.Url}
     * @param username     the saved {@code DB.Username}
     * @param password     the saved {@code DB.Password}
     */
    public void loadInitial(String explicitLink,
            String url, String username, String password) {
        this.savedUrl = url == null ? "" : url.trim();
        this.savedUsername = username == null ? "" : username.trim();
        this.savedPassword = password == null ? "" : password.trim();
        String explicit = explicitLink == null ? "" : explicitLink.trim();
        if (explicit.contains(":")) {
            int idx = explicit.indexOf(':');
            DatabaseConfigurationPanel.DatabaseEngine engine = parseEngine(explicit.substring(0, idx).trim());
            String profileName = explicit.substring(idx + 1).trim();
            boolean materializable = engine != null && !profileName.isEmpty()
                    && profileMaterializes(confFolder, engine, profileName);
            if (materializable) {
                applyLink(explicit, url, username);
                return;
            }
            LOGGER.warn("Recorded database link '{}' cannot be materialized — loading in manual mode",
                    explicit);
        }
        // SQLite derivation: the URL's directory names an existing profile
        // (the per-profile convention) — the file-based assignment is
        // derivable from the properties alone. For the file-based engine the
        // profile IS the convention folder (its profile.json, when present,
        // only carries optional tuning), so a folder without one still links.
        if (savedUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:")) {
            SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(savedUrl);
            if (parts != null) {
                String derived = SqliteDatabasePaths.deriveProfileName(parts.dir());
                if (derived != null && Files.isDirectory(SqliteDatabasePaths.profileDir(derived))) {
                    applyLink("SQLite:" + derived, url, username);
                    return;
                }
            }
        }
        loadManual(url, username, password);
    }

    /**
     * Discards the remembered per-engine and per-profile working states:
     * after a full re-load from disk ("Reload Configuration") nothing of the
     * pre-load UI state may survive — the next engine or profile switch
     * re-derives everything from the baselines.
     */
    public void resetWorkingState() {
        engineSnapshots.clear();
        profileSnapshots.clear();
        lastProfileLink = "";
    }

    /**
     * Re-derives the (automatic) SQLite link from the panel's current URL:
     * when the URL follows the per-profile convention
     * ({@code ./database/SQLite/<profile>}) and the database profile exists,
     * the row becomes linked (the host's "Linked Database Profile" checkbox
     * follows via the link callback). Server engines are never auto-linked —
     * their assignment is explicit.
     *
     * @return {@code true} when the row became (or stayed) linked as a result
     */
    public boolean rediscoverSqliteLink() {
        if (linked) {
            return true;
        }
        if (!isSqlite()) {
            return false;
        }
        String name = null;
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(getJdbcUrl());
        if (parts != null) {
            name = SqliteDatabasePaths.deriveProfileName(parts.dir());
            if (name != null && !Files.isDirectory(SqliteDatabasePaths.profileDir(name))) {
                name = null;
            }
        }
        if (name == null) {
            return false;
        }
        applyLink("SQLite:" + name, getJdbcUrl(), null);
        return true;
    }

    /**
     * Loads the given values into the manual mode (engine/fields derived from
     * the URL). An untouched manual SQLite configuration preserves the
     * original URL it was loaded from (see {@link #getJdbcUrl()}).
     */
    public void loadManual(String url, String username, String password) {
        programmaticDepth++;
        try {
            originalUrl = url == null ? "" : url.trim();
            originalUsername = username == null ? "" : username.trim();
            originalPassword = password == null ? "" : password.trim();
            // Every loadManual caller passes property-derived values — remember
            // them as the manual-mode restore baseline.
            savedUrl = originalUrl;
            savedUsername = originalUsername;
            savedPassword = originalPassword;
            manualFieldsTouched = false;
            setLinkedInternal(false);
            refreshProfileList();

            DatabaseConfigurationPanel.DatabaseEngine engine = engineFromUrl(originalUrl);
            engineCombo.setSelectedItem(engine != null ? engine
                    : DatabaseConfigurationPanel.DatabaseEngine.SQLITE);

            if (isSqlite()) {
                SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(originalUrl);
                String dir = "./database/SQLite";
                String file = "";
                if (parts != null) {
                    String normalized = SqliteDatabasePaths.normalize(parts.dir().toString());
                    if (!normalized.isEmpty()) {
                        // The display keeps the ./ prefix of a relative
                        // directory (the JDBC URL and the default path are
                        // ./-prefixed too); absolute paths keep their form.
                        dir = parts.dir().isAbsolute() ? normalized : "./" + normalized;
                    }
                    file = parts.fileName();
                }
                setPathSelection(dir);
                refreshSqlitePathOptions();
                refreshSqliteFiles(Path.of(dir), file, false);
            } else {
                Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(originalUrl);
                if (m.find()) {
                    hostField.setText(m.group(2));
                    portField.setText(m.group(3) != null ? m.group(3) : "");
                    databaseField.setText(m.group(4));
                    suffixField.setText(m.group(5) != null ? m.group(5) : "");
                }
                userField.setText(originalUsername);
                passField.setText(originalPassword);
            }
            applyModeVisibility();
        } finally {
            programmaticDepth--;
        }
        lastProfileLink = "";
        updatePreviewText();
    }

    /**
     * A programmatic manual-mode load that never fires link callbacks (used by
     * the host's reload/reset/copy flows — the recorded link in the metadata
     * is preserved by the host, the UI shows the given values).
     */
    public void forceManual(String url, String username, String password) {
        programmaticDepth++;
        try {
            profileSnapshots.clear();
            if (linked) {
                setLinkedInternal(false);
            }
            loadManual(url, username, password);
        } finally {
            programmaticDepth--;
        }
    }

    private static DatabaseConfigurationPanel.DatabaseEngine parseEngine(String name) {
        return name == null || name.trim().isEmpty()
                ? null
                : DatabaseConfigurationPanel.DatabaseEngine.fromDisplayName(name.trim());
    }

    /**
     * Whether the named database profile materializes for the given engine:
     * <ul>
     *   <li>SQLite (file-based): the profile IS the convention folder
     *       ({@code ./database/SQLite/<profile>}) — its {@code profile.json},
     *       when present, only carries optional tuning (journal mode, cache
     *       size), so a folder without one is a valid profile;</li>
     *   <li>server engines: the profile is its {@code profile.json} (the
     *       databases created on the server) — it must exist and be loadable.</li>
     * </ul>
     */
    private static boolean profileMaterializes(String confFolder,
            DatabaseConfigurationPanel.DatabaseEngine engine, String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return false;
        }
        if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE) {
            return Files.isDirectory(SqliteDatabasePaths.profileDir(profileName));
        }
        return DatabaseConfigurationUtils.loadProfile(confFolder, engine.toString(), profileName) != null;
    }

    /**
     * The profile part of an {@code "Engine:Profile"} link, or {@code null}
     * when the value is empty or malformed.
     */
    private static String profileOfLink(String link) {
        if (link == null) {
            return null;
        }
        int sep = link.indexOf(':');
        if (sep <= 0 || sep == link.length() - 1) {
            return null;
        }
        String profile = link.substring(sep + 1).trim();
        return profile.isEmpty() ? null : profile;
    }

    /**
     * The profile name recorded in the baseline's link when that link belongs
     * to the given engine ({@code "Engine:Profile"}), else {@code null}.
     */
    private static String recordedProfileForEngine(DatabaseConfigurationPanel.DatabaseEngine engine,
            JdbcState state) {
        String link = state.link();
        String profile = profileOfLink(link);
        if (profile == null) {
            return null;
        }
        String linkEngine = link.substring(0, link.indexOf(':')).trim();
        return engine.toString().equals(linkEngine) ? profile : null;
    }

    /**
     * The baseline (saved first, then applied) whose link element equals the
     * given link, else {@code null} — the source of the file/instance the
     * recorded link points at.
     */
    private JdbcState baselineOfLink(String link) {
        if (link != null && !link.isEmpty()) {
            if (savedState.link().equals(link)) {
                return savedState;
            }
            if (appliedState.link().equals(link)) {
                return appliedState;
            }
        }
        return null;
    }

    /** The engine a JDBC URL belongs to, or {@code null} for an unknown form. */
    private static DatabaseConfigurationPanel.DatabaseEngine engineFromUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        if (u.startsWith("jdbc:sqlite:")) {
            return DatabaseConfigurationPanel.DatabaseEngine.SQLITE;
        }
        if (u.startsWith("jdbc:mariadb:")) {
            return DatabaseConfigurationPanel.DatabaseEngine.MARIADB;
        }
        if (u.startsWith("jdbc:postgresql:")) {
            return DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Effective state (the host reads these)
    // ------------------------------------------------------------------

    /** The panel's current effective state (the color-coding comparison subject). */
    public JdbcState getState() {
        return new JdbcState(getLink(), getJdbcUrl(), getUsername(), getPassword());
    }

    /**
     * The {@code "Engine:Profile"} database link, or the empty string in
     * manual mode / when no profile is selected (an empty-profile checkbox
     * is "on in appearance" but yields no link — a link exists only with a
     * profile).
     */
    public String getLink() {
        return linked ? JdbcState.linkOf(getEngine(), getProfileName()) : "";
    }

    /**
     * The linked database profile name, or the empty string in manual mode.
     * While the user types into the editable profile select this returns the
     * LIVE editor text (the combo model only commits it on Enter/selection) —
     * so the effective link, the composed URL and the color coding track the
     * keystrokes; the commit-time value is the same the model ends up with.
     */
    public String getProfileName() {
        if (profileCombo.isEditable()
                && profileCombo.getEditor() != null
                && profileCombo.getEditor().getEditorComponent() instanceof JTextField editor) {
            String text = editor.getText().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        Object sel = profileCombo.getSelectedItem();
        return sel == null ? "" : sel.toString().trim();
    }

    /** The currently selected engine. */
    public DatabaseConfigurationPanel.DatabaseEngine getEngine() {
        return (DatabaseConfigurationPanel.DatabaseEngine) engineCombo.getSelectedItem();
    }

    /**
     * The effective JDBC URL — the single composition SSOT:
     * <ul>
     *   <li>manual SQLite: the untouched configuration preserves the URL it
     *       was loaded from; an edited one composes {@code Path} +
     *       {@code DB file} (via {@link SqliteDatabasePaths#composeUrl});</li>
     *   <li>manual server: composed from host/port/database/suffix;</li>
     *   <li>linked SQLite: {@code ./database/SQLite/<profile>/<file>};</li>
     *   <li>linked server: the selected instance's URL (the last applied URL
     *       is kept while no instance is selected).</li>
     * </ul>
     */
    public String getJdbcUrl() {
        DatabaseConfigurationPanel.DatabaseEngine engine = getEngine();
        if (linked) {
            String profileName = getProfileName();
            if (profileName.isEmpty()) {
                return originalUrl; // transient: the link does not exist yet
            }
            if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE) {
                return SqliteDatabasePaths.composeUrl(
                        SqliteDatabasePaths.profileDir(profileName), sqliteFileSelection());
            }
            DbInstance instance = selectedInstance();
            return instance != null ? instance.url : originalUrl;
        }
        if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE) {
            if (!manualFieldsTouched && !originalUrl.isEmpty()) {
                return originalUrl;
            }
            String path = pathSelection();
            if (path.isEmpty()) {
                path = ".";
            }
            return SqliteDatabasePaths.composeUrl(Path.of(path), sqliteFileSelection());
        }
        return buildServerUrl(engine, hostField.getText().trim(), portField.getText().trim(),
                databaseField.getText().trim(), suffixField.getText().trim());
    }

    /** The effective database username (empty for SQLite). */
    public String getUsername() {
        if (isSqlite()) {
            return "";
        }
        if (linked) {
            DbUser user = (DbUser) userCombo.getSelectedItem();
            return user == null || user.username == null ? "" : user.username;
        }
        return userField.getText().trim();
    }

    /** The effective database password (empty for SQLite). */
    public String getPassword() {
        if (isSqlite()) {
            return "";
        }
        if (linked) {
            DbUser user = (DbUser) userCombo.getSelectedItem();
            return user == null || user.password == null ? "" : user.password;
        }
        return new String(passField.getPassword());
    }

    private String sqliteFileSelection() {
        // The (editable) combo model only commits the editor text on
        // Enter/selection — while the user types, read the LIVE editor text
        // (like getProfileName does) so the composed URL and the color coding
        // track the keystrokes.
        if (dbCombo.isEditable()
                && dbCombo.getEditor() != null
                && dbCombo.getEditor().getEditorComponent() instanceof JTextField editor) {
            String text = editor.getText().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        Object sel = dbCombo.getSelectedItem();
        return sel == null ? "" : sel.toString().trim();
    }

    private DbInstance selectedInstance() {
        Object sel = dbCombo.getSelectedItem();
        return sel instanceof DbInstance ? (DbInstance) sel : null;
    }

    private static String buildServerUrl(DatabaseConfigurationPanel.DatabaseEngine engine, String host,
            String port, String dbName, String suffix) {
        String protocol = engine == DatabaseConfigurationPanel.DatabaseEngine.MARIADB ? "mariadb" : "postgresql";
        StringBuilder sb = new StringBuilder("jdbc:").append(protocol).append("://").append(host);
        if (!port.isEmpty()) {
            sb.append(':').append(port);
        }
        sb.append('/').append(dbName);
        if (!suffix.isEmpty()) {
            sb.append(suffix);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // List refresh (the discovery SSOT: DatabaseConfigurationUtils +
    // SqliteDatabasePaths)
    // ------------------------------------------------------------------

    /** Re-scans the database profiles of the current engine into the select. */
    public void refreshProfiles() {
        refreshProfileList();
        refreshSqlitePathOptions();
        refreshInstanceList();
        applyModeVisibility();
        updatePreviewText();
    }

    private void refreshProfileList() {
        programmaticDepth++;
        try {
            Object sel = profileCombo.getSelectedItem();
            profileCombo.removeAllItems();
            DatabaseConfigurationPanel.DatabaseEngine engine = getEngine();
            if (engine != null) {
                for (String name : DatabaseConfigurationUtils.getProfileNames(confFolder, engine.toString())) {
                    profileCombo.addItem(name);
                }
            }
            if (sel != null && comboContains(profileCombo, sel)) {
                profileCombo.setSelectedItem(sel);
            }
        } finally {
            programmaticDepth--;
        }
    }

    private void refreshInstanceList() {
        programmaticDepth++;
        try {
            String selectedSqliteFile = isSqlite() ? sqliteFileSelection() : "";
            currentProfile = null;
            dbCombo.removeAllItems();
            String profileName = getProfileName();
            DatabaseConfigurationPanel.DatabaseEngine engine = getEngine();
            if (linked && !profileName.isEmpty() && engine != null) {
                currentProfile = DatabaseConfigurationUtils.loadProfile(confFolder, engine.toString(),
                        profileName);
                if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE) {
                    // The DB file select: the profile directory's *.db files,
                    // plus the file the profile URL points at and the default
                    // file name (a never-started profile has no .db yet). The
                    // list derives from the folder itself, so it also works for
                    // a profile without a profile.json (for the file-based
                    // engine the folder is the profile).
                    Set<String> files = new LinkedHashSet<>(
                            SqliteDatabasePaths.discoverDbFiles(SqliteDatabasePaths.profileDir(profileName)));
                    if (currentProfile != null) {
                        for (DbInstance instance : currentProfile.databases) {
                            SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(instance.url);
                            if (parts != null && !parts.fileName().isEmpty()) {
                                files.add(parts.fileName());
                            }
                        }
                    }
                    files.add(ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME);
                    files.forEach(dbCombo::addItem);
                    if (!selectedSqliteFile.isEmpty()) {
                        setSqliteFileSelection(selectedSqliteFile);
                    }
                } else if (currentProfile != null) {
                    currentProfile.databases.forEach(dbCombo::addItem);
                }
            }
            refreshUserList();
        } finally {
            programmaticDepth--;
        }
    }

    private void refreshUserList() {
        programmaticDepth++;
        try {
            userCombo.removeAllItems();
            if (selectedInstance() != null) {
                selectedInstance().users.forEach(userCombo::addItem);
                if (userCombo.getItemCount() > 0) {
                    userCombo.setSelectedItem(userCombo.getItemAt(0));
                }
            }
        } finally {
            programmaticDepth--;
        }
        syncPasswordFromUser();
        syncDerivedInstanceFields();
    }

    /**
     * Linked server mode: the read-only Host/Port/Suffix rows mirror the
     * selected instance's URL (AD-03) — without this they would keep the
     * construction-time / last-manual values while the instance select holds
     * the real ones (the section looked empty / stale for linked server
     * profiles).
     */
    private void syncDerivedInstanceFields() {
        DbInstance instance = selectedInstance();
        if (!linked || isSqlite() || instance == null || instance.url == null) {
            return;
        }
        Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(instance.url);
        if (!m.find()) {
            return;
        }
        programmaticDepth++;
        try {
            hostField.setText(m.group(2));
            portField.setText(m.group(3) != null ? m.group(3) : "");
            suffixField.setText(m.group(5) != null ? m.group(5) : "");
        } finally {
            programmaticDepth--;
        }
    }

    /**
     * Picks the instance/file the {@code preferredUrl} points at (the first
     * one otherwise) and, for server engines, the user the
     * {@code preferredUsername} names (the first one otherwise).
     */
    private void selectInstanceUser(String preferredUrl, String preferredUser) {
        if (isSqlite()) {
            SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(preferredUrl);
            String wanted = parts != null ? parts.fileName() : "";
            if (!wanted.isEmpty() && comboContains(dbCombo, wanted)) {
                setSqliteFileSelection(wanted);
            } else if (dbCombo.getItemCount() > 0 && dbCombo.getSelectedItem() == null) {
                dbCombo.setSelectedItem(dbCombo.getItemAt(0));
            }
            return;
        }
        String wantedUrl = preferredUrl == null ? "" : preferredUrl.trim();
        DbInstance wanted = null;
        for (int i = 0; i < dbCombo.getItemCount(); i++) {
            Object item = dbCombo.getItemAt(i);
            if (item instanceof DbInstance d && d.url != null && wantedUrl.equalsIgnoreCase(d.url.trim())) {
                wanted = d;
                break;
            }
        }
        if (wanted == null && dbCombo.getItemCount() > 0) {
            wanted = (DbInstance) dbCombo.getItemAt(0);
        }
        dbCombo.setSelectedItem(wanted);
        refreshUserList();
        if (preferredUser != null && !preferredUser.trim().isEmpty()) {
            for (int i = 0; i < userCombo.getItemCount(); i++) {
                DbUser user = userCombo.getItemAt(i);
                if (user != null && preferredUser.trim().equalsIgnoreCase(user.username)) {
                    userCombo.setSelectedItem(user);
                    break;
                }
            }
        }
    }

    private void syncPasswordFromUser() {
        DbUser user = (DbUser) userCombo.getSelectedItem();
        passField.setText(user == null || user.password == null ? "" : user.password);
    }

    /**
     * Fills the DB file select with the {@code *.db} files of the given
     * directory (plus the default file name when none is found).
     *
     * @param wanted          the file name to select when offered
     * @param autoSelectFirst select the first file when {@code wanted} is not
     *                        offered (an empty selection keeps the untouched
     *                        original URL)
     */
    private void refreshSqliteFiles(Path dir, String wanted, boolean autoSelectFirst) {
        programmaticDepth++;
        try {
            dbCombo.removeAllItems();
            List<String> files = SqliteDatabasePaths.discoverDbFiles(dir);
            files.forEach(dbCombo::addItem);
            if (files.isEmpty()) {
                dbCombo.addItem(ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME);
            }
            String w = wanted == null ? "" : wanted.trim();
            if (!w.isEmpty()) {
                setSqliteFileSelection(w);
            } else if (autoSelectFirst && dbCombo.getItemCount() > 0 && dbCombo.getSelectedItem() == null) {
                dbCombo.setSelectedItem(dbCombo.getItemAt(0));
            }
        } finally {
            programmaticDepth--;
        }
    }

    private void selectSqliteFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        setSqliteFileSelection(fileName);
    }

    private void setSqliteFileSelection(String fileName) {
        dbCombo.setSelectedItem(fileName);
        dbCombo.getEditor().setItem(fileName);
    }

    private void refreshSqlitePathOptions() {
        String current = pathSelection();
        programmaticDepth++;
        try {
            pathCombo.removeAllItems();
            pathCombo.addItem("./database/SQLite");
            for (String profile : DatabaseConfigurationUtils.getProfileNames(confFolder, "SQLite")) {
                pathCombo.addItem("./database/SQLite/" + profile);
            }
            setPathSelection(current);
        } finally {
            programmaticDepth--;
        }
    }

    private String pathSelection() {
        if (pathCombo.isEditable() && pathCombo.getEditor() != null) {
            Object value = pathCombo.getEditor().getItem();
            if (value != null) {
                return value.toString().trim();
            }
        }
        Object selected = pathCombo.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    private void setPathSelection(String path) {
        pathCombo.getEditor().setItem(path == null ? "" : path);
    }

    private static boolean comboContains(JComboBox<?> combo, Object item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (java.util.Objects.equals(combo.getItemAt(i), item)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Color coding (the value-state model)
    // ------------------------------------------------------------------

    /**
     * Sets the color-coding baselines: {@code saved} (the profile on disk) and
     * {@code applied} (the loaded baseline the node runs with).
     */
    public void setBaselines(JdbcState saved, JdbcState applied) {
        this.savedState = saved != null ? saved : new JdbcState("", "", "", "");
        this.appliedState = applied != null ? applied : new JdbcState("", "", "", "");
    }

    /**
     * Recolors every row against the baselines. Each row is compared by the
     * value it actually represents — the Profile row by its link, the URL
     * preview by the URL, the Engine by its engine, the SQLite Path/DB file
     * rows by the directory/file the baseline URL points at, the server
     * instance row by the instance URL and the credentials by themselves —
     * so a change to one element (e.g. typing a profile name) does not flag
     * the unrelated rows. Each per-row comparison:
     * {@code current == applied → applied, == saved → saved, else unsaved}.
     * A row in UNSAVED state gets the trailing {@code *} label marker
     * (applied and saved rows carry none).
     */
    public void refreshColors() {
        paint(previewField, null,
                statusOfValue(getJdbcUrl(), appliedState.url(), savedState.url()));

        if (linked) {
            paint(profileCombo, profileLabel, statusOfLink());
        }
        paint(engineCombo, engineLabel,
                statusOfValue(getEngine() == null ? "" : getEngine().toString(),
                        engineOfUrl(appliedState.url()), engineOfUrl(savedState.url())));
        paint(pathCombo, pathLabel, linked ? ValueState.APPLIED
                : statusOfValue(SqliteDatabasePaths.normalize(pathSelection()),
                        dirOfUrl(appliedState.url()), dirOfUrl(savedState.url())));
        // SQLite: the file select is the row's value; server engines: the
        // selected instance IS the URL.
        paint(dbCombo, dbLabel, isSqlite()
                ? statusOfValue(fileOfUrl(getJdbcUrl()), fileOfUrl(appliedState.url()), fileOfUrl(savedState.url()))
                : statusOfValue(getJdbcUrl(), appliedState.url(), savedState.url()));
        if (isSqlite()) {
            // The server segment rows do not exist in SQLite mode (hidden);
            // paint them neutral so no stale star lingers on their labels.
            // databaseField shares the "DB file:" label with the visible file
            // select — it must not touch it (no stale star on the label).
            paint(databaseField, null, ValueState.APPLIED);
            paint(hostField, hostLabel, ValueState.APPLIED);
            paint(portField, portLabel, ValueState.APPLIED);
            paint(suffixField, suffixLabel, ValueState.APPLIED);
        } else {
            paint(databaseField, dbLabel,
                    statusOfValue(databaseField.getText().trim(),
                            serverSegmentOfUrl(appliedState.url(), 4), serverSegmentOfUrl(savedState.url(), 4)));
            paint(hostField, hostLabel,
                    statusOfValue(hostField.getText().trim(),
                            serverSegmentOfUrl(appliedState.url(), 2), serverSegmentOfUrl(savedState.url(), 2)));
            paint(portField, portLabel,
                    statusOfValue(portField.getText().trim(),
                            serverSegmentOfUrl(appliedState.url(), 3), serverSegmentOfUrl(savedState.url(), 3)));
            paint(suffixField, suffixLabel,
                    statusOfValue(suffixField.getText().trim(),
                            serverSegmentOfUrl(appliedState.url(), 5), serverSegmentOfUrl(savedState.url(), 5)));
        }
        paint(userField, null,
                statusOfValue(getUsername(), appliedState.username(), savedState.username()));
        paint(userCombo, userLabel,
                statusOfValue(getUsername(), appliedState.username(), savedState.username()));
        paint(passField, passLabel,
                statusOfValue(getPassword(), appliedState.password(), savedState.password()));
    }

    /**
     * The value-state of a single per-row value, with the unsaved-first
     * priority {@code UNSAVED > SAVED > APPLIED} (this section's 3-state
     * display projection of the shared 4-state value model — a dirty row is
     * plain UNSAVED here): UNSAVED when it differs from the saved value,
     * SAVED when it equals the saved value but differs from the applied
     * value, otherwise APPLIED.
     */
    private static ValueState statusOfValue(String current, String applied, String saved) {
        String c = current == null ? "" : current;
        String s = saved == null ? "" : saved;
        String a = applied == null ? "" : applied;
        if (!c.equals(s)) {
            return ValueState.UNSAVED;
        }
        if (!c.equals(a)) {
            return ValueState.SAVED;
        }
        return ValueState.APPLIED;
    }

    /** The engine a baseline URL belongs to (display name), or {@code null}. */
    private static String engineOfUrl(String url) {
        DatabaseConfigurationPanel.DatabaseEngine engine = engineFromUrl(url);
        return engine == null ? null : engine.toString();
    }

    /** The normalized directory of a baseline URL ({@code ""} when not derivable). */
    private static String dirOfUrl(String url) {
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        return parts == null ? "" : SqliteDatabasePaths.normalize(parts.dir().toString());
    }

    /** The database file name of a baseline URL ({@code ""} when not derivable). */
    private static String fileOfUrl(String url) {
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        return parts == null ? "" : parts.fileName();
    }

    /** The given segment of a server baseline URL ({@code ""} when not present). */
    private static String serverSegmentOfUrl(String url, int group) {
        if (url == null) {
            return "";
        }
        Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url);
        String s = m.find() ? m.group(group) : null;
        return s == null ? "" : s;
    }

    /** The value-state of the link element alone (the Profile row). */
    private ValueState statusOfLink() {
        String link = getLink();
        if (!link.equals(savedState.link())) {
            return ValueState.UNSAVED;
        }
        if (!link.equals(appliedState.link())) {
            return ValueState.SAVED;
        }
        return ValueState.APPLIED;
    }

    private void paint(JComponent input, JLabel label, ValueState state) {
        if (input == null) {
            return;
        }
        Color color;
        switch (state) {
            case APPLIED:
                color = GuiColors.getApplied();
                break;
            case SAVED:
                color = GuiColors.getSaved();
                break;
            default:
                color = GuiColors.getUnsaved();
                break;
        }
        // An editable combo's visible text lives in the editor's field — the
        // combo's own foreground does not reach it (same convention as the
        // host's state-aware editable selects). Both are painted: the combo's
        // foreground stays the component-level state (and the source an L&F-
        // recreated editor field inherits from), the editor's the visible one.
        JComponent target = input;
        if (input instanceof JComboBox<?> combo && combo.isEditable() && combo.getEditor() != null
                && combo.getEditor().getEditorComponent() instanceof JComponent editor) {
            target = editor;
        }
        target.setForeground(color);
        String tooltip = "Status: " + stateDisplayName(state);
        input.setToolTipText(tooltip);
        target.setToolTipText(tooltip);
        setToolTipTextRecursively(target, tooltip);
        if (target != input) {
            input.setForeground(color);
        }
        if (label != null) {
            // The * marker signals an UNSAVED change only — an applied (green)
            // or a saved value carries no star.
            boolean dirty = state == ValueState.UNSAVED;
            String base = label.getText().replace(" *", "");
            label.setText(base + (dirty ? " *" : ""));
        }
    }

    private static void setToolTipTextRecursively(Component component, String tooltip) {
        if (component instanceof JComponent swingComponent) {
            swingComponent.setToolTipText(tooltip);
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                setToolTipTextRecursively(child, tooltip);
            }
        }
    }

    private static String stateDisplayName(ValueState state) {
        return switch (state) {
            case APPLIED -> "Applied";
            case SAVED -> "Saved";
            case UNSAVED -> "Unsaved";
        };
    }

    // ------------------------------------------------------------------
    // Popup list state coloring (which entry is applied / saved)
    // ------------------------------------------------------------------

    /**
     * Wraps a combo's default renderer with the value-state coloring of the
     * POPUP list (and of the collapsed display): the entry equal to the
     * applied baseline value is painted applied (green), the one equal to the
     * saved baseline value saved (yellow) — so the user can identify at a
     * glance which entry the node runs with and which one is recorded on
     * disk. The highlighted (selected) entry keeps its contrast.
     */
    private <T> void installStateRenderer(JComboBox<T> combo,
            Function<T, String> itemValue,
            Supplier<String> appliedValue, Supplier<String> savedValue) {
        ListCellRenderer<? super T> base = combo.getRenderer();
        combo.setRenderer((c, value, index, isSelected, hasFocus) -> {
            if (c != null && index >= 0) {
                ComboBoxItemToolTips.install(c, item -> {
                    for (int itemIndex = 0; itemIndex < combo.getItemCount(); itemIndex++) {
                        T candidate = combo.getItemAt(itemIndex);
                        if (java.util.Objects.equals(candidate, item)) {
                            String itemText = itemValue.apply(candidate);
                            if (itemText == null) {
                                return null;
                            }
                            return "Status: " + stateDisplayName(
                                    statusOfValue(itemText, appliedValue.get(), savedValue.get()));
                        }
                    }
                    return null;
                });
            }
            Component comp = base.getListCellRendererComponent(c, value, index, isSelected, hasFocus);
            if (value != null) {
                String v = itemValue.apply(value);
                if (v != null && !v.isEmpty()) {
                    String applied = appliedValue.get();
                    String saved = savedValue.get();
                    ValueState state = statusOfValue(v, applied, saved);
                    if (comp instanceof JComponent swingComponent) {
                        swingComponent.setToolTipText("Status: " + stateDisplayName(state));
                    }
                    if (!isSelected && applied != null && v.equals(applied)) {
                        comp.setForeground(GuiColors.getApplied());
                    } else if (!isSelected && saved != null && v.equals(saved)) {
                        comp.setForeground(GuiColors.getSaved());
                    }
                }
            }
            return comp;
        });
    }

    /** Installs the popup state coloring on every stateful select. */
    private void installStateRenderers() {
        installStateRenderer(engineCombo,
                e -> e == null ? "" : e.toString(),
                () -> engineOfUrl(appliedState.url()),
                () -> engineOfUrl(savedState.url()));
        installStateRenderer(profileCombo,
                p -> p,
                () -> profileOfLink(appliedState.link()),
                () -> profileOfLink(savedState.link()));
        installStateRenderer(pathCombo,
                p -> SqliteDatabasePaths.normalize(p),
                () -> dirOfUrl(appliedState.url()),
                () -> dirOfUrl(savedState.url()));
        installStateRenderer(dbCombo,
                item -> item instanceof DbInstance d ? (d.url == null ? "" : d.url.trim())
                        : (item instanceof String s ? s : null),
                () -> dbBaselineItemValue(appliedState),
                () -> dbBaselineItemValue(savedState));
        installStateRenderer(userCombo,
                u -> u == null || u.username == null ? "" : u.username,
                () -> appliedState.username(),
                () -> savedState.username());
    }

    /**
     * The baseline value comparable to a DB popup item: for the server
     * instance list (items are instances) the instance URL itself; for the
     * SQLite file list (items are file names) the file part of the baseline
     * URL — and only when the file's context matches the list currently
     * shown (linked: the baseline link points at the same profile; manual:
     * the baseline URL points at the same directory). {@code null} = nothing
     * to compare (empty baseline or a foreign context).
     */
    private String dbBaselineItemValue(JdbcState state) {
        if (state.url().isEmpty()) {
            return null;
        }
        boolean instanceList = dbCombo.getItemCount() > 0
                && dbCombo.getItemAt(0) instanceof DbInstance;
        if (instanceList) {
            return state.url();
        }
        if (linked) {
            String profile = profileOfLink(state.link());
            if (profile == null || !profile.equals(getProfileName())) {
                return null;
            }
        } else {
            String dir = dirOfUrl(state.url());
            String current = SqliteDatabasePaths.normalize(pathSelection());
            if (current.isEmpty() || !dir.equals(current)) {
                return null;
            }
        }
        String file = fileOfUrl(state.url());
        return file.isEmpty() ? null : file;
    }

    // ------------------------------------------------------------------
    // Host accessors (search / highlight / value suppliers)
    // ------------------------------------------------------------------

    /** The composed-URL preview field (read-only). */
    public JComponent getPreviewField() {
        return previewField;
    }

    /** The manual-mode username field (the editable credential input). */
    public JComponent getUserEditComponent() {
        return userField;
    }

    /** The password field (shared by both modes). */
    public JComponent getPassField() {
        return passField;
    }

    /** The engine select (for hosts that track the visible engine). */
    public JComponent getEngineComboComponent() {
        return engineCombo;
    }

    /** The (editable) profile select (linked mode). */
    public JComponent getProfileComboComponent() {
        return profileCombo;
    }

    /** The independent manual SQLite path input. */
    public JComponent getPathFieldComponent() {
        return pathCombo;
    }

    /** The SQLite DB file / server instance select (for hosts and tests). */
    public JComponent getDbFileComboComponent() {
        return dbCombo;
    }

    /** The manual-server database name field (the db row's text card). */
    public JComponent getDatabaseField() {
        return databaseField;
    }

    /** The host field (manual: editable; linked: the instance's derived value). */
    public JComponent getHostField() {
        return hostField;
    }

    /** The port field (manual: editable; linked: the instance's derived value). */
    public JComponent getPortField() {
        return portField;
    }

    /** The URL suffix field (manual: editable; linked: the instance's derived value). */
    public JComponent getSuffixField() {
        return suffixField;
    }

    /**
     * MigLayout 11.x counts a phantom 2 px into the preferred (and minimum)
     * height that the actual layout never uses — with this grid it would
     * show up as dead space under the last row (guarded by
     * {@code NodeConfigurationPanelJdbcCardHeightTest}). Trim it.
     */
    private static Dimension trimPhantomRowGap(Dimension d) {
        return d.height > 2 ? new Dimension(d.width, d.height - 2) : d;
    }

    @Override
    public Dimension getPreferredSize() {
        return trimPhantomRowGap(super.getPreferredSize());
    }

    @Override
    public Dimension getMinimumSize() {
        return trimPhantomRowGap(super.getMinimumSize());
    }
}
