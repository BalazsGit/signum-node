package application.module.node.profile;

/**
 * The node networks of the setup wizard / profile creation (SSOT).
 * <p>
 * The network is decided in the FIRST step of the setup wizard (it is part of
 * the suggested profile name — {@code mainnet} / {@code testnet} / {@code mocknet}
 * — and of the created profile's {@code node.network} property). The
 * {@link #networkParametersClass()} is the fully-qualified
 * {@code NetworkParameters} class name the property must hold (loaded via
 * reflection at node start); mainnet is the default and leaves the property
 * unset (a non-class value there would break the reflective load).
 * </p>
 * <p>
 * Headless and Swing-free (reusable by the CLI and the tests).
 * </p>
 */
public enum Network {

    /** The public network (the {@code node.network} property stays unset). */
    MAINNET("mainnet", null),
    /** The public test network. */
    TESTNET("testnet", ProfileCreateDefaults.TESTNET_NETWORK_CLASS),
    /** The local mock network (a private chain with mock mining). */
    MOCKNET("mocknet", ProfileCreateDefaults.MOCKNET_NETWORK_CLASS);

    private final String displayName;
    private final String networkParametersClass;

    Network(String displayName, String networkParametersClass) {
        this.displayName = displayName;
        this.networkParametersClass = networkParametersClass;
    }

    /** The lowercase network name used in suggested profile names and the GUI. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * The {@code node.network} property value of this network (the FQCN of the
     * {@code NetworkParameters} class), or {@code null} for mainnet (the
     * property stays unset — the node falls back to the built-in mainnet).
     */
    public String getNetworkParametersClass() {
        return networkParametersClass;
    }

    /**
     * The network a stored {@code node.network} property value belongs to:
     * null/blank → mainnet (the default), an unknown value → mainnet (the
     * property is left to the node start's reflective load, which reports it).
     */
    public static Network fromNetworkParameter(String value) {
        if (value == null) {
            return MAINNET;
        }
        String v = value.trim();
        for (Network n : values()) {
            if (n.networkParametersClass != null && n.networkParametersClass.equals(v)) {
                return n;
            }
        }
        return MAINNET;
    }
}
