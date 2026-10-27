package application.utils.profile;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the R1 layer ({@code application.utils.profile}) is <b>module-independent</b>
 * by driving all four components with a DUMMY module (no node, no logging, no Log4j2).
 * <p>
 * This is the P6.1 acceptance test: the R1 infrastructure must serve an arbitrary
 * module (here {@code testmod}) exactly like it will serve the logging module, with
 * no {@code org.apache.logging.*} dependency anywhere in the package.
 * </p>
 */
@DisplayName("R1 generic profile infrastructure (module-independent)")
class GenericProfileInfraTest {

    private static final String DUMMY_MODULE = "testmod";
    private static final String DUMMY_CATEGORY = "prefs";
    private static final String RESERVED = "default";

    @TempDir
    Path confRoot;

    private ProfileRepository repository;
    private ProfileAssignmentStore assignments;
    private ProfileLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        ProfileRegistry.getInstance().clear();
        repository = new ProfileRepository(confRoot.toString(), DUMMY_MODULE, DUMMY_CATEGORY,
                ".properties", Set.of(RESERVED), "appliedProfile", "profile.json");
        assignments = new ProfileAssignmentStore(new FileCodec(confRoot.resolve("owners-assignments.json")));
        lifecycle = new ProfileLifecycle(DUMMY_MODULE, repository, assignments);
    }

    // ── Dummy module (proves independence from node/logging) ──────────

    static final class DummyModule implements ProfileModule {
        private final String id;

        DummyModule(String id) {
            this.id = id;
        }

        @Override
        public String getModuleId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return id + " (dummy)";
        }

        @Override
        public String getDescription() {
            return "A dummy module proving R1 module-independence.";
        }

        @Override
        public Map<String, String> getDefaultProfile() {
            return Map.of("testmod.level", "INFO");
        }

        @Override
        public String getCategory() {
            return "prefs";
        }
    }

    // ── File-backed assignment codec (a non-ProfileConfig SSOT) ───────

    static final class FileCodec implements ProfileAssignmentStore.Codec {
        private final Path file;
        private final Gson gson = new Gson();

        FileCodec(Path file) {
            this.file = file;
        }

        private Map<String, Map<String, String>> read() {
            Map<String, Map<String, String>> all = new LinkedHashMap<>();
            if (!Files.exists(file)) {
                return all;
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> owner : root.entrySet()) {
                    Map<String, String> map = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> mod : owner.getValue().getAsJsonObject().entrySet()) {
                        map.put(mod.getKey(), mod.getValue().getAsString());
                    }
                    all.put(owner.getKey(), map);
                }
            } catch (Exception ignored) {
                // corrupt/missing -> treat as empty
            }
            return all;
        }

        private void write(Map<String, Map<String, String>> all) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, gson.toJson(all));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Map<String, String> get(String owner) {
            return read().get(owner);
        }

        @Override
        public void set(String owner, Map<String, String> assignment) {
            Map<String, Map<String, String>> all = read();
            if (assignment == null) {
                all.remove(owner);
            } else {
                all.put(owner, new LinkedHashMap<>(assignment));
            }
            write(all);
        }

        @Override
        public List<String> listOwners() {
            return new ArrayList<>(read().keySet());
        }
    }

    // ── 1. Registry ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ProfileRegistry")
    class RegistryTests {

        @Test
        @DisplayName("register and lookup a dummy module")
        void registerAndLookup() {
            DummyModule module = new DummyModule(DUMMY_MODULE);
            ProfileRegistry.getInstance().register(module);

            assertTrue(ProfileRegistry.getInstance().isRegistered(DUMMY_MODULE));
            assertEquals(module, ProfileRegistry.getInstance().getModule(DUMMY_MODULE));
            assertTrue(ProfileRegistry.getInstance().getModuleIds().contains(DUMMY_MODULE));
            assertEquals(1, ProfileRegistry.getInstance().size());
        }

        @Test
        @DisplayName("re-registering the same id replaces and fires both listeners")
        void reregisterReplaces() {
            ProfileRegistry registry = ProfileRegistry.getInstance();
            List<String> removed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            registry.addUnregisteredListener(removed::add);
            registry.addRegisteredListener(m -> added.add(m.getModuleId()));

            registry.register(new DummyModule(DUMMY_MODULE));
            registry.register(new DummyModule(DUMMY_MODULE));

            assertTrue(removed.contains(DUMMY_MODULE));
            assertEquals(List.of(DUMMY_MODULE, DUMMY_MODULE), added);
            assertEquals(1, registry.size());
        }

        @Test
        @DisplayName("unregister removes the module")
        void unregisterRemoves() {
            ProfileRegistry registry = ProfileRegistry.getInstance();
            registry.register(new DummyModule(DUMMY_MODULE));
            assertTrue(registry.unregister(DUMMY_MODULE));
            assertFalse(registry.isRegistered(DUMMY_MODULE));
            assertEquals(0, registry.size());
            assertNull(registry.getModule(DUMMY_MODULE));
        }
    }

    // ── 2. Repository ─────────────────────────────────────────────────

    @Nested
    @DisplayName("ProfileRepository")
    class RepositoryTests {

        @Test
        @DisplayName("create then load round-trips the profile contents")
        void createThenLoadRoundTrips() throws IOException {
            Properties props = new Properties();
            props.setProperty("testmod.level", "DEBUG");
            props.setProperty("testmod.console", "true");

            Path file = repository.create("prod", props);
            assertTrue(Files.exists(file));
            assertTrue(repository.isResolvable("prod"));

            Properties loaded = repository.load("prod");
            assertEquals("DEBUG", loaded.getProperty("testmod.level"));
            assertEquals("true", loaded.getProperty("testmod.console"));
        }

        @Test
        @DisplayName("reserved names are protected from every CRUD operation")
        void reservedNamesProtected() throws IOException {
            Properties props = new Properties();
            assertThrows(IllegalArgumentException.class, () -> repository.create(RESERVED, props));
            assertThrows(IllegalArgumentException.class, () -> repository.save(RESERVED, props));
            // a reserved file placed manually cannot be deleted or renamed
            Files.createDirectories(repository.getDirectory());
            Files.writeString(repository.getDirectory().resolve(RESERVED + ".properties"), "# reserved");
            assertThrows(IllegalArgumentException.class, () -> repository.delete(RESERVED));
            assertThrows(IllegalArgumentException.class, () -> repository.rename(RESERVED, "other"));
            assertThrows(IllegalArgumentException.class, () -> repository.rename("src", RESERVED));
        }

        @Test
        @DisplayName("listProfiles excludes reserved names")
        void listExcludesReserved() throws IOException {
            repository.create("alpha", new Properties());
            repository.create("beta", new Properties());
            Files.writeString(repository.getDirectory().resolve(RESERVED + ".properties"), "# reserved");

            List<String> listed = repository.listProfiles();
            assertTrue(listed.contains("alpha"));
            assertTrue(listed.contains("beta"));
            assertFalse(listed.contains(RESERVED));
        }

        @Test
        @DisplayName("applied-profile metadata round-trips and clears")
        void appliedMetadataRoundTrips() throws IOException {
            assertNull(repository.getApplied());
            repository.setApplied("prod");
            assertEquals("prod", repository.getApplied());
            repository.setApplied(null);
            assertNull(repository.getApplied());
        }

        @Test
        @DisplayName("renaming the applied profile updates the applied metadata")
        void renameUpdatesApplied() throws IOException {
            repository.create("alpha", new Properties());
            repository.setApplied("alpha");
            repository.rename("alpha", "beta");
            assertEquals("beta", repository.getApplied());
            assertTrue(repository.isResolvable("beta"));
            assertFalse(repository.isResolvable("alpha"));
        }
    }

    // ── 3. Assignment store ───────────────────────────────────────────

    @Nested
    @DisplayName("ProfileAssignmentStore")
    class AssignmentTests {

        @Test
        @DisplayName("per-module assignment round-trips through the codec")
        void moduleAssignmentRoundTrips() {
            assignments.setModuleAssignment("owner1", DUMMY_MODULE, "prod");
            assertEquals("prod", assignments.getAssignment("owner1").get(DUMMY_MODULE));
            assertTrue(assignments.isManaged("owner1"));
        }

        @Test
        @DisplayName("a blank profile name removes the module's assignment")
        void blankRemovesModule() {
            assignments.setModuleAssignment("owner1", DUMMY_MODULE, "prod");
            assignments.setModuleAssignment("owner1", DUMMY_MODULE, null);
            assertFalse(assignments.isManaged("owner1"));
        }

        @Test
        @DisplayName("reverse lookup finds every owner referencing a profile")
        void findReferencingProfiles() {
            assignments.setModuleAssignment("owner1", DUMMY_MODULE, "shared");
            assignments.setModuleAssignment("owner2", DUMMY_MODULE, "shared");
            assignments.setModuleAssignment("owner3", DUMMY_MODULE, "other");

            List<String> referencing = assignments.findReferencingProfiles("shared");
            assertEquals(Set.of("owner1", "owner2"), Set.copyOf(referencing));
            assertTrue(assignments.findReferencingProfiles("missing").isEmpty());
        }

        @Test
        @DisplayName("renaming an owner re-keys its assignment")
        void renameRekeys() {
            assignments.setModuleAssignment("owner1", DUMMY_MODULE, "prod");
            assignments.renameAssignment("owner1", "owner2");
            assertEquals("prod", assignments.getAssignment("owner2").get(DUMMY_MODULE));
            assertFalse(assignments.isManaged("owner1"));
        }

        @Test
        @DisplayName("change listeners fire on every write")
        void changeListenersFire() {
            int[] fired = {0};
            assignments.addChangeListener(() -> fired[0]++);
            assignments.setModuleAssignment("owner1", DUMMY_MODULE, "prod");
            assignments.clearAssignment("owner1");
            assertEquals(2, fired[0]);
        }
    }

    // ── 4. Lifecycle ──────────────────────────────────────────────────

    @Nested
    @DisplayName("ProfileLifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("create assigns the new profile to the given owner")
        void createAssignsOwner() throws IOException {
            Path file = lifecycle.create("owner1", "prod", new Properties());
            assertTrue(Files.exists(file));
            assertEquals("prod", assignments.getAssignment("owner1").get(DUMMY_MODULE));
        }

        @Test
        @DisplayName("delete is refused while any owner references the profile")
        void deleteRefusedWhenInUse() throws IOException {
            lifecycle.create("owner1", "prod", new Properties());
            ProfileLifecycle.ProfileInUseException ex =
                    assertThrows(ProfileLifecycle.ProfileInUseException.class, () -> lifecycle.delete("prod"));
            assertTrue(ex.getReferencingProfiles().contains("owner1"));
            assertTrue(repository.isResolvable("prod"), "the profile must still exist after a refused delete");
        }

        @Test
        @DisplayName("delete succeeds once no owner references the profile")
        void deleteOkWhenFree() throws IOException {
            lifecycle.create(null, "prod", new Properties());
            lifecycle.delete("prod");
            assertFalse(repository.isResolvable("prod"));
        }

        @Test
        @DisplayName("rename re-keys every owner that referenced the old name")
        void renameRekeysOwners() throws IOException {
            lifecycle.create("owner1", "prod", new Properties());
            lifecycle.rename("prod", "prod2");
            assertEquals("prod2", assignments.getAssignment("owner1").get(DUMMY_MODULE));
            assertTrue(repository.isResolvable("prod2"));
            assertFalse(repository.isResolvable("prod"));
        }

        @Test
        @DisplayName("applied-profile marking delegates to the repository")
        void appliedDelegates() throws IOException {
            lifecycle.create(null, "prod", new Properties());
            lifecycle.setApplied("prod");
            assertEquals("prod", lifecycle.getApplied());
        }
    }
}

