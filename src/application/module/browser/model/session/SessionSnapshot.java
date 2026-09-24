package application.module.browser.model.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Serializable picture of the open tabs (plan D8: {@code conf/browser/session.json}, T8).
 * <p>
 * Gson-friendly on purpose: public no-arg constructor, plain fields, no
 * Swing/CEF references. Favicon/loading state is intentionally excluded —
 * restore re-derives everything from the URL (D13 discard/restore parity).
 */
public final class SessionSnapshot {

    public static final int VERSION = 1;

    private int version = VERSION;
    private int activeIndex = 0;
    private List<SessionTab> tabs = new ArrayList<>();

    public SessionSnapshot() {
        // gson
    }

    /** One saved tab: just enough to reopen it. */
    public static final class SessionTab {
        private String url;
        private String title;

        public SessionTab() {
            // gson
        }

        public SessionTab(String url, String title) {
            this.url = url;
            this.title = title;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }
    }

    public int getVersion() {
        return version;
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public void setActiveIndex(int activeIndex) {
        this.activeIndex = activeIndex;
    }

    public List<SessionTab> getTabs() {
        return Collections.unmodifiableList(tabs);
    }

    void setTabs(List<SessionTab> tabs) {
        this.tabs = tabs != null ? tabs : new ArrayList<>();
    }

    public void addTab(String url, String title) {
        tabs.add(new SessionTab(url, title));
    }
}
