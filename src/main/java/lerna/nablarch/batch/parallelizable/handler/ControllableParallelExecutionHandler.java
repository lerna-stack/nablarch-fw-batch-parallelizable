package lerna.nablarch.batch.parallelizable.handler;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import akka.NotUsed;
import akka.stream.javadsl.*;
import lerna.nablarch.batch.parallelizable.db.TransactionalSession;
import lerna.nablarch.batch.parallelizable.source.DataReaderSource;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.Done;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.japi.pf.PFBuilder;
import akka.stream.KillSwitches;
import akka.stream.SharedKillSwitch;
import lerna.nablarch.batch.parallelizable.flow.TransactionalSessionFlow;
import nablarch.common.handler.threadcontext.ExecutionIdAttribute;
import nablarch.common.handler.threadcontext.ThreadContextAttribute;
import nablarch.core.ThreadContext;
import nablarch.core.db.connection.ConnectionFactory;
import nablarch.core.log.Logger;
import nablarch.core.log.LoggerManager;
import nablarch.core.log.app.CommitLogger;
import nablarch.core.transaction.TransactionContext;
import nablarch.core.transaction.TransactionFactory;
import nablarch.core.util.StringUtil;
import nablarch.fw.ExecutionContext;
import nablarch.fw.Handler;
import nablarch.fw.Result;
import nablarch.fw.Result.MultiStatus;
import nablarch.fw.TransactionEventCallback;
import nablarch.fw.action.BatchAction;
import nablarch.fw.handler.ParallelExecutionHandler;
import nablarch.fw.handler.ExecutionHandlerCallback;
import nablarch.fw.results.InternalError;
import scala.PartialFunction;
import scala.compat.java8.FutureConverters;

/**
 * 逐次処理する単位が指定できる並行実行ハンドラ
 * <p>
 * 並行処理を最適化するため、下記に示した複数のハンドラの責務をこのハンドラに集約している。
 * <ul>
 * <li>{@link nablarch.fw.handler.MultiThreadExecutionHandler}</li>
 * <li>{@link nablarch.fw.handler.DataReadHandler}</li>
 * <li>{@link nablarch.common.handler.TransactionManagementHandler}</li>
 * </ul>
 *
 * <p>
 * このハンドラを利用する場合は、{@link ControllableParallelExecutor} インターフェイスを実装したハンドラをこのハンドラの下位に置く必要がある。
 * 通常は、{@link BatchAction} を実装したクラスに {@link ControllableParallelExecutor} インターフェイスを実装する。
 *
 * @since 2018/04/09
 */
public class ControllableParallelExecutionHandler extends TransactionEventCallback.Provider<Object> implements ParallelExecutionHandler<Object, MultiStatus, ControllableParallelExecutionHandler> {

    /** ユーティリティ */
    private final Support<Object, MultiStatus> support = new Support<>();

    /** ロガー */
    private final Logger logger = LoggerManager.get(this.getClass());

    /** @return 設定 */
    private final Config getConfig() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("akka.actor.default-dispatcher.thread-pool-executor.fixed-pool-size", threadPoolSize);

