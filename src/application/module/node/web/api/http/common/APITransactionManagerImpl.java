package application.module.node.web.api.http.common;

import application.module.node.*;
import application.module.node.Appendix.EncryptToSelfMessage;
import application.module.node.Appendix.EncryptedMessage;
import application.module.node.Appendix.Message;
import application.module.node.Appendix.PublicKeyAnnouncement;
import application.module.node.Transaction.Builder;
import application.module.node.crypto.Crypto;
import application.module.node.crypto.EncryptedData;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.services.TransactionService;
import application.module.node.util.Convert;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.*;
import static application.module.node.web.api.http.common.ResultFields.FULL_HASH_RESPONSE;

import java.util.Arrays;

public class APITransactionManagerImpl implements APITransactionManager {

    private final ParameterService parameterService;
    private final TransactionProcessor transactionProcessor;
    private final Blockchain blockchain;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final PropertyService propertyService;
    private final FluxCapacitor fluxCapacitor;

    public APITransactionManagerImpl(ParameterService parameterService, TransactionProcessor transactionProcessor,
            Blockchain blockchain, AccountService accountService,
            TransactionService transactionService, PropertyService propertyService, FluxCapacitor fluxCapacitor) {
        this.parameterService = parameterService;
        this.transactionProcessor = transactionProcessor;
        this.blockchain = blockchain;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.propertyService = propertyService;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    public JsonElement createTransaction(HttpServletRequest req, Account senderAccount, Long recipientId,
            long amountNQT, Attachment attachment, long minimumFeeNQT) throws SignumException {
        int blockchainHeight = blockchain.getHeight();
        String deadlineValue = req.getParameter(DEADLINE_PARAMETER);
        String referencedTransactionFullHash = Convert
                .emptyToNull(req.getParameter(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER));
        String referencedTransactionId = Convert.emptyToNull(req.getParameter(REFERENCED_TRANSACTION_PARAMETER));
        String secretPhrase = Convert.emptyToNull(req.getParameter(SECRET_PHRASE_PARAMETER));
        String publicKeyValue = Convert.emptyToNull(req.getParameter(PUBLIC_KEY_PARAMETER));
        String recipientPublicKeyValue = Convert.emptyToNull(ParameterParser.getRecipientPublicKey(req));
        boolean broadcast = !Parameters.isFalse(req.getParameter(BROADCAST_PARAMETER));

        long cashBackId = Convert.parseUnsignedLong(propertyService.getString(Props.CASH_BACK_ID));

        EncryptedMessage encryptedMessage = null;

        if (attachment.getTransactionType().hasRecipient()) {
            EncryptedData encryptedData = parameterService.getEncryptedMessage(req,
                    accountService.getAccount(recipientId), Convert.parseHexString(recipientPublicKeyValue));
            if (encryptedData != null) {
                encryptedMessage = new EncryptedMessage(encryptedData,
                        !Parameters.isFalse(req.getParameter(MESSAGE_TO_ENCRYPT_IS_TEXT_PARAMETER)), blockchainHeight);
            }
        }

        EncryptToSelfMessage encryptToSelfMessage = null;
        EncryptedData encryptedToSelfData = parameterService.getEncryptToSelfMessage(req);
        if (encryptedToSelfData != null) {
            encryptToSelfMessage = new EncryptToSelfMessage(encryptedToSelfData,
                    !Parameters.isFalse(req.getParameter(MESSAGE_TO_ENCRYPT_TO_SELF_IS_TEXT_PARAMETER)),
                    blockchainHeight);
        }
        Message message = null;
        String messageValue = Convert.emptyToNull(req.getParameter(MESSAGE_PARAMETER));
        if (messageValue != null) {
            boolean messageIsText = fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, blockchainHeight)
                    && !Parameters.isFalse(req.getParameter(MESSAGE_IS_TEXT_PARAMETER));
            try {
                message = messageIsText ? new Message(messageValue, blockchainHeight)
                        : new Message(Convert.parseHexString(messageValue), blockchainHeight);
            } catch (RuntimeException e) {
                throw new ParameterException(INCORRECT_ARBITRARY_MESSAGE);
            }
        } else if (attachment instanceof Attachment.ColoredCoinsAssetTransfer
                && fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, blockchainHeight)) {
            String commentValue = Convert.emptyToNull(req.getParameter(COMMENT_PARAMETER));
            if (commentValue != null) {
                message = new Message(commentValue, blockchainHeight);
            }
        } else if (attachment == Attachment.ARBITRARY_MESSAGE
                && !fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, blockchainHeight)) {
            message = new Message(new byte[0], blockchainHeight);
        }
        PublicKeyAnnouncement publicKeyAnnouncement = null;
        byte[] recipientPublicKey = Convert.parseHexString(recipientPublicKeyValue);
        if (recipientPublicKeyValue != null
                && fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, blockchainHeight)) {
            publicKeyAnnouncement = new PublicKeyAnnouncement(recipientPublicKey, blockchainHeight);
        }

        if (secretPhrase == null && publicKeyValue == null) {
            return MISSING_SECRET_PHRASE;
        } else if (deadlineValue == null) {
            return MISSING_DEADLINE;
        }

        short deadline;
        try {
            deadline = Short.parseShort(deadlineValue);
            if (deadline < 1 || deadline > 1440) {
                return INCORRECT_DEADLINE;
            }
        } catch (NumberFormatException e) {
            return INCORRECT_DEADLINE;
        }

        long feeNQT = ParameterParser.getFeeNQT(req);
        if (feeNQT < minimumFeeNQT) {
            return INCORRECT_FEE;
        }

        try {
            Account.Balance senderBalance = Account.getAccountBalance(senderAccount.getId());
            if (Convert.safeAdd(amountNQT, feeNQT) > senderBalance.getUnconfirmedBalanceNqt()) {
                return NOT_ENOUGH_FUNDS;
            }
        } catch (ArithmeticException e) {
            return NOT_ENOUGH_FUNDS;
        }

        if (referencedTransactionId != null) {
            return INCORRECT_REFERENCED_TRANSACTION;
        }

        JsonObject response = new JsonObject();

        // shouldn't try to get publicKey from senderAccount as it may have not been set
        // yet
        byte[] publicKey = secretPhrase != null ? Crypto.getPublicKey(secretPhrase)
                : Convert.parseHexString(publicKeyValue);

        try {
            Builder builder = transactionProcessor
                    .newTransactionBuilder(publicKey, amountNQT, feeNQT, deadline, attachment)
                    .referencedTransactionFullHash(referencedTransactionFullHash);
            if (attachment.getTransactionType().hasRecipient()) {
                Account recipientAccount = accountService.getAccount(recipientId);
                if (recipientId != 0L && propertyService.getBoolean(Props.PK_API_BLOCK)) {
                    long referenceId = Convert.fullHashToId(referencedTransactionFullHash);
                    if ((referenceId != recipientId) // allow to send to a fresh new account if we use the reference to
                                                     // a transaction that has that ID (contract creation pending)
                            && (recipientAccount == null || recipientAccount.getPublicKey() == null)
                            && publicKeyAnnouncement == null) {
                        return incorrect(RECIPIENT_PARAMETER);
                    }
                }
                if (recipientAccount != null && recipientAccount.getPublicKey() != null
                        && Arrays.equals(recipientAccount.getPublicKey(), recipientPublicKey)) {
                    // no need to announce the same public key already on chain
                    publicKeyAnnouncement = null;
                }
                builder.recipientId(recipientId);
            }
            if (encryptedMessage != null) {
                builder.encryptedMessage(encryptedMessage);
            }
            if (message != null) {
                builder.message(message);
            }
            if (publicKeyAnnouncement != null) {
                builder.publicKeyAnnouncement(publicKeyAnnouncement);
            }
            if (encryptToSelfMessage != null) {
                builder.encryptToSelfMessage(encryptToSelfMessage);
            }
            builder.cashBackId(cashBackId);

            Transaction transaction = builder.build();
            transactionService.validate(transaction);

            if (secretPhrase != null) {
                transaction.sign(secretPhrase);
                transactionService.validate(transaction); // 2nd validate may be needed if validation requires id to be
                                                          // known
                response.addProperty(TRANSACTION_RESPONSE, transaction.getStringId());
                response.addProperty(FULL_HASH_RESPONSE, transaction.getFullHash());
                response.addProperty(TRANSACTION_BYTES_RESPONSE, Convert.toHexString(transaction.getBytes()));
                response.addProperty(SIGNATURE_HASH_RESPONSE,
                        Convert.toHexString(Crypto.sha256().digest(transaction.getSignature())));
                if (broadcast) {
                    response.addProperty(NUMBER_PEERS_SENT_TO_RESPONSE, transactionProcessor.broadcast(transaction));
                    response.addProperty(BROADCASTED_RESPONSE, true);
                } else {
                    response.addProperty(BROADCASTED_RESPONSE, false);
                }
            } else {
                response.addProperty(BROADCASTED_RESPONSE, false);
            }
            response.addProperty(UNSIGNED_TRANSACTION_BYTES_RESPONSE,
                    Convert.toHexString(transaction.getUnsignedBytes()));
            response.add(TRANSACTION_JSON_RESPONSE, JSONData.unconfirmedTransaction(transaction));

        } catch (SignumException.NotYetEnabledException e) {
            return FEATURE_NOT_AVAILABLE;
        } catch (SignumException.ValidationException e) {
            response.addProperty(ERROR_CODE_RESPONSE, 4);
            response.addProperty(ERROR_DESCRIPTION_RESPONSE, e.getMessage());
        }
        return response;
    }
}
