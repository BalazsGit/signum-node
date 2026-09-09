package application.cli;

/**
 * The <b>owner</b> of a console command — the entity a {@code domain.action} is
 * addressed to.
 * <p>
 * This is the unifying generalisation of the legacy {@code -module.profile} target:
 * instead of a node-profile-only address it is a {@code (module, profile)} pair, so
 * any module (node, database, …) can be the owner of a command. The owner is
 * <em>explicit</em> (typed, {@code node.mainnet …}) or <em>implicit</em> (the
 * profile console the command was typed in supplies it as the default).
 * </p>
 * <p>
 * Immutable value object, headless-safe (no Swing).
 * </p>
 */
public final class OwnerContext {

    private final String module;
    private final String profile;

    private OwnerContext(String module, String profile) {
        if (module == null || module.isBlank()) {
            throw new IllegalArgumentException("owner module must not be null or blank");
        }
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("owner profile must not be null or blank");
        }
        this.module = module;
        this.profile = profile;
    }

    /**
     * Creates an owner.
     *
     * @param module  owning module id (e.g. {@code "node"})
     * @param profile owning profile name (e.g. {@code "mainnet"})
     * @return the owner
     */
    public static OwnerContext of(String module, String profile) {
        return new OwnerContext(module, profile);
    }

    /** Parses a {@code module.profile} token; the module is the text before the first dot. */
    public static OwnerContext parse(String token) {
        if (token == null) {
            throw new IllegalArgumentException("owner token must not be null");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new IllegalArgumentException(
                    "Malformed owner '" + token + "'; expected '<module>.<profile>' (e.g. 'node.mainnet').");
        }
        return new OwnerContext(token.substring(0, dot), token.substring(dot + 1));
    }

    public String module() {
        return module;
    }

    public String profile() {
        return profile;
    }

    @Override
    public String toString() {
        return module + "." + profile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OwnerContext)) {
            return false;
        }
        OwnerContext that = (OwnerContext) o;
        return module.equals(that.module) && profile.equals(that.profile);
    }

    @Override
    public int hashCode() {
        return 31 * module.hashCode() + profile.hashCode();
    }
}
