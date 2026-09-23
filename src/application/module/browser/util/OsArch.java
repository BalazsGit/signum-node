package application.module.browser.util;

/**
 * OS/architecture detection for the JCEF native distribution (plan D1/D2).
 * <p>
 * The produced key ({@code <os>-<arch>}) must stay aligned with the Gradle
 * contract in {@code build.gradle} ({@code jcefKey}): the same two artifacts
 * (Gradle extraction target folder and {@code jcef-natives-<key>} Maven
 * coordinate) are named from this single mapping.
 * <ul>
 *   <li>OS: {@code windows | linux | macosx}</li>
 *   <li>Arch: {@code amd64 | arm64}</li>
 * </ul>
 * Unknown combinations fail fast with a descriptive error instead of silently
 * resolving to a wrong distribution.
 */
public final class OsArch {

    /** Supported operating systems (key = Gradle/Maven name). */
    public enum Os {
        WINDOWS("windows"),
        LINUX("linux"),
        MACOSX("macosx");

        private final String key;

        Os(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** Supported CPU architectures (key = Gradle/Maven name). */
    public enum Arch {
        AMD64("amd64"),
        ARM64("arm64");

        private final String key;

        Arch(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private final Os os;
    private final Arch arch;

    public OsArch(Os os, Arch arch) {
        this.os = os;
        this.arch = arch;
    }

    /**
     * Parses raw JVM values ({@code os.name} / {@code os.arch}) into an {@link OsArch}.
     *
     * @throws IllegalArgumentException on unsupported OS or architecture
     */
    public static OsArch of(String osName, String osArchName) {
        if (osName == null || osArchName == null) {
            throw new IllegalArgumentException("osName/osArchName must not be null");
        }
        String os = osName.trim().toLowerCase();
        Os osEnum;
        if (os.startsWith("windows")) {
            osEnum = Os.WINDOWS;
        } else if (os.startsWith("linux") || os.equals("unix")) {
            osEnum = Os.LINUX;
        } else if (os.startsWith("mac") || os.startsWith("os x")) {
            osEnum = Os.MACOSX;
        } else {
            throw new IllegalArgumentException("Unsupported OS: " + osName);
        }

        String a = osArchName.trim().toLowerCase();
        Arch archEnum;
        if (a.contains("aarch64") || a.contains("arm64")) {
            archEnum = Arch.ARM64;
        } else if (a.contains("amd64") || a.contains("x86")) {
            archEnum = Arch.AMD64;
        } else {
            throw new IllegalArgumentException("Unsupported architecture: " + osArchName);
        }
        return new OsArch(osEnum, archEnum);
    }

    /**
     * @return the {@link OsArch} of the current JVM (from {@code os.name}/{@code os.arch})
     */
    public static OsArch current() {
        return of(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    public Os os() {
        return os;
    }

    public Arch arch() {
        return arch;
    }

    /**
     * @return the distribution key, e.g. {@code windows-amd64}
     */
    public String key() {
        return os.key() + "-" + arch.key();
    }

    @Override
    public String toString() {
        return key();
    }
}
