package application.module.logging.context;

import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.2: {@link NodeLoggingConfigResolver} — the L0/L1/L2 priority chain,
 * class-ownership split, JUL level-name spacing and the generated-file
 * pattern (plan §8, §6.4) plus a real LoggerContext round-trip of the
 * rendered node config (the P0 spike pattern: the file must load standalone).
 */
@DisplayName("P1.2 - NodeLoggingConfigResolver")
class NodeLoggingConfigResolverTest {

    @TempDir
    Path tempDir;

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    private NodeLoggingConfigResolver.Resolved resolve(Properties l0Node, Properties l0Global,
                                                       Properties l1, Properties l2) {
        return NodeLoggingConfigResolver.resolve(
                new NodeLoggingConfigResolver.Layers(l0Node, l0Global, l1, l2), tempDir.toString());
    }

    @Test
    @DisplayName("node root level follows the chain: L2 -> L1 -> L0-node -> global root")
    void nodeRootLevelChain() {
        Properties l0Node = props("node.level", "INFO", "org.eclipse.jetty.level", "OFF");
        Properties l0Global = props("level", "WARN");
        // stage 3a: L2 wins
        assertEquals("DEBUG", resolve(l0Node, l0Global, props(), props("node.level", "FINE"))
                .nodeRootLevel);
        // stage 3b: L1 next
        assertEquals("DEBUG", resolve(l0Node, l0Global, props("node.level", "FINE"), props())
                .nodeRootLevel);
        // stage 3c: L0-node next
        assertEquals("INFO", resolve(l0Node, l0Global, props(), props()).nodeRootLevel);
        // stage 4: falls back to the global root when no node layer sets node.level
        Properties l0NodeBare = props();
        assertEquals("WARN", resolve(l0NodeBare, l0Global, props(), props()).nodeRootLevel);
    }

    @Test
    @DisplayName("node motor class overrides follow L2 -> L1 -> L0, only when present")
    void nodeClassOverrideChain() {
        String c = "application.module.node.peer.Peers";
        Properties l0Node = props(c + ".level", "FINE");
        Properties l1 = props(c + ".level", "FINEST");
        Properties l2 = props(c + ".level", "SEVERE");

        assertEquals("ERROR", resolve(l0Node, props(), l1, l2).nodeLoggerLevels.get(c));
        assertEquals("TRACE", resolve(l0Node, props(), l1, props()).nodeLoggerLevels.get(c));
        assertEquals("DEBUG", resolve(l0Node, props(), props(), props()).nodeLoggerLevels.get(c));
        // no layer has an opinion -> no entry (the class inherits the node root)
        assertTrue(resolve(props(), props(), props(), props()).nodeLoggerLevels.isEmpty());
    }

    @Test
    @DisplayName("class ownership: node motor vs gui/profile/logging prefixes vs external")
    void classOwnership() {
        Properties l1 = props(
                "application.module.node.peer.Peers.level", "DEBUG",
                "application.module.node.gui.NodePanel.level", "DEBUG",
                "application.module.node.profile.NodeProfileRepository.level", "DEBUG",
                "application.module.node.logging.NodeLoggingProfile.level", "DEBUG",
                "org.eclipse.jetty.server.Server.level", "INFO");
        NodeLoggingConfigResolver.Resolved r = resolve(props(), props(), l1, props());

        assertEquals(1, r.nodeLoggerLevels.size());
        assertTrue(r.nodeLoggerLevels.containsKey("application.module.node.peer.Peers"));
        assertEquals(4, r.globalLoggerLevels.size());
        assertTrue(r.globalLoggerLevels.containsKey("application.module.node.gui.NodePanel"));
        assertTrue(r.globalLoggerLevels.containsKey("application.module.node.profile.NodeProfileRepository"));
        assertTrue(r.globalLoggerLevels.containsKey("application.module.node.logging.NodeLoggingProfile"));
        assertTrue(r.globalLoggerLevels.containsKey("org.eclipse.jetty.server.Server"));
    }

    @Test
    @DisplayName("JUL level names map to Log4j2 names; Log4j2 names pass through")
    void levelNameMapping() {
        assertEquals("TRACE", NodeLoggingConfigResolver.toLog4jLevel("FINEST"));
        assertEquals("TRACE", NodeLoggingConfigResolver.toLog4jLevel("all"));
        assertEquals("DEBUG", NodeLoggingConfigResolver.toLog4jLevel("FINE"));
        assertEquals("DEBUG", NodeLoggingConfigResolver.toLog4jLevel("debug"));
        assertEquals("INFO", NodeLoggingConfigResolver.toLog4jLevel("CONFIG"));
        assertEquals("INFO", NodeLoggingConfigResolver.toLog4jLevel("info"));
        assertEquals("WARN", NodeLoggingConfigResolver.toLog4jLevel("WARNING"));
        assertEquals("ERROR", NodeLoggingConfigResolver.toLog4jLevel("SEVERE"));
        assertEquals("OFF", NodeLoggingConfigResolver.toLog4jLevel("OFF"));
        assertEquals("TRACE", NodeLoggingConfigResolver.toLog4jLevel("TRACE"));
        assertEquals("INFO", NodeLoggingConfigResolver.toLog4jLevel("garbage"));
        assertEquals("INFO", NodeLoggingConfigResolver.toLog4jLevel(null));
    }

