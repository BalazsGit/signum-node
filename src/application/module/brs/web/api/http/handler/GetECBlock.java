package application.module.brs.web.api.http.handler;

import application.module.brs.Block;
import application.module.brs.Blockchain;
import application.module.brs.SignumException;
import application.module.brs.EconomicClustering;
import application.module.brs.services.TimeService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONResponses;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.TIMESTAMP_PARAMETER;
import static application.module.brs.web.api.http.common.ResultFields.*;

public final class GetECBlock extends ApiServlet.JsonRequestHandler {

    private final Blockchain blockchain;
    private final TimeService timeService;
    private final EconomicClustering economicClustering;

    public GetECBlock(Blockchain blockchain, TimeService timeService, EconomicClustering economicClustering) {
        super(new LegacyDocTag[] { LegacyDocTag.BLOCKS }, TIMESTAMP_PARAMETER);
        this.blockchain = blockchain;
        this.timeService = timeService;
        this.economicClustering = economicClustering;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        int timestamp = ParameterParser.getTimestamp(req);
        if (timestamp == 0) {
            timestamp = timeService.getEpochTime();
        }
        if (timestamp < blockchain.getLastBlock().getTimestamp() - 15) {
            return JSONResponses.INCORRECT_TIMESTAMP;
        }
        Block ecBlock = economicClustering.getECBlock(timestamp);
        JsonObject response = new JsonObject();
        response.addProperty(EC_BLOCK_ID_RESPONSE, ecBlock.getStringId());
        response.addProperty(EC_BLOCK_HEIGHT_RESPONSE, ecBlock.getHeight());
        response.addProperty(TIMESTAMP_RESPONSE, timestamp);
        return response;
    }

}
