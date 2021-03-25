package lerna.nablarch.batch.parallelizable.db;

import java.util.function.Supplier;

import nablarch.core.db.connection.ConnectionFactory;
import nablarch.core.db.connection.DbConnectionContext;
import nablarch.core.db.connection.TransactionManagerConnection;
import nablarch.core.transaction.Transaction;
import nablarch.core.transaction.TransactionContext;
import nablarch.core.transaction.TransactionExecutor;
import nablarch.core.transaction.TransactionFactory;

/**
 * DBコネクションとトランザクションを紐づけて管理するためのクラス。
 *
 * @since 2018/04/09
 */
public class TransactionalSession {

    /**
     * {@link TransactionalSession} を作成します。
     *
     * @param connectionFactory コネクションを取得するための {@link ConnectionFactory}
     * @param connectionName {@link DbConnectionContext} に設定するコネクション名
     * @param transactionFactory トランザクションを作成するための {@link TransactionFactory}
     * @param transactionName {@link TransactionContext} に設定するトランザクション名
     * @return 作成された {@link TransactionalSession}
     */
    public static TransactionalSession create(ConnectionFactory connectionFactory, String connectionName, TransactionFactory transactionFactory,
                                              String transactionName) {
        final TransactionManagerConnection connection = connectionFactory.getConnection(connectionName);
        // TransactionFactory#getTransaction にはトランザクションを貼る対象のコネクション名を指定する必要がある
        // transactionName は TransactionContext にトランザクションを保存するときに使うリソース名
        final Transaction transaction = transactionFactory.getTransaction(connectionName);

        return new TransactionalSession(connection, connectionName, transaction, transactionName);
    }

    private final TransactionManagerConnection connection;

    private final Transaction transaction;

    private final String connectionName;

    private final String transactionName;

    /**
     * コンストラクタ
     *
     * @param connection {@link DbConnectionContext} に設定する {@link TransactionManagerConnection}
     * @param connectionName {@link DbConnectionContext} に設定するコネクション名
     * @param transaction {@link TransactionContext} に設定するトランザクション
     * @param transactionName {@link TransactionContext} に設定するトランザクション名
     */
    private TransactionalSession(TransactionManagerConnection connection, String connectionName, Transaction transaction, String transactionName) {
        this.connection = connection;
        this.connectionName = connectionName;
        this.transaction = transaction;
        this.transactionName = transactionName;
    }

    /**
     * DBコネクションを {@link DbConnectionContext} に設定し、指定された処理を実行します。
     *
     * @param <T> DBコネクションを用いて実行する処理の戻り値
     * @param task DBコネクションを用いて実行する処理
     * @return DBコネクションを用いて実行する処理の戻り値
     */
    private <T> T doInConnectionContext(Supplier<T> task) {
        try {
            DbConnectionContext.setConnection(connectionName, connection);
            final T result = task.get();
            return result;
        } finally {
            DbConnectionContext.removeConnection(connectionName);
        }
    }

    /**
     * トランザクションを {@link nablarch.core.ThreadContext} に設定し、指定された処理を実行します。
     * <p>
     * トランザクションの状態管理は行わないため、別途
     * {@link TransactionalSession#begin()}、{@link TransactionalSession#commit()}、{@link TransactionalSession#rollback()}
     * を実行する必要があります。
     *
     * @param <T> トランザクションを用いて実行する処理の戻り値
     * @param task トランザクションを用いて実行する処理
     * @return トランザクションを用いて実行する処理の戻り値
     */
    public <T> T doInTransactionContext(Supplier<T> task) {
        return doInConnectionContext(() -> {
            try {
                TransactionContext.setTransaction(transactionName, transaction);
                final T result = task.get();
                return result;
            } finally {
                TransactionContext.removeTransaction(transactionName);
            }
        });
    }

    /**
     * トランザクションを {@link nablarch.core.ThreadContext} に設定し、指定された処理を実行します。
     * <p>
     * トランザクションの状態管理は行わないため、別途
     * {@link TransactionalSession#begin()}、{@link TransactionalSession#commit()}、{@link TransactionalSession#rollback()}
     * を実行する必要があります。
     *
     * @param task トランザクションを用いて実行する処理
     */
    public void runInTransactionContext(Runnable task) {
        this.<Void> doInTransactionContext(() -> {
            task.run();
            return null;
        });
    }

    /**
     * トランザクション内で実行する。
     * <p>
     * 処理完了後は自動的にコミットされ、例外が起きた場合、トランザクションはロールバックします。
     *
     * @param task トランザクション内で実行する処理
     */
    public void runInTransaction(Runnable task) {
        runInTransactionContext(() -> new TransactionExecutor<Void>(transaction) {
            @Override
            protected Void doInTransaction(Transaction transaction) {
                task.run();
                return null;
            }
        }.execute());
    }

    /**
     * トランザクションを開始します
     */
    public void begin() {
        runInTransactionContext(() -> transaction.begin());
    }

    /**
     * トランザクションをコミットします
     */
    public void commit() {
        runInTransactionContext(() -> transaction.commit());
    }

    /**
     * トランザクションをロールバックします
     */
    public void rollback() {
        runInTransactionContext(() -> transaction.rollback());
    }

    /**
     * トランザクションを終了します
     */
    public void close() {
        connection.terminate();
    }
}
