package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Attachment;
import application.module.node.SignumException;
import application.module.node.common.AbstractUnitTest;
import application.module.node.web.api.http.common.APITransactionManager;
import com.google.gson.JsonElement;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.servlet.http.HttpServletRequest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// These transaction-handler tests share the QuickMocker fixtures and a set of
// pre-existing per-test stubs that are not all consumed on every code path.
// Relaxing strictness for this test family avoids UnnecessaryStubbingException
// while keeping the functional assertions intact.
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class AbstractTransactionTest extends AbstractUnitTest {

    @FunctionalInterface
    public interface TransactionCreationFunction<R> {
        R apply() throws SignumException;
    }

    protected Attachment attachmentCreatedTransaction(TransactionCreationFunction r,
            APITransactionManager apiTransactionManagerMock) throws SignumException {
        final ArgumentCaptor<Attachment> ac = ArgumentCaptor.forClass(Attachment.class);

        when(apiTransactionManagerMock.createTransaction(any(HttpServletRequest.class), nullable(Account.class),
                nullable(Long.class), anyLong(), ac.capture(), anyLong())).thenReturn(mock(JsonElement.class));

        r.apply();

        return ac.getValue();
    }

}
