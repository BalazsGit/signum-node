package application.module.node.gui.wizard.steps;

import application.module.node.gui.wizard.WizardContext;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LoggingSelectionStep} (G5) — the logging-profile selection, driven
 * through the step's injectable hooks (the profile-name listing and the profile-property
 * loading are stubbed, so the tests never touch the real {@code ./conf} tree).
 */
@DisplayName("LoggingSelectionStep (G5: logging profile selection)")
class LoggingSelectionStepTest {

    @BeforeAll
    static void registerIconFont() {
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    @Test
    @DisplayName("id / title / header icon")
    void identity() {
        LoggingSelectionStep step = new LoggingSelectionStep();
        assertEquals("logging-selection", step.getId());
        assertEquals("Logging", step.getTitle());
        assertEquals(FontAwesome.COG, step.getHeaderIcon());
    }

    @Test
    @DisplayName("the combo lists the default entry first, then the existing profiles")
    void comboListsDefaultThenProfiles() {
        LoggingSelectionStep step = stubbedStep(List.of("verbose", "quiet"), n -> new Properties());
        step.onEnter(new WizardContext());
        assertEquals(LoggingSelectionStep.DEFAULT_ENTRY, step.getProfileCombo().getItemAt(0));
        assertEquals("verbose", step.getProfileCombo().getItemAt(1));
        assertEquals("quiet", step.getProfileCombo().getItemAt(2));
    }

    @Test
    @DisplayName("onExit mirrors the selected profile into the context")
    void onExitMirrorsSelection() {
        LoggingSelectionStep step = stubbedStep(List.of("verbose"), n -> new Properties());
        WizardContext ctx = new WizardContext();
        step.onEnter(ctx);
        step.getProfileCombo().setSelectedItem("verbose");
        step.onExit(ctx);
        assertEquals("verbose", ctx.getSelectedLoggingProfileName());
    }

    @Test
    @DisplayName("the default entry mirrors a null selection (the default seeding applies)")
    void defaultEntryMirrorsNull() {
        LoggingSelectionStep step = stubbedStep(List.of("verbose"), n -> new Properties());
        WizardContext ctx = new WizardContext();
        step.onEnter(ctx);
        step.getProfileCombo().setSelectedIndex(0);
        step.onExit(ctx);
        assertNull(ctx.getSelectedLoggingProfileName());
    }

    @Test
    @DisplayName("the create button opens the wizard (injected), adds and selects the created profile")
    void createButtonAddsAndSelects() {
        LoggingSelectionStep step = stubbedStep(List.of("quiet"), n -> new Properties());
        step.setProfileCreator(() -> "verbose2");
        WizardContext ctx = new WizardContext();
        step.onEnter(ctx);
        step.getCreateButton().doClick();
        assertEquals("verbose2", step.getProfileCombo().getSelectedItem());
        step.onExit(ctx);
        assertEquals("verbose2", ctx.getSelectedLoggingProfileName());
    }

    @Test
    @DisplayName("a cancelled create (null) keeps the current selection")
    void cancelledCreateKeepsSelection() {
        LoggingSelectionStep step = stubbedStep(List.of("quiet"), n -> new Properties());
        step.setProfileCreator(() -> null);
        WizardContext ctx = new WizardContext();
        step.onEnter(ctx);
        step.getCreateButton().doClick();
        assertEquals(LoggingSelectionStep.DEFAULT_ENTRY, step.getProfileCombo().getSelectedItem());
    }

    @Test
    @DisplayName("the summary shows the default-against state (default, then the difference)")
    void summaryShowsDefaultAgainst() {
        LoggingSelectionStep step = stubbedStep(List.of("verbose"), n -> {
            Properties p = new Properties();
            if ("verbose".equals(n)) {
                p.setProperty("rootLogger.level", "DEBUG");
            }
            return p; // "default" → empty → INFO
        });
        WizardContext ctx = new WizardContext();
        step.onEnter(ctx);
        assertTrue(step.getSummary().getText().contains("default"), "the default entry mentions the default");

        step.getProfileCombo().setSelectedItem("verbose");
        String diff = step.getSummary().getText();
        assertTrue(diff.contains("differs from the default"), "a differing root level is reported: " + diff);
        assertTrue(diff.contains("DEBUG"), "the selected root level is shown: " + diff);
    }

    @Test
    @DisplayName("logging is never auto-skipped (the node always gets a logging assignment)")
    void neverAutoSkips() {
        assertFalse(new LoggingSelectionStep().autoSkip(new WizardContext()));
    }

    /** A step whose file I/O is fully stubbed (profile names + profile properties). */
    private static LoggingSelectionStep stubbedStep(List<String> names,
                                                    java.util.function.Function<String, Properties> props) {
        LoggingSelectionStep step = new LoggingSelectionStep();
        step.setProfileNames(() -> names);
        step.setProfileProperties(props);
        return step;
    }
}