package application.module.node.web.server;

import application.module.node.TransactionApplyContext;
import application.module.node.TransactionType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Servlet Filter that binds the per-node {@link TransactionApplyContext} to the
 * current thread for the duration of each HTTP request.
 * <p>
 * This ensures multi-node isolation: when two nodes run in the same JVM,
 * each web server's request threads see only their own node's context
 * (via the ThreadLocal in {@link TransactionType}), not the other node's.
 * </p>
 *
 * @since 4.1
 */
public final class TransactionContextFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TransactionContextFilter.class);

    private final TransactionApplyContext context;

    public TransactionContextFilter(TransactionApplyContext context) {
        this.context = context;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        TransactionType.bindContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            TransactionType.clearContext();
        }
    }
}