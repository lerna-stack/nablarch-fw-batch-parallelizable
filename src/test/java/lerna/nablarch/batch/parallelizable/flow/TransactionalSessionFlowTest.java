package lerna.nablarch.batch.parallelizable.flow;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

import lerna.nablarch.batch.parallelizable.testkit.TestTransactionBase;
import lerna.nablarch.batch.parallelizable.testkit.TestTransactionManagerConnectionBase;
import nablarch.core.db.connection.ConnectionFactory;
import nablarch.core.db.connection.TransactionManagerConnection;
import nablarch.core.transaction.Transaction;
import nablarch.core.transaction.TransactionFactory;
import org.junit.Test;

public class TransactionalSessionFlowTest {
    @Test
    public void コミットインターバルに負数を設定できないこと() {
        ConnectionFactory connectionFactory = new ConnectionFactory() {
            @Override
            public TransactionManagerConnection getConnection(String s) {
                return new TestTransactionManagerConnectionBase();
            }
        };
        TransactionFactory transactionFactory = new TransactionFactory() {
            @Override
            public Transaction getTransaction(String s) {
                return new TestTransactionBase();
            }
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            TransactionalSessionFlow.create(connectionFactory, "connection-name", transactionFactory, "transaction-name", -1);
        });
        assertThat(e.getMessage(), is("commitInterval(-1) should be greater than or equal to zero."));
    }
}
