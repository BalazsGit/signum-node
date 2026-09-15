package application.module.node.gui;

import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.utils.gui.GuiColors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the SSOT node status resolver {@link NodeStateIcon}: the combined
 * (lifecycle + operating + maintenance + start-pending) state must map to a single,
 * consistent icon + label using the documented priority
 * {@code ERROR > STARTING/STOPPING > trim/prune > paused > generating > running}.
 * <p>
 * The resolver is pure (no Signum instance required), so each state combination can be
 * asserted directly — this is the single mapping shared by the tab header, info bar and
 * toolbar, so a change here is verified in one place.
 */
@DisplayName("NodeStateIcon SSOT resolver")
class NodeStateIconTest {

    private static final BlockchainProcessor.ArchivalMaintenanceState IDLE =
            BlockchainProcessor.ArchivalMaintenanceState.IDLE;
    private static final BlockchainProcessor.ArchivalMaintenanceState TRIM =
            BlockchainProcessor.ArchivalMaintenanceState.TRIMMING;
    private static final BlockchainProcessor.ArchivalMaintenanceState PRUNE =
            BlockchainProcessor.ArchivalMaintenanceState.PRUNING;

    // ── Lifecycle states ──────────────────────────────────────────────────

    @Test
    @DisplayName("ERROR always resolves to the error status, even over running + maintenance")
    void error_wins_over_everything() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.ERROR,
                Signum.OperatingState.SYNC_IDLE, PRUNE, false);
        assertEquals("Error", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("INITIALIZED resolves to a ready status")
    void initialized_is_ready() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.INITIALIZED, null, IDLE, false);
        assertEquals("Ready", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("STOPPED resolves to a stopped status")
    void stopped() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.STOPPED, null, IDLE, false);
        assertEquals("Stopped", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("CREATED resolves to a created status")
    void created() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.CREATED, null, IDLE, false);
        assertEquals("Created", res.label());
        assertNotNull(res.icon());
    }

    // ── Transitions ───────────────────────────────────────────────────────

    @Test
    @DisplayName("STARTING resolves to a starting transition status")
    void starting() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.STARTING, null, IDLE, false);
        assertEquals("Starting…", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("STOPPING resolves to a stopping transition status")
    void stopping() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.STOPPING, null, IDLE, false);
        assertEquals("Stopping…", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("A queued/running start (startPending) renders as the starting transition")
    void startPending_renders_starting() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.STOPPED, null, IDLE, true);
        assertEquals("Starting…", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("A transition wins over a concurrent maintenance phase")
    void transition_wins_over_maintenance() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.STARTING,
                Signum.OperatingState.PAUSED_USER, TRIM, false);
        assertEquals("Starting…", res.label());
    }

    // ── Running + operating + maintenance ─────────────────────────────────

    @Test
    @DisplayName("Running + paused (user) resolves to a paused status")
    void running_paused() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.PAUSED_USER, IDLE, false);
        assertEquals("Paused", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("Running + paused (system) also resolves to a paused status")
    void running_paused_system() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.PAUSED_SYSTEM, IDLE, false);
        assertEquals("Paused", res.label());
    }

    @Test
    @DisplayName("Running + generating resolves to a generating status")
    void running_generating() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.GENERATING, IDLE, false);
        assertEquals("Generating…", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("Running + syncing resolves to a syncing status")
    void running_syncing() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.SYNCING, IDLE, false);
        assertEquals("Syncing…", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("Running + idle resolves to a running status")
    void running_idle() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.SYNC_IDLE, IDLE, false);
        assertEquals("Running", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("Running + trimming resolves to a trimming maintenance status (even while paused)")
    void running_trimming() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.PAUSED_USER, TRIM, false);
        assertEquals("Trimming…", res.label());
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("Running + pruning resolves to a pruning maintenance status")
    void running_pruning() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.SYNC_IDLE, PRUNE, false);
        assertEquals("Pruning…", res.label());
        assertNotNull(res.icon());
    }

    // ── Consistency (the SSOT guarantee) ──────────────────────────────────

    @Test
    @DisplayName("The same combined state always resolves to the same label")
    void resolution_is_deterministic() {
        NodeStateIcon.Res a = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.GENERATING, IDLE, false);
        NodeStateIcon.Res b = NodeStateIcon.resolve(Signum.State.RUNNING,
                Signum.OperatingState.GENERATING, IDLE, false);
        assertEquals(a.label(), b.label());
        assertNotNull(a.icon());
        assertNotNull(b.icon());
    }

    @Test
    @DisplayName("Null / unknown inputs fall back to a defined created status (never throws)")
    void null_inputs_are_safe() {
        NodeStateIcon.Res res = NodeStateIcon.resolve(null, null, null, false);
        assertNotNull(res);
        assertNotNull(res.icon());
    }

    @Test
    @DisplayName("The SSOT state colors are wired to non-null palette entries")
    void ssot_state_colors_are_wired() {
        assertNotNull(GuiColors.getTransition(), "gui.state.transition must be defined");
        assertNotNull(GuiColors.getReady(), "gui.state.ready must be defined");
        assertNotNull(GuiColors.getPaused(), "gui.state.paused must be defined");
    }
}