package application.module.brs.web.api.http.common;

import application.module.brs.Account;
import application.module.brs.Attachment;
import application.module.brs.SignumException;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

public interface APITransactionManager {

    JsonElement createTransaction(HttpServletRequest req, Account senderAccount, Long recipientId, long amountNQT,
            Attachment attachment, long minimumFeeNQT) throws SignumException;

}
