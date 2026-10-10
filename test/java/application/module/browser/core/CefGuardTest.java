package application.module.browser.core;

import application.module.browser.config.BrowserSettings;
import application.module.browser.config.BrowserSettingsRepository;
import application.utils.config.ModuleIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the R2 resilience guard ({@link CefGuard}).
 * <p>
 * The scenarios reproduce the observed crash class: {@code GPU process launch
 * failed: error_code=63} → {@code FATAL: GPU process isn't usable. Goodbye.}
 * — which kills the JVM. The guard must recover the next start with the right
 * switch (or by disabling the engine), without ever reaching that point again.
 */
class CefGuardTest {

    private static final String GPU_FATAL_LOG =
            "1010/203344.738:ERROR:content\\browser\\gpu\\gpu_process_host.cc:990] GPU process launch failed: error_code=63\n"
                    + "1010/203345.434:FATAL:content\\browser\\gpu\\gpu_data_manager_impl_private.cc:417] GPU process isn't usable. Goodbye.\n";

    private static Path browserDir(Path conf) {
        return conf.resolve(ModuleIds.BROWSER);
    }

    private static BrowserSettingsRepository repository(Path conf) {
        return new BrowserSettingsRepository(browserDir(conf).resolve("settings.json"));
    }

    private static void writeCefLog(Path conf, String content) throws IOException {
        Path log = CefGuard.cefLog(conf);
        Files.createDirectories(log.getParent());
        Files.writeString(log, content, StandardCharsets.UTF_8);
    }

    private static Path jcefDir(Path conf) throws IOException {
        // An empty jcef dir: the probe finds no helper and reports "spawnable"
        // (the missing distribution is the engine's own failure mode).
        return Files.createDirectories(conf.resolve("jcef-test"));
    }

    @Test
    @DisplayName("a healthy machine without any CEF log starts with no resilience switches")
    void cleanMachineNoSwitches(@TempDir Path conf) throws IOException {
        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertFalse(result.engineDisabled());
        assertTrue(result.switches().isEmpty());
        assertFalse(repository(conf).load().isDisableGpu());
    }

    @Test
    @DisplayName("a previous fatal run escalates to --disable-gpu (persisted)")
    void previousFatalEscalatesToDisableGpu(@TempDir Path conf) throws IOException {
        writeCefLog(conf, GPU_FATAL_LOG);

        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertFalse(result.engineDisabled());
        assertEquals(List.of(CefGuard.SWITCH_DISABLE_GPU), result.switches());
        assertTrue(repository(conf).load().isDisableGpu());
    }

    @Test
    @DisplayName("a second fatal run (GPU already off) escalates to --single-process (persisted)")
    void secondFatalEscalatesToSingleProcess(@TempDir Path conf) throws IOException {
        BrowserSettings settings = repository(conf).load();
        settings.setDisableGpu(true);
        repository(conf).save(settings);
        writeCefLog(conf, GPU_FATAL_LOG);

        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertFalse(result.engineDisabled());
        assertEquals(List.of(CefGuard.SWITCH_DISABLE_GPU, CefGuard.SWITCH_SINGLE_PROCESS), result.switches());
        BrowserSettings persisted = repository(conf).load();
        assertTrue(persisted.isDisableGpu());
        assertTrue(persisted.isSingleProcess());
    }

    @Test
    @DisplayName("a third fatal run (already single-process) disables the engine entirely (persisted)")
    void thirdFatalDisablesEngine(@TempDir Path conf) throws IOException {
        BrowserSettings settings = repository(conf).load();
        settings.setSingleProcess(true);
        repository(conf).save(settings);
        writeCefLog(conf, GPU_FATAL_LOG);

        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertTrue(result.engineDisabled());
        assertTrue(result.switches().isEmpty());
        assertTrue(repository(conf).load().isCefDisabled());
    }

    @Test
    @DisplayName("a disabled engine short-circuits: no CEF, no switches, regardless of the log")
    void disabledEngineShortCircuits(@TempDir Path conf) throws IOException {
        BrowserSettings settings = repository(conf).load();
        settings.setCefDisabled(true);
        repository(conf).save(settings);

        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertTrue(result.engineDisabled());
        assertTrue(result.switches().isEmpty());
    }

    @Test
    @DisplayName("a healthy run keeps the persisted resilience switches (no auto-demotion)")
    void healthyRunKeepsPersistedSwitches(@TempDir Path conf) throws IOException {
        BrowserSettings settings = repository(conf).load();
        settings.setDisableGpu(true);
        repository(conf).save(settings);
        writeCefLog(conf, "INFO: healthy line\n");

        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertFalse(result.engineDisabled());
        assertEquals(List.of(CefGuard.SWITCH_DISABLE_GPU), result.switches());
        assertTrue(repository(conf).load().isDisableGpu());
    }

    @Test
    @DisplayName("a fatal line from an OLD run (before the marker) does not escalate")
    void staleFatalBeforeMarkerIsIgnored(@TempDir Path conf) throws IOException {
        // Run 1 (crashed): FATAL line. Run 2 (healthy): starts at the marker offset.
        String oldFatal = "1010/200000.000:FATAL: GPU process isn't usable. Goodbye.\n";
        Path log = CefGuard.cefLog(conf);
        Files.createDirectories(log.getParent());
        Files.writeString(log, oldFatal, StandardCharsets.UTF_8);
        long markerOffset = Files.size(log);
        Files.writeString(log, "INFO: healthy run\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.writeString(log.resolveSibling(".cef-guard-marker"), Long.toString(markerOffset), StandardCharsets.UTF_8);

        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertFalse(result.engineDisabled());
        assertTrue(result.switches().isEmpty());
        assertFalse(repository(conf).load().isDisableGpu());
    }

    @Test
    @DisplayName("evaluate() records the cef.log start offset as the run marker")
    void evaluateWritesMarker(@TempDir Path conf) throws IOException {
        writeCefLog(conf, "INFO: previous content\n");
        long sizeBefore = Files.size(CefGuard.cefLog(conf));

        CefGuard.evaluate(conf, jcefDir(conf));

        String marker = Files.readString(CefGuard.cefLog(conf).resolveSibling(".cef-guard-marker"));
        assertEquals(Long.toString(sizeBefore), marker.trim());
    }

    @Test
    @DisplayName("the in-run escalation advances the persisted ladder one step per fatal event")
    void escalateAfterFatalAdvancesLadder(@TempDir Path conf) {
        CefGuard.escalateAfterFatal(browserDir(conf));
        BrowserSettings first = repository(conf).load();
        assertTrue(first.isDisableGpu());
        assertFalse(first.isSingleProcess());
        assertFalse(first.isCefDisabled());

        CefGuard.escalateAfterFatal(browserDir(conf));
        BrowserSettings second = repository(conf).load();
        assertTrue(second.isDisableGpu());
        assertTrue(second.isSingleProcess());
        assertFalse(second.isCefDisabled());

        CefGuard.escalateAfterFatal(browserDir(conf));
        assertTrue(repository(conf).load().isCefDisabled());
    }

    @Test
    @DisplayName("readRegion reads from the given offset; null for missing/empty regions")
    void readRegionBounds(@TempDir Path conf) throws IOException {
        Path log = CefGuard.cefLog(conf);
        Files.createDirectories(log.getParent());
        String head = "HEAD-LINE\n";
        String tail = "TAIL-LINE\n";
        Files.writeString(log, head + tail, StandardCharsets.UTF_8);

        String region = CefGuard.readRegion(log, head.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(region.contains("TAIL-LINE"));
        assertFalse(region.contains("HEAD-LINE"));

        assertEquals(null, CefGuard.readRegion(conf.resolve("missing.log"), 0));
        assertEquals(null, CefGuard.readRegion(log, Files.size(log)));
    }

    @Test
    @DisplayName("indicatesFatal matches the CEF fatal lines only")
    void indicatesFatalMatchesOnlyFatalLines() {
        assertTrue(CefGuard.indicatesFatal(GPU_FATAL_LOG));
        assertTrue(CefGuard.indicatesFatal("... ERROR: GPU process launch failed: error_code=63 ..."));
        assertFalse(CefGuard.indicatesFatal("INFO: all good\n"));
        // The network service auto-restarts itself (not fatal) — it must not trigger the ladder.
        assertFalse(CefGuard.indicatesFatal("ERROR: content\\browser\\network_service_instance_impl.cc:608] "
                + "Network service crashed or was terminated, restarting service.\n"));
    }

    @Test
    @DisplayName("the probe reports spawnable when the helper is missing (nothing to probe)")
    void probeMissingHelperIsSpawnable(@TempDir Path conf) throws IOException {
        assertTrue(CefGuard.probeSubprocessSpawn(jcefDir(conf)));
    }

    @Test
    @DisplayName("forbidden D17 switches are dropped from the extra args, the rest passes through")
    void forbiddenSwitchesAreDropped(@TempDir Path conf) throws IOException {
        BrowserSettings settings = repository(conf).load();
        settings.setCefExtraArgs(List.of("--lang=hu", "--no-sandbox", "--remote-debugging-port=9222", " "));
        repository(conf).save(settings);

        CefGuard.Result result = CefGuard.evaluate(conf, jcefDir(conf));

        assertFalse(result.engineDisabled());
        assertEquals(List.of("--lang=hu"), result.switches());
    }
}