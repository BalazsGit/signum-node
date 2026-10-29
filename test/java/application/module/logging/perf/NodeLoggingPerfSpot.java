package application.module.logging.perf;

import application.module.logging.context.NodeLoggers;
import application.module.logging.context.NodeLoggingContext;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P7.1 "perf-spot" ({@code node_isolated_loggercontext_logging_plan.md} §14.2 K7): a
 * per-node Log4j2 hot path standalone spot-mérése. Nem JUnit-teszt (nincs {@code @Test}) —
 * a test classpath-en futtassd: {@code java ... application.module.logging.perf.NodeLoggingPerfSpot}.
 *
 * <p>A kontextusok csak root-szinttel rendelkeznek (appendernélkül), így a mérés a
 * Logger/LoggerConfig költségét adja (az R3 "cached LoggerConfig" hot path), NEM az
 * appender I/O-t. Mért scenáriók (ns/op + ops/sec):
 * <ul>
 *   <li>{@code isDebugEnabled()} — az R3 guard, engedélyezve (DEBUG node) és letiltva (INFO node);
 *   <li>{@code debug(...)} — egy valós esemény (DEBUG node) vs. egy supprimált hívás (INFO node,
 *       ahol csak a guard fut, esemény nem keletkezik).</li>
 * </ul>
 */
public final class NodeLoggingPerfSpot {

    /** Escape cella: a JIT ne szüntethesse meg a guard mérést. */
    private static boolean GUARD_SINK;

    public static void main(String[] args) throws Exception {
        long iters = args.length > 0 ? Long.parseLong(args[0]) : 2_000_000L;
        Path dir = Files.createTempDirectory("node-logging-perf");

        NodeLoggingContext debugNode = newContext(dir, "perf-debug", "DEBUG");
        NodeLoggingContext infoNode = newContext(dir, "perf-info", "INFO");
        Logger on = new NodeLoggers(debugNode.loggerContext()).forName("perf.hot");
        Logger off = new NodeLoggers(infoNode.loggerContext()).forName("perf.hot");
        try {
            warmup(on, off, iters / 25);
            System.out.println("node-logging perf-spot (Log4j2 2.26.1, per-node context, no appender)");
            System.out.println("iters = " + iters);
            System.out.printf("%-44s %12s %14s%n", "scenario", "ns/op", "ops/sec");
            System.out.printf("%-44s %12s %14s%n", "--------", "------", "-------");
            report("isDebugEnabled()   DEBUG node (enabled)", nsGuard(on, iters), iters);
            report("isDebugEnabled()   INFO node  (disabled)", nsGuard(off, iters), iters);
            report("debug(\"e\", i)      DEBUG node  (emits)  ", nsEmit(on, iters), iters);
            report("debug(\"e\", i)      INFO node   (suppressed)", nsEmit(off, iters), iters);
        } finally {
            debugNode.stop();
            infoNode.stop();
        }
    }

    private static NodeLoggingContext newContext(Path dir, String name, String rootLevel) throws Exception {
        Path p = dir.resolve(name + ".log4j2.properties");
        Files.writeString(p, "# perf-spot — root level only (no appender)\n"
                + "status = ERROR\nconfigWatch = false\n"
                + "rootLogger.level = " + rootLevel + "\n");
        return new NodeLoggingContext(name, p.toUri()).start();
    }

    private static void warmup(Logger on, Logger off, long n) {
        for (long i = 0; i < n; i++) {
            on.isDebugEnabled();
            off.isDebugEnabled();
            if (on.isDebugEnabled()) {
                on.debug("warm-{}", i);
            }
        }
    }

    /** Az R3 guard: egyetlen cached-LoggerConfig szint-ellenőrzés. */
    private static long nsGuard(Logger logger, long n) {
        long t0 = System.nanoTime();
        boolean sink = false;
        for (long i = 0; i < n; i++) {
            sink |= logger.isDebugEnabled();
        }
        long elapsed = System.nanoTime() - t0;
        GUARD_SINK ^= sink;
        return elapsed;
    }

    /** Engedélyezett node-on valós esemény; letiltott node-on csak a guard fut. */
    private static long nsEmit(Logger logger, long n) {
        long t0 = System.nanoTime();
        for (long i = 0; i < n; i++) {
            logger.debug("event-{}", i);
        }
        return System.nanoTime() - t0;
    }

    private static void report(String label, long ns, long n) {
        double per = (double) ns / n;
        System.out.printf("%-44s %12.1f %14.0f%n", label, per, 1e9 / per);
    }
}
