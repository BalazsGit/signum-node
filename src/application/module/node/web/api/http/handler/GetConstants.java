package application.module.node.web.api.http.handler;

import jakarta.servlet.http.HttpServletRequest;

import application.module.node.Blockchain;
import application.module.node.Constants;
import application.module.node.Genesis;
import application.module.node.TransactionType;
import application.module.node.TransactionType.Fee;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.util.Convert;
import application.module.node.util.JSON;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import signumj.util.SignumUtils;

public final class GetConstants extends ApiServlet.JsonRequestHandler {

    private final Blockchain blockchain;
    private final PropertyService propertyService;
    private final FluxCapacitor fluxCapacitor;

    public GetConstants(Blockchain blockchain, PropertyService propertyService, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.INFO });
        this.blockchain = blockchain;
        this.propertyService = propertyService;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        JsonObject response = new JsonObject();
        response.addProperty("networkName", this.propertyService.getString(Props.NETWORK_NAME));
        response.addProperty("genesisBlockId", this.propertyService.getString(Props.GENESIS_BLOCK_ID));
        response.addProperty("genesisAccountId", Convert.toUnsignedLong(Genesis.CREATOR_ID));
        response.addProperty("maxBlockPayloadLength",
                (this.fluxCapacitor.getValue(FluxValues.MAX_PAYLOAD_LENGTH)));
        response.addProperty("maxArbitraryMessageLength", Constants.MAX_ARBITRARY_MESSAGE_LENGTH);
        response.addProperty("ordinaryTransactionLength", Constants.ORDINARY_TRANSACTION_BYTES);
        response.addProperty("addressPrefix", SignumUtils.getAddressPrefix());
        response.addProperty("valueSuffix", SignumUtils.getValueSuffix());
        response.addProperty("blockTime", this.fluxCapacitor.getValue(FluxValues.BLOCK_TIME));
        response.addProperty("decimalPlaces", this.propertyService.getInt(Props.DECIMAL_PLACES));
        response.addProperty("feeQuantNQT", this.fluxCapacitor.getValue(FluxValues.FEE_QUANT));
        response.addProperty("cashBackId", this.propertyService.getString(Props.CASH_BACK_ID));
        response.addProperty("cashBackFactor", this.propertyService.getInt(Props.CASH_BACK_FACTOR));

        JsonArray transactionTypes = new JsonArray();
        TransactionType.getTransactionTypes()
                .forEach((key, value) -> {
                    JsonObject transactionType = new JsonObject();
                    transactionType.addProperty("value", key.getType());
                    transactionType.addProperty("description", key.getDescription());
                    JsonArray transactionSubtypes = new JsonArray();
                    transactionSubtypes.addAll(value.entrySet().stream()
                            .map(entry -> {
                                JsonObject transactionSubtype = new JsonObject();
                                Fee fee = entry.getValue().getBaselineFee(this.blockchain.getHeight());
                                transactionSubtype.addProperty("value", entry.getKey());
                                transactionSubtype.addProperty("description", entry.getValue().getDescription());
                                transactionSubtype.addProperty("minimumFeeConstantNQT", fee.getConstantFee());
                                transactionSubtype.addProperty("minimumFeeAppendagesNQT", fee.getAppendagesFee());
                                return transactionSubtype;
                            })
                            .collect(JSON.jsonArrayCollector()));
                    transactionType.add("subtypes", transactionSubtypes);
                    transactionTypes.add(transactionType);
                });
        response.add("transactionTypes", transactionTypes);

        JsonArray peerStates = new JsonArray();
        JsonObject peerState = new JsonObject();
        peerState.addProperty("value", 0);
        peerState.addProperty("description", "Non-connected");
        peerStates.add(peerState);
        peerState = new JsonObject();
        peerState.addProperty("value", 1);
        peerState.addProperty("description", "Connected");
        peerStates.add(peerState);
        peerState = new JsonObject();
        peerState.addProperty("value", 2);
        peerState.addProperty("description", "Disconnected");
        peerStates.add(peerState);
        response.add("peerStates", peerStates);

        return response;
    }
}
