package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Attachment;
import application.module.node.SignumException;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;

import static application.module.node.web.api.http.common.Parameters.*;

public abstract class CreateTransaction extends ApiServlet.JsonRequestHandler {

    private static final String[] commonParameters = new String[] {
            SECRET_PHRASE_PARAMETER, PUBLIC_KEY_PARAMETER, FEE_NQT_PARAMETER,
            DEADLINE_PARAMETER, REFERENCED_TRANSACTION_FULL_HASH_PARAMETER, BROADCAST_PARAMETER,
            MESSAGE_PARAMETER, MESSAGE_IS_TEXT_PARAMETER,
            MESSAGE_TO_ENCRYPT_PARAMETER, MESSAGE_TO_ENCRYPT_IS_TEXT_PARAMETER, ENCRYPTED_MESSAGE_DATA_PARAMETER,
            ENCRYPTED_MESSAGE_NONCE_PARAMETER,
            MESSAGE_TO_ENCRYPT_TO_SELF_PARAMETER, MESSAGE_TO_ENCRYPT_TO_SELF_IS_TEXT_PARAMETER,
            ENCRYPT_TO_SELF_MESSAGE_DATA, ENCRYPT_TO_SELF_MESSAGE_NONCE,
            RECIPIENT_PUBLIC_KEY_PARAMETER };

    private final APITransactionManager apiTransactionManager;
    protected final FluxCapacitor fluxCapacitor;

    private static String[] addCommonParameters(String[] parameters) {
        String[] result = Arrays.copyOf(parameters, parameters.length + commonParameters.length);
        System.arraycopy(commonParameters, 0, result, parameters.length, commonParameters.length);
        return result;
    }

    protected CreateTransaction(LegacyDocTag[] legacyDocTags, APITransactionManager apiTransactionManager,
            FluxCapacitor fluxCapacitor, boolean replaceParameters, String... parameters) {
        super(legacyDocTags, replaceParameters ? parameters : addCommonParameters(parameters));
        this.apiTransactionManager = apiTransactionManager;
        this.fluxCapacitor = fluxCapacitor;
    }

    protected CreateTransaction(LegacyDocTag[] legacyDocTags, APITransactionManager apiTransactionManager,
            FluxCapacitor fluxCapacitor, String... parameters) {
        super(legacyDocTags, addCommonParameters(parameters));
        this.apiTransactionManager = apiTransactionManager;
        this.fluxCapacitor = fluxCapacitor;
    }

    public final JsonElement createTransaction(HttpServletRequest req, Account senderAccount, Attachment attachment)
            throws SignumException {
        return createTransaction(req, senderAccount, null, 0, attachment);
    }

    public final JsonElement createTransaction(HttpServletRequest req, Account senderAccount, Long recipientId,
            long amountNQT)
            throws SignumException {
        return createTransaction(req, senderAccount, recipientId, amountNQT, Attachment.ORDINARY_PAYMENT);
    }

    public final JsonElement createTransaction(HttpServletRequest req, Account senderAccount, Long recipientId,
            long amountNQT, Attachment attachment) throws SignumException {
        return apiTransactionManager.createTransaction(req, senderAccount, recipientId, amountNQT, attachment,
                minimumFeeNQT());
    }

    final boolean requirePost() {
        return true;
    }

    private long minimumFeeNQT() {
        return fluxCapacitor.getValue(FluxValues.FEE_QUANT);
    }

}
