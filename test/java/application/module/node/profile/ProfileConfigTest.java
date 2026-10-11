package application.module.node.profile;

import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.utils.config.ModuleIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@link ProfileConfig} reload-on-write contract: every
 * mutating method starts from a fresh disk read, so a long-lived instance
 * (e.g. the GUI's app-lifetime tab-order instance in {@code NodePanel}) can
 * never serialize a stale snapshot and clobber a write made by another
 * {@link ProfileConfig} instance (the creation-time logging seed being the
 * canonical victim — it was wiped by the following tab-order save in the field).
 * <p>
 * Uses an explicit {@code confRoot} sandbox (a JUnit {@link TempDir}) so the
 * tests never touch the real {@code conf/} tree. Follows AAA + JUnit 5
 * conventions.
 */
@DisplayName("ProfileConfig Tests")
class ProfileConfigTest {

    @TempDir
    Path tempDir;

    private String confRoot() {
        return tempDir.toString();
    }

    @Test
    @DisplayName("the creation-time seed survives a stale long-lived ProfileConfig tab-order save (NodePanel clobber)")
    void createEmpty_SeededAssignmentSurvivesStaleTabOrderSave() throws IOException {
        // Arrange — an applied (on-disk) logging profile of the node module.
        LoggingProfileRepository repo = new LoggingProfileRepository(confRoot());
        String appliedName = "zz-seed-applied";
        repo.create(ModuleIds.NODE, appliedName, new Properties());
        repo.setApplied(ModuleIds.NODE, appliedName);
        try {
            // A long-lived ProfileConfig instance loaded BEFORE the creation —
            // the NodePanel's tab-order instance lives for the whole app: its
            // in-memory cache holds the pre-creation state of profiles.json.
            ProfileConfig longLived = new ProfileConfig(
                    tempDir.resolve("node").resolve("profiles.json"));
            List<String> order = new ArrayList<>(
                    longLived.getTabOrder() == null ? List.of() : longLived.getTabOrder());

            // Act — create the profile (which seeds the applied profile) and
            // then the GUI's addProfileTab tab-order save against the stale
            // instance (the exact sequence that clobbered the seed in the
            // field: create + seed first, tab-order save a moment later).
            String created = ProfileRuntimeService.createEmptyProfile(confRoot(), "node");
            order.add(created);
            longLived.setTabOrder(order);

            // Assert — the seeded assignment must survive the stale save.
            assertEquals(appliedName,
                    new LoggingAssignmentStore(confRoot()).getAssignment(created).get(ModuleIds.NODE),
                    "a stale long-lived ProfileConfig save must not discard the creation-time seed");
        } finally {
            repo.setApplied(ModuleIds.NODE, null);
            repo.delete(ModuleIds.NODE, appliedName);
        }
    }
}
