package application.module.node.gui.wizard;

import application.module.node.profile.ProfileConflictDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConflictHighlighter")
class ConflictHighlighterTest {

    @Test
    @DisplayName("describeConflict renders field, own value, other profile + run state")
    void describeConflict() {
        ProfileConflictDetector.Conflict c = new ProfileConflictDetector.Conflict(
                ProfileConflictDetector.ConflictField.API_PORT, "9001", "other", "9001", true);
        String text = ConflictHighlighter.describeConflict(c);
        assertTrue(text.contains("API_PORT"));
        assertTrue(text.contains("other"));
        assertTrue(text.contains("[RUNNING]"));
    }

    @Test
    @DisplayName("describeConflict omits the RUNNING marker when the other is idle")
    void describeConflictIdle() {
        ProfileConflictDetector.Conflict c = new ProfileConflictDetector.Conflict(
                ProfileConflictDetector.ConflictField.P2P_PORT, "9002", "idle-profile", "9002", false);
        assertEquals("P2P_PORT '9002' conflicts with profile 'idle-profile' (9002)",
                ConflictHighlighter.describeConflict(c));
    }
}