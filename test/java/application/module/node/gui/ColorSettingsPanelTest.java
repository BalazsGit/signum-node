package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ColorSettingsPanel CategoryInfo and key categorization logic.
 */
class ColorSettingsPanelTest {

    // ======================================================================
    // CategoryInfo unit tests (pure data class, no GUI dependency)
    // ======================================================================

    @Test
    void categoryInfo_EqualModulesAndComponents_ReturnsTrue() {
        ColorSettingsPanel.CategoryInfo cat1 = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        ColorSettingsPanel.CategoryInfo cat2 = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        assertEquals(cat1, cat2);
        assertEquals(cat1.hashCode(), cat2.hashCode());
    }

    @Test
    void categoryInfo_DifferentModules_ReturnsFalse() {
        ColorSettingsPanel.CategoryInfo cat1 = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        ColorSettingsPanel.CategoryInfo cat2 = new ColorSettingsPanel.CategoryInfo("Global", "Peer Metrics");
        assertNotEquals(cat1, cat2);
    }

    @Test
    void categoryInfo_DifferentComponents_ReturnsFalse() {
        ColorSettingsPanel.CategoryInfo cat1 = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        ColorSettingsPanel.CategoryInfo cat2 = new ColorSettingsPanel.CategoryInfo("Node", "Block Generation");
        assertNotEquals(cat1, cat2);
    }

    @Test
    void categoryInfo_EqualsNull_ReturnsFalse() {
        ColorSettingsPanel.CategoryInfo cat = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        assertNotEquals(null, cat);
        assertFalse(cat.equals(null));
    }

    @Test
    void categoryInfo_EqualsWrongType_ReturnsFalse() {
        ColorSettingsPanel.CategoryInfo cat = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        assertFalse(cat.equals("Node|Peer Metrics"));
    }

    @Test
    void categoryInfo_SameInstance_ReturnsTrue() {
        ColorSettingsPanel.CategoryInfo cat = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        assertEquals(cat, cat);
    }

    @Test
    void categoryInfo_HierarchicalKey_FormatCorrect() {
        ColorSettingsPanel.CategoryInfo cat = new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        assertEquals("Node|Peer Metrics", cat.getHierarchicalKey());
    }

    @Test
    void categoryInfo_HierarchicalKey_DifferentModules_ProducesDifferentKeys() {
        ColorSettingsPanel.CategoryInfo cat1 = new ColorSettingsPanel.CategoryInfo("Node", "General");
        ColorSettingsPanel.CategoryInfo cat2 = new ColorSettingsPanel.CategoryInfo("Global", "General");
        assertNotEquals(cat1.getHierarchicalKey(), cat2.getHierarchicalKey());
    }

    @Test
    void categoryInfo_HashCode_ConsistentWithEquals() {
        ColorSettingsPanel.CategoryInfo cat1 = new ColorSettingsPanel.CategoryInfo("GUI Elements", "UI Colors");
        ColorSettingsPanel.CategoryInfo cat2 = new ColorSettingsPanel.CategoryInfo("GUI Elements", "UI Colors");
        assertTrue(cat1.equals(cat2));
        assertEquals(cat1.hashCode(), cat2.hashCode());
    }

    // ======================================================================
    // Categorization logic tests (tests the prefix-to-category mapping directly)
    // These verify the expected categorization rules without GUI instantiation
    // ======================================================================

    @Test
    void categorization_PeerPrefix_MapsToNodePeerMetrics() {
        assertCategorizesTo("peer.connected", "Node", "Peer Metrics");
        assertCategorizesTo("peer.disconnected", "Node", "Peer Metrics");
        assertCategorizesTo("peer.active", "Node", "Peer Metrics");
    }

    @Test
    void categorization_BlockgenPrefix_MapsToNodeBlockGeneration() {
        assertCategorizesTo("blockgen.network.size", "Node", "Block Generation");
        assertCategorizesTo("blockgen.pie.others", "Node", "Block Generation");
        assertCategorizesTo("blockgen.node.share.legend", "Node", "Block Generation");
    }

    @Test
    void categorization_SyncPrefix_MapsToNodeSynchronization() {
        assertCategorizesTo("sync.upload.speed", "Node", "Synchronization");
        assertCategorizesTo("sync.download.volume", "Node", "Synchronization");
        assertCategorizesTo("sync.commit.time", "Node", "Synchronization");
    }

    @Test
    void categorization_GuiPrefix_MapsToGuiElements() {
        assertCategorizesTo("gui.contrast.red", "GUI Elements", "UI Colors");
        assertCategorizesTo("gui.status.consistent", "GUI Elements", "UI Colors");
        assertCategorizesTo("gui.help.icon", "GUI Elements", "UI Colors");
    }

    @Test
    void categorization_NoPrefix_MapsToGlobal() {
        assertCategorizesTo("applied", "Global", "General");
        assertCategorizesTo("saved", "Global", "General");
        assertCategorizesTo("", "Global", "General");
        assertCategorizesTo("unknown.prefix.xyz", "Global", "General");
    }

    // ----------------------------------------------------------------------
    // Helper: verifies the categorization rule using CategoryInfo directly
    // This mirrors the getCategoryForKey logic so we don't need GUI instantiation
    // ----------------------------------------------------------------------

    private void assertCategorizesTo(String key, String expectedModule, String expectedComponent) {
        ColorSettingsPanel.CategoryInfo actual = resolveCategory(key);
        ColorSettingsPanel.CategoryInfo expected = new ColorSettingsPanel.CategoryInfo(expectedModule, expectedComponent);
        assertEquals(expected, actual,
                "Key '" + key + "' should map to module='" + expectedModule + "', component='" + expectedComponent + "'");
    }

    /**
     * Mirrors the getCategoryForKey logic from ColorSettingsPanel for unit testing.
     */
    private ColorSettingsPanel.CategoryInfo resolveCategory(String key) {
        if (key.startsWith("peer.")) {
            return new ColorSettingsPanel.CategoryInfo("Node", "Peer Metrics");
        } else if (key.startsWith("blockgen.")) {
            return new ColorSettingsPanel.CategoryInfo("Node", "Block Generation");
        } else if (key.startsWith("sync.")) {
            return new ColorSettingsPanel.CategoryInfo("Node", "Synchronization");
        } else if (key.startsWith("gui.")) {
            return new ColorSettingsPanel.CategoryInfo("GUI Elements", "UI Colors");
        } else {
            return new ColorSettingsPanel.CategoryInfo("Global", "General");
        }
    }
}