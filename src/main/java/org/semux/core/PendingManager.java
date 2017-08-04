/*
 * Copyright (c) 2017 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.semux.Config;
import org.semux.core.state.AccountState;
import org.semux.core.state.DelegateState;
import org.semux.net.Channel;
import org.semux.net.ChannelManager;
import org.semux.net.msg.p2p.TransactionMessage;
import org.semux.utils.ByteArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pending transaction manager that organizes all the received transactions.
 * 
 * TODO: prevent transaction bombard
 * 
 * TODO: sort the pending transactions by fee, and other metric
 *
 */
public class PendingManager implements Runnable, BlockchainListener {

    private static final Logger logger = LoggerFactory.getLogger(PendingManager.class);

    private static ThreadFactory factory = new ThreadFactory() {

        private AtomicInteger cnt = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "pending-mgr-" + cnt.getAndIncrement());
        }
    };

    private Blockchain chain;
    private ChannelManager channelMgr;

    /**
     * Transactions to validate.
     */
    private Queue<Transaction> queue = new LinkedBlockingQueue<>();

    /**
     * Transactions that have been validated.
     */
    private Map<ByteArray, Transaction> transactions = new ConcurrentHashMap<>();

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> validateFuture;

    private boolean isRunning;

    private static PendingManager instance;

    private PendingManager() {
        this.exec = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /**
     * Get the singleton instance.
     * 
     * @return
     */
    public static synchronized PendingManager getInstance() {
        if (instance == null) {
            instance = new PendingManager();
        }

        return instance;
    }

    /**
     * Start this pending manager.
     */
    public void start(Blockchain chain, ChannelManager channelMgr) {
        if (!isRunning) {
            this.chain = chain;
            this.channelMgr = channelMgr;

            this.validateFuture = exec.scheduleAtFixedRate(this, 1, 1, TimeUnit.MILLISECONDS);

            logger.debug("Pending manager started");
            this.isRunning = true;
        }
    }

    /**
     * Shut down this pending manager.
     */
    public void stop() {
        if (isRunning) {
            validateFuture.cancel(true);

            logger.debug("Pending manager stopped");
            isRunning = false;
        }
    }

    /**
     * Check if the pending manager is running or not.
     * 
     * @return
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Get a copy of the queue, for test purpose only.
     * 
     * @return
     */
    public List<Transaction> getQueue() {
        return new ArrayList<>(queue);
    }

    /**
     * Add a transaction to the queue, which will be validated by a background
     * worker.
     * 
     * @param tx
     */
    public void addTransaction(Transaction tx) {
        queue.add(tx);
    }

    /**
     * Get a limited number of transactions in the pool.
     * 
     * @param limit
     * @return
     */
    public List<Transaction> getTransactions(int limit) {
        List<Transaction> list = new ArrayList<>();
        synchronized (transactions) {
            list.addAll(transactions.values());
        }

        list.sort((tx1, tx2) -> {
            int c = Long.compare(tx1.getTimestamp(), tx2.getTimestamp());
            return (c != 0) ? c : Long.compare(tx1.getNonce(), tx2.getNonce());
        });

        if (limit >= 0) {
            return list.size() > limit ? list.subList(0, limit) : list;
        }
        return list;
    }

    /**
     * Get all transactions in the pool.
     * 
     * @return
     */
    public List<Transaction> getTransactions() {
        return getTransactions(-1);
    }

    /**
     * Remove transactions from the pool.
     * 
     * @param txs
     */
    public void removeTransactions(List<Transaction> txs) {
        for (Transaction tx : txs) {
            transactions.remove(ByteArray.of(tx.getHash()));
        }
    }

    /**
     * Remove a transaction from the pool.
     * 
     * @param tx
     */
    public void removeTransaction(Transaction tx) {
        transactions.remove(ByteArray.of(tx.getHash()));
    }

    @Override
    public void onBlockAdded(Block block) {
        removeTransactions(block.getTransactions());
    }

    @Override
    public void run() {
        TransactionExecutor exec = TransactionExecutor.getInstance();
        Transaction tx = null;

        while ((tx = queue.poll()) != null) {
            ByteArray key = ByteArray.of(tx.getHash());

            if (!transactions.containsKey(key) && chain.getTransaction(tx.getHash()) == null && tx.validate()) {

                AccountState as = chain.getAccountState().track();
                DelegateState ds = chain.getDeleteState().track();

                if (exec.execute(tx, as, ds, false).isValid()) {
                    transactions.put(key, tx);

                    List<Channel> channels = channelMgr.getActiveChannels();
                    for (int i = 0; i < Config.NET_BROADCAST_REDUNDANCY && i < channels.size(); i++) {
                        if (channels.get(i).isActive()) {
                            TransactionMessage msg = new TransactionMessage(tx);
                            channels.get(i).getMessageQueue().sendMessage(msg);
                        }
                    }

                    // one new transaction per time, so exit
                    return;
                }
            }
        }
    }
}