    @Test
    @DisplayName("JUL-only keys are dropped (handlers, .level, java.util.logging.*, GUI/file keys)")
    void julKeysAreDropped() {
        Properties l0Node = props(
                "handlers", "java.util.logging.ConsoleHandler",
                ".level", "SEVERE",
                "java.util.logging.ConsoleHandler.level", "ALL",
                "java.util.logging.ConsoleHandler.formatter", "application.module.node.util.BriefLogFormatter",
                "node.gui.consoleLogSize", "100000",
                "node.level", "INFO",
                "org.jooq.Constants.level", "OFF");
        NodeLoggingConfigResolver.Resolved r = resolve(l0Node, props(), props(), props());

        assertFalse(r.nodeLoggerLevels.keySet().stream()
                .anyMatch(k -> k.startsWith("java.util.logging.") || k.equals("node")));
        // the external class key survives on the global side (JUL name spaced to OFF)
        assertEquals("OFF", r.globalLoggerLevels.get("org.jooq.Constants"));
    }

    @Test
    @DisplayName("node render: header, status, configWatch, explicit appender name keys, forward slashes")
    void nodeRenderPattern() {
        String dir = "C:\\signum\\logs\\node";
        Properties l2 = props("node.fileLog.dir", dir);
        NodeLoggingConfigResolver.Resolved r = resolve(props(), props(), props(), l2);
        String rendered = NodeLoggingConfigResolver.renderNodeProperties(r, "mainnet");

        assertTrue(rendered.startsWith("# GENERATED by Node - do not edit\n"));
        assertTrue(rendered.contains("status = ERROR\n"));
        assertTrue(rendered.contains("configWatch = false\n"));
        assertTrue(rendered.contains("rootLogger.level = INFO\n"));
        // P0 spike fact: every appender block needs the explicit name key
        assertTrue(rendered.contains("appender.console.name = console"));
        assertTrue(rendered.contains("appender.nodefile.name = nodefile"));
        // P0 spike fact: backslashes are escape characters -> forward slashes
        assertTrue(rendered.contains("appender.nodefile.fileName = C:/signum/logs/node/mainnet.log"));
        assertTrue(rendered.contains("appender.nodefile.filePattern = C:/signum/logs/node/mainnet-%i.log.gz"));
        // the console line carries the profile prefix
        assertTrue(rendered.contains("[mainnet] %logger{1} - %msg%n"));
    }

    @Test
    @DisplayName("fileLog: disabled omits the RollingFile block; a level sets the appenderRef level")
    void fileLogVariants() {
        NodeLoggingConfigResolver.Resolved off =
                resolve(props(), props(), props(), props("node.fileLog.enable", "false"));
        String renderedOff = NodeLoggingConfigResolver.renderNodeProperties(off, "mainnet");
        assertFalse(renderedOff.contains("nodefile"));

        NodeLoggingConfigResolver.Resolved leveled =
                resolve(props(), props(), props(), props("node.fileLog.level", "FINE"));
        String renderedOn = NodeLoggingConfigResolver.renderNodeProperties(leveled, "mainnet");
        assertTrue(renderedOn.contains("rootLogger.appenderRef.nodefile.level = DEBUG"));
    }

    @Test
    @DisplayName("global render: levels only, no appender blocks")
    void globalRenderPattern() {
        NodeLoggingConfigResolver.Resolved r =
                resolve(props("org.eclipse.jetty.level", "OFF"), props("level", "WARN"), props(), props());
        String rendered = NodeLoggingConfigResolver.renderGlobalProperties(r);

        assertTrue(rendered.contains("rootLogger.level = WARN\n"));
        assertTrue(rendered.contains("logger.l1.name = org.eclipse.jetty\n"));
        assertTrue(rendered.contains("logger.l1.level = OFF\n"));
        assertFalse(rendered.contains("appender."));
    }

