package application.module.node.web.api.http.common;

import application.module.node.Account;
import application.module.node.Attachment;
import application.module.node.SignumException;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

public interface APITransactionManager {

    JsonElement createTransaction(HttpServletRequest req, Account senderAccount, Long recipientId, long amountNQT,
            Attachment attachment, long minimumFeeNQT) throws SignumException;

}