        Config rootConfig = ConfigFactory.load();
        return ConfigFactory.parseMap(configMap)
                .withFallback(rootConfig.getConfig("lerna.nablarch.batch.parallelizable"))
                .withFallback(rootConfig);
    }

    /**
     * 推奨値： parallelism + CPUコア数 + 2（INPUTデータ取得用 1 + 予備 1）
     *
     * @param threadPoolSize スレッドプールサイズ
     */
    public void setThreadPoolSize(int threadPoolSize) {
        if (threadPoolSize <= 0) {
            throw new IllegalArgumentException("threadPoolSize(" + threadPoolSize + ") must be greater than 0.");
        }
        this.threadPoolSize = threadPoolSize;
    }

    /**
     * スレッドプールサイズを取得する。
     * @return スレッドプールサイズ
     */
    public int getThreadPoolSize() {
        return this.threadPoolSize;
    }

    /** スレッドプールサイズ */
    private int threadPoolSize = 32;

    /**
     *
     * @param commitLogger コミットロガー
     */
    public void setCommitLogger(CommitLogger commitLogger) {
        this.commitLogger = Optional.of(commitLogger);
    }

    /** コミットロガー */
    private Optional<CommitLogger> commitLogger = Optional.empty();

    /**
     * 並列実行スレッド数を設定する。
     * <p>
     * デフォルト値は 1 である。
     *
     * @param parallelism 並列実行スレッド数
     */
    public void setParallelism(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism(" + parallelism + ") must be greater than or equal to 1.");
        }
        this.parallelism = parallelism;
    }

    /**
     * 並列実行スレッド数を取得する
     * @return 並列実行スレッド数
     */
    public int getParallelism() {
        return this.parallelism;
    }

    /** 並列実行スレッド数 */
    private int parallelism = 1;

    /**
     * 逐次 並列実行される下位ハンドラの処理時間に差がある場合、
     * <p>
     * デフォルト値は 2 である。
     *
     * @param bufferSize バッファサイズ
     */
    public void setHandleDataBufferSize(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("handleDataBufferSize(" + bufferSize + ") must be greater than 0.");
        }
        this.handleDataBufferSize = bufferSize;
    }

    /**
     * ハンドラが使用するバッファサイズを取得する。
     * @return バッファサイズ
     */
    public int getHandleDataBufferSize() {
        return this.handleDataBufferSize;
    }

    /** バッファサイズ */
    private int handleDataBufferSize = 2;

    /**
     * コミット間隔を設定する。
     * <p>
     * デフォルト値は 1 である。
     * 0 を指定した場合は parallelism で分割される処理単位ごとに、処理が完了したタイミングでまとめてコミットされる。
     * （最大で parallelism と同じ数のコミットが行われる）
     *
     * @param commitInterval コミット間隔
     */
    public void setCommitInterval(int commitInterval) {
        if (commitInterval < 0) {
            throw new IllegalArgumentException("commitInterval(" + commitInterval + ") must be greater than or equal to 0.");
        }
        this.commitInterval = commitInterval;
    }

    /**
     * コミット間隔を取得する。
     * @return コミット間隔
     */
    public int getCommitInterval() {
        return this.commitInterval;
    }

    /** コミット間隔 */
    private int commitInterval = 1;

    /**
     * アクターシステム停止のタイムアウト秒数を設定する。
     * <p>
     * デフォルト値は 180秒 である。
     *
     * @param actorSystemTerminationTimeoutSec アクターシステム停止のタイムアウト秒数
     */
    public void setActorSystemTerminationTimeoutSec(long actorSystemTerminationTimeoutSec) {
        if (actorSystemTerminationTimeoutSec <= 0) {
            throw new IllegalArgumentException("actorSystemTerminationTimeoutSec(" +
                    actorSystemTerminationTimeoutSec + ") must be greater than 0.");
        }
        this.actorSystemTerminationTimeoutSec = actorSystemTerminationTimeoutSec;
    }

    /**
     * アクターシステム停止のタイムアウト秒数を取得する。
     * @return アクターシステム停止のタイムアウト秒数
     */
    public long getActorSystemTerminationTimeoutSec() {
        return this.actorSystemTerminationTimeoutSec;
    }

    /** アクターシステム停止のタイムアウト秒数 (デフォルト: 180秒) */
    private long actorSystemTerminationTimeoutSec = 180;

    /**
     * 実行時IDを初期化する際に使用する{@link ThreadContextAttribute}を設定する。
     *
     * @param attribute 実行時IDを初期化する
     * @see ThreadContextAttribute
     */
    public void setExecutionIdAttribute(ExecutionIdAttribute attribute) {
        if (attribute == null) {
            throw new IllegalArgumentException("executionIdAttribute must be not null.");
        }
        this.executionIdAttribute = attribute;
    }

    // TODO ExecutionContextAttribute の getter/setter で API を統一したい

    /**
     * 実行時IDを初期化する際に使用する{@link ThreadContextAttribute}を取得する。
     * @return 実行時IDを初期化する際に使用する {@link ThreadContextAttribute}
     */
    public ThreadContextAttribute<Object> getExecutionContextAttribute() {
        return this.executionIdAttribute;
    }

    /** 実行時ID初期化コンポーネント */
    // TODO リネーム executionIdAttribute => executionContextAttribute
    private ThreadContextAttribute<Object> executionIdAttribute = new ExecutionIdAttribute();

    /**
     * データベース接続オブジェクトを生成するためのファクトリを設定する。
     *
     * @param connectionFactory データベース接続オブジェクトを生成するためのファクトリ
     */
    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /** データベース接続オブジェクトを取得するためのファクトリ */
    private ConnectionFactory connectionFactory;

    /**
     * データベース接続のスレッドコンテキスト上の登録名を設定する。
     *
     * <pre>
     * デフォルトでは既定のトランザクション名
     * ({@link TransactionContext#DEFAULT_TRANSACTION_CONTEXT_KEY})を使用する。
     * </pre>
     *
     * @param connectionName データベース接続のスレッドコンテキスト上の登録名
     */
    public void setConnectionName(String connectionName) {
        if (StringUtil.isNullOrEmpty(connectionName)) {
            throw new IllegalArgumentException("connectionName(" + connectionName + ") must be not null and non empty.");
        }
        this.connectionName = connectionName;
    }

    /**
     * データベース接続のスレッドコンテキスト上の登録名を取得する。
     * @return データベース接続のスレッドコンテキスト上の登録名
     */
    public String getConnectionName() {
        return this.connectionName;
    }

    /** このハンドラが生成するコネクションの登録名 */
    private String connectionName = TransactionContext.DEFAULT_TRANSACTION_CONTEXT_KEY;

    /**
     * トランザクションオブジェクトを取得するためのファクトリを設定する。
     *
     * @param transactionFactory トランザクションオブジェクトを取得するためのファクトリ
     */
    public void setTransactionFactory(TransactionFactory transactionFactory) {
        this.transactionFactory = transactionFactory;
    }

    /** トランザクションオブジェクトを取得するためのファクトリ */
    private TransactionFactory transactionFactory;

    /**
     * このハンドラが管理するトランザクションの、スレッドコンテキスト上での登録名を設定する。
     *
     * <pre>
     * デフォルトでは既定のトランザクション名
     * ({@link TransactionContext#DEFAULT_TRANSACTION_CONTEXT_KEY})を使用する。
     *
     * </pre>
     *
     * @param transactionName データベース接続のスレッドコンテキスト上の登録名
     */
    public void setTransactionName(String transactionName) {
        if (StringUtil.isNullOrEmpty(transactionName)) {
            throw new IllegalArgumentException("transactionName(" + transactionName + ") must be not null and non empty.");
        }
        this.transactionName = transactionName;
    }

    /**
     * このハンドラが管理するトランザクションの、スレッドコンテキスト上での登録名を取得する。
     * @return データベース接続のスレッドコンテキスト上の登録名
     */
    public String getTransactionName() {
        return this.transactionName;
    }

    /** トランザクションが使用するコネクションの登録名 */
    private String transactionName = TransactionContext.DEFAULT_TRANSACTION_CONTEXT_KEY;

    @Override
    public MultiStatus handle(Object data, ExecutionContext context) {

        logger.logInfo("parallelism: " + parallelism);
        logger.logInfo("commit interval: " + commitInterval);

        // スレッドコンテキストに並行実行スレッド数を格納する
        ThreadContext.setConcurrentNumber(parallelism);

        // 後続ハンドラのうち、コールバックメソッドを実装したもの。
        @SuppressWarnings("rawtypes")
        final List<ExecutionHandlerCallback> listeners = support.prepareListeners(data, context);

        // 初期処理用コールバックを呼び出す。
        support.callPreExecution(listeners, data, context);

        // 後続処理で使用するデータリーダを準備する。
        support.prepareDataReader(data, context);

        commitLogger.ifPresent(logger -> logger.initialize());

        @SuppressWarnings("unchecked")
        final ControllableParallelExecutor<Object> executor = context.findHandler(data, ControllableParallelExecutor.class,
                ExecutionHandlerCallback.class);

        if (executor == null) {
            @SuppressWarnings("rawtypes")
            // 通常はハンドラキューの最後のハンドラが ControllableParallelExecutor を実装する必要があるため、
            // その旨を提案する
            final List<Handler> candidateHandlers = context.selectHandlers(data, Handler.class, ControllableParallelExecutor.class);
            final Handler<?, ?> lastHandler = candidateHandlers.get(candidateHandlers.size() - 1);
            throw new IllegalStateException(
                    "ControllableParallelExecutor Not Found. "
                            + lastHandler.getClass().getName()
                            + " may have to implement "
                            + ControllableParallelExecutor.class.getName() + ".");
        }

        @SuppressWarnings("rawtypes")
        final List<TransactionEventCallback> transactionEventListeners = prepareListeners(data, context);

        /**
         * 注意: {@link ActorSystem} は handle メソッド内で初期化しないと、{@link ThreadContext} が子スレッドに引き継がれない
         */
        final ActorSystem actorSystem = ActorSystem.create(getClass().getSimpleName(), getConfig());

        /** エラー時に途中経過を報告するため */
        final MultiStatus results = new MultiStatus();

        final Sink<MultiStatus, CompletionStage<MultiStatus>> multiResultSink =
                Sink.fold(results, (acc, result) -> acc.addResults(result.getResults().toArray(new Result[0])));

        final Supplier<Sink<Result, CompletionStage<MultiStatus>>> resultSink =
                () -> // MultiStatus はスレッドセーフではないため、fold の第一引数のインスタンスが複数のスレッドから書き込まれるのを防ぐ必要がある
                    Flow.of(Result.class)
                            .toMat(Sink.fold(new MultiStatus(), MultiStatus::addResults), Keep.right());

        final Sink<Result, CompletionStage<Done>> commitLoggingSink =
                Sink.foreach(result -> {
                    if (result.isSuccess()) {
                        commitLogger.ifPresent(logger -> logger.increment(1));
                    }
                });

        final Flow<Result, TransactionalSession, CompletionStage<Done>> transactionFlow =
                TransactionalSessionFlow.create(connectionFactory, connectionName, transactionFactory, transactionName, commitInterval);

        final PartialFunction<Throwable, Result> recoverPF =
                new PFBuilder<Throwable, Result>()
                    .match(Result.Error.class, e -> e)
                    .matchAny(e ->
                            /* Sourceなど handleFlow 以外で例外が起きた場合 */
                            new InternalError(e)
                    ).build();

        final SharedKillSwitch killSwitch = KillSwitches.shared("handle-kill-switch");

        final Source<Object, NotUsed> partitionedSource =
                DataReaderSource.create(context).toMat(
                        PartitionHub.ofStateful(Object.class, () -> (info, d) -> {
                            // Consumer identifier として 負の値を返すと要素はドロップされる
                            // See also https://doc.akka.io/api/akka/2.6.9/akka/stream/javadsl/PartitionHub$.html
                            final int CONSUMER_IDENTIFIER_TO_DROP_THE_ELEMENT = -1;
                            try {
                                final ControllableParallelExecutor.SequentialExecutionIdExtractor executionIdExtractor =
                                        executor.sequentialExecutionId(d);
                                if (executionIdExtractor == null) {
                                    // SequentialExecutionIdExtractor が null の場合には、データを振り分けることができない。
                                    // 自動的に復旧することは難しいため、Stream 全体を中止し、処理を継続できないことをユーザに通知する。
                                    final String message =
                                            String.format(
                                                    "%s: ControllableParallelExecutor.sequentialExecutionId(element) should not return null.",
                                                    executor.toString()
                                            );
                                    killSwitch.abort(new IllegalStateException(message));
                                    return CONSUMER_IDENTIFIER_TO_DROP_THE_ELEMENT;
                                }
                                final int executionId = Math.abs(executionIdExtractor.getAsInt() % parallelism);
                                // 下流でエラーが発生して killSwitch が発動した場合は size が減少して executionId と consumer の ID がずれる。
                                // そのような状況になると同じ executionId の要素が同時実行されてしまうので、size が変ったときは全ての要素を無視する。
                                return info.size() == parallelism ? info.consumerIdByIdx(executionId) : CONSUMER_IDENTIFIER_TO_DROP_THE_ELEMENT;
                            } catch (Throwable cause) {
                                // 次のような理由でデータを振り分ける処理がうまくいかない場合がある。
                                //  * SequentialExecutionIdExtractor を取得する際に例外が発生した
                                //  * SequentialExecutionIdExtractor.getAsInt で例外が発生した
                                // 自動的に復旧することは難しいため、Stream 全体を中止し、処理を継続できないことをユーザに通知する。
                                killSwitch.abort(cause);
                                return CONSUMER_IDENTIFIER_TO_DROP_THE_ELEMENT;
                            }
                        }, parallelism, handleDataBufferSize),
                        Keep.right()
                ).run(actorSystem);

        final Supplier<Flow<TransactionalSession, Result, CompletionStage<MultiStatus>>> handleFlow =
                () -> Flow.of(TransactionalSession.class)
                    .zip(partitionedSource)
                    .via(killSwitch.flow())
                    .map(d -> onHandle(d.second(), context, d.first(), transactionEventListeners))
                    .recover(recoverPF).map(e -> {
                        // 結果がエラーの場合は仕掛中のものを処理してから shutdown する
                        if (e instanceof Result.Error) {
                            killSwitch.shutdown();
                        }
                        return e;
                    })
                    .map(this::reportResult)
                    .alsoTo(commitLoggingSink)
                    .alsoToMat(resultSink.get(), Keep.right());

        CompletionStage<MultiStatus> resultStage =
                Source.range(1, parallelism)
                    .mapAsyncUnordered(parallelism, i -> {
                        Pair<CompletionStage<MultiStatus>, CompletionStage<Done>> handled =
                                handleFlow.get().joinMat(transactionFlow, Keep.both()).run(actorSystem);
                        CompletionStage<MultiStatus> handledResult = handled.first();
                        CompletionStage<Done> transactionFinished = handled.second();
                        return transactionFinished.thenCompose(done -> handledResult);
                    })
                    .toMat(multiResultSink, Keep.right())
                    .run(actorSystem);

        // 処理結果
        final CompletableFuture<MultiStatus> status = resultStage.toCompletableFuture();

        // 処理が成功したかどうか（true: 成功、false: 何らかの例外・エラーが起きた）
        boolean executionSucceeded = true;
        try {
            // グラフの処理が全て終了するまでブロック
            final MultiStatus statuses = status.get();
            if (statuses.isSuccess()) {
                // 全て正常終了している場合
                return statuses;
            } else {
                // 異常終了している場合
                for (Result result : statuses.getResults()) {
                    // ハンドリング中に捕捉した最初のエラーを上位ハンドラに移譲する
                    // 注意: 複数の処理が同時に行われるため、複数のエラーが存在する可能性があるが
                    // このハンドラでは最初のエラーのみを上位ハンドラに委譲するという仕様にする
                    if (result instanceof Result.Error) {
                        // handle 実行中に起きた 例外
                        final Throwable error = ((Result.Error) result).getCause();
                        if (error instanceof RuntimeException) {
                            final RuntimeException cause = (RuntimeException) error;
                            // 異常終了時のコールバックを呼び出す
                            support.callErrorInExecution(listeners, cause, context);
                            throw cause;
                        } else if (error instanceof Error) {
                            final Error cause = (Error) error;
                            // 異常終了時のコールバックを呼び出す
                            support.callErrorInExecution(listeners, cause, context);
                            throw cause;
                        }
                    }
                }
                // スローされなかった Result.Error があった場合
                return statuses;
            }
        } catch (InterruptedException e) {
            executionSucceeded = false;
            // カレントスレッドに対する割り込み要求が発生した場合は、処理を停止させる
            // (このハンドラはメインスレッド上で使用する想定なので、ここにはこないはず)
            support.callErrorInExecution(listeners, e, context);
            final RuntimeException exception = new RuntimeException("parallel execution was canceled.", e);
            killSwitch.abort(exception);
            throw exception;
        } catch (ExecutionException e) {
            executionSucceeded = false;
            final Throwable cause = e.getCause();
            // ActorSystem で何らかの例外が起きて中断した場合は、上位ハンドラにエラーハンドリングを移譲
            if (cause instanceof RuntimeException) {
                support.callErrorInExecution(listeners, cause, context);
                results.addResults(new InternalError(cause));
                throw (RuntimeException) cause;
            } else if (cause instanceof Error) {
                support.callErrorInExecution(listeners, cause, context);
                results.addResults(new InternalError(cause));
                throw (Error) cause;
            } else {
                // ここにはこないはず
                throw new RuntimeException("parallel execution ended abnormally.", e.getCause());
            }
        } finally {
            commitLogger.ifPresent(logger -> logger.terminate());
            try {
                // 終端処理用コールバックを呼び出す
                support.callPostExecution(listeners, results, context);
                try {
                    // ActorSystem を終了させる
                    FutureConverters.toJava(actorSystem.terminate())
                            .toCompletableFuture()
                            .get(actorSystemTerminationTimeoutSec, TimeUnit.SECONDS);
                    // バッチの処理自体は終了しているため、例外が起きたとしても Warn ログを出力するだけにする
                } catch (InterruptedException e) {
                    logger.logWarn("InterruptedException occurred during terminating ActorSystem(" + actorSystem.name() + ")", e);
                } catch (ExecutionException e) {
                    logger.logWarn("An Exception occurred during terminating ActorSystem(" + actorSystem.name() + ")", e.getCause());
                } catch (TimeoutException e) {
                    logger.logWarn("Could not terminate ActorSystem(" + actorSystem.name() + ") in time. " + "actorSystemTerminationTimeoutSec: "
                            + actorSystemTerminationTimeoutSec + " sec.", e);
                }
                // 以下、本処理が正常終了している場合のみ例外を送出
            } catch (Exception e) {
                if (executionSucceeded) {
                    throw e;
                }
            } catch (Error e) {
                if (executionSucceeded) {
                    throw e;
                }
            }
        }
    }

    /**
     * 入力要素とトランザクションの 1 ペアごとに呼び出されるメソッド。下位ハンドラの呼び出しを行う
     *
     * @param data 下位ハンドラで処理するデータ
     * @param context 下位ハンドラを呼び出すのに利用する {@link ExecutionContext}
     * @param session 下位ハンドラの処理で利用する {@link TransactionalSession}
     * @param transactionEventListeners トランザクションで発生したイベントを受け取る {@link TransactionEventCallback}
     * @return 下位ハンドラが返した {@link Result}
     */
    private Result onHandle(Object data, ExecutionContext context, TransactionalSession session,
                            @SuppressWarnings("rawtypes") List<TransactionEventCallback> transactionEventListeners) {
        final ExecutionContext clonedContext = new ExecutionContext(context);

        // 入力データごとに実行時IDを発番する。
        ThreadContext.setExecutionId((String) this.executionIdAttribute.getValue(data, context));

        // トランザクションデータをリクエストスコープに設定
        context.setRequestScopedVar(TransactionEventCallback.REQUEST_DATA_REQUEST_SCOPE_KEY, data);
        try {
            return session.doInTransactionContext(() -> {
                final Result result = clonedContext.handleNext(data);
                callNormalEndHandlers(transactionEventListeners, data, clonedContext);
                return (result == null) ? NullResult.getInstance() : result;
            });
        } catch (Result.Error e) {
            onHandleError(data, context, clonedContext, e, transactionEventListeners);
            throw new HandlingError(e);
        } catch (RuntimeException e) {
            onHandleError(data, context, clonedContext, e, transactionEventListeners);
            throw new HandlingError(e);
        } catch (Error e) {
            onHandleError(data, context, clonedContext, e, transactionEventListeners);
            throw new HandlingError(e);
        } finally {
            // トランザクションデータをリクエストスコープから破棄
            context.setRequestScopedVar(TransactionEventCallback.REQUEST_DATA_REQUEST_SCOPE_KEY, null);
        }
    }

    /**
     * {@link #onHandle(Object, ExecutionContext, TransactionalSession, List)} でエラーが発生した際に呼び出されるメソッド
     *
     * @param data エラーが発生した際に処理していたデータ
     * @param context このハンドラを処理するのに利用された {@link ExecutionContext}
     * @param childContext 下位ハンドラを呼び出すのに利用していた {@link ExecutionContext}
     * @param cause 下位ハンドラで発生したエラー
     * @param eventListeners トランザクションで起きた例外のイベントを受け取る {@link TransactionEventCallback}
     */
    private void onHandleError(Object data, ExecutionContext context, ExecutionContext childContext, Throwable cause,
                               @SuppressWarnings("rawtypes") List<TransactionEventCallback> eventListeners) {
        writeWarnLog(data, cause);
        context.putDataOnException(cause, data);
        // エラー時のコールバック処理を別トランザクションで再実行
        final TransactionalSession s = TransactionalSession.create(connectionFactory, connectionName, transactionFactory, transactionName);
        s.begin();
        s.runInTransaction(() -> callAbnormalEndHandlers(eventListeners, cause, data, childContext));
        s.close();
    }

    /**
     * 処理結果をログに出力する
     *
     * @param result 処理結果
     * @return 引数の処理結果
     */
    private Result reportResult(Result result) {
        if (logger.isDebugEnabled()) {
            if (result.isSuccess()) {
                final StringBuilder report = new StringBuilder(Logger.LS).append("Thread Status: normal end." + Logger.LS)
                        .append("Thread Result:" + String.valueOf(result) + Logger.LS);
                logger.logDebug(report.toString());
            } else {
                final StringBuilder report = new StringBuilder(Logger.LS).append("Thread Status: failure." + Logger.LS)
                        .append("Thread Result:" + String.valueOf(result) + Logger.LS);
                logger.logDebug(report.toString());
            }
        }
        if (result instanceof Result.Error && !(result instanceof HandlingError)) {
            // HandlingError の例外は既に onHandleError で warn ログを出力しているはずなのでログ出力しない
            writeWarnLog(((Result.Error) result).getCause());
        }
        return result;
    }

    /**
     * ワーニングログを出力する。
     *
     * @param requestData リクエストデータ
     * @param e 例外情報
     */
    protected void writeWarnLog(Object requestData, Throwable e) {
        final String dataStr = (requestData == null ? "null" : requestData.toString());
        logger.logWarn("application was abnormal end." + Logger.LS + '\t' + "input data = " + dataStr, e);
    }

    /**
     * ワーニングログを出力する。
     *
     * @param e 例外情報
     */
    protected void writeWarnLog(Throwable e) {
        logger.logWarn("application was abnormal end.", e);
    }

    /**
     * 下位ハンドラの{@link Handler#handle(Object, ExecutionContext)}を実行中に例外が起きたことを表す{@link Result}
     *
     * @since 2018/04/09
     */
    private static class HandlingError extends Result.Error {

        private static final long serialVersionUID = 1L;

        /**
         * コンストラクタ
         *
         * @param e 下位ハンドラの{@link Handler#handle(Object, ExecutionContext)}を実行中に起きた例外
         */
        private HandlingError(Throwable e) {
            super(e);
        }
    }

}
