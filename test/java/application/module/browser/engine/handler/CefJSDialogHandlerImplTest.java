package application.module.browser.engine.handler;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefJSDialogCallback;
import org.cef.handler.CefJSDialogHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CefJSDialogHandlerImpl} (F8): the page's JS dialogs
 * must be handled (never silently dropped), their user answer must be
 * continued back to the page, and a dead browser must not hang the page's
 * script. The modal Swing dialogs themselves are replaced through the
 * package-private seam (the tests are headless).
 */
class CefJSDialogHandlerImplTest {

    private static final CefJSDialogHandler.JSDialogType CONFIRM =
            CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_CONFIRM;
    private static final CefJSDialogHandler.JSDialogType PROMPT =
            CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_PROMPT;

    private record Answer(boolean confirmed, String text) {
    }

    private CefBrowser liveBrowser() {
        return mock(CefBrowser.class);
    }

    /** The callback: records the continued answer and counts down. */
    private CefJSDialogCallback recordingCallback(CountDownLatch done, Answer[] answer) {
        CefJSDialogCallback callback = mock(CefJSDialogCallback.class);
        doAnswer(invocation -> {
            answer[0] = new Answer(invocation.getArgument(0), invocation.getArgument(1));
            done.countDown();
            return null;
        }).when(callback).Continue(anyBoolean(), nullable(String.class));
        return callback;
    }

    @Test
    @DisplayName("a confirm's OK answer is continued to the page")
    void confirmAnswerIsContinued() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Answer[] answer = new Answer[1];
        AtomicReference<CefJSDialogHandler.JSDialogType> seenType = new AtomicReference<>();

        CefJSDialogHandlerImpl handler = new CefJSDialogHandlerImpl() {
            @Override
            Result dialog(java.awt.Window owner, CefJSDialogHandler.JSDialogType type,
                          String message, String defaultPromptText) {
                seenType.set(type);
                return new Result(true, null); // the user clicked OK
            }
        };

        boolean handled = handler.onJSDialog(liveBrowser(), "main", CONFIRM,
                "Delete?", "", recordingCallback(done, answer), null);

        assertTrue(handled, "the handler must report the dialog as handled");
        assertTrue(done.await(5, TimeUnit.SECONDS), "the callback must fire");
        assertEquals(CONFIRM, seenType.get());
        assertTrue(answer[0].confirmed());
        assertNull(answer[0].text());
    }

    @Test
    @DisplayName("a prompt's entered text is continued to the page")
    void promptTextIsContinued() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Answer[] answer = new Answer[1];
        AtomicReference<String> seenDefault = new AtomicReference<>();

        CefJSDialogHandlerImpl handler = new CefJSDialogHandlerImpl() {
            @Override
            Result dialog(java.awt.Window owner, CefJSDialogHandler.JSDialogType type,
                          String message, String defaultPromptText) {
                seenDefault.set(defaultPromptText);
                return new Result(true, "Renamed page");
            }
        };

        handler.onJSDialog(liveBrowser(), "main", PROMPT,
                "New name:", "Old name", recordingCallback(done, answer), null);

        assertTrue(done.await(5, TimeUnit.SECONDS), "the callback must fire");
        assertEquals("Old name", seenDefault.get(), "the JS default text must reach the dialog");
        assertEquals("Renamed page", answer[0].text());
    }

    @Test
    @DisplayName("a canceled prompt continues as a negative answer")
    void canceledPromptContinuesFalse() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Answer[] answer = new Answer[1];

        CefJSDialogHandlerImpl handler = new CefJSDialogHandlerImpl() {
            @Override
            Result dialog(java.awt.Window owner, CefJSDialogHandler.JSDialogType type,
                          String message, String defaultPromptText) {
                return new Result(false, null);
            }
        };

        handler.onJSDialog(liveBrowser(), "main", PROMPT,
                "Name:", "", recordingCallback(done, answer), null);

        assertTrue(done.await(5, TimeUnit.SECONDS), "the callback must fire");
        assertFalse(answer[0].confirmed());
    }

    @Test
    @DisplayName("a missing browser continues immediately without showing a dialog")
    void deadBrowserContinuesFalseWithoutDialog() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Answer[] answer = new Answer[1];
        AtomicReference<Boolean> dialogShown = new AtomicReference<>(false);

        CefJSDialogHandlerImpl handler = new CefJSDialogHandlerImpl() {
            @Override
            Result dialog(java.awt.Window owner, CefJSDialogHandler.JSDialogType type,
                          String message, String defaultPromptText) {
                dialogShown.set(true);
                return new Result(true, null);
            }
        };

        boolean handled = handler.onJSDialog(null, "main", CONFIRM,
                "Delete?", "", recordingCallback(done, answer), null);

        assertTrue(handled);
        assertTrue(done.await(1, TimeUnit.SECONDS), "the page's script must not hang");
        assertFalse(dialogShown.get(), "no dialog may be shown for a dead browser");
        assertFalse(answer[0].confirmed());
    }

    @Test
    @DisplayName("the beforeunload ask is continued as a leave/veto decision")
    void beforeunloadContinuesTheDecision() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Answer[] answer = new Answer[1];

        CefJSDialogHandlerImpl handler = new CefJSDialogHandlerImpl() {
            @Override
            Result beforeunloadDialog(java.awt.Window owner) {
                return new Result(true, null); // the user chose to leave
            }
        };

        boolean handled = handler.onBeforeUnloadDialog(liveBrowser(), "main", false,
                recordingCallback(done, answer));

        assertTrue(handled);
        assertTrue(done.await(5, TimeUnit.SECONDS), "the callback must fire");
        assertTrue(answer[0].confirmed());
    }
}