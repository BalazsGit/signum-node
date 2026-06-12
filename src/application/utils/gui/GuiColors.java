package application.utils.gui;

import javax.swing.*;

import java.awt.*;

/**
 * Provides access to the application's color scheme.
 * <p>
 * This class acts as a centralized accessor for colors, which are managed by
 * the {@link ColorPaletteManager}.
 * Instead of holding static final colors, it provides static methods to
 * retrieve colors from the
 * currently active palette. This allows the entire application's color scheme
 * to be changed dynamically
 * when the Look and Feel theme is updated.
 *
 * @see ColorPaletteManager
 * @see ColorPalette
 */
public final class GuiColors {

    private GuiColors() {
    } // prevent instantiation

    public static Color getApplied() {
        return ColorPaletteManager.getColor("applied");
    }

    public static Color getSaved() {
        return ColorPaletteManager.getColor("saved");
    }

    public static Color getPeerDisconnected() {
        return ColorPaletteManager.getColor("peer.disconnected");
    }

    public static Color getPeerOutdatedVersion() {
        return ColorPaletteManager.getColor("peer.outdated.version");
    }

    public static Color getPeerUpToDateVersion() {
        return ColorPaletteManager.getColor("peer.up-to-date.version");
    }

    public static Color getPeerOutdatedHeight() {
        return ColorPaletteManager.getColor("peer.outdated.height");
    }

    public static Color getPeerUpToDateHeight() {
        return ColorPaletteManager.getColor("peer.up-to-date.height");
    }

    // Peer Metrics Colors
    public static Color getPeerOtherResponseTime() {
        return ColorPaletteManager.getColor("peer.other.response.time");
    }

    public static Color getPeerBlacklisted() {
        return ColorPaletteManager.getColor("peer.blacklisted");
    }

    public static Color getPeerMinResponseTime() {
        return ColorPaletteManager.getColor("peer.min.response.time");
    }

    public static Color getPeerMaxResponseTime() {
        return ColorPaletteManager.getColor("peer.max.response.time");
    }

    public static Color getPeerRxResponseTime() {
        return ColorPaletteManager.getColor("peer.rx.response.time");
    }

    public static Color getPeerRxCount() {
        return ColorPaletteManager.getColor("peer.rx.count");
    }

    public static Color getPeerTxResponseTime() {
        return ColorPaletteManager.getColor("peer.tx.response.time");
    }

    public static Color getPeerTxCount() {
        return ColorPaletteManager.getColor("peer.tx.count");
    }

    public static Color getPeerOtherCount() {
        return ColorPaletteManager.getColor("peer.other.count");
    }

    public static Color getPeerConnected() {
        return ColorPaletteManager.getColor("peer.connected");
    }

    public static Color getPeerActive() {
        return ColorPaletteManager.getColor("peer.active");
    }

    public static Color getPeerAll() {
        return ColorPaletteManager.getColor("peer.all");
    }

    // Block Generation Metrics Colors
    public static Color getBlockGenNetworkSize() {
        return ColorPaletteManager.getColor("blockgen.network.size");
    }

    public static Color getBlockGenCommitment() {
        return ColorPaletteManager.getColor("blockgen.commitment");
    }

    public static Color getBlockGenBaseTarget() {
        return ColorPaletteManager.getColor("blockgen.base.target");
    }

    public static Color getBlockGenNodeMiners() {
        return ColorPaletteManager.getColor("blockgen.node.miners");
    }

    public static Color getBlockGenNetworkMiners() {
        return ColorPaletteManager.getColor("blockgen.network.miners");
    }

    public static Color getBlockGenActiveMiner() {
        return ColorPaletteManager.getColor("blockgen.active.miner");
    }

    public static Color getBlockGenDeadlinesRx() {
        return ColorPaletteManager.getColor("blockgen.deadlines.rx");
    }

    public static Color getBlockGenNodeShare() {
        return ColorPaletteManager.getColor("blockgen.node.share");
    }

    public static Color getBlockGenChainDeadline() {
        return ColorPaletteManager.getColor("blockgen.chain.deadline");
    }

    public static Color getBlockGenChainDeadlineMa() {
        return ColorPaletteManager.getColor("blockgen.chain.deadline.ma");
    }

    public static Color getBlockGenNodeDeadlineMa() {
        return ColorPaletteManager.getColor("blockgen.node.deadline.ma");
    }

    public static Color getBlockGenNodeShareLegend() {
        return ColorPaletteManager.getColor("blockgen.node.share.legend");
    }

