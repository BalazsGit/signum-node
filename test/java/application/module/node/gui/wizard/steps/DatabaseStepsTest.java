package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.WizardContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Database wizard steps (installation + connection)")
class DatabaseStepsTest {

    @Test
    @DisplayName("selection step defaults to SQLite and exposes the selected engine")
    void selectionStep() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        assertEquals(DatabaseEngine.SQLITE, step.getSelectedEngine());
        assertNull(step.validate(new WizardContext()));
        step.onExit(new WizardContext());
    }

    // ── installation step ──────────────────────────────────────────────

    @Test
    @DisplayName("installation step auto-skips for SQLite")
    void installationAutoSkipsSqlite() {
        DatabaseInstallationStep step = new DatabaseInstallationStep(engine -> new JPanel(), engine -> false);
        WizardContext sqlite = new WizardContext();
        sqlite.setEngine(DatabaseEngine.SQLITE);
        assertTrue(step.autoSkip(sqlite));
    }

    @Test
    @DisplayName("installation step blocks next when the engine is not installed and skip is off")
    void installationBlocksWhenNotInstalled() {
        AtomicInteger builds = new AtomicInteger();
        DatabaseInstallationStep step = new DatabaseInstallationStep(
                engine -> {
                    builds.incrementAndGet();
                    return new JPanel();
                },
                engine -> false);
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.MARIADB);
        step.onEnter(ctx);
        assertEquals(1, builds.get()); // installer shown → embedded panel lazily built
        String error = step.validate(ctx);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("install"));
    }

    @Test
    @DisplayName("installation step allows next when the user selects 'skip'")
    void installationSkipAllowsNext() {
        DatabaseInstallationStep step = new DatabaseInstallationStep(engine -> new JPanel(), engine -> false);
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.POSTGRESQL);
        step.onEnter(ctx);
        findCheck(step.getPanel()).setSelected(true);
        assertNull(step.validate(ctx));
        step.onExit(ctx);
        assertTrue(ctx.isDbInstallSkipped());
    }

    @Test
    @DisplayName("installation step pre-selects 'skip' when the engine is already installed")
    void installationPreselectsSkipWhenInstalled() {
        AtomicInteger builds = new AtomicInteger();
        DatabaseInstallationStep step = new DatabaseInstallationStep(
                engine -> {
                    builds.incrementAndGet();
                    return new JPanel();
                },
                engine -> true);
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.MARIADB);
        step.onEnter(ctx);
        assertTrue(findCheck(step.getPanel()).isSelected());
        assertEquals(0, builds.get()); // skip pre-selected → panel not built yet
        findCheck(step.getPanel()).doClick(); // user wants to review/reconfigure
        assertEquals(1, builds.get()); // lazily built when 'skip' is unchecked
        assertNull(step.validate(ctx));
        step.onExit(ctx);
        assertFalse(ctx.isDbInstallSkipped());
    }

    // ── connection step ────────────────────────────────────────────────

    @Test
    @DisplayName("connection step auto-skips for SQLite")
    void connectionAutoSkipsSqlite() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext sqlite = new WizardContext();
        sqlite.setEngine(DatabaseEngine.SQLITE);
        assertTrue(step.autoSkip(sqlite));
    }

    @Test
    @DisplayName("connection step validates host + port")
    void connectionValidation() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.MARIADB); // default port 3306 (helper-locatable)
        step.onEnter(ctx);
        assertNull(step.validate(ctx)); // valid defaults
        portField(step).setText("not-a-port");
        String error = step.validate(ctx);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("port"));
    }

    @Test
    @DisplayName("connection step onExit collects the connection settings")
    void connectionOnExit() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        hostField(step).setText("mydb");
        portField(step).setText("5433");
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.POSTGRESQL);
        step.onExit(ctx);
        assertEquals(false, ctx.isSkipDbSetup());
        assertEquals("mydb", ctx.getDbHost());
        assertEquals(5433, ctx.getDbPort());
        assertEquals("signum", ctx.getDbName());
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static javax.swing.JTextField hostField(DatabaseConnectionStep step) {
        return findByText(step.getPanel(), javax.swing.JTextField.class, "localhost");
    }

    private static javax.swing.JTextField portField(DatabaseConnectionStep step) {
        return findByText(step.getPanel(), javax.swing.JTextField.class, "3306");
    }

    private static javax.swing.JCheckBox findCheck(javax.swing.JComponent root) {
        return findByText(root, javax.swing.JCheckBox.class,
                "Skip — the database is already installed / I will install it manually");
    }

    private static <T extends javax.swing.JComponent> T findByText(
            javax.swing.JComponent root, Class<T> type, String text) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (!type.isInstance(c)) {
                continue;
            }
            String t;
            if (c instanceof javax.swing.JTextField f) {
                t = f.getText();
            } else if (c instanceof javax.swing.JCheckBox cb) {
                t = cb.getText();
            } else {
                continue;
            }
            if (text.equals(t)) {
                return type.cast(c);
            }
        }
        throw new IllegalStateException("not found: " + text);
    }

    private static void collectChildren(javax.swing.JComponent root, java.util.List<java.awt.Component> out) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JComponent) {
                out.add(c);
                collectChildren((javax.swing.JComponent) c, out);
            }
        }
    }
}
