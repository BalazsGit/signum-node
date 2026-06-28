package application.module.node.lifecycle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeLifecycleStateTest {

    @Test
    void idle_ShouldTransitionToInitializing() {
        assertTrue(NodeLifecycleState.IDLE.canTransitionTo(NodeLifecycleState.INITIALIZING));
    }

    @Test
    void initializing_ShouldTransitionToReady() {
        assertTrue(NodeLifecycleState.INITIALIZING.canTransitionTo(NodeLifecycleState.READY));
    }

    @Test
    void initializing_ShouldTransitionToWaitingForDatabase() {
        assertTrue(NodeLifecycleState.INITIALIZING.canTransitionTo(NodeLifecycleState.WAITING_FOR_DATABASE));
    }

    @Test
    void initializing_ShouldTransitionToError() {
        assertTrue(NodeLifecycleState.INITIALIZING.canTransitionTo(NodeLifecycleState.ERROR));
    }

    @Test
    void waitingForDatabase_ShouldTransitionToReady() {
        assertTrue(NodeLifecycleState.WAITING_FOR_DATABASE.canTransitionTo(NodeLifecycleState.READY));
    }

    @Test
    void waitingForDatabase_ShouldTransitionToError() {
        assertTrue(NodeLifecycleState.WAITING_FOR_DATABASE.canTransitionTo(NodeLifecycleState.ERROR));
    }

    @Test
    void waitingForDatabase_ShouldNotTransitionToRunning() {
        assertFalse(NodeLifecycleState.WAITING_FOR_DATABASE.canTransitionTo(NodeLifecycleState.RUNNING));
    }

    @Test
    void ready_ShouldTransitionToRunning() {
        assertTrue(NodeLifecycleState.READY.canTransitionTo(NodeLifecycleState.RUNNING));
    }

    @Test
    void running_ShouldTransitionToPaused() {
        assertTrue(NodeLifecycleState.RUNNING.canTransitionTo(NodeLifecycleState.PAUSED));
    }

    @Test
    void stopped_ShouldBeTerminal() {
        assertTrue(NodeLifecycleState.STOPPED.isTerminal());
    }

    @Test
    void error_ShouldBeTerminal() {
        assertTrue(NodeLifecycleState.ERROR.isTerminal());
    }

    @Test
    void waitingForDatabase_ShouldNotBeTerminal() {
        assertFalse(NodeLifecycleState.WAITING_FOR_DATABASE.isTerminal());
    }

    @Test
    void running_ShouldBeActive() {
        assertTrue(NodeLifecycleState.RUNNING.isActive());
    }

    @Test
    void paused_ShouldBeActive() {
        assertTrue(NodeLifecycleState.PAUSED.isActive());
    }

    @Test
    void waitingForDatabase_ShouldNotBeActive() {
        assertFalse(NodeLifecycleState.WAITING_FOR_DATABASE.isActive());
    }

    @Test
    void waitingForDatabase_ShouldBeWaiting() {
        assertTrue(NodeLifecycleState.WAITING_FOR_DATABASE.isWaiting());
    }

    @Test
    void running_ShouldNotBeWaiting() {
        assertFalse(NodeLifecycleState.RUNNING.isWaiting());
    }

    @Test
    void waitingForDatabase_ShouldHaveCorrectDescription() {
        assertEquals("Waiting for database", NodeLifecycleState.WAITING_FOR_DATABASE.getDescription());
    }

    @Test
    void idle_ShouldNotTransitionToRunning() {
        assertFalse(NodeLifecycleState.IDLE.canTransitionTo(NodeLifecycleState.RUNNING));
    }

    @Test
    void error_ShouldNotTransitionToRunning() {
        assertFalse(NodeLifecycleState.ERROR.canTransitionTo(NodeLifecycleState.RUNNING));
    }

    @Test
    void allStates_ShouldHaveNonEmptyDescription() {
        for (NodeLifecycleState state : NodeLifecycleState.values()) {
            assertNotNull(state.getDescription());
            assertFalse(state.getDescription().isEmpty());
        }
    }
}