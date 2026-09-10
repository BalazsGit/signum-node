package application.module.node.gui;

import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.module.node.util.Listener;
import application.utils.gui.GuiColors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeToolbar DB check icon color")
class NodeToolbarDbCheckIconTest {

    private static final String PROFILE = "toolbar-dbcheck-icon-test";

    static final class FakeProcessor {
        final AtomicReference<BlockchainProcessor.ConsistencyState> consistency =
                new AtomicReference<>(BlockchainProcessor.ConsistencyState.UNDEFINED);
        final Map<BlockchainProcessor.Event, List<Listener<?>>> blockListeners = new ConcurrentHashMap<>();
        final Map<BlockchainProcessor.Event, List<Listener<?>>> trimListeners  = new ConcurrentHashMap<>();
        final Map<BlockchainProcessor.Event, List<Listener<?>>> pruneListeners = new ConcurrentHashMap<>();
        final BlockchainProcessor proxy;

        FakeProcessor() {
            InvocationHandler handler = (p, method, args) -> {
                switch (method.getName()) {
                    case "getConsistencyState": return consistency.get();
                    case "addListener": {
                        @SuppressWarnings("unchecked") BlockchainProcessor.Event evt1 = (BlockchainProcessor.Event) args[1];
                        blockListeners.computeIfAbsent(evt1, k -> new CopyOnWriteArrayList<>())
                                .add((Listener<?>) args[0]);
                        return true;
                    }
                    case "removeListener": {
                        @SuppressWarnings("unchecked") BlockchainProcessor.Event evt2 = (BlockchainProcessor.Event) args[1];
                        List<Listener<?>> list = blockListeners.get(evt2);
                        if (list != null) list.remove(args[0]);
                        return true;
                    }
                    case "addTrimListener":
                        trimListeners.computeIfAbsent((BlockchainProcessor.Event) args[1],
                                k -> new CopyOnWriteArrayList<>()).add((Listener<?>) args[0]);
                        return null;
                    case "removeTrimListener": {
                        List<Listener<?>> tl = trimListeners.get((BlockchainProcessor.Event) args[1]);
                        if (tl != null) tl.remove(args[0]);
                        return null;
                    }
                    case "addPruneListener":
                        pruneListeners.computeIfAbsent((BlockchainProcessor.Event) args[1],
                                k -> new CopyOnWriteArrayList<>()).add((Listener<?>) args[0]);
                        return null;
                    case "removePruneListener": {
                        List<Listener<?>> pl = pruneListeners.get((BlockchainProcessor.Event) args[1]);
                        if (pl != null) pl.remove(args[0]);
                        return null;
                    }
                    case "getArchivalMaintenanceState":
                        return BlockchainProcessor.ArchivalMaintenanceState.IDLE;
                    case "isTrimming": case "isPruning": case "isSkipDbCheckOnManualPopOff":
                        return false;
                    case "toString": return "FakeProcessor";
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == args[0];
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class)    return 0;
                        if (method.getReturnType() == long.class)   return 0L;
                        if (method.getReturnType() == double.class) return 0.0;
                        return null;
                }
            };
            proxy = (BlockchainProcessor) Proxy.newProxyInstance(
                    BlockchainProcessor.class.getClassLoader(),
                    new Class<?>[]{BlockchainProcessor.class}, handler);
        }

