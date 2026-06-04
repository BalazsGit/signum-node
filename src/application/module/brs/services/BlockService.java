package application.module.brs.services;

import application.module.brs.Block;
import application.module.brs.BlockchainProcessor;
import application.module.brs.BlockchainProcessor.BlockNotAcceptedException;
import application.module.brs.BlockchainProcessor.BlockOutOfOrderException;

public interface BlockService {

    void preVerify(Block block, Block prevBlock)
            throws BlockchainProcessor.BlockNotAcceptedException, InterruptedException;

    void preVerify(Block block, Block prevBlock, byte[] scoopData)
            throws BlockchainProcessor.BlockNotAcceptedException, InterruptedException;

    void watchBlock(Block block);

    long getBlockReward(Block block);

    void calculateBaseTarget(Block block, Block lastBlock) throws BlockOutOfOrderException;

    void setPrevious(Block block, Block previousBlock);

    boolean verifyGenerationSignature(Block block) throws BlockNotAcceptedException;

    boolean verifyBlockSignature(Block block) throws BlockOutOfOrderException;

    void apply(Block block);

    int getScoopNum(Block block);
}
