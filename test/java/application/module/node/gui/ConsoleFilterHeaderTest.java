package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import application.utils.logging.event.CompositeFilter;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;

/**
 * Unit tests for {@link ConsoleFilterHeader}.
 * <p>
 * Focuses on the non-UI filter-building logic. Since Swing components require
 * EDT access, we test the public API methods that manipulate state and the
 * resulting filter expressions.
 * </p>
 */
@DisplayName("ConsoleFilterHeader Tests")
class ConsoleFilterHeaderTest {

    private ConsoleFilterHeader header;
    private AtomicReference<LogFilter> callbackFilter;
    private int callbackCount;

    private Consumer<LogFilter> mockCallback = filter -> {
        callbackFilter = new AtomicReference<>(filter);
        callbackCount++;
    };

    @BeforeEach
    void setUp() {
        callbackFilter = new AtomicReference<>();
        callbackCount = 0;
        header = new ConsoleFilterHeader(mockCallback);
    }

    @AfterEach
    void tearDown() {
        if (header != null) {
            header.removeAll();
        }
    }

    // ── Constructor & Basic State ────────────────────────────────────────

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        void constructor_WithNullCallback_doesNotThrow() {
            assertDoesNotThrow(() -> new ConsoleFilterHeader(null));
        }

        @Test
        void constructor_DefaultState_hasExpectedLevelsSelected() {
            Set<LogLevel> levels = header.getSelectedLevels();
            assertTrue(levels.contains(LogLevel.TRACE), "TRACE should be checked by default");
            assertTrue(levels.contains(LogLevel.DEBUG), "DEBUG should be checked by default");
            assertTrue(levels.contains(LogLevel.INFO), "INFO should be checked by default");
            assertTrue(levels.contains(LogLevel.WARN), "WARN should be checked by default");
            assertTrue(levels.contains(LogLevel.ERROR), "ERROR should be checked by default");
        }

        @Test
        void constructor_DefaultState_allLevelsSelected_meansNoFilter() {
            // With all 5 levels selected there is nothing to filter
            assertNull(header.getCurrentFilter());
        }

        @Test
        void constructor_SearchDoesNotFilter_textChangeKeepsFilterNull() {
            // Search is a live highlight — it must not produce a filter
            assertNull(header.getCurrentFilter());
            header.setSearchText("error");
            assertNull(header.getCurrentFilter(), "Search text must not create a filter");
        }

        @Test
        void constructor_SearchTextChange_firesSearchListener() {
            AtomicReference<String> received = new AtomicReference<>("sentinel");
            header.setSearchTextListener(received::set);

            header.setSearchText("keyword");

            assertEquals("keyword", received.get());
        }

        @Test
        void constructor_DefaultState_profileTextIsEmptyOrAll() {
            String profile = header.getProfileText();
            assertTrue(profile.isEmpty() || profile.equals("(all)"),
                "Default profile should be empty or (all), got: " + profile);
        }

        @Test
        void constructor_DefaultState_moduleTextIsEmpty() {
            assertEquals("", header.getModuleText());
        }

