package application.module.node.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import application.module.node.profile.NodeProfile;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@code MetricsPanel} lazy/background-construction guard that was added
 * when the first-time construction (four {@code JFreeChart} sub-panels, ~15 s) was moved off the
 * EDT. Constructing it synchronously on the EDT froze the whole window.
 *
 * <p>Guarantees under test:
 * <ul>
 *   <li>a second {@code updateMetricsPanelState(true)} while a construction is in flight is a safe
 *       no-op — it must <b>not</b> start a second (wasteful) background build (resource guard),</li>
 *   <li>{@code disposeStaleMetricsPanel()} bumps the generation counter so an in-flight background
 *       build discards its now-stale result instead of installing it.</li>
 * </ul>
 * Headless-safe: the panel is constructed but never shown, and the heavy build is never triggered
 * (the in-flight flag is seeded via reflection, so only the guard path runs).</p>
 */
@DisplayName("NodeConsolePanel - MetricsPanel background-construction guard")
class NodeConsolePanelMetricsPanelTest {

    private static int seq = 0;
    private static String name() { return "metrics-guard-test-" + (seq++); }

    private static Class<?>[] paramTypes(Object... args) {
        Class<?>[] c = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Boolean) c[i] = boolean.class;
            else if (args[i] instanceof Integer) c[i] = int.class;
            else if (args[i] instanceof Long) c[i] = long.class;
            else c[i] = args[i].getClass();
        }
        return c;
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object readField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void writeField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Invokes a private method on the EDT (the state handler is EDT-bound by design). */
    private static void invokeOnEdt(Object target, String method, Object... args) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
                        java.lang.reflect.Method mm;
                        try {
                            mm = k.getDeclaredMethod(method, paramTypes(args));
                        } catch (NoSuchMethodException notHere) {
                            continue;
                        }
                        mm.setAccessible(true);
                        mm.invoke(target, args);
                        return;
                    }
                    throw new NoSuchMethodException(method);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    @DisplayName("show while a construction is in flight is a safe no-op (no double build)")
    void show_whileLoading_isSafeNoOp() throws Exception {
        NodeConsolePanel panel = new NodeConsolePanel(null, new NodeProfile(name()));
        // Seed the in-flight flag (a background build is "underway"); metricsPanel stays null.
        writeField(panel, "metricsPanelLoading", Boolean.TRUE);

        // Must not throw and must NOT construct a second panel.
        invokeOnEdt(panel, "updateMetricsPanelState", Boolean.TRUE);

        assertNull(readField(panel, "metricsPanel"),
                "a second MetricsPanel must not be built while one is still loading");
    }

    @Test
    @DisplayName("dispose invalidates an in-flight construction via the generation counter")
    void dispose_bumpsGeneration_soInFlightWorkerDiscards() throws Exception {
        NodeConsolePanel panel = new NodeConsolePanel(null, new NodeProfile(name()));
        int before = (Integer) readField(panel, "metricsPanelGeneration");

        invokeOnEdt(panel, "disposeStaleMetricsPanel");

        int after = (Integer) readField(panel, "metricsPanelGeneration");
        assertTrue(after > before, "dispose must invalidate any in-flight background construction");
    }
}