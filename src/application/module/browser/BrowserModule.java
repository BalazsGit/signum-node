package application.module.browser;

import application.api.Module;
import application.api.ModuleContext;
import application.module.browser.core.BrowserEngine;
import application.module.browser.gui.BrowserPanel;
import application.utils.config.ModuleIds;
import application.utils.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import javax.swing.JComponent;

/**
 * Web3-enabled browser module (plan D3) — F1: the full tab-based UI
 * (tab strip, NTP, popups-as-tabs, session restore) on the JCEF engine.
 * <p>
 * This class is lifecycle only (kept small on purpose, plan §5.2): the engine
 * lives in {@code core.BrowserEngine}, the tab model in
 * {@code model.tab.TabController}, the UI in {@code gui.BrowserPanel}.
 * <p>
 * Headless contract (AC-1): the module registers and starts normally, but the
 * CEF engine is never initialized and {@link #getUI()} returns {@code null}.
 */
public class BrowserModule implements Module {

    /** Module identifier (SSOT: {@link ModuleIds#BROWSER}). */
    public static final String ID = ModuleIds.BROWSER;

    private static final Logger logger = LoggerFactory.getLogger(BrowserModule.class);

    private final BrowserEngine engine = BrowserEngine.getInstance();
    private ModuleContext context;
    private Path browserConfDir;
    private volatile boolean headless;
    private volatile BrowserPanel ui;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return I18n.get("browser.module.name");
    }

    @Override
    public void init(ModuleContext context) {
        this.context = context;
        // Headless = app mode (-l flag / no GUI shell) OR no display at all.
        // The app mode is authoritative: -l on a machine with a display must
        // still keep the CEF engine off (AC-1).
        this.headless = context.isHeadless() || GraphicsEnvironment.isHeadless();
        // Module runtime data under conf/browser/ (plan D8).
        browserConfDir = context.getConfigDirectory().resolve(ModuleIds.BROWSER);
        logger.info("Browser module initialized (conf dir: {}, headless: {})", browserConfDir, headless);
    }

    @Override
    public void start() {
        if (headless) {
            logger.info("Browser module started in headless mode; the CEF engine is not initialized");
            return;
        }
        logger.info("Starting browser engine (asynchronous)...");
        engine.init(browserConfDir);
    }

    @Override
    public void stop() {
        if (headless) {
            return;
        }
        logger.info("Stopping browser engine...");
        engine.shutdown();
    }

    @Override
    public JComponent getUI() {
        // Defensive headless guard (AC-1): the kernel does not call getUI() in
        // headless mode, but the contract must hold regardless of the caller.
        if (headless) {
            return null;
        }
        if (ui == null) {
            // Called on the EDT by the ApplicationKernel (see its boot sequence).
            ui = new BrowserPanel(engine, browserConfDir);
        }
        return ui;
    }
}
