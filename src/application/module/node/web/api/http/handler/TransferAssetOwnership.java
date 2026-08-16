package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;

import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.JSONResponses;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;

public final class TransferAssetOwnership extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AssetExchange assetExchange;
    private final FluxCapacitor fluxCapacitor;

    public TransferAssetOwnership(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, AssetExchange assetExchange,
            FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                RECIPIENT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.assetExchange = assetExchange;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getSenderAccount(req);
        long recipientId = ParameterParser.getRecipientId(req, fluxCapacitor);

        String fullHashReference = Convert.emptyToNull(req.getParameter(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER));
        Transaction assetIssuanceTransaction = blockchain.getTransactionByFullHash(fullHashReference);

        if (assetIssuanceTransaction == null) {
            return JSONResponses.incorrect(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER);
        }

        Asset asset = assetExchange.getAsset(assetIssuanceTransaction.getId());
        if (asset == null) {
            return JSONResponses.incorrect(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER,
                    "reference transaction full hash is not an asset issuance transaction");
        }

        if (asset.getAccountId() != account.getId()) {
            return JSONResponses.incorrect(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER,
                    "sender is not the asset current owner");
        }
        if (!this.fluxCapacitor.getValue(FluxValues.PK_FREEZE2)) {
            return JSONResponses.incorrect(REFERENCED_TRANSACTION_FULL_HASH_PARAMETER,
                    "ownership transfer is not enabled yet");
        }

        Attachment attachment = Attachment.COLORED_COINS_ASSET_TRANSFER_OWNERSHIP;
        return createTransaction(req, account, recipientId, 0L, attachment);
    }

}