        void fireConsistencyUpdate() {
            blockListeners.getOrDefault(BlockchainProcessor.Event.DATABASE_CONSISTENCY_UPDATE, List.of())
                    .forEach(l -> l.notify(null));
        }
    }

    private static Signum signumWith(BlockchainProcessor processor) throws Exception {
        Signum signum = new Signum(new NodeProfile(PROFILE), java.nio.file.Paths.get("./conf"));
        java.lang.reflect.Field f = Signum.class.getDeclaredField("blockchainProcessor");
        f.setAccessible(true);
        f.set(signum, processor);
        return signum;
    }

    private static void flushEdt() throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(latch::countDown);
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(50);
    }

    /**
     * Renders an Icon to a BufferedImage and returns the most common non-transparent RGB colour.
     */
    static Color dominantColorOf(Icon icon) {
        if (icon == null) return null;
        int w = Math.max(1, icon.getIconWidth());
        int h = Math.max(1, icon.getIconHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(new javax.swing.JPanel(), g, 0, 0);
        g.dispose();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = img.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha > 128) {
                    int rgb = argb & 0xFFFFFF;
                    counts.merge(rgb, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> new Color(e.getKey()))
                .orElse(null);
    }

    private static boolean colorsAreClose(Color a, Color b) {
        int dr = a.getRed()   - b.getRed();
        int dg = a.getGreen() - b.getGreen();
        int db = a.getBlue()  - b.getBlue();
        return (dr * dr + dg * dg + db * db) <= 64;
    }

    @Test
    @DisplayName("CONSISTENT state → DB check icon is green")
    void consistentState_IconIsGreen() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        fake.consistency.set(BlockchainProcessor.ConsistencyState.CONSISTENT);
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();
        JButton dbBtn = toolbar.getDbCheckButton();
        assertNotNull(dbBtn.getIcon(), "DB check button must have an icon");
        Color dominant = dominantColorOf(dbBtn.getIcon());
        assertNotNull(dominant, "icon must render to a non-transparent pixel");
        Color expected = GuiColors.getStatusConsistent();
        assertTrue(colorsAreClose(dominant, expected),
                "CONSISTENT icon should be green (expected " + expected + ", got " + dominant + ")");
    }

    @Test
    @DisplayName("INCONSISTENT state → DB check icon is red")
    void inconsistentState_IconIsRed() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        fake.consistency.set(BlockchainProcessor.ConsistencyState.INCONSISTENT);
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();
        JButton dbBtn = toolbar.getDbCheckButton();
        assertNotNull(dbBtn.getIcon(), "DB check button must have an icon");
        Color dominant = dominantColorOf(dbBtn.getIcon());
        assertNotNull(dominant, "icon must render to a non-transparent pixel");
        Color expected = GuiColors.getContrastRed();
        assertTrue(colorsAreClose(dominant, expected),
                "INCONSISTENT icon should be red (expected " + expected + ", got " + dominant + ")");
    }

    @Test
    @DisplayName("UNDEFINED state → DB check icon is default button-icon colour")
    void undefinedState_IconIsDefault() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        fake.consistency.set(BlockchainProcessor.ConsistencyState.UNDEFINED);
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();
        JButton dbBtn = toolbar.getDbCheckButton();
        assertNotNull(dbBtn.getIcon(), "DB check button must have an icon");
        Color dominant = dominantColorOf(dbBtn.getIcon());
        assertNotNull(dominant, "icon must render to a non-transparent pixel");
        Color expected = GuiColors.getButtonIcon();
        assertTrue(colorsAreClose(dominant, expected),
                "UNDEFINED icon should be default (expected " + expected + ", got " + dominant + ")");
    }

    @Test
    @DisplayName("DATABASE_CONSISTENCY_UPDATE event → icon updates to green")
    void consistencyEvent_IconTurnsGreen() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        fake.consistency.set(BlockchainProcessor.ConsistencyState.UNDEFINED);
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();
        fake.consistency.set(BlockchainProcessor.ConsistencyState.CONSISTENT);
        fake.fireConsistencyUpdate();
        flushEdt();
        Color dominant = dominantColorOf(toolbar.getDbCheckButton().getIcon());
        assertNotNull(dominant, "icon must render to a non-transparent pixel");
        Color expected = GuiColors.getStatusConsistent();
        assertTrue(colorsAreClose(dominant, expected),
                "after CONSISTENT event, icon should be green (expected " + expected + ", got " + dominant + ")");
    }

    @Test
    @DisplayName("DATABASE_CONSISTENCY_UPDATE event → icon updates to red")
    void consistencyEvent_IconTurnsRed() throws Exception {
        FakeProcessor fake = new FakeProcessor();
        fake.consistency.set(BlockchainProcessor.ConsistencyState.CONSISTENT);
        NodeToolbar toolbar = new NodeToolbar(new NodeProfile(PROFILE));
        toolbar.setSignum(signumWith(fake.proxy));
        flushEdt();
        fake.consistency.set(BlockchainProcessor.ConsistencyState.INCONSISTENT);
        fake.fireConsistencyUpdate();
        flushEdt();
        Color dominant = dominantColorOf(toolbar.getDbCheckButton().getIcon());
        assertNotNull(dominant, "icon must render to a non-transparent pixel");
        Color expected = GuiColors.getContrastRed();
        assertTrue(colorsAreClose(dominant, expected),
                "after INCONSISTENT event, icon should be red (expected " + expected + ", got " + dominant + ")");
    }
}