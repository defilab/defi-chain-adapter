package com.defilab.ChainAdapter.ledgers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public abstract class Ledger {
    abstract public String putOffer(Map<String, Object> offer) throws Exception;
    abstract public String acceptOffer(Map<String, Object> offer) throws Exception;
    abstract public Map<String, Object> getTransaction(String transactionId) throws Exception;
    abstract public Boolean verifyTransaction(String offerId, String transactionId, String action) throws Exception;
    abstract public Integer getBlockchainHeight() throws Exception;
    abstract public Double getAccountBalance(String accountAddress) throws Exception;
    abstract public List<Map<String, Object>> getTransactionsFromBlock(Integer blockNumber) throws Exception;
    
    public Boolean verifyTransaction(String offerId, String transactionId, String action, Integer timeout) throws Exception {
        long startTime = System.currentTimeMillis();
        while (!verifyTransaction(offerId, transactionId, action)) {
            if (System.currentTimeMillis() - startTime > timeout * 1000) return false;
            else TimeUnit.MILLISECONDS.sleep(500);
        }
        return true;
    }
}