    @Test
    @DisplayName("writer: files land in the §6.4 layout with the rendered content")
    void writerLayout() throws Exception {
        NodeLoggingConfigResolver.Resolved r = resolve(props(), props(), props(), props());
        Path[] written = NodeLoggingConfigWriter.write(tempDir, "mainnet", r);

        Path global = written[0];
        Path node = written[1];
        assertEquals(tempDir.resolve("system").resolve("logging").resolve("runtime")
                        .resolve("global.log4j2.properties"),
                global,
                "the generated global file lives under conf/system/logging/runtime (LoggingPaths SSOT)");
        assertEquals(tempDir.resolve("node").resolve("logging").resolve("runtime")
                .resolve("mainnet.log4j2.properties"), node);
        assertEquals(NodeLoggingConfigResolver.renderGlobalProperties(r), Files.readString(global));
        assertEquals(NodeLoggingConfigResolver.renderNodeProperties(r, "mainnet"), Files.readString(node));
    }

    @Test
    @DisplayName("file log size/max are configurable (L2 -> L1 -> L0 -> defaults) and rendered")
    void fileLogSizeMaxChain() {
        // defaults (no layer opinion)
        NodeLoggingConfigResolver.Resolved def = resolve(props(), props(), props(), props());
        assertEquals(NodeLoggingConfigResolver.DEFAULT_FILE_LOG_SIZE, def.fileLogSize);
        assertEquals(NodeLoggingConfigResolver.DEFAULT_FILE_LOG_MAX, def.fileLogMax);
        String renderedDefault = NodeLoggingConfigResolver.renderNodeProperties(def, "mainnet");
        assertTrue(renderedDefault.contains(".policies.size.size = 10MB"));
        assertTrue(renderedDefault.contains(".strategy.max = 5"));

        // L0 node layer
        NodeLoggingConfigResolver.Resolved l0 =
                resolve(props("node.fileLog.size", "20MB", "node.fileLog.max", "7"), props(), props(), props());
        assertEquals("20MB", l0.fileLogSize);
        assertEquals(7, l0.fileLogMax);

        // L1 beats L0
        NodeLoggingConfigResolver.Resolved l1 = resolve(
                props("node.fileLog.size", "20MB", "node.fileLog.max", "7"),
                props(), props("node.fileLog.size", "1MB"), props());
        assertEquals("1MB", l1.fileLogSize);
        assertEquals(7, l1.fileLogMax);

        // L2 beats L1 (and L0)
        NodeLoggingConfigResolver.Resolved l2 = resolve(
                props("node.fileLog.size", "20MB"), props(),
                props("node.fileLog.size", "1MB"), props("node.fileLog.size", "50MB", "node.fileLog.max", "3"));
        assertEquals("50MB", l2.fileLogSize);
        assertEquals(3, l2.fileLogMax);
        String rendered = NodeLoggingConfigResolver.renderNodeProperties(l2, "mainnet");
        assertTrue(rendered.contains(".policies.size.size = 50MB"));
        assertTrue(rendered.contains(".strategy.max = 3"));

        // invalid values fall back to the defaults (never break the generated file)
        NodeLoggingConfigResolver.Resolved bad =
                resolve(props("node.fileLog.max", "not-a-number"), props(), props(), props());
        assertEquals(NodeLoggingConfigResolver.DEFAULT_FILE_LOG_MAX, bad.fileLogMax);
    }

    @Test
    @DisplayName("round-trip: the rendered node file loads into a real LoggerContext with the resolved levels")
    void renderedNodeConfigLoadsIntoRealContext() throws Exception {
        Properties l1 = props("application.module.node.peer.Peers.level", "FINEST");
        Properties l2 = props("node.level", "FINE",
                "node.fileLog.dir", tempDir.resolve("logs").toString());
        NodeLoggingConfigResolver.Resolved r = resolve(
                props("org.eclipse.jetty.level", "OFF"), props("level", "WARN"), l1, l2);

        Path file = NodeLoggingConfigWriter.writeNode(tempDir, "mainnet", r);
        LoggerContext ctx = new LoggerContext("rt-node", null, file.toUri());
        ctx.start();
        try {
            assertEquals("DEBUG", String.valueOf(ctx.getConfiguration().getRootLogger().getLevel()),
                    "node root must come from the resolved L2 node.level (FINE -> DEBUG)");
            assertTrue(ctx.getLogger("application.module.node.peer.Peers").isTraceEnabled(),
                    "the node motor class override must be applied (FINEST -> TRACE)");
            assertFalse(ctx.getConfiguration().getLoggers().keySet().stream()
                            .anyMatch(n -> n.startsWith("org.eclipse.jetty")),
                    "jetty suppression lives in the GLOBAL config — the node context must not carry an explicit jetty LoggerConfig");
            assertTrue(Files.exists(tempDir.resolve("logs").resolve("mainnet.log")),
                    "the RollingFile appender must open its per-profile file");
        } finally {
            ctx.stop();
        }
    }
}