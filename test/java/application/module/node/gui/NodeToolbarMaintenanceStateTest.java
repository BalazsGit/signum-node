package application.module.node.gui;

import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.module.node.util.Listener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the push-based DB archival maintenance state indicator in {@link NodeToolbar}
 * (v4 §9.6): the toolbar must subscribe to TRIM_START/END and PRUNE_START/END events,
 * update its status label on the EDT when a phase begins/ends, and detach the listeners
 * when the Signum facade changes. No polling anywhere.
 */
@DisplayName("NodeToolbar maintenance state (push listeners)")
class NodeToolbarMaintenanceStateTest {

    private static final String PROFILE = "toolbar-maintenance-test";

    /**
     * Headless stand-in for BlockchainProcessor: configurable
     * {@link BlockchainProcessor.ArchivalMaintenanceState} plus capture of the
     * trim/prune listeners so tests can fire pushed events.
     */
    static final class FakeProcessor {
        final AtomicReference<BlockchainProcessor.ArchivalMaintenanceState> state =
                new AtomicReference<>(BlockchainProcessor.ArchivalMaintenanceState.IDLE);
        final Map<BlockchainProcessor.Event, List<Listener<?>>> trimListeners = new ConcurrentHashMap<>();
        final Map<BlockchainProcessor.Event, List<Listener<?>>> pruneListeners = new ConcurrentHashMap<>();
        final BlockchainProcessor proxy;

        FakeProcessor() {
            InvocationHandler handler = (p, method, args) -> {
                switch (method.getName()) {
                    case "getArchivalMaintenanceState":
                        return state.get();
                    case "isTrimming":
                        return state.get() == BlockchainProcessor.ArchivalMaintenanceState.TRIMMING;
                    case "isPruning":
                        return state.get() == BlockchainProcessor.ArchivalMaintenanceState.PRUNING;
                    case "addTrimListener":
                        trimListeners.computeIfAbsent((BlockchainProcessor.Event) args[1],
                                k -> new CopyOnWriteArrayList<>()).add((Listener<?>) args[0]);
                        return null;
                    case "removeTrimListener":
                        List<Listener<?>> tl = trimListeners.get((BlockchainProcessor.Event) args[1]);
                        if (tl != null) {
                            tl.remove(args[0]);
                        }
                        return null;
                    case "addPruneListener":
                        pruneListeners.computeIfAbsent((BlockchainProcessor.Event) args[1],
                                k -> new CopyOnWriteArrayList<>()).add((Listener<?>) args[0]);
                        return null;
                    case "removePruneListener":
                        List<Listener<?>> pl = pruneListeners.get((BlockchainProcessor.Event) args[1]);
                        if (pl != null) {
                            pl.remove(args[0]);
                        }
                        return null;
                    case "toString":
                        return "FakeBlockchainProcessor";
                    case "hashCode":
                        return System.identityHashCode(p);
                    case "equals":
                        return p == args[0];
                    default:
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) {
                            return false;
                        }
                        if (rt == int.class) {
                            return 0;
                        }
                        if (rt == long.class) {
                            return 0L;
                        }
                        return null;
                }
            };
            proxy = (BlockchainProcessor) Proxy.newProxyInstance(
                    BlockchainProcessor.class.getClassLoader(),
                    new Class<?>[]{BlockchainProcessor.class}, handler);
        }

        void fireTrimStart() {
            trimListeners.get(BlockchainProcessor.Event.TRIM_START).forEach(l -> l.notify(null));
        }

        void fireTrimEnd() {
            trimListeners.get(BlockchainProcessor.Event.TRIM_END).forEach(l -> l.notify(null));
        }

        void firePruneStart() {
            pruneListeners.get(BlockchainProcessor.Event.PRUNE_START).forEach(l -> l.notify(null));
        }

        void firePruneEnd() {
            pruneListeners.get(BlockchainProcessor.Event.PRUNE_END).forEach(l -> l.notify(null));
        }
    }

    private static Signum signumWith(BlockchainProcessor processor) throws Exception {
        Signum signum = new Signum(new NodeProfile(PROFILE), Paths.get("./conf"));
        Field f = Signum.class.getDeclaredField("blockchainProcessor");
        f.setAccessible(true);
        f.set(signum, processor);
        return signum;
    }

    private static JLabel maintenanceLabelOf(NodeToolbar toolbar) throws Exception {
        Field f = NodeToolbar.class.getDeclaredField("maintenanceLabel");
        f.setAccessible(true);
        return (JLabel) f.get(toolbar);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    @DisplayName("trim/prune listeners are attached on setSignum and detached when the facade leaves")
    void listenersAttachedAndDetached() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));

        toolbar.setSignum(signumWith(fake.proxy));
        assertEquals(1, fake.trimListeners.get(BlockchainProcessor.Event.TRIM_START).size());
        assertEquals(1, fake.trimListeners.get(BlockchainProcessor.Event.TRIM_END).size());
        assertEquals(1, fake.pruneListeners.get(BlockchainProcessor.Event.PRUNE_START).size());
        assertEquals(1, fake.pruneListeners.get(BlockchainProcessor.Event.PRUNE_END).size());

        toolbar.setSignum(null);
        assertTrue(fake.trimListeners.values().stream().allMatch(List::isEmpty),
                "trim listeners must be detached");
        assertTrue(fake.pruneListeners.values().stream().allMatch(List::isEmpty),
                "prune listeners must be detached");
    }

    @Test
    @DisplayName("TRIM_START push shows the trimming indicator")
    void trimmingShownOnTrimStartPush() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();

        fake.state.set(BlockchainProcessor.ArchivalMaintenanceState.TRIMMING);
        fake.fireTrimStart();
        flushEdt();

        JLabel label = maintenanceLabelOf(toolbar);
        assertTrue(label.isVisible(), "maintenance label must be visible while trimming");
        assertTrue(label.getText().contains("trimming"), "label must mention trimming: " + label.getText());
    }

    @Test
    @DisplayName("PRUNE_START push shows the pruning indicator")
    void pruningShownOnPruneStartPush() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();

        fake.state.set(BlockchainProcessor.ArchivalMaintenanceState.PRUNING);
        fake.firePruneStart();
        flushEdt();

        JLabel label = maintenanceLabelOf(toolbar);
        assertTrue(label.isVisible(), "maintenance label must be visible while pruning");
        assertTrue(label.getText().contains("pruning"), "label must mention pruning: " + label.getText());
    }

    @Test
    @DisplayName("PRUNE_END push (state IDLE) hides the indicator again")
    void hiddenAfterPruneEnd() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));

        fake.state.set(BlockchainProcessor.ArchivalMaintenanceState.PRUNING);
        fake.firePruneStart();
        flushEdt();
        assertTrue(maintenanceLabelOf(toolbar).isVisible());

        fake.state.set(BlockchainProcessor.ArchivalMaintenanceState.IDLE);
        fake.firePruneEnd();
        flushEdt();
        assertFalse(maintenanceLabelOf(toolbar).isVisible(), "label must hide when maintenance is IDLE");
    }

    @Test
    @DisplayName("attaching while a maintenance is already running shows it immediately (initial sync)")
    void initialSyncShowsRunningMaintenance() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        fake.state.set(BlockchainProcessor.ArchivalMaintenanceState.TRIMMING);

        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();

        JLabel label = maintenanceLabelOf(toolbar);
        assertTrue(label.isVisible(), "label must reflect an already-running maintenance on attach");
        assertTrue(label.getText().contains("trimming"));
    }
}