package application.module.node.profile;

import application.module.logging.LoggingAssignmentStore;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.props.Props;
import application.utils.config.ModuleIds;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.Reader;
import java.io.Writer;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GUI-free runtime operations on node profiles that span more than the single
 * profile file (file + cross-profile metadata).
 * <p>
 * The public methods use the default runtime conf root
 * (SSOT: {@link NodeProfile#CONF_ROOT}); the package-private {@code (confRoot, ...)}
 * overloads are the core implementations and accept an explicit conf root so
 * that unit tests can operate on a sandbox directory (codebase pattern, see
 * {@link NodeProfileRepository}).
 * </p>
 * <p>
 * This class stays GUI/Swing-free: tab registration, dialogs and status
 * reporting are the caller's responsibility.
 * </p>
 */
public final class ProfileRuntimeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileRuntimeService.class);

    private ProfileRuntimeService() {
        // static utility
    }

    // ── Clone ───────────────────────────────────────────────────────────

    /**
     * Clones a node profile (default conf root). See
     * {@link #cloneProfile(String, String, String, Properties)}.
     *
     * @throws IllegalArgumentException if the new name is blank, invalid, reserved, or already exists
     * @throws IOException              on persistence failure
     */
    public static String cloneProfile(String sourceProfileName, String newProfileName, Properties overrideProps)
            throws IOException {
        return cloneProfile(NodeProfile.CONF_ROOT, sourceProfileName, newProfileName, overrideProps);
    }

    /**
     * Clones a node profile: creates {@code newProfileName} from the given
     * <b>minimal-override</b> properties (the keys whose value differs from
     * the application default — the same payload the clone dialog computes
     * with {@link ProfileDiffCalculator#toProperties}) and copies the source
     * profile's logging association.
     * <p>
     * Create-only semantics (clone is a <i>create</i> action, not a lifecycle
     * action): the new profile is <b>not</b> started, and the source profile
     * (file, metadata, runtime state) is left completely untouched. The
     * clone keeps the source's ports and database settings as-is; starting
     * both at the same time is up to the runtime resource-conflict
     * protection to reject.
     * </p>
     *
     * @param confRoot          the runtime conf root (e.g. {@code conf} or a test sandbox dir)
     * @param sourceProfileName the source profile whose logging association is copied
     * @param newProfileName    the clone's name (validated)
     * @param overrideProps     the minimal-override payload for the clone (null → empty profile)
     * @return the created profile name
     * @throws IllegalArgumentException if the new name is blank, invalid, reserved, or already exists
     * @throws IOException              on persistence failure
     */
    static String cloneProfile(String confRoot, String sourceProfileName, String newProfileName,
            Properties overrideProps) throws IOException {
        Objects.requireNonNull(confRoot, "confRoot");
        Objects.requireNonNull(newProfileName, "newProfileName");
        if (sourceProfileName == null || sourceProfileName.isBlank()) {
            throw new IllegalArgumentException("Source profile name must not be null or blank");
        }

        // 1) Profile file from the minimal-override payload (validates the name).
        NodeProfile created = NodeProfileRepository.createProfile(confRoot, newProfileName, overrideProps);

        // 2) Logging association (SSOT: LoggingAssignmentStore → conf/node/profiles.json).
        LoggingAssignmentStore store = new LoggingAssignmentStore(confRoot);
        String preset = store.getAssignment(sourceProfileName).get(ModuleIds.NODE);
        if (preset != null && !preset.isBlank()) {
            store.setAssignmentForModule(newProfileName, ModuleIds.NODE, preset);
        }

        LOGGER.info("Cloned node profile '{}' -> '{}' (logging preset: {})",
                sourceProfileName, newProfileName, preset);
        return created.getName();
    }

    // ── Create (empty default) ────────────────────────────────────────────

    /**
     * Creates a truly empty (zero-override) node profile (default conf root).
     * See {@link #createEmptyProfile(String, String)}.
     *
     * @throws IllegalArgumentException if the name is blank, invalid, reserved, or already exists
     * @throws IOException              on persistence failure
     */
    public static String createEmptyProfile(String profileName) throws IOException {
        return createEmptyProfile(NodeProfile.CONF_ROOT, profileName);
    }

    /**
     * Creates a truly empty (zero-override) node profile: the profile file
     * contains <b>no keys at all</b>, so every setting resolves to the
     * application's internal {@code Props} defaults. The profile is registered
     * with the default logging preset
     * (SSOT: {@link NodeProfile#DEFAULT_LOGGING_PRESET}).
     * <p>
     * Create-only semantics (this is a <i>create</i> action, not a lifecycle
     * action): the new profile is <b>not</b> started — no {@code NodeModule}
     * registration, no resource claiming. Tab registration is the caller's
     * responsibility (GUI layer).
     * </p>
     *
     * @param confRoot    the runtime conf root (e.g. {@code conf} or a test sandbox dir)
     * @param profileName the profile name (validated: pattern / reserved / taken)
     * @return the created profile name
     * @throws IllegalArgumentException if the name is blank, invalid, reserved, or already exists
     * @throws IOException              on persistence failure
     */
    static String createEmptyProfile(String confRoot, String profileName) throws IOException {
        Objects.requireNonNull(confRoot, "confRoot");
        // 1) Profile file with zero overrides (validates the name).
        NodeProfile created = NodeProfileRepository.createProfile(confRoot, profileName, new Properties());

        // 2) Logging association with the default preset
        //    (SSOT: LoggingAssignmentStore → conf/node/profiles.json).
        new LoggingAssignmentStore(confRoot).setAssignmentForModule(
                profileName, ModuleIds.NODE, NodeProfile.DEFAULT_LOGGING_PRESET);

        LOGGER.info("Created empty default node profile '{}' (logging preset: {})",
                profileName, NodeProfile.DEFAULT_LOGGING_PRESET);
        return created.getName();
    }

    // ── Rename ──────────────────────────────────────────────────────────

    /** How long the rename chain waits for a full node stop before aborting. */
    private static final long STOP_TIMEOUT_SECONDS = 30;
    /** How long the rename chain waits for the restarted node to reach RUNNING (pause re-apply). */
    private static final long RUNNING_TIMEOUT_SECONDS = 60;

    /**
     * Renames a node profile (default conf root). See
     * {@link #renameProfile(String, String, String, Runnable)}.
     *
     * @throws IllegalArgumentException if a name is blank/invalid/reserved, or the new name is taken
     * @throws IOException              if the node does not stop in time, or on persistence failure
     */
    public static void renameProfile(String oldProfileName, String newProfileName, Runnable onMetaRenamed)
            throws IOException {
        renameProfile(NodeProfile.CONF_ROOT, oldProfileName, newProfileName, onMetaRenamed);
    }

    /**
     * State-preserving rename of a running (or stopped) node profile — the node is
     * re-initialized in place, the application keeps running (no full app restart):
     * <ol>
     *   <li><b>State capture:</b> {@code wasRunning} (the node is RUNNING or STARTING — a
     *       stopped/failed node stays stopped, D3) and {@code wasUserPaused}
     *       (operating state {@code PAUSED_USER}).</li>
     *   <li><b>Stop</b> (only when running/starting) + <b>wait for the full stop</b> — this is
     *       the guarantee for the SQLite data-dir move below; a stop that does not finish in
     *       time aborts the rename (nothing is renamed yet). The instance registered under the
 *       old name is then <b>torn down</b> via {@code NodeModule.removeNode()} (port release +
 *       {@code Signum.dispose()}): its context no longer exists and the restart in step 5
 *       runs under the new name.</li>
     *   <li><b>Meta operations (atomic file moves):</b> the profile file + tab order + logging
     *       association ({@link NodeProfileRepository#renameProfile}), the {@code DB.Url}
     *       rewrite + SQLite data-dir move (SQLite only — a server-DB URL is left untouched),
     *       and the {@code profile.json} {@code appliedProfile} when it points at the old name.</li>
     *   <li><b>GUI reinitialization hook:</b> {@code onMetaRenamed} is invoked (on the calling
     *       thread, AFTER the meta operations succeeded and BEFORE the restart) — the caller
     *       uses it to rebuild the profile tab's panel under the new name.</li>
     *   <li><b>State restoration:</b> a running/starting node is started again under the new
     *       name; a user-paused node is paused again once it reaches RUNNING (the stop/start
     *       cycle clears the pause state, so it must be re-applied afterwards). A stopped node
     *       stays stopped.</li>
     * </ol>
     * <p>
     * <b>Blocking:</b> this method waits for the stop (and, for a user-paused node, for the
     * restarted node to reach RUNNING) — call it from a background thread, never the EDT.
     * Failures of the state-restoration step (step 5) are logged, not thrown: the rename itself
     * has already succeeded by then.
     * </p>
     *
     * @param confRoot       the runtime conf root (e.g. {@code conf} or a test sandbox dir)
     * @param oldProfileName the current profile name
     * @param newProfileName the new profile name (validated; must differ from the old name)
     * @param onMetaRenamed  called after the meta operations succeed (null is allowed)
     * @throws IllegalArgumentException if a name is blank/invalid/reserved, or the new name is taken
     * @throws IOException              if the node does not stop in time, or on persistence failure
     */
    static void renameProfile(String confRoot, String oldProfileName, String newProfileName,
            Runnable onMetaRenamed) throws IOException {
        Objects.requireNonNull(confRoot, "confRoot");
        Objects.requireNonNull(newProfileName, "newProfileName");
        if (oldProfileName == null || oldProfileName.isBlank()) {
            throw new IllegalArgumentException("Old profile name must not be null or blank");
        }
        if (oldProfileName.equals(newProfileName)) {
            throw new IllegalArgumentException("New name must differ from the current name");
        }

        NodeModule module = NodeModule.getInstance();
        Signum signum = module.get(oldProfileName);
        boolean wasRunning = signum != null
                && (signum.getState() == Signum.State.RUNNING || signum.getState() == Signum.State.STARTING);
        boolean wasUserPaused = wasRunning
                && signum.getOperatingState() == Signum.OperatingState.PAUSED_USER;

        // 1) Stop the running node and wait for the FULL stop (guarantees the SQLite
        //    data-dir move in step 3 runs against a closed database).
        if (wasRunning) {
            stopAndWait(module, signum, oldProfileName);
        }

        // 1b) The OLD NAME'S context is gone: the instance registered under it (stopped now,
        //     with a stale profile snapshot) will never be reused — the state restoration in
        //     step 4 starts a FRESH instance under the NEW name. NodeModule.stopNode() keeps
        //     instances registered by design (single-profile restart reuses them), so the
        //     rename must tear the old one down explicitly. removeNode() IS the permanent-
        //     removal hook: it releases the instance's ports and disposes it (closes the
        //     ProfileLogger + console subscribers, unregisters it from the NodeLoggerRegistry).
        if (signum != null) {
            module.removeNode(oldProfileName);
        }

        // 2) Meta operations (atomic file moves) — nothing is renamed before these run.
        NodeProfileRepository.renameProfile(confRoot, oldProfileName, newProfileName);
        rewriteSqliteDatabasePath(confRoot, oldProfileName, newProfileName);
        repointProfileJsonMetadata(confRoot, oldProfileName, newProfileName);

        // 3) The meta operations succeeded → the caller rebuilds the GUI (it marshals the EDT).
        if (onMetaRenamed != null) {
            onMetaRenamed.run();
        }

        // 4) State restoration (never fatal — the rename has already succeeded).
        if (wasRunning) {
            module.startNode(newProfileName);
            if (wasUserPaused) {
                reapplyUserPause(module, newProfileName);
            }
        }
        LOGGER.info("Renamed node profile '{}' -> '{}' (state restored: {})", oldProfileName,
                newProfileName,
                wasRunning ? (wasUserPaused ? "running + user-paused" : "running") : "stopped");
    }

    /**
     * Stops the given node and blocks until it is fully STOPPED (a terminal ERROR also releases
     * the wait — a node that errored out holds no open database).
     *
     * @throws IOException if the node does not stop within {@link #STOP_TIMEOUT_SECONDS} seconds
     */
    private static void stopAndWait(NodeModule module, Signum signum, String profileName) throws IOException {
        CountDownLatch stopped = new CountDownLatch(1);
        Signum.StateListener waiter = (s, oldState, newState) -> {
            if (newState == Signum.State.STOPPED || newState == Signum.State.ERROR) {
                stopped.countDown();
            }
        };
        signum.addStateListener(waiter);
        try {
            module.stopNode(profileName);
            if (!stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("The node did not stop within " + STOP_TIMEOUT_SECONDS
                        + " seconds — rename aborted (nothing was renamed)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the node to stop", e);
        } finally {
            signum.removeStateListener(waiter);
        }
    }

    /**
     * After the profile file has been moved, re-points a per-profile SQLite database to the new
     * name: when the (new) file's {@code DB.Url} still references the OLD profile's SQLite path
     * (SSOT: {@link ProfileCreateDefaults#sqliteDbUrl}), the property is rewritten to the new
     * name's URL and the data directory is moved with it. Any other URL (server DB —
     * mariadb/postgres, or a manually set path) is left untouched: remote databases are never
     * renamed.
     *
     * @throws IOException on persistence failure, or if the target data directory already exists
     */
    static void rewriteSqliteDatabasePath(String confRoot, String oldName, String newName) throws IOException {
        Path file = Paths.get(confRoot, NodeProfile.MODULE_ID, NodeProfile.CATEGORY, newName + ".properties");
        if (!Files.exists(file)) {
            return;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        if (!ProfileCreateDefaults.sqliteDbUrl(oldName).equals(props.getProperty(Props.DB_URL.getName()))) {
            return; // server-DB or manually managed URL — skip
        }
        props.setProperty(Props.DB_URL.getName(), ProfileCreateDefaults.sqliteDbUrl(newName));
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "Node profile: " + newName);
        }
        LOGGER.info("Renamed SQLite database path for profile '{}' -> '{}'", oldName, newName);
        moveSqliteDataDir(oldName, newName);
    }

    /**
     * Moves the per-profile SQLite data directory ({@code ./database/SQLite/<old>} →
     * {@code <new>}, SSOT: {@link ProfileCreateDefaults#sqliteDataDir}). No-op when the source
     * directory does not exist (a profile that was never started has no data).
     *
     * @throws IOException on move failure, or if the target directory already exists
     */
    static void moveSqliteDataDir(String oldName, String newName) throws IOException {
        Path from = ProfileCreateDefaults.sqliteDataDir(oldName);
        Path to = ProfileCreateDefaults.sqliteDataDir(newName);
        if (!Files.exists(from)) {
            return;
        }
        if (Files.exists(to)) {
            throw new IOException("SQLite data directory already exists: " + to);
        }
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
        LOGGER.info("Moved SQLite data directory: {} -> {}", from, to);
    }

    /**
     * Re-points the {@code profile.json} metadata that names the old profile after a rename:
     * <ul>
     *   <li>{@code appliedProfile} — when it points at the old name;</li>
     *   <li>{@code profileLinks.<oldName>} — the per-profile links object (linked database
     *       profile, auto-start/stop, legacy linked logging) is moved to the new name, so a
     *       renamed profile keeps its DB association.</li>
     * </ul>
     * The file is rewritten only when something changed (untouched files keep their format).
     */
    static void repointProfileJsonMetadata(String confRoot, String oldName, String newName) {
        Path profileJson = Paths.get(confRoot, NodeProfile.MODULE_ID, "profile.json");
        if (!Files.exists(profileJson)) {
            return;
        }
        JsonObject metadata;
        try (Reader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
            metadata = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("Failed to read profile.json after rename '{}' -> '{}'", oldName, newName, e);
            return;
        }
        boolean changed = false;
        if (metadata.has("appliedProfile") && oldName.equals(metadata.get("appliedProfile").getAsString())) {
            metadata.addProperty("appliedProfile", newName);
            changed = true;
        }
        if (metadata.has("profileLinks") && metadata.getAsJsonObject("profileLinks").has(oldName)) {
            JsonObject links = metadata.getAsJsonObject("profileLinks");
            links.add(newName, links.remove(oldName));
            changed = true;
        }
        if (!changed) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(profileJson, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata, writer);
            LOGGER.info("Re-pointed profile.json metadata after rename '{}' -> '{}'", oldName, newName);
        } catch (Exception e) {
            LOGGER.warn("Failed to update profile.json after rename '{}' -> '{}'", oldName, newName, e);
        }
    }

    /**
     * Re-applies the user pause after a rename restart: waits (bounded) for the restarted node
     * to reach RUNNING, then pauses it — the stop/start cycle clears the pause state, so the
     * pause must be applied afterwards. Failures are logged only (the rename has succeeded).
     */
    private static void reapplyUserPause(NodeModule module, String profileName) {
        Signum signum = module.get(profileName);
        if (signum == null) {
            LOGGER.warn("reapplyUserPause: no Signum registered for renamed profile '{}'", profileName);
            return;
        }
        CountDownLatch running = new CountDownLatch(1);
        Signum.StateListener waiter = (s, oldState, newState) -> {
            if (newState == Signum.State.RUNNING || newState == Signum.State.ERROR
                    || newState == Signum.State.STOPPED) {
                running.countDown();
            }
        };
        signum.addStateListener(waiter);
        try {
            if (!running.await(RUNNING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("Node '{}' did not reach RUNNING within {} s after rename — user pause not re-applied",
                        profileName, RUNNING_TIMEOUT_SECONDS);
                return;
            }
            if (signum.isRunning() && signum.getOperatingState() != Signum.OperatingState.PAUSED_USER) {
                signum.pauseByUser();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for the renamed node '{}' to run", profileName);
        } finally {
            signum.removeStateListener(waiter);
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────

    /**
     * Deletes a node profile (default conf root). See
     * {@link #deleteProfile(String, String, boolean)}.
     *
     * @param profileName the profile to delete
     * @param deleteData  whether the profile's per-profile SQLite data directory is deleted too
     * @throws IllegalArgumentException if the profile name is blank, reserved, or unknown
     * @throws IOException              if the node does not stop in time, or on persistence failure
     */
    public static void deleteProfile(String profileName, boolean deleteData) throws IOException {
        deleteProfile(NodeProfile.CONF_ROOT, profileName, deleteData);
    }

    /**
     * Deletes a node profile at runtime — the node is stopped (the application keeps running,
     * and the node is <b>not</b> restarted afterwards):
     * <ol>
     *   <li><b>Validation</b> — the name must not be blank, the profile must exist, and a
     *       reserved profile cannot be deleted (validated BEFORE any runtime side effect).</li>
     *   <li><b>Stop</b> (only when running/starting) + <b>wait for the full stop</b> — this is
     *       the guarantee for the SQLite data-dir deletion below.</li>
     *   <li><b>Registry teardown</b> via {@code NodeModule.removeNode()} — the permanent-removal
     *       hook (port release + {@code Signum.dispose()}); a stopped instance is removed too,
     *       since a deleted profile must never leave a ghost under its dead name.</li>
     *   <li><b>Meta operations:</b> the profile file + tab order + logging association
     *       ({@link NodeProfileRepository#deleteProfile} — which also clears the canonical
     *       logging presets) and the {@code profile.json} {@code appliedProfile} /
     *       {@code profileLinks} entries that name the deleted profile.</li>
     *   <li><b>Optional data deletion:</b> the per-profile SQLite data directory, but only when
     *       {@code deleteData} is set AND the profile actually uses its per-profile SQLite
     *       database (SSOT: {@link ProfileCreateDefaults#isPerProfileSqliteUrl}) — a server-DB
     *       or manually managed URL is left untouched.</li>
     * </ol>
     * <p>
     * The GUI tab removal (Swing) is the caller's responsibility
     * ({@code NodePanel.removeProfileTab}) — the profile file is already gone when it runs,
     * and its repository call is a tolerated no-op then. Other node profiles (files, metadata,
     * runtime state) are left untouched.
     * </p>
     *
     * @param confRoot    the runtime conf root (e.g. {@code conf} or a test sandbox dir)
     * @param profileName the profile to delete
     * @param deleteData  whether the profile's per-profile SQLite data directory is deleted too
     * @throws IllegalArgumentException if the profile name is blank, reserved, or unknown
     * @throws IOException              if the node does not stop in time, or on persistence failure
     */
    static void deleteProfile(String confRoot, String profileName, boolean deleteData) throws IOException {
        Objects.requireNonNull(confRoot, "confRoot");
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be null or blank");
        }
        if (NodeProfileRepository.isReservedProfileName(profileName)) {
            throw new IllegalArgumentException("The reserved profile '" + profileName + "' cannot be deleted");
        }
        Path profileFile = Paths.get(confRoot, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                profileName + ".properties");
        if (!Files.exists(profileFile)) {
            throw new IllegalArgumentException("Profile '" + profileName + "' not found");
        }

        // Capture the database target BEFORE the profile file is deleted in step 4.
        boolean perProfileSqlite = usesPerProfileSqliteDatabase(confRoot, profileName);

        NodeModule module = NodeModule.getInstance();
        Signum signum = module.get(profileName);
        boolean wasRunning = signum != null
                && (signum.getState() == Signum.State.RUNNING || signum.getState() == Signum.State.STARTING);

        // 1) Stop the running node and wait for the FULL stop (guarantees the SQLite
        //    data-dir deletion in step 5 runs against a closed database).
        if (wasRunning) {
            stopAndWait(module, signum, profileName);
        }

        // 2) Permanent registry teardown (port release + Signum.dispose()) — the same hook the
        //    rename uses for the old name. A stopped instance is removed as well, since a
        //    deleted profile must never be restarted.
        if (signum != null) {
            module.removeNode(profileName);
        }

        // 3) Meta operations: profile file + tab order + logging association
        //    (SSOT: NodeProfileRepository — also clears the canonical logging presets).
        NodeProfileRepository.deleteProfile(confRoot, profileName);
        stripProfileJsonMetadata(confRoot, profileName);

        // 4) Optional: the per-profile SQLite data directory.
        boolean dataDeleted = deleteData && perProfileSqlite;
        if (dataDeleted) {
            deleteSqliteDataDir(profileName);
        }
        LOGGER.info("Deleted node profile '{}' (registry: removed, SQLite data: {})",
                profileName, dataDeleted ? "deleted" : "kept");
    }

    // ── Delete helpers ──────────────────────────────────────────────────

    /**
     * Whether a profile's {@code DB.Url} is its per-profile default SQLite database
     * (SSOT: {@link ProfileCreateDefaults#isPerProfileSqliteUrl}) — the signal that the
     * database data directory belongs to the profile. A missing or unreadable profile file
     * reports {@code false}.
     *
     * @param profileName the profile name
     * @return true when the profile uses its per-profile SQLite database
     */
    public static boolean usesPerProfileSqliteDatabase(String profileName) {
        return usesPerProfileSqliteDatabase(NodeProfile.CONF_ROOT, profileName);
    }

    static boolean usesPerProfileSqliteDatabase(String confRoot, String profileName) {
        Path file = Paths.get(confRoot, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                profileName + ".properties");
        if (!Files.exists(file)) {
            return false;
        }
        try {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return ProfileCreateDefaults.isPerProfileSqliteUrl(profileName,
                    props.getProperty(Props.DB_URL.getName()));
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve the database of profile '{}'", profileName, e);
            return false;
        }
    }

    /**
     * Recursively deletes the per-profile SQLite data directory
     * ({@code ./database/SQLite/<name>}, SSOT: {@link ProfileCreateDefaults#sqliteDataDir}).
     * No-op when the directory does not exist (a profile that was never started has no data).
     *
     * @param profileName the profile whose data directory is deleted
     * @throws IOException on delete failure
     */
    static void deleteSqliteDataDir(String profileName) throws IOException {
        Path dir = ProfileCreateDefaults.sqliteDataDir(profileName);
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        LOGGER.info("Deleted SQLite data directory for profile '{}': {}", profileName, dir);
    }

    /**
     * Removes the {@code profile.json} metadata that names a deleted profile:
     * <ul>
     *   <li>{@code appliedProfile} — removed when it points at the deleted profile
     *       (it would reference a profile that no longer exists);</li>
     *   <li>{@code profileLinks.<name>} — the deleted profile's links object (linked database
     *       profile, auto-start/stop, legacy linked logging) is removed, so a linked database
     *       profile is no longer associated (or auto-started) for a profile that is gone.</li>
     * </ul>
     * The file is rewritten only when something changed (untouched files keep their format).
     */
    static void stripProfileJsonMetadata(String confRoot, String profileName) {
        Path profileJson = Paths.get(confRoot, NodeProfile.MODULE_ID, "profile.json");
        if (!Files.exists(profileJson)) {
            return;
        }
        JsonObject metadata;
        try (Reader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
            metadata = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("Failed to read profile.json after delete '{}'", profileName, e);
            return;
        }
        boolean changed = false;
        if (metadata.has("appliedProfile") && profileName.equals(metadata.get("appliedProfile").getAsString())) {
            metadata.remove("appliedProfile");
            changed = true;
        }
        if (metadata.has("profileLinks") && metadata.getAsJsonObject("profileLinks").has(profileName)) {
            metadata.getAsJsonObject("profileLinks").remove(profileName);
            changed = true;
        }
        if (!changed) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(profileJson, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata, writer);
            LOGGER.info("Stripped profile.json metadata after delete '{}'", profileName);
        } catch (Exception e) {
            LOGGER.warn("Failed to update profile.json after delete '{}'", profileName, e);
        }
    }
}
