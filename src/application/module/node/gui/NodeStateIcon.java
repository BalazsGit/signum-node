package application.module.node.gui;

import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.utils.gui.GuiIcons;

import javax.swing.Icon;

/**
 * Single source of truth (SSOT) for node status icons and colors across every UI surface
 * (the profile tab header, the info bar and the toolbar).
 * <p>
 * A node has <b>three</b> orthogonal state dimensions that were historically mapped to
 * icons/colors independently (and inconsistently) by each surface:
 * <ol>
 *   <li>{@link Signum.State} — the lifecycle state (CREATED … RUNNING … ERROR)</li>
 *   <li>{@link Signum.OperatingState} — the operating mode (SYNCING, PAUSED, GENERATING, …)</li>
 *   <li>{@link BlockchainProcessor.ArchivalMaintenanceState} — archival maintenance (TRIMMING / PRUNING)</li>
 * </ol>
 * This class collapses those three dimensions (plus the "start pending" flag) into a
 * single {@link Res} (icon + human-readable label) using one explicit priority order:
 * <pre>
 *   ERROR &gt; STARTING/STOPPING &gt; trim/prune &gt; paused &gt; generating &gt; running
 * </pre>
 * All surfaces must resolve their node status through {@link #resolve(...)} so the icon
 * and color are always identical for the same combined state — a single place to change
 * the mapping when a new state or surface is introduced.
 *
 * @see GuiIcons
 */
public final class NodeStateIcon {

    private NodeStateIcon() {
        // Prevent instantiation
    }

    /** A resolved status: the {@link Icon} to render plus a human-readable label (for tooltips). */
    public record Res(Icon icon, String label) {
    }

    /**
     * Resolves the node's combined state to a single status icon + label.
     * <p>
     * Priority (highest first):
     * <ol>
     *   <li>ERROR — any error always wins.</li>
     *   <li>STARTING / STOPPING (or a queued/running start) — a transition in progress.</li>
     *   <li>Archival maintenance (TRIMMING / PRUNING) — running, but doing DB maintenance.</li>
     *   <li>Paused (PAUSED_USER / PAUSED_SYSTEM) — running, sync paused.</li>
     *   <li>Generating (GENERATING) — running, producing blocks.</li>
     *   <li>Running (SYNCING / SYNC_IDLE) — running, syncing or idle.</li>
     *   <li>Otherwise the lifecycle state (READY / STOPPED / CREATED).</li>
     * </ol>
     *
     * @param state       the node lifecycle state (may be null → treated as CREATED)
     * @param operating   the node operating state (nullable)
     * @param maintenance the archival maintenance state (nullable / IDLE when not in maintenance)
     * @param startPending true while a start is queued/running but the state push has not arrived
     * @return the resolved icon + label (never null)
     */
    public static Res resolve(Signum.State state,
                              Signum.OperatingState operating,
                              BlockchainProcessor.ArchivalMaintenanceState maintenance,
                              boolean startPending) {
        int size = GuiIcons.sizeSmall();

        if (state == Signum.State.ERROR) {
            return new Res(GuiIcons.error(size), "Error");
        }

        if (state == Signum.State.STARTING || state == Signum.State.STOPPING || startPending) {
            boolean stopping = state == Signum.State.STOPPING;
            return new Res(GuiIcons.stopping(size), stopping ? "Stopping…" : "Starting…");
        }

        if (state == Signum.State.RUNNING) {
            if (maintenance == BlockchainProcessor.ArchivalMaintenanceState.TRIMMING
                    || maintenance == BlockchainProcessor.ArchivalMaintenanceState.PRUNING) {
                boolean pruning = maintenance == BlockchainProcessor.ArchivalMaintenanceState.PRUNING;
                return new Res(GuiIcons.maintenance(size), pruning ? "Pruning…" : "Trimming…");
            }
            if (operating == Signum.OperatingState.PAUSED_USER
                    || operating == Signum.OperatingState.PAUSED_SYSTEM) {
                return new Res(GuiIcons.paused(size), "Paused");
            }
            if (operating == Signum.OperatingState.GENERATING) {
                return new Res(GuiIcons.generating(size), "Generating…");
            }
            // SYNC_IDLE / SYNCING → running (green).
            String label = operating == Signum.OperatingState.SYNCING ? "Syncing…" : "Running";
            return new Res(GuiIcons.running(size), label);
        }

        if (state == Signum.State.INITIALIZED) {
            return new Res(GuiIcons.ready(size), "Ready");
        }

        if (state == Signum.State.STOPPED) {
            return new Res(GuiIcons.stopped(size), "Stopped");
        }

        // CREATED (and anything unknown) → a neutral "created" icon.
        return new Res(GuiIcons.created(size), "Created");
    }
}