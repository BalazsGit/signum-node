package application.utils.gui.console;

import application.module.node.gui.ConsoleFilterHeader;
import application.module.node.gui.SystemConsoleSubscriber;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.gui.BaseConsoleSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end tests for the live "current/total" match indicator (e.g.
 * {@code 1/23}) shown next to the console search field, wired through
 * {@link UnifiedConsolePanel}: typing, chevron navigation, and clearing.
 */
@DisplayName("UnifiedConsolePanel - Search Match Indicator Tests")
class UnifiedConsolePanelSearchIndicatorTest {

    private UnifiedConsolePanel panel;

    @BeforeEach
    void setUp() {
        panel = new UnifiedConsolePanel(
                ConsolePanelConfiguration.systemConsole(),
                SystemConsoleSubscriber.class);
    }

    @AfterEach
    void tearDown() {
        panel.dispose();
    }

    private ConsoleFilterHeader header() {
        ConsoleFilterHeader header = panel.getFilterHeader();
        assertNotNull(header, "the system console must show the filter header");
        return header;
    }

    /** Seeds the console document with plain text (synchronous, no EDT pump needed). */
    private void seedConsole(String text) {
        panel.getTextPane().setText(text);
    }

    private JButton findButtonByTooltip(Container root, String tooltip) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && tooltip.equals(((JButton) c).getToolTipText())) {
                return (JButton) c;
            }
            if (c instanceof Container) {
                JButton found = findButtonByTooltip((Container) c, tooltip);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("indicator starts hidden (no active search)")
    void indicator_InitiallyHidden() {
        assertEquals("", header().getSearchMatchIndicatorText());
    }

    @Test
    @DisplayName("searching shows 'active/total' next to the field")
    void searchText_ShowsActiveAndTotal() {
        seedConsole("alpha error beta\nno match here\nerror again\n");

        header().setSearchText("error");

        assertEquals("1/2", header().getSearchMatchIndicatorText(),
                "first match of two must be reported as 1/2");
    }

    @Test
    @DisplayName("chevron navigation updates the active index")
    void navigation_UpdatesActiveIndex() {
        seedConsole("alpha error beta\nno match\nerror again\nanother error\n");
        ConsoleFilterHeader h = header();
        h.setSearchText("error");
        assertEquals("1/3", h.getSearchMatchIndicatorText());

        JButton next = findButtonByTooltip(h, "Next match");
        assertNotNull(next, "Next match chevron must exist");
        next.doClick();
        assertEquals("2/3", h.getSearchMatchIndicatorText(), "next chevron must advance the index");
        next.doClick();
        assertEquals("3/3", h.getSearchMatchIndicatorText());
        next.doClick();
        assertEquals("1/3", h.getSearchMatchIndicatorText(), "next from last wraps to first");

        JButton prev = findButtonByTooltip(h, "Previous match");
        assertNotNull(prev, "Previous match chevron must exist");
        prev.doClick();
        assertEquals("3/3", h.getSearchMatchIndicatorText(), "previous from first wraps to last");
    }

    @Test
    @DisplayName("search without matches shows '0/0'")
    void searchText_NoMatches_ShowsZero() {
        seedConsole("nothing to see\n");

        header().setSearchText("error");

        assertEquals("0/0", header().getSearchMatchIndicatorText());
    }

    @Test
    @DisplayName("first Enter stays on match 1, further Enters advance")
    void enterFirstScrollsToFirstMatchThenAdvances() {
        seedConsole("alpha error beta\nno match\nerror again\nanother error\n");
        ConsoleFilterHeader h = header();
        h.setSearchText("error");
        assertEquals("1/3", h.getSearchMatchIndicatorText());

        pressEnter(panel);
        assertEquals("1/3", h.getSearchMatchIndicatorText(),
                "the first Enter must stay on the (already active) first match");
        pressEnter(panel);
        assertEquals("2/3", h.getSearchMatchIndicatorText(), "the second Enter advances to the next match");
        pressEnter(panel);
        assertEquals("3/3", h.getSearchMatchIndicatorText());
        pressEnter(panel);
        assertEquals("1/3", h.getSearchMatchIndicatorText(), "Enter from the last wraps to the first");
    }

    @Test
    @DisplayName("re-typing the query resets Enter to the first match")
    void enterResetsAfterQueryChange() {
        seedConsole("alpha error beta\nerror again\n");
        ConsoleFilterHeader h = header();
        h.setSearchText("error");
        pressEnter(panel);
        pressEnter(panel);
        assertEquals("2/2", h.getSearchMatchIndicatorText());

        h.setSearchText("erro");
        pressEnter(panel);
        assertEquals("1/2", h.getSearchMatchIndicatorText(),
                "after a text change the first Enter must go back to match 1");
    }

    @Test
    @DisplayName("Enter without matches is a no-op")
    void enterNoMatches_noOp() {
        seedConsole("nothing here\n");
        ConsoleFilterHeader h = header();
        h.setSearchText("error");
        assertEquals("0/0", h.getSearchMatchIndicatorText());

        assertDoesNotThrow(() -> pressEnter(panel));
        assertEquals("0/0", h.getSearchMatchIndicatorText(), "Enter with zero matches must change nothing");
    }

    /**
     * Invokes the panel's Enter handling. (The reduced JDK does not deliver
     * dispatched synthetic KeyEvents, so the panel method — the same one the
     * header's search-field Enter key invokes — is called directly; it is
     * package-private for exactly this purpose.)
     */
    private static void pressEnter(UnifiedConsolePanel p) {
        p.onSearchEnter();
    }

    @Test
    @DisplayName("filter change re-runs the active search (no stale 0/0)")
    void filterChange_RerunsActiveSearch_WhenMatchesReturn() {
        // Seed content through the subscriber so the filter rebuild can
        // hide AND restore the lines (like unchecking / rechecking a level).
        BaseConsoleSubscriber subscriber = panel.getSubscriber();
        assertNotNull(subscriber, "the panel must own a subscriber");
        subscriber.onLogEvent(eventAt(LogLevel.INFO, "info error alpha"));
        subscriber.onLogEvent(eventAt(LogLevel.INFO, "info error beta"));
        subscriber.onLogEvent(eventAt(LogLevel.WARN, "warn line gamma"));
        subscriber.flush();
        pumpEdt();

        ConsoleFilterHeader h = header();
        h.setSearchText("info error");
        assertEquals("1/2", h.getSearchMatchIndicatorText());

        // Uncheck INFO (only WARN stays visible): all matches disappear
        h.setSelectedLevels(EnumSet.of(LogLevel.WARN));
        pumpEdt();
        assertEquals("0/0", h.getSearchMatchIndicatorText(),
                "all matching lines are hidden, so the search must report 0/0");

        // Re-check: the matching lines come back and the search must re-run
        h.setSelectedLevels(EnumSet.allOf(LogLevel.class));
        pumpEdt();
        assertEquals("1/2", h.getSearchMatchIndicatorText(),
                "the active search must re-run after the lines are restored (no stale 0/0)");
    }

    private static LogEvent eventAt(LogLevel level, String message) {
        return new LogEvent.Builder()
                .timestamp(System.currentTimeMillis())
                .level(level)
                .loggerName("test.logger")
                .message(message)
                .threadName("test")
                .build();
    }

    /** Pumps the EDT so pending invokeLater work (batch append, rebuild, reapply) runs. */
    private static void pumpEdt() {
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            throw new AssertionError("EDT pump failed", e);
        }
    }

    @Test
    @DisplayName("chevrons are visible only while the search has matches")
    void chevrons_FollowMatchCount() throws Exception {
        seedConsole("nothing to see\n");
        ConsoleFilterHeader h = header();

        h.setSearchText("error");
        assertChevronVisibility(false);

        h.setSearchText("see");
        assertChevronVisibility(true);

        h.setSearchText("");
        assertChevronVisibility(false);
    }

    /**
     * Shows the panel in a frame (JComponent#isVisible() also requires a
     * displayable tree) and asserts both chevron buttons' visibility.
     */
    private void assertChevronVisibility(boolean expected) throws Exception {
        final AtomicReference<JFrame> ownerRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame("chevron-visibility-test-owner");
            owner.add(panel);
            owner.setSize(600, 400);
            owner.setVisible(true);
            ownerRef.set(owner);
        });
        try {
            JButton next = findButtonByTooltip(panel, "Next match");
            JButton prev = findButtonByTooltip(panel, "Previous match");
            assertNotNull(next, "Next match chevron must exist");
            assertNotNull(prev, "Previous match chevron must exist");
            assertEquals(expected, next.isVisible(), "next chevron visibility must follow the match count");
            assertEquals(expected, prev.isVisible(), "previous chevron visibility must follow the match count");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = ownerRef.get();
                if (owner != null) {
                    owner.setVisible(false);
                    owner.dispose();
                }
            });
        }
    }

    @Test
    @DisplayName("clearing the search hides the indicator again")
    void clearSearch_HidesIndicator() {
        seedConsole("alpha error beta\n");
        ConsoleFilterHeader h = header();
        h.setSearchText("error");
        assertEquals("1/1", h.getSearchMatchIndicatorText());

        h.setSearchText("");

        assertEquals("", h.getSearchMatchIndicatorText(), "clearing the query must hide the counter");
    }
}
