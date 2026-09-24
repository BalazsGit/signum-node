package application.module.browser.engine.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * S7 (D17): verifies that the CEF renderer sandbox is actually in effect.
 * <p>
 * Run once, shortly after the engine reaches READY — CEF spawns its
 * {@code jcef_helper} subprocesses lazily (first navigation), so the check is
 * delayed by a few seconds. A full job-object inspection (the Windows
 * sandbox mechanism) would require native calls; instead this verifies the
 * observable contract: helper processes exist and <em>none</em> of them runs
 * with a sandbox-disabling command-line switch.
 * <p>
 * Log-only by design: the safe defaults (plan D17) make the sandbox on, this
 * check makes a regression <em>visible</em> rather than fatal.
 */
public final class SandboxVerifier {

    private static final Logger logger = LoggerFactory.getLogger(SandboxVerifier.class);

    private static final long DELAY_SECONDS = 3;
    private static final String HELPER_MARKER = "jcef_helper";
    private static final List<String> UNSAFE_SWITCHES = List.of(
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--in-process-gpu");

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "browser-sandbox-check");
        t.setDaemon(true);
        return t;
    });

    private SandboxVerifier() {
        // utility class — never instantiated
    }

    /** Schedules the post-READY sandbox check (idempotent, daemon thread). */
    public static void verifyAfterReady() {
        SCHEDULER.schedule(SandboxVerifier::verifyNow, DELAY_SECONDS, TimeUnit.SECONDS);
    }

    static void verifyNow() {
        try {
            List<String> helperCommands = new ArrayList<>();
            ProcessHandle.allProcesses().forEach(handle -> {
                // JDK 21+: the command line moved to ProcessHandle.Info.
                String commandLine = handle.info().commandLine().orElse(null);
                if (commandLine != null && commandLine.contains(HELPER_MARKER)) {
                    helperCommands.add(commandLine);
                }
            });
            if (helperCommands.isEmpty()) {
                logger.info("Sandbox check (S7): no {} process found yet (subprocesses start lazily) — not verified",
                        HELPER_MARKER);
                return;
            }
            List<String> unsafe = helperCommands.stream()
                    .filter(cmd -> UNSAFE_SWITCHES.stream().anyMatch(cmd::contains))
                    .toList();
            if (!unsafe.isEmpty()) {
                logger.warn("Sandbox check (S7): {} {} process(es) run with sandbox-disabling switches — "
                        + "the renderer sandbox is NOT in effect: {}", unsafe.size(), HELPER_MARKER, unsafe);
            } else {
                logger.info("Sandbox check (S7): {} {} process(es) running, no sandbox-disabling switches — "
                        + "renderer sandbox in effect", helperCommands.size(), HELPER_MARKER);
            }
        } catch (Exception e) {
            logger.warn("Sandbox check (S7) could not run: {}", e.toString());
        }
    }
}
