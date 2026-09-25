package application.module.browser.engine.scheme;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.LongRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

/**
 * Serves the built-in {@code signum://} pages from the classpath (plan D6).
 * <p>
 * The scheme is registered as a <em>standard</em> CEF scheme in all processes
 * by {@link SignumSchemeRegistrar} (installed at startup by
 * {@code JcefProcessBootstrap} — CEF requires the registration in every
 * process for a custom scheme), and this handler is registered once, after
 * engine init, via
 * {@code CefApp.registerSchemeHandlerFactory("signum", "", factory)}.
 * The other scheme flags stay off (F1: static pages — no cookies, fetch or
 * service workers on the internal scheme); if a later phase needs XHR with
 * cookies on internal pages, that is the point to revisit the flags.
 * <p>
 * Lifecycle: {@link #factory()} is the long-lived factory; each request gets
 * its own serving instance (body buffered in memory — the pages are tiny).
 * The body is served synchronously from {@link #read} (the fork's CEF glue
 * calls the new-API {@code read()}; the deprecated {@code readResponse()}
 * stays as a fallback with identical behaviour).
 */
public final class BrowserSchemeHandler implements CefResourceHandler, CefSchemeHandlerFactory {

    /** The custom scheme (plan D6). */
    public static final String SCHEME = "signum";
    /** Full URL prefix of the custom scheme. */
    public static final String SCHEME_PREFIX = SCHEME + "://";

    private final byte[] body;
    private final boolean found;
    private final String mimeType;
    private int readPosition;

    private BrowserSchemeHandler(byte[] body, boolean found, String mimeType) {
        this.body = body;
        this.found = found;
        this.mimeType = mimeType;
    }

    /** @return the factory registered with the engine (one instance for the app's lifetime). */
    public static CefSchemeHandlerFactory factory() {
        return new BrowserSchemeHandler(new byte[0], false, "text/html");
    }

    @Override
    public CefResourceHandler create(CefBrowser browser, CefFrame frame, String schemeName, CefRequest request) {
        return forRequest(request);
    }

    static BrowserSchemeHandler forRequest(CefRequest request) {
        String url = request != null ? request.getURL() : null;
        String requested = InternalPage.pageForUrl(url);
        boolean found = requested != null;
        byte[] body = null;
        if (found) {
            // Dynamic pages (F3+): the body is produced from live module data
            // (a null body means "unknown action" → the 404 page).
            InternalPage.PageRenderer renderer = InternalPage.rendererFor(requested);
            if (renderer != null) {
                body = renderer.render(url, InternalPage.query(url));
                found = body != null;
            }
        }
        if (found && body == null) {
            body = InternalPage.read(requested);
        }
        if (body == null) {
            // A missing classpath resource still falls back to the friendly 404.
            body = InternalPage.read(InternalPage.NOT_FOUND);
        }
        if (body == null) {
            body = new byte[0];
        }
        String page = found ? requested : InternalPage.NOT_FOUND;
        return new BrowserSchemeHandler(body, found, InternalPage.mimeFor(page));
    }

    // ------------------------------------------------------------------
    // CefResourceHandler
    // ------------------------------------------------------------------

    @Override
    public boolean processRequest(CefRequest request, CefCallback callback) {
        return true; // this handler fully owns the request
    }

    @Override
    public boolean open(CefRequest request, BoolRef handleRequest, CefCallback callback) {
        readPosition = 0;
        // handleRequest = true: this handler fully owns the request; the whole
        // body is in memory, so getResponseHeaders/read() are served synchronously.
        handleRequest.set(true);
        if (callback != null) {
            callback.Continue();
        }
        return true;
    }

    @Override
    public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
        readPosition = 0;
        response.setStatus(found ? 200 : 404);
        response.setStatusText(found ? "OK" : "Not Found");
        // The mime type belongs on the response object — NOT on the third
        // parameter: java-cef's getResponseHeaders(CefResponse, IntRef, StringRef)
        // declares the StringRef as an optional *redirect URL*. Writing the mime
        // type into it makes CEF follow a redirect to "text/html" (resolved
        // relative to the current URL) until ERR_TOO_MANY_REDIRECTS.
        //
        // The mime MUST be the bare type ("text/html"), never parameterized
        // ("text/html;charset=utf-8"): the pinned JCEF build (146.0.10)
        // downgrades a main-frame response with a parameterized mime to a
        // download (rejected → the page shows as plain-text source). UTF-8 is
        // the default charset for HTML/CSS, and the pages declare
        // <meta charset="utf-8"> anyway.
        response.setMimeType(mimeType);
        response.setHeaderByName("Content-Type", mimeType, false);
        response.setHeaderByName("Content-Length", String.valueOf(body.length), false);
        response.setHeaderByName("Cache-Control", "no-store", false);
        responseLength.set(body.length);
        // redirectUrl is intentionally left unset: every signum:// page is final.
    }

    @Override
    public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
        // bytesToRead is the number of bytes the loader wants (NOT a buffer
        // offset): the CEF glue passes a buffer that is exactly bytesToRead
        // long and the data must start at index 0.
        int available = body.length - readPosition;
        int n = Math.min(available, bytesToRead);
        if (n <= 0) {
            bytesRead.set(0);
            return false; // EOF
        }
        System.arraycopy(body, readPosition, dataOut, 0, n);
        readPosition += n;
        bytesRead.set(n);
        return true;
    }

    @Override
    public boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
        // The fork's CEF glue calls the NEW-API read() (readResponse() is only
        // a backwards-compatibility fallback). bytesToRead is the number of
        // bytes the loader wants (NOT a buffer offset): copy from index 0 of
        // dataOut. Returning false with bytesRead == 0 means "response
        // complete", so the body MUST be served here.
        int available = body.length - readPosition;
        int n = Math.min(available, bytesToRead);
        if (n <= 0) {
            bytesRead.set(0);
            return false; // EOF
        }
        System.arraycopy(body, readPosition, dataOut, 0, n);
        readPosition += n;
        bytesRead.set(n);
        return true;
    }

    @Override
    public boolean skip(long offsets, LongRef totalSkipped, org.cef.callback.CefResourceSkipCallback callback) {
        long skip = Math.max(0, Math.min(offsets, body.length - readPosition));
        readPosition += (int) skip;
        totalSkipped.set(skip);
        return true;
    }

    @Override
    public void cancel() {
        // Nothing to release: the body is a classpath byte array.
    }
}