    public static Color getBlockGenNetworkShareLegend() {
        return ColorPaletteManager.getColor("blockgen.network.share.legend");
    }

    public static Color getBlockGenPieOthers() {
        return ColorPaletteManager.getColor("blockgen.pie.others");
    }

    public static Color getBlockGenPieWaiting() {
        return ColorPaletteManager.getColor("blockgen.pie.waiting");
    }

    public static Color getBlockGenPieFiltered() {
        return ColorPaletteManager.getColor("blockgen.pie.filtered");
    }

    // Synchronization Metrics Colors
    public static Color getSyncSystemTxPerBlock() {
        return ColorPaletteManager.getColor("sync.system.tx.per.block");
    }

    public static Color getSyncAllTxPerBlock() {
        return ColorPaletteManager.getColor("sync.all.tx.per.block");
    }

    public static Color getSyncUploadVolume() {
        return ColorPaletteManager.getColor("sync.upload.volume");
    }

    public static Color getSyncDownloadVolume() {
        return ColorPaletteManager.getColor("sync.download.volume");
    }

    public static Color getSyncPushTime() {
        return ColorPaletteManager.getColor("sync.push.time");
    }

    public static Color getSyncValidationTime() {
        return ColorPaletteManager.getColor("sync.validation.time");
    }

    public static Color getSyncTxLoopTime() {
        return ColorPaletteManager.getColor("sync.tx.loop.time");
    }

    public static Color getSyncHousekeepingTime() {
        return ColorPaletteManager.getColor("sync.housekeeping.time");
    }

    public static Color getSyncTxApplyTime() {
        return ColorPaletteManager.getColor("sync.tx.apply.time");
    }

    public static Color getSyncAtTime() {
        return ColorPaletteManager.getColor("sync.at.time");
    }

    public static Color getSyncSubscriptionTime() {
        return ColorPaletteManager.getColor("sync.subscription.time");
    }

    public static Color getSyncBlockApplyTime() {
        return ColorPaletteManager.getColor("sync.block.apply.time");
    }

    public static Color getSyncCommitTime() {
        return ColorPaletteManager.getColor("sync.commit.time");
    }

    public static Color getSyncMiscTime() {
        return ColorPaletteManager.getColor("sync.misc.time");
    }

    public static Color getSyncPayloadFullness() {
        return ColorPaletteManager.getColor("sync.payload.fullness");
    }

    public static Color getSyncBlocksPerSec() {
        return ColorPaletteManager.getColor("sync.blocks.per.sec");
    }

    public static Color getSyncAllTxPerSec() {
        return ColorPaletteManager.getColor("sync.all.tx.per.sec");
    }

    public static Color getSyncSystemTxPerSec() {
        return ColorPaletteManager.getColor("sync.system.tx.per.sec");
    }

    public static Color getSyncAtCountPerBlock() {
        return ColorPaletteManager.getColor("sync.at.count.per.block");
    }

    public static Color getSyncUploadSpeed() {
        return ColorPaletteManager.getColor("sync.upload.speed");
    }

    public static Color getSyncDownloadSpeed() {
        return ColorPaletteManager.getColor("sync.download.speed");
    }

    public static Color getContrastRed() {
        return ColorPaletteManager.getColor("gui.contrast.red");
    }

    public static Color getStatusConsistent() {
        return ColorPaletteManager.getColor("gui.status.consistent");
    }

    /**
     * Gets the color for unsaved UI elements.
     * This typically matches the default text color of the current Look and Feel.
     *
     * @return The color for unsaved elements.
     */
    public static Color getUnsaved() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : Color.BLACK;
    }

    /**
     * Gets the color for faint or disabled-looking text.
     *
     * @return The color for faint text.
     */
    public static Color getFaintText() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    /**
     * Gets the color for separator lines.
     *
     * @return The color for separators.
     */
    public static Color getSeparator() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : Color.GRAY;
    }

    /**
     * Gets the color for icons inside buttons, intended to match the button's text
     * color.
     *
     * @return The color for button icons.
     */
    public static Color getButtonIcon() {
        Color c = UIManager.getColor("Button.foreground");
        if (c == null) {
            c = UIManager.getColor("Label.foreground");
        }
        return c != null ? c : Color.BLACK;
    }

    /**
     * Gets the color for help or informational icons.
     *
     * @return The color for help icons.
     */
    public static Color getHelpIcon() {
        return ColorPaletteManager.getColor("gui.help.icon");
    }
}