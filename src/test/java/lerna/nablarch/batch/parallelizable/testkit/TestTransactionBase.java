package lerna.nablarch.batch.parallelizable.testkit;

import nablarch.core.transaction.Transaction;


public class TestTransactionBase implements Transaction {
    @Override
    public void begin() {
        // Do nothing
    }
    @Override
    public void commit() {
        // Do nothing
    }
    @Override
    public void rollback() {
        // Do nothing
    }
}
