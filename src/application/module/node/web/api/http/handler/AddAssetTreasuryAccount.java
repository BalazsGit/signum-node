package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.JSONResponses;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;

public final class AddAssetTreasuryAccount extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AssetExchange assetExchange;
    private final FluxCapacitor fluxCapacitor;

    public AddAssetTreasuryAccount(ParameterService parameterService, AssetExchange assetExchange,
            Blockchain blockchain, APITransactionManager apiTransactionManager, AccountService accountService,
            FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager,
                fluxCapacitor, RECIPIENT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.assetExchange = assetExchange;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        long recipient = ParameterParser.getRecipientId(req, fluxCapacitor);
        Account sender = parameterService.getSenderAccount(req);

        String referenceTransaction = req.getParameter(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER);
        if (referenceTransaction == null) {
            return JSONResponses.missing(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER);
        }

        Transaction transaction = blockchain.getTransactionByFullHash(referenceTransaction);
        if (transaction == null || !(transaction.getAttachment() instanceof Attachment.ColoredCoinsAssetIssuance)) {
            return JSONResponses.incorrect(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER);
        }
        Asset asset = assetExchange.getAsset(transaction.getId());
        if (asset == null || asset.getAccountId() != sender.getId()) {
            return JSONResponses.incorrect(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER);
        }

        return createTransaction(req, sender, recipient, 0L, Attachment.ASSET_ADD_TREASURY_ACCOUNT_ATTACHMENT);
    }

}