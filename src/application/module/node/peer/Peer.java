package application.module.node.peer;

import application.module.node.Version;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

//TODO: Create JavaDocs and remove this
@SuppressWarnings({ "checkstyle:MissingJavadocTypeCheck", "checkstyle:MissingJavadocMethodCheck" })
public interface Peer extends Comparable<Peer> {

    void connect(int currentTime);

    void connect();

    void disconnect();

    enum State {
        NON_CONNECTED, CONNECTED, DISCONNECTED;
    }

    enum ArchivalMode {
        ARCHIVE, TRIM, PRUNE, UNKNOWN
    }

    String getPeerAddress();

    String getAnnouncedAddress();

    State getState();

    void updateUploadedVolume(long volume);

    Version getVersion();

    int getHeight();

    void setHeight(int height);

    String getApplication();

    String getPlatform();

    String getNetworkName();

    String getSoftware();

    boolean shareAddress();

    int getPort();

    boolean isWellKnown();

    boolean isRebroadcastTarget();

    boolean isBlacklisted();

    boolean isAtLeastMyVersion();

    boolean isHigherOrEqualVersionThan(Version version);

    ArchivalMode getArchivalMode();

    void blacklist(Exception cause, String description);

    void blacklist(String description);

    void blacklist();

    void unBlacklist();

    void whitelist();

    void updateBlacklistedStatus(long curTime);

    void remove();

    boolean isState(State cmpState);

    void setState(State state);

    long getDownloadedVolume();

    void updateDownloadedVolume(long volume);

    long getUploadedVolume();

    int getLastUpdated();

    JsonObject send(JsonElement request);

    static boolean isHigherOrEqualVersion(Version ourVersion, Version possiblyLowerVersion) {
        if (ourVersion == null || possiblyLowerVersion == null) {
            return false;
        }

        return possiblyLowerVersion.isGreaterThanOrEqualTo(ourVersion);
    }
}
