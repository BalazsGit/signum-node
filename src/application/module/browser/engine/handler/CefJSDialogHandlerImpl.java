package application.module.browser.engine.handler;

import application.utils.i18n.I18n;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefJSDialogCallback;
import org.cef.handler.CefJSDialogHandlerAdapter;
import org.cef.misc.BoolRef;

import java.awt.Component;
import java.awt.Window;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * F8: bridges a page's JS dialogs (alert / confirm / prompt) to native
 * Swing dialogs.
 * <p>
 * <b>Without this handler CEF silently drops every JS dialog call</b> —
 * {@code prompt()} returns {@code null} and {@code confirm()} returns
 * {@code false} — so the interactive buttons of the internal pages
 * (the bookmarks manager, the history clear) would do nothing. Each
 * callback arrives on a non-EDT CEF thread: the dialog is pumped to the
 * EDT and {@link CefJSDialogCallback#Continue(boolean, String)} is called
 * with the user's answer when the dialog closes. Returning {@code true}
 * tells CEF the dialog is handled (no CEF-side default rendering).
 * <p>
 * The dialog is modal on the top-level window that hosts the browser, so
 * the page's script — which awaits the dialog's result — resumes
 * naturally when the user answers.
 * <p>
 * Not final: the unit tests replace the modal dialog seams without ever
 * touching a real (headless-hostile) {@link JOptionPane}.
 */
class CefJSDialogHandlerImpl extends CefJSDialogHandlerAdapter {

    /** The user's answer of one native dialog (package seam for tests). */
    record Result(boolean confirmed, String text) {
    }

    @Override
    public boolean onJSDialog(CefBrowser browser, String frameName, JSDialogType dialogType,
                              String message, String defaultPromptText,
                              CefJSDialogCallback callback, BoolRef suppressMessage) {
        // no browser to host the dialog — continue immediately so the page's
        // script does not hang on the unanswered dialog
        if (browser == null) {
            callback.Continue(false, null);
            return true;
        }
        BrowserCefHandlers.runInEdt(() -> {
            Result result = dialog(ownerOf(browser), dialogType, message, defaultPromptText);
            callback.Continue(result.confirmed(), result.text());
        });
        return true;
    }

    @Override
    public boolean onBeforeUnloadDialog(CefBrowser browser, String frameName, boolean isReload,
                                        CefJSDialogCallback callback) {
        // beforeunload (a page asks "leave? unsaved changes may be lost")
        if (browser == null) {
            callback.Continue(false, null);
            return true;
        }
        BrowserCefHandlers.runInEdt(() -> {
            Result result = beforeunloadDialog(ownerOf(browser));
            callback.Continue(result.confirmed(), null);
        });
        return true;
    }

    /** The dialog's owner: the top-level window hosting the browser (null when it has none). */
    private static Window ownerOf(CefBrowser browser) {
        Component component = uiComponent(browser);
        return component == null ? null : SwingUtilities.getWindowAncestor(component);
    }

    private static Component uiComponent(CefBrowser browser) {
        try {
            return browser.getUIComponent();
        } catch (RuntimeException e) {
            return null; // a torn-down browser — the dialog just loses its owner
        }
    }

    /**
     * The actual Swing dialogs (package-private seam — the tests replace
     * this without touching the modal {@link JOptionPane} calls).
     */
    Result dialog(Window owner, JSDialogType dialogType, String message, String defaultPromptText) {
        return switch (dialogType) {
            case JSDIALOGTYPE_ALERT -> {
                JOptionPane.showMessageDialog(owner, message,
                        I18n.get("browser.jsdialog.title"), JOptionPane.INFORMATION_MESSAGE);
                yield new Result(true, null);
            }
            case JSDIALOGTYPE_CONFIRM -> {
                int answer = JOptionPane.showConfirmDialog(owner, message,
                        I18n.get("browser.jsdialog.title"), JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                yield new Result(answer == JOptionPane.OK_OPTION, null);
            }
            case JSDIALOGTYPE_PROMPT -> {
                Object input = JOptionPane.showInputDialog(owner, message,
                        I18n.get("browser.jsdialog.title"), JOptionPane.PLAIN_MESSAGE,
                        null, null, defaultPromptText);
                yield new Result(input != null,
                        input == null ? null : String.valueOf(input));
            }
            default -> new Result(false, null);
        };
    }

    /** The beforeunload ask (its own seam — same modal-dialog reason). */
    Result beforeunloadDialog(Window owner) {
        int answer = JOptionPane.showConfirmDialog(owner,
                I18n.get("browser.jsdialog.beforeunload"),
                I18n.get("browser.jsdialog.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return new Result(answer == JOptionPane.OK_OPTION, null);
    }
}
