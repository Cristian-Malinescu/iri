package com.iota.iri.controllers;

import com.iota.iri.conf.Configuration;
import com.iota.iri.model.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static com.iota.iri.service.TipsManager.printNewSolidTransactions;

/**
 * Created by paul on 3/27/17.
 */
public abstract class TransactionRequester {

    private final Logger log = LoggerFactory.getLogger(TransactionRequester.class);
    private final Queue<Hash> transactionsToRequest = new LinkedList<>();
    private volatile long lastTime = System.currentTimeMillis();
    public  static final int REQUEST_HASH_SIZE = 46;
    private static final byte[] NULL_REQUEST_HASH_BYTES = new byte[REQUEST_HASH_SIZE];


    public void rescanTransactionsToRequest() throws ExecutionException, InterruptedException {
        Hash[] missingTx = TransactionViewModel.getMissingTransactions();
        synchronized (this) {
            transactionsToRequest.clear();
            transactionsToRequest.addAll(Arrays.asList(missingTx));
        }
    }
    public Hash[] getRequestedTransactions() {
        return transactionsToRequest.stream().toArray(Hash[]::new);
    }

    public static int getTotalNumberOfRequestedTransactions() {
        return MissingMilestones.instance().numberOfTransactionsToRequest() +
                MissingTipTransactions.instance().numberOfTransactionsToRequest();
    }

    public int numberOfTransactionsToRequest() {
        return transactionsToRequest.size();
    }

    boolean clearTransactionRequest(Hash hash) {
        synchronized (this) {
            return transactionsToRequest.remove(hash);
        }
    }

    public void requestTransaction(Hash hash) throws ExecutionException, InterruptedException {
        if (!hash.equals(Hash.NULL_HASH) && !TransactionViewModel.exists(hash)) {
            synchronized (this) {
                transactionsToRequest.add(hash);
            }
        }
    }

    public Hash transactionToRequest() throws Exception {
        final long beginningTime = System.currentTimeMillis();
        Hash hash = null;
        while(hash == null && transactionsToRequest.size() > 0) {
            synchronized (this) {
                hash = transactionsToRequest.poll();
                if(!TransactionViewModel.mightExist(hash)) {
                    transactionsToRequest.offer(hash);
                } else {
                    log.info("Removed existing tx from request list: " + hash);
                    hash = null;
                }
            }
            if(transactionsToRequest.size() == 0) {
                break;
            }
        }

        long now = System.currentTimeMillis();
        if ((now - lastTime) > 10000L) {
            lastTime = now;
            log.info("Transactions to request = {}", transactionsToRequest.size() + " / " + TransactionViewModel.getNumberOfStoredTransactions() + " (" + (now - beginningTime) + " ms ). " );
        }
        return hash;
    }

    public boolean checkSolidity(Hash hash) throws Exception {
        Set<Hash> analyzedHashes = new HashSet<>(Collections.singleton(Hash.NULL_HASH));
        boolean solid = true;
        final Queue<Hash> nonAnalyzedTransactions = new LinkedList<>(Collections.singleton(hash));
        Hash hashPointer, trunkInteger, branchInteger;
        while ((hashPointer = nonAnalyzedTransactions.poll()) != null) {
            if (analyzedHashes.add(hashPointer)) {
                final TransactionViewModel transactionViewModel2 = TransactionViewModel.fromHash(hashPointer);
                if(!transactionViewModel2.isSolid()) {
                    if (transactionViewModel2.getType() == TransactionViewModel.PREFILLED_SLOT && !hashPointer.equals(Hash.NULL_HASH)) {
                        requestTransaction(hashPointer);
                        solid = false;
                        break;

                    } else {
                        trunkInteger = transactionViewModel2.getTrunkTransactionHash();
                        branchInteger = transactionViewModel2.getBranchTransactionHash();
                        nonAnalyzedTransactions.offer(trunkInteger);
                        nonAnalyzedTransactions.offer(branchInteger);
                    }
                }
            }
        }
        if (solid) {
            printNewSolidTransactions(analyzedHashes);
            TransactionViewModel.updateSolidTransactions(analyzedHashes);
        }
        return solid;
    }

}
