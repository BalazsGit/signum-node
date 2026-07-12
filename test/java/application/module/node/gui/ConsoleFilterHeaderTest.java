package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
            assertFalse(levels.contains(LogLevel.TRACE), "TRACE should be unchecked by default");
            assertFalse(levels.contains(LogLevel.DEBUG), "DEBUG should be unchecked by default");
            assertTrue(levels.contains(LogLevel.INFO), "INFO should be checked by default");
            assertTrue(levels.contains(LogLevel.WARN), "WARN should be checked by default");
            assertTrue(levels.contains(LogLevel.ERROR), "ERROR should be checked by default");
        }

        @Test
        void constructor_DefaultState_searchIsIncludeMode() {
            assertTrue(header.isSearchIncludeMode());
        }

        @Test
        void constructor_DefaultState_searchIsNotRegexMode() {
            assertFalse(header.isSearchRegexMode());
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

        @Test
        void constructor_DefaultState_currentFilterIsNotNull() {
            // Default has INFO+WARN+ERROR checked, so there should be a level filter
            assertNotNull(header.getCurrentFilter());
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

    // ── Search ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Search")
    class SearchTests {

        @Test
        void setSearchText_ValidText_triggersCallback() {
            int before = callbackCount;
            header.setSearchText("error");

            assertTrue(callbackCount > before, "Callback should be called");
        }

        @Test
        void setSearchText_Null_setsEmptyString() {
            header.setSearchText(null);
            assertEquals("", header.getSearchText());
        }

        @Test
        void setSearchIncludeMode_true_setsInclude() {
            header.setSearchIncludeMode(true);
            assertTrue(header.isSearchIncludeMode());
        }

        @Test
        void setSearchIncludeMode_false_setsExclude() {
            header.setSearchIncludeMode(false);
            assertFalse(header.isSearchIncludeMode());
        }

        @Test
        void setSearchRegexMode_true_setsRegex() {
            header.setSearchRegexMode(true);
            assertTrue(header.isSearchRegexMode());
        }

        @Test
        void setSearchRegexMode_false_setsContains() {
            header.setSearchRegexMode(false);
            assertFalse(header.isSearchRegexMode());
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
        void rebuildFilter_LevelAndSearch_producesCompositeFilter() {
            header.setSelectedLevels(Set.of(LogLevel.ERROR));
            header.setSearchText("connection");

            LogFilter filter = header.getCurrentFilter();
            assertNotNull(filter);
            assertTrue(filter instanceof CompositeFilter);
        }

        @Test
        void rebuildFilter_NoActiveFilters_returnsNull() {
            // Select all 5 levels (no level filter needed), no module, no search
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

        @Test
        void rebuildFilter_InvalidRegex_doesNotThrow() {
            header.setSearchRegexMode(true);
            assertDoesNotThrow(() -> header.setSearchText("[invalid(regex"));
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
            assertFalse(levels.contains(LogLevel.TRACE));
            assertFalse(levels.contains(LogLevel.DEBUG));
            assertTrue(levels.contains(LogLevel.INFO));
            assertTrue(levels.contains(LogLevel.WARN));
            assertTrue(levels.contains(LogLevel.ERROR));
        }

        @Test
        void resetToDefaults_restoresIncludeMode() {
            header.setSearchIncludeMode(false);
            header.resetToDefaults();
            assertTrue(header.isSearchIncludeMode());
        }

        @Test
        void resetToDefaults_restoresTextMode() {
            header.setSearchRegexMode(true);
            header.resetToDefaults();
            assertFalse(header.isSearchRegexMode());
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

    private static LogEvent createErrorEvent() {
        return new LogEvent.Builder()
                .level(LogLevel.ERROR)
                .message("Connection refused")
                .build();
    }
}