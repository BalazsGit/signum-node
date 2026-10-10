package application.module.browser.core;

import application.module.browser.config.BrowserSettings;
import application.module.browser.config.BrowserSettingsRepository;
import application.module.browser.util.OsArch;
import application.utils.config.ModuleIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * R2 (plan §Risk): the CEF resilience guard.
 * <p>
 * A fatal CEF event (e.g. {@code GPU process launch failed} →
 * {@code FATAL: GPU process isn't usable. Goodbye.}) does not throw — it
 * <b>kills the whole JVM</b>. There is no in-process recovery from that
 * moment, so the only robust strategy is to decide <em>before</em> the engine
 * is initialized whether it can run at all, and with which resilience
 * switches. This guard makes that decision deterministically, based on:
 * <ol>
 *   <li><b>Settings</b> ({@code conf/browser/settings.json}) — the persisted
 *       escalation state: {@code disableGpu} → {@code --disable-gpu},
 *       {@code singleProcess} → {@code --single-process},
 *       {@code cefDisabled} → the engine is off entirely (the application
 *       keeps running, the browser is unavailable).</li>
 *   <li><b>The previous run's CEF log</b> ({@code conf/browser/cef/cef.log})
 *       — a {@code FATAL:} / {@code GPU process launch failed} line after the
 *       last run's start (tracked by an offset marker) escalates the ladder:
 *       1st crash → {@code --disable-gpu}, 2nd (GPU already off) →
 *       {@code --single-process}, 3rd (already single-process) → engine
 *       disabled. The escalation is persisted so the recovery survives
 *       restarts without any further crash.</li>
 *   <li><b>A subprocess spawn probe</b> — launching the CEF helper
 *       ({@code jcef_helper.exe}) is the exact operation that fails with
 *       {@code error_code=63} (access denied) when the OS/antivirus blocks
 *       CEF subprocesses; when the probe fails, {@code --single-process} is
 *       used for this run (not persisted — a transient block must not
 *       permanently degrade the engine).</li>
 * </ol>
 * D17: forbidden switches are never forwarded to the CEF command line,
 * including via the {@code cefExtraArgs} escape hatch.
 */
public final class CefGuard {

    public static final String SWITCH_DISABLE_GPU = "--disable-gpu";
    public static final String SWITCH_SINGLE_PROCESS = "--single-process";

    private static final Logger logger = LoggerFactory.getLogger(CefGuard.class);

    /** CEF fatal lines that end the whole process (cef.log). */
    private static final String FATAL_MARKER = "FATAL:";
    private static final String GPU_LAUNCH_FAILURE_MARKER = "GPU process launch failed";

    private static final String MARKER_FILE_NAME = ".cef-guard-marker";
    private static final long PROBE_TIMEOUT_MILLIS = 3000;
    private static final long MAX_SCAN_BYTES = 4L * 1024 * 1024;

    /** D17 (code-review gate, enforced at runtime): these must never reach CEF. */
    private static final List<String> FORBIDDEN_SWITCH_PREFIXES = List.of(
            "--no-sandbox",
            "--disable-web-security",
            "--allow-running-insecure-content",
            "--remote-debugging-port",
            "--test-type",
            "--enable-automation");

    private CefGuard() {
        // utility class — never instantiated
    }

    /**
     * The outcome of {@link #evaluate(Path, Path)}: the CEF switches that must
     * be appended to the engine's command line, and whether the engine must
     * stay off entirely for this run.
     */
    public record Result(List<String> switches, boolean engineDisabled) {
    }

    /** The CEF engine log (plan D8: {@code conf/browser/cef/cef.log}). */
    public static Path cefLog(Path confPath) {
        return confPath.resolve(ModuleIds.BROWSER).resolve("cef").resolve("cef.log");
    }

    /**
     * Decides the engine configuration for this run (browser process only —
     * never call from a CEF subprocess). May persist an escalation into the
     * browser settings when the previous run crashed fatally.
     *
     * @param confPath the application config directory ({@code conf/})
     * @param jcefDir  the JCEF native distribution directory (for the probe)
     */
    public static Result evaluate(Path confPath, Path jcefDir) {
        BrowserSettingsRepository repository =
                new BrowserSettingsRepository(confPath.resolve(ModuleIds.BROWSER).resolve("settings.json"));
        BrowserSettings settings = repository.load();

        if (settings.isCefDisabled()) {
            logger.warn("The CEF engine is disabled in the browser settings (repeated fatal crashes) — "
                    + "the browser will be unavailable; re-enable with \"cefDisabled\": false in settings.json");
            writeMarker(cefLog(confPath));
            return new Result(List.of(), true);
        }

        List<String> switches = new ArrayList<>();
        if (previousRunCrashedFatally(cefLog(confPath))) {
            if (settings.isSingleProcess()) {
                settings.setCefDisabled(true);
                repository.save(settings);
                logger.error("The previous CEF run crashed fatally even in single-process mode — disabling the "
                        + "browser engine (the application keeps running). Re-enable with \"cefDisabled\": false "
                        + "in conf/browser/settings.json after the underlying problem is fixed.");
                writeMarker(cefLog(confPath));
                return new Result(List.of(), true);
            }
            if (settings.isDisableGpu()) {
                settings.setSingleProcess(true);
                repository.save(settings);
                logger.warn("The previous CEF run crashed fatally with GPU disabled — escalating to "
                        + "--single-process (persisted)");
            } else {
                settings.setDisableGpu(true);
                repository.save(settings);
                logger.warn("The previous CEF run crashed fatally (see {} in cef.log) — escalating to "
                        + "--disable-gpu (persisted)", FATAL_MARKER);
            }
        }
        if (settings.isDisableGpu()) {
            switches.add(SWITCH_DISABLE_GPU);
        }
        if (settings.isSingleProcess()) {
            switches.add(SWITCH_SINGLE_PROCESS);
        }
        if (!settings.isSingleProcess() && !probeSubprocessSpawn(jcefDir)) {
            // The OS refuses CEF subprocess launches right now (e.g. antivirus) —
            // single-process mode avoids every subprocess. Transient on purpose:
            // NOT persisted, so a healthy machine is never permanently degraded.
            switches.add(SWITCH_SINGLE_PROCESS);
            logger.warn("The OS refused to launch the CEF subprocess helper — using --single-process "
                    + "for this run only (not persisted)");
        }
        switches.addAll(filterForbidden(settings.getCefExtraArgs()));
        writeMarker(cefLog(confPath));
        if (!switches.isEmpty()) {
            logger.warn("CEF resilience switches active for this run: {}", switches);
        }
        return new Result(List.copyOf(switches), false);
    }

    /**
     * In-run escalation (called by the engine's fatal watchdog when a fatal
     * CEF event is observed shortly after the engine reported READY): advances
     * the persisted ladder one step so the <em>next</em> start recovers.
     *
     * @param browserConfDir the module's runtime config directory ({@code conf/browser/})
     */
    public static void escalateAfterFatal(Path browserConfDir) {
        BrowserSettingsRepository repository =
                new BrowserSettingsRepository(browserConfDir.resolve("settings.json"));
        BrowserSettings settings = repository.load();
        if (settings.isCefDisabled()) {
            logger.warn("CEF fatal event — the engine is already disabled in settings; the restart will "
                    + "start without the browser");
            return;
        }
        if (settings.isSingleProcess()) {
            settings.setCefDisabled(true);
        } else if (settings.isDisableGpu()) {
            settings.setSingleProcess(true);
        } else {
            settings.setDisableGpu(true);
        }
        repository.save(settings);
        logger.error("CEF fatal event escalated the browser settings (disableGpu={}, singleProcess={}, "
                        + "cefDisabled={}) — a restart will apply the new configuration",
                settings.isDisableGpu(), settings.isSingleProcess(), settings.isCefDisabled());
    }

    /**
     * Whether the CEF log region since the last run's start (marker offset)
     * contains a fatal event. A missing marker (first run) scans the whole file.
     *
     * @param cefLog the CEF engine log
     */
    static boolean previousRunCrashedFatally(Path cefLog) {
        String region = readRegion(cefLog, readMarker(cefLog));
        return region != null && indicatesFatal(region);
    }

    /**
     * @param region a slice of the CEF log
     * @return whether it contains a fatal CEF event
     */
    static boolean indicatesFatal(String region) {
        return region.contains(FATAL_MARKER) || region.contains(GPU_LAUNCH_FAILURE_MARKER);
    }

    /**
     * Reads the log region starting at {@code sinceOffset} (capped to the last
     * few MB).
     *
     * @return the region text, or {@code null} when the file is missing or the
     *         region is empty
     */
    static String readRegion(Path cefLog, long sinceOffset) {
        try {
            if (!Files.isRegularFile(cefLog)) {
                return null;
            }
            long size = Files.size(cefLog);
            long from = Math.max(0, Math.min(sinceOffset, size));
            from = Math.max(from, size - MAX_SCAN_BYTES);
            if (from >= size) {
                return null;
            }
            byte[] all = Files.readAllBytes(cefLog);
            byte[] region = new byte[(int) (size - from)];
            System.arraycopy(all, (int) from, region, 0, region.length);
            return new String(region, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not scan the CEF log at {} ({}): {}", cefLog, e.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Probes whether the OS will actually launch the CEF subprocess helper —
     * the exact operation CEF performs when it starts its GPU/network service
     * processes. A launch refusal ({@code error_code=63}, e.g. antivirus or a
     * blocked executable) surfaces here as an {@link IOException} from
     * {@code ProcessBuilder.start()}.
     *
     * @param jcefDir the JCEF native distribution directory
     * @return {@code true} when the helper can be launched (or cannot be
     *         probed at all, e.g. the distribution is incomplete — the engine
     *         reports that separately); {@code false} when the OS refused it
     */
    static boolean probeSubprocessSpawn(Path jcefDir) {
        Path helper = jcefDir.resolve(JcefBootstrap.subprocessName(OsArch.current().os()));
        if (!Files.isRegularFile(helper)) {
            return true; // nothing to probe; the missing distribution is the engine's failure mode
        }
        try {
            Process process = new ProcessBuilder(helper.toString(), "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
            return true;
        } catch (IOException e) {
            logger.warn("CEF subprocess spawn probe: the OS refused to launch {} ({}); CEF subprocess "
                            + "launches will fail",
                    helper.getFileName(), e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /**
     * D17: drops the forbidden switches from the user-provided extra args
     * (everything else is passed through untouched).
     */
    static List<String> filterForbidden(List<String> extraArgs) {
        List<String> result = new ArrayList<>();
        for (String arg : extraArgs) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            boolean forbidden = FORBIDDEN_SWITCH_PREFIXES.stream().anyMatch(arg::startsWith);
            if (forbidden) {
                logger.warn("Dropping forbidden CEF switch from the browser settings: {}", arg);
            } else {
                result.add(arg);
            }
        }
        return result;
    }

    private static Path markerFile(Path cefLog) {
        return cefLog.resolveSibling(MARKER_FILE_NAME);
    }

    /** @return the cef.log offset recorded at the last run's start, or 0 when unknown */
    private static long readMarker(Path cefLog) {
        try {
            Path marker = markerFile(cefLog);
            if (Files.isRegularFile(marker)) {
                return Math.max(0, Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8).trim()));
            }
        } catch (Exception e) {
            logger.warn("Could not read the CEF guard marker next to {} ({}); scanning the whole log",
                    cefLog.getFileName(), e.getMessage());
        }
        return 0;
    }

    /** Records the current cef.log size as this run's start offset (best effort). */
    private static void writeMarker(Path cefLog) {
        try {
            Path marker = markerFile(cefLog);
            Files.createDirectories(marker.getParent());
            long size = Files.isRegularFile(cefLog) ? Files.size(cefLog) : 0L;
            Files.writeString(marker, Long.toString(size), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Could not write the CEF guard marker next to {} ({}); the next run will scan the "
                    + "whole log", cefLog.getFileName(), e.getMessage());
        }
    }
}