        @Test
        void constructor_DefaultState_searchTextIsEmpty() {
            assertEquals("", header.getSearchText());
        }
    }

    // ── Level Selection ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Level Selection")
    class LevelSelectionTests {

        @Test
        void setSelectedLevels_AllLevels_selectedSetContainsAll() {
            Set<LogLevel> all = new HashSet<>();
            all.add(LogLevel.TRACE);
            all.add(LogLevel.DEBUG);
            all.add(LogLevel.INFO);
            all.add(LogLevel.WARN);
            all.add(LogLevel.ERROR);

            header.setSelectedLevels(all);

            assertEquals(5, header.getSelectedLevels().size());
        }

        @Test
        void setSelectedLevels_EmptySet_blocksAll() {
            header.setSelectedLevels(Set.of());
            header.rebuildFilter();

            LogFilter filter = header.getCurrentFilter();
            assertNotNull(filter);

            // Create a test event and verify it's blocked
            LogEvent event = LogEvent.fromText("test");
            assertFalse(filter.matches(event), "Empty level set should block all events");
        }

        @Test
        void setSelectedLevels_OnlyError_blocksInfo() {
            header.setSelectedLevels(Set.of(LogLevel.ERROR));
            header.rebuildFilter();

            LogFilter filter = header.getCurrentFilter();
            assertNotNull(filter);

            LogEvent infoEvent = LogEvent.fromText("info message");
            // INFO events should be blocked when only ERROR is selected
            assertFalse(filter.matches(infoEvent));
        }

        @Test
        void setSelectedLevels_TriggerRebuild_callsCallback() {
            int before = callbackCount;
            header.setSelectedLevels(Set.of(LogLevel.ERROR));

            assertTrue(callbackCount > before, "Callback should be called");
        }

        @Test
        void setSelectedLevels_FourOfFive_producesFilterExcludingTheUnselected() {
            // Regression: with 4 of 5 levels selected the old code produced NO
            // filter at all, so the unselected level was still visible.
            Set<LogLevel> allButInfo = new HashSet<>();
            allButInfo.add(LogLevel.TRACE);
            allButInfo.add(LogLevel.DEBUG);
            allButInfo.add(LogLevel.WARN);
            allButInfo.add(LogLevel.ERROR);

            header.setSelectedLevels(allButInfo);
            LogFilter filter = header.getCurrentFilter();
            assertNotNull(filter, "4-of-5 selection must produce a level filter");

            // INFO events must be blocked, WARN events must pass
            LogEvent infoEvent = LogEvent.fromText("info message");
            assertFalse(filter.matches(infoEvent), "INFO should be excluded when unselected");

            LogEvent warnEvent = new LogEvent.Builder()
                    .level(LogLevel.WARN)
                    .message("warn message")
                    .build();
            assertTrue(filter.matches(warnEvent), "WARN should pass when selected");
        }
    }

    // ── Profile Selection ────────────────────────────────────────────────

    @Nested
    @DisplayName("Profile Selection")
    class ProfileSelectionTests {

        @Test
        void setProfiles_List_populatesCombo() {
            header.setProfiles("mainnet", "testnet", "devnet");

            String text = header.getProfileText();
            assertTrue(text.equals("(all)"), "Default should still be (all): " + text);
        }

        @Test
        void setProfiles_Varargs_populatesCombo() {
            assertDoesNotThrow(() -> header.setProfiles("alpha", "beta"));
        }

        @Test
        void setProfiles_NullList_onlyShowsAll() {
            header.setProfiles((List<String>) null);

            String text = header.getProfileText();
            assertTrue(text.equals("(all)"), "With null list, default should be (all): " + text);
        }

        @Test
        void setProfiles_EmptyList_onlyShowsAll() {
            header.setProfiles((String[]) new String[0]);
            // "(all)" is always present
            assertDoesNotThrow(() -> header.getProfileText());
        }

        @Test
        void setProfileText_Null_resetsToAll() {
            assertDoesNotThrow(() -> header.setProfileText(null));
        }

        @Test
        void setProfileText_EmptyString_resetsToAll() {
            assertDoesNotThrow(() -> header.setProfileText(""));
        }

        @Test
        void setProfileText_ValidName_triggersCallback() {
            int before = callbackCount;
            header.setProfileText("mainnet");

            assertTrue(callbackCount > before, "Callback should be called");
        }
    }

    // ── Module Filter ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Module Filter")
    class ModuleFilterTests {

        @Test
        void setModuleText_ValidText_triggersCallback() {
            int before = callbackCount;
            header.setModuleText("application.node");

            assertTrue(callbackCount > before, "Callback should be called");
        }

        @Test
        void setModuleText_Null_setsEmptyString() {
            header.setModuleText(null);
            assertEquals("", header.getModuleText());
        }

        @Test
        void getModuleText_DefaultIsEmpty() {
            assertEquals("", header.getModuleText());
        }
    }

    // ── Search (live highlight, not a filter) ────────────────────────────

    @Nested
    @DisplayName("Search")
    class SearchTests {

        @Test
        void setSearchText_ValidText_doesNotTriggerFilterCallback() {
            // Search is a live highlight — changing the text must NOT change
            // the combined filter (no extra callback for unchanged filter state).
            int before = callbackCount;
            header.setSearchText("error");

            assertEquals(before, callbackCount, "Search text must not trigger the filter callback");
        }

        @Test
        void setSearchText_Null_setsEmptyString() {
            header.setSearchText(null);
            assertEquals("", header.getSearchText());
        }

        @Test
        void setSearchText_Cleared_firesSearchListenerWithEmpty() {
            AtomicReference<String> received = new AtomicReference<>("sentinel");
            header.setSearchTextListener(received::set);

            header.setSearchText("abc");
            header.setSearchText("");

            assertEquals("", received.get(), "Clearing the search must notify with empty text");
        }

        @Test
        void chevronButtons_fireNavigationListener() {
            AtomicReference<Boolean> received = new AtomicReference<>(null);
            header.setSearchNavigationListener(received::set);

            // Click the "Next match" chevron (down) button
            JButton nextButton = findButtonByTooltip(header, "Next match");
            assertNotNull(nextButton, "Next match button should exist");
            nextButton.doClick();
            assertEquals(Boolean.TRUE, received.get(), "Next chevron should fire with true");

            // Click the "Previous match" chevron (up) button
            JButton prevButton = findButtonByTooltip(header, "Previous match");
            assertNotNull(prevButton, "Previous match button should exist");
            prevButton.doClick();
            assertEquals(Boolean.FALSE, received.get(), "Previous chevron should fire with false");
        }

        // NOTE: the Enter-key-to-listener wiring cannot be verified in this
        // environment: the reduced JDK does not deliver dispatched (synthetic)
        // KeyEvents to key listeners or key bindings (probed explicitly). The
        // production wiring is a plain KeyAdapter on the search field, and the
        // listener contract itself is covered by UnifiedConsolePanelSearchIndicatorTest.

        @Test
        void searchMatchIndicator_InitiallyHidden() {
            assertEquals("", header.getSearchMatchIndicatorText(),
                    "the match counter must be hidden before any search");
        }

        @Test
        void setSearchMatchIndicatorText_NonEmpty_showsText() {
            header.setSearchMatchIndicatorText("1/23");
            assertEquals("1/23", header.getSearchMatchIndicatorText());
        }

        @Test
        void setSearchMatchIndicatorText_NullOrEmpty_hidesLabel() {
            header.setSearchMatchIndicatorText("2/5");
            header.setSearchMatchIndicatorText(null);
            assertEquals("", header.getSearchMatchIndicatorText(), "null text must hide the label");
            header.setSearchMatchIndicatorText("3/9");
            header.setSearchMatchIndicatorText("");
            assertEquals("", header.getSearchMatchIndicatorText(), "empty text must hide the label");
        }

        @Test
        void chevrons_OnlyVisibleWhileSearchHasMatches() throws Exception {
            // JComponent#isVisible() also requires the component to be
            // displayable, so the header must live in a shown frame (the same
            // pattern NodeConfigurationPanelUnsavedFilterTest uses).
            final AtomicReference<JFrame> ownerRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = new JFrame("chevron-visibility-test-owner");
                owner.add(header);
                owner.setSize(500, 200);
                owner.setVisible(true);
                ownerRef.set(owner);
            });
            try {
                JButton next = findButtonByTooltip(header, "Next match");
                JButton prev = findButtonByTooltip(header, "Previous match");
                assertNotNull(next, "Next match button should exist");
                assertNotNull(prev, "Previous match button should exist");

                // Before any search there is nothing to navigate to: both
                // chevrons must start hidden.
                assertFalse(next.isVisible(), "before any search the next chevron must be hidden");
                assertFalse(prev.isVisible(), "before any search the previous chevron must be hidden");

                header.setSearchChevronsVisible(true);
                assertTrue(next.isVisible(), "with matches the next chevron must be visible");
                assertTrue(prev.isVisible(), "with matches the previous chevron must be visible");

                header.setSearchChevronsVisible(false);
                assertFalse(next.isVisible(), "without matches the next chevron must be hidden");
                assertFalse(prev.isVisible(), "without matches the previous chevron must be hidden");
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
    }

    // ── Filter Building Logic ────────────────────────────────────────────

    @Nested
    @DisplayName("Filter Building")
    class FilterBuildingTests {

        @Test
        void rebuildFilter_OnlyLevelSelected_producesLevelFilter() {
            header.setSelectedLevels(Set.of(LogLevel.ERROR));

            LogFilter filter = header.getCurrentFilter();
            assertNotNull(filter);

            // ERROR event should pass
            LogEvent errorEvent = createErrorEvent();
            assertTrue(filter.matches(errorEvent));

            // INFO event should be blocked
            LogEvent infoEvent = LogEvent.fromText("info msg");
            assertFalse(filter.matches(infoEvent));
        }

        @Test
        void rebuildFilter_LevelAndModule_producesCompositeFilter() {
            header.setSelectedLevels(Set.of(LogLevel.ERROR, LogLevel.WARN));
            header.setModuleText("node");

            LogFilter filter = header.getCurrentFilter();
            assertNotNull(filter);
            assertTrue(filter instanceof CompositeFilter);
        }

        @Test
        void rebuildFilter_SearchText_doesNotAffectFilter() {
            // Search is a live highlight, not part of the combined filter
            header.setSelectedLevels(Set.of(LogLevel.ERROR));
            LogFilter filterBefore = header.getCurrentFilter();

            header.setSearchText("connection");

            LogFilter filterAfter = header.getCurrentFilter();
            assertNotNull(filterAfter);
            assertSame(filterBefore, filterAfter, "Search text must not change the filter");
            assertFalse(filterAfter instanceof CompositeFilter,
                "Search must not add a composite filter component");
        }

        @Test
        void rebuildFilter_NoActiveFilters_returnsNull() {
            // All 5 levels selected (no level filter needed), no module, no search
            Set<LogLevel> allLevels = new HashSet<>();
            allLevels.add(LogLevel.TRACE);
            allLevels.add(LogLevel.DEBUG);
            allLevels.add(LogLevel.INFO);
            allLevels.add(LogLevel.WARN);
            allLevels.add(LogLevel.ERROR);
            header.setSelectedLevels(allLevels);

            // Clear module and search (already empty by default)
            header.setModuleText("");
            header.setSearchText("");

            LogFilter filter = header.getCurrentFilter();
            assertNull(filter, "No active filters should produce null");
        }
    }

    // ── Reset ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reset")
    class ResetTests {

        @Test
        void resetToDefaults_clearsAllFilters() {
            header.setSelectedLevels(Set.of(LogLevel.ERROR));
            header.setModuleText("something");
            header.setSearchText("keyword");

            header.resetToDefaults();

            String module = header.getModuleText();
            String search = header.getSearchText();
            assertEquals("", module);
            assertEquals("", search);
        }

        @Test
        void resetToDefaults_restoresDefaultLevelSelection() {
            header.setSelectedLevels(Set.of());
            header.resetToDefaults();

            Set<LogLevel> levels = header.getSelectedLevels();
            assertTrue(levels.contains(LogLevel.TRACE));
            assertTrue(levels.contains(LogLevel.DEBUG));
            assertTrue(levels.contains(LogLevel.INFO));
            assertTrue(levels.contains(LogLevel.WARN));
            assertTrue(levels.contains(LogLevel.ERROR));
        }
    }

    // ── Hidden Sections (node console: no profile/module) ────────────────

    @Nested
    @DisplayName("Hidden Sections")
    class HiddenSectionTests {

        @Test
        void constructor_hiddenProfileAndModule_apiIsSafe() {
            ConsoleFilterHeader nodeHeader = new ConsoleFilterHeader(mockCallback, false, false);
            try {
                // API must remain callable (no NPE) even though sections are hidden
                assertEquals("", nodeHeader.getProfileText());
                assertDoesNotThrow(() -> nodeHeader.setProfileText("mainnet"));
                assertDoesNotThrow(() -> nodeHeader.setProfiles("mainnet", "testnet"));
                assertEquals("", nodeHeader.getModuleText());
                assertDoesNotThrow(() -> nodeHeader.setModuleText("node"));
                assertDoesNotThrow(nodeHeader::resetToDefaults);
            } finally {
                nodeHeader.removeAll();
            }
        }

        @Test
        void constructor_hiddenSections_allLevelsSelected_filterIsNull() {
            ConsoleFilterHeader nodeHeader = new ConsoleFilterHeader(mockCallback, false, false);
            try {
                assertNull(nodeHeader.getCurrentFilter(),
                    "All levels selected + no profile/module sections = no filter");
            } finally {
                nodeHeader.removeAll();
            }
        }

        @Test
        void constructor_hiddenSections_levelFilterStillWorks() {
            ConsoleFilterHeader nodeHeader = new ConsoleFilterHeader(mockCallback, false, false);
            try {
                nodeHeader.setSelectedLevels(Set.of(LogLevel.ERROR));
                LogFilter filter = nodeHeader.getCurrentFilter();
                assertNotNull(filter);
                assertFalse(filter.matches(LogEvent.fromText("info msg")));
            } finally {
                nodeHeader.removeAll();
            }
        }
    }

    // ── Callback Deduplication ───────────────────────────────────────────

    @Nested
    @DisplayName("Callback Invocation")
    class CallbackTests {

        @Test
        void callback_WithNullCallback_doesNotThrow() {
            ConsoleFilterHeader noCallback = new ConsoleFilterHeader(null);
            assertDoesNotThrow(() -> noCallback.rebuildFilter());
            noCallback.removeAll();
        }

        @Test
        void rebuildFilter_IdempotentWhenSameState_noExtraCalls() {
            // Reset counter after constructor calls
            int before = callbackCount;
            // Call rebuild twice without changing state
            header.rebuildFilter();
            header.rebuildFilter();

            // Since the filter hasn't changed, no additional callbacks (idempotent)
            assertEquals(before, callbackCount, "No extra callbacks for same state");
        }
    }

    // ── toString ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        void toString_ContainsClassName() {
            assertTrue(header.toString().contains("ConsoleFilterHeader"));
        }

        @Test
        void toString_ContainsFilterInfo() {
            assertTrue(header.toString().contains("filter="));
        }
    }

    // ── Test Helpers ─────────────────────────────────────────────────────

    /**
     * Finds a JButton inside the component tree by its tooltip text.
     */
    private static JButton findButtonByTooltip(java.awt.Container root, String tooltip) {
        java.util.Deque<java.awt.Component> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            java.awt.Component c = queue.poll();
            if (c instanceof JButton) {
                JButton button = (JButton) c;
                if (tooltip.equals(button.getToolTipText())) {
                    return button;
                }
            }
            if (c instanceof java.awt.Container) {
                for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    private static LogEvent createErrorEvent() {
        return new LogEvent.Builder()
                .level(LogLevel.ERROR)
                .message("Connection refused")
                .build();
    }
}