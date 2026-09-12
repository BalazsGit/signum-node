package application.module.node.gui.wizard;

import application.module.node.NodeModule;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileConflictDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Thin GUI-facing facade over the conflict-detection SSOT ({@link ProfileConflictDetector}).
 * <p>
 * Mirrors exactly how the running GUI surfaces conflicts (see {@code NodeInfoBar}):
 * "others" = every discovered profile except the candidate; "running/claiming" = the
 * profiles that actually hold claimed resources ({@code NodeModule.getClaimingProfileNames()}).
 * </p>
 */
public final class ConflictHighlighter {

    private ConflictHighlighter() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** All discovered profiles except {@code exceptName} (SSOT: repository discovery). */
    public static List<NodeProfile> otherProfiles(String exceptName) {
        List<NodeProfile> others = new ArrayList<>();
        for (NodeProfile p : NodeProfileRepository.loadAll()) {
            if (p != null && !p.getName().equals(exceptName)) {
                others.add(p);
            }
        }
        return others;
    }

    /** Profiles that currently hold claimed resources (SSOT: NodeModule ownership). */
    public static Set<String> claimingProfiles() {
        return NodeModule.getInstance().getClaimingProfileNames();
    }

    /** Conflicts of the candidate against all other profiles (SSOT: ProfileConflictDetector). */
    public static List<ProfileConflictDetector.Conflict> findConflicts(NodeProfile candidate) {
        if (candidate == null) {
            return List.of();
        }
        return ProfileConflictDetector.detect(candidate, otherProfiles(candidate.getName()), claimingProfiles());
    }

    /** One-line human description of a single conflict. */
    public static String describeConflict(ProfileConflictDetector.Conflict c) {
        return c.getField() + " '" + c.getOwnValue() + "' conflicts with profile '"
                + c.getOtherProfile() + "' (" + c.getOtherValue() + ")"
                + (c.isOtherRunning() ? " [RUNNING]" : "");
    }
}