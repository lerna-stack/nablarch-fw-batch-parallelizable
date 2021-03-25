package lerna.nablarch.batch.parallelizable.flow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import akka.Done;
import akka.stream.Attributes;
import akka.stream.FlowShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.javadsl.Flow;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import akka.stream.stage.GraphStageWithMaterializedValue;
import lerna.nablarch.batch.parallelizable.db.TransactionalSession;
import nablarch.core.db.connection.ConnectionFactory;
import nablarch.core.db.connection.TransactionManagerConnection;
import nablarch.core.log.Logger;
import nablarch.core.log.LoggerManager;
import nablarch.core.transaction.Transaction;
import nablarch.core.transaction.TransactionFactory;
import nablarch.fw.Result;
import scala.Tuple2;

/**
 * {@link TransactionalSession} の作成と終了（commit, rollback）を行う {@link Flow}
 *
 * @since 2018/04/09
 */
public class TransactionalSessionFlow {

    /**
     * このクラスは静的メソッドによるファクトリ機能のみを提供するためインスタンス化しない
     */
    private TransactionalSessionFlow() {
        // Do nothing
    }

    /**
     * <p>
     * {@link TransactionalSession} を作成し、入力からある要素を受け取ると、 その要素をトランザクションの終了シグナルとみなし、トランザクションをコミットする
     * {@link Flow}。
     * </p>
     * <p>
     * 入力から何らかのエラーのシグナルを受け取った場合は、トランザクションはロールバックされる。
     * </p>
     *
     * <pre>{@literal
     *     +--------------------------------------------------------------+
     *     | Flow<IN, TransactionalSession, NotUsed>                      |
     *     |                                                              |
     *     |  +------------------------+      +------------------------+  |
     *     |  |                        |      |                        |  |
     * IN ~~> | TransactionalSession を|      | TransactionalSession を| ~~> TransactionalSession
     *     |  | 終了（commit/rollback）|      | 作成                   |  |
     *     |  +------------------------+      +------------------------+  |
     *     +--------------------------------------------------------------+
     * }</pre>
     **
     * @param <IN> トランザクションを終了させるシグナルになる要素の型
     * @param connectionFactory {@link TransactionalSession} に設定する
     *            {@link TransactionManagerConnection} を作成する {@link ConnectionFactory}
     * @param connectionName {@link TransactionalSession} に設定する {@link TransactionManagerConnection}
     *            を識別する名前
     * @param transactionFactory {@link TransactionalSession} に設定する {@link Transaction} を作成する
     *            {@link TransactionFactory}
     * @param transactionName {@link TransactionalSession} に設定する {@link Transaction} を識別する名前
     * @param commitInterval コミット間隔。0 を指定した場合は上流が完了するまでコミットしない
     * @return {@link Flow}
     */
    public static <IN extends Result> Flow<IN, TransactionalSession, CompletionStage<Done>> create(ConnectionFactory connectionFactory, String connectionName,
                                                                                    TransactionFactory transactionFactory, String transactionName, int commitInterval) {
        return Flow.fromGraph(TransactionalSessionFlowGraphStage.create(connectionFactory, connectionName, transactionFactory, transactionName, commitInterval));
    }

    /**
     * {@link TransactionalSession} の作成と終了（commit, rollback）を行う {@link GraphStage}
     *
     * @param <IN> トランザクションを終了させるシグナルになる要素の型
     *
     * @since 2018/04/09
     */
    public static class TransactionalSessionFlowGraphStage<IN extends Result> extends GraphStageWithMaterializedValue<FlowShape<IN, TransactionalSession>, CompletionStage<Done>> {

        /**
         * {@link TransactionalSessionFlowGraphStage} を作成する
         *
         * @param <IN> トランザクションを終了させるシグナルになる要素の型
         * @param connectionFactory {@link TransactionalSession} に設定する
         *            {@link TransactionManagerConnection} を作成する {@link ConnectionFactory}
         * @param connectionName {@link TransactionalSession} に設定する
         *            {@link TransactionManagerConnection} を識別する名前
         * @param transactionFactory {@link TransactionalSession} に設定する {@link Transaction} を作成する
         *            {@link TransactionFactory}
         * @param transactionName {@link TransactionalSession} に設定する {@link Transaction} を識別する名前
         * @param commitInterval コミット間隔。0 を指定した場合は上流が完了するまでコミットしない
         * @return 作成された {@link TransactionalSessionFlowGraphStage}
         */
        public static <IN extends Result> TransactionalSessionFlowGraphStage<IN> create(ConnectionFactory connectionFactory, String connectionName,
                                                                         TransactionFactory transactionFactory, String transactionName, int commitInterval) {
            return new TransactionalSessionFlowGraphStage<IN>(connectionFactory, connectionName, transactionFactory, transactionName, commitInterval);
        }

        private final Logger logger = LoggerManager.get(this.getClass());

        private final Inlet<IN> in = Inlet.create("TransactionalSessionFlowGraphStage.in");

        private final Outlet<TransactionalSession> out = Outlet.create("TransactionalSessionFlowGraphStage.out");

        private final ConnectionFactory connectionFactory;

        private final String connectionName;

        private final TransactionFactory transactionFactory;

        private final String transactionName;

        private final int commitInterval;

        /**
         * コンストラクタ
         *
         * @param connectionFactory {@link TransactionalSession} に設定する
         *            {@link TransactionManagerConnection} を作成する {@link ConnectionFactory}
         * @param connectionName {@link TransactionalSession} に設定する
         *            {@link TransactionManagerConnection} を識別する名前
         * @param transactionFactory {@link TransactionalSession} に設定する {@link Transaction} を作成する
         *            {@link TransactionFactory}
         * @param transactionName {@link TransactionalSession} に設定する {@link Transaction} を識別する名前
         * @param commitInterval コミット間隔。0 を指定した場合は上流が完了するまでコミットしない
         */
        private TransactionalSessionFlowGraphStage(ConnectionFactory connectionFactory, String connectionName, TransactionFactory transactionFactory,
                                                   String transactionName, int commitInterval) {
            this.connectionFactory = connectionFactory;
            this.connectionName = connectionName;
            this.transactionFactory = transactionFactory;
            this.transactionName = transactionName;

            if (commitInterval < 0) {
                throw new IllegalArgumentException("commitInterval(" + commitInterval + ") should be greater than or equal to zero.");
            }
            this.commitInterval = commitInterval;
        }

        @Override
        public FlowShape<IN, TransactionalSession> shape() {
            return FlowShape.of(in, out);
        }

        @Override
        public Tuple2<GraphStageLogic, CompletionStage<Done>> createLogicAndMaterializedValue(Attributes inheritedAttributes) throws Exception {

            final CompletableFuture<Done> connectionClosing = new CompletableFuture<>();
            final GraphStageLogic logic = new GraphStageLogic(shape()) {

                private TransactionalSession session;

                /** 処理済件数 */
                private long processedCounts = 0L;

                @Override
                public void preStart() {
                    pull(in);
                    try {
                        session = TransactionalSession.create(connectionFactory, connectionName, transactionFactory, transactionName);
                        session.begin();
                    } catch (Throwable ex) {
                        connectionClosing.completeExceptionally(ex);
                        failStage(ex);
                    }
                }

                {
                    setHandler(out, new AbstractOutHandler() {
                        @Override
                        public void onPull() {
                            push(out, session);
                        }

                        @Override
                        public void onDownstreamFinish(Throwable cause) {
                            // onUpstreamFinish イベントを起こすため completeStage() を無効化
                        }
                    });

                    setHandler(in, new AbstractInHandler() {

                        @Override
                        public void onPush() {
                            final IN data = grab(in);
                            // Result.Error はこのフローよりも後続のフローでも処理するため、Streamがfailしないようになっている。
                            // データがエラーを示す場合には、ここで明示的にロールバックを実施する。
                            // 一度でもエラーが発生したらそれ以降の入力を受け付けないようにするため、cancel を実行して入力の受付を停止する。
                            if (data instanceof Result.Error) {
                                try {
                                    session.rollback();
                                } catch (Throwable ex) {
                                    // Result.Error によって ControllableParallelExecutionHandler で例外をスローするため、
                                    // ここではロールバックのエラーはログに記録するのみである。
                                    logger.logError("Could not rollback the session.", ex);
                                }
                                cancel(in);
                                return;
                            }
                            processedCounts += 1;
                            if (commitInterval != 0 && processedCounts % commitInterval == 0) {
                                try {
                                    session.commit();
                                } catch (Throwable ex) {
                                    connectionClosing.completeExceptionally(ex);
                                    failStage(ex);
                                }
                            }
                            pull(in);
                        }

                        @Override
                        public void onUpstreamFinish() {
                            try {
                                session.commit();
                            } catch (Throwable ex) {
                                connectionClosing.completeExceptionally(ex);
                                return;
                            }
                            completeStage();
                        }

                        @Override
                        public void onUpstreamFailure(Throwable ex) {
                            session.rollback();
                            failStage(ex);
                        }
                    });
                }

                @Override
                public void postStop() {
                    if (session != null) {
                        try {
                            session.close();
                        } catch (Throwable ex) {
                            connectionClosing.completeExceptionally(ex);
                            return;
                        }
                    }
                    connectionClosing.complete(Done.getInstance());
                }
            };

            return Tuple2.apply(logic, connectionClosing);
        }
    }

}
