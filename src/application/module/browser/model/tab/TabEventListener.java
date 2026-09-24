package application.module.browser.model.tab;

/**
 * Observer of {@link TabController} changes (plan §4.4).
 * <p>
 * Callbacks arrive on the thread that changed the model (usually the EDT for
 * user actions, a CEF thread for engine-driven updates) — implementors that
 * touch the UI must pump to the EDT.
 */
@FunctionalInterface
public interface TabEventListener {

    void onTabEvent(TabEvent event);
}
