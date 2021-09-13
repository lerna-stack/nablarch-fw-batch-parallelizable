package lerna.nablarch.batch.parallelizable.handler;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import lerna.nablarch.batch.parallelizable.testkit.TestTransactionBase;
import lerna.nablarch.batch.parallelizable.testkit.TestTransactionManagerConnectionBase;
import nablarch.common.handler.threadcontext.ExecutionIdAttribute;
import nablarch.core.log.app.BasicCommitLogger;
import nablarch.fw.*;
import nablarch.fw.handler.ExecutionHandlerCallback;
import nablarch.test.core.log.LogVerifier;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

import nablarch.core.db.DbAccessException;
import nablarch.core.db.connection.ConnectionFactory;
import nablarch.core.db.connection.TransactionManagerConnection;
import nablarch.core.db.connection.exception.DbConnectionException;
import nablarch.core.transaction.Transaction;
import nablarch.core.transaction.TransactionFactory;

public class ControllableParallelExecutionHandlerTest {

    @Test
    public void 雛形() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory(new ConnectionFactory() {
            @Override
            public TransactionManagerConnection getConnection(String s) {
                return new TestTransactionManagerConnectionBase();
            }
        });
        handler.setTransactionFactory(new TransactionFactory() {
            @Override
            public Transaction getTransaction(String s) {
                return new TestTransactionBase();
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                assertThat(1, is(1));
                return super.handle(s, executionContext);
            }
        });
        context.handleNext("input");

    }

    @Test
    public void 正常に全件処理される() {
        int numberOfData = 100;
        AtomicInteger handledCount = new AtomicInteger(0);

        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(numberOfData);
            }

            Random random = new Random();
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                handledCount.getAndIncrement();
                try {
                    // いずれも sequentialExecutionId(s).getAsInt() % parallelism が 1 になるもの → handleDataBufferSize(2) を超えるため待ち時間が発生する
                    // 一部IDが先に終了するケース
                    if (s.equals("77") || s.equals("86") || s.equals("95")) {
                        println("s: " + s + ", sequentialExecutionId(s).getAsInt(): " + (sequentialExecutionId(s).getAsInt() % parallelism));
                        Thread.sleep(3000);
                    }
                    Thread.sleep(random.nextInt(100));
                } catch (Throwable e) {}
                return super.handle(s, executionContext);
            }
        });

        context.handleNext("input");
        assertThat("処理件数がログとDBで一致する", handledCount.get(), is(numberOfData));
    }

    @Test
    public void ハンドラでErrorが発生したらリスローされる() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                throw new StackOverflowError();
            }
        });
        assertThrows(StackOverflowError.class, () -> context.handleNext("input"));
    }

    @Test
    public void interruptでジョブ実行を中断できる() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        AtomicBoolean running = new AtomicBoolean(false);
        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                try {
                    // handleNext の完了が遅くなるようにあえて処理を遅延させる
                    running.set(true);
                    Thread.sleep(100);
                } catch (InterruptedException e) { }
                return new Result.Success();
            }
        });
        final Thread current = Thread.currentThread();

        // context.handleNext("input") の処理完了待ち中に割り込む
        new Thread(() -> {
            try {
                // wait until the Executor starts handle data
                while (!running.get()) { Thread.sleep(10); }
                current.interrupt();
            } catch (InterruptedException e) { }
        }).start();

        final RuntimeException exception =
            assertThrows(RuntimeException.class, () -> context.handleNext("input"));
        assertThat(exception.getMessage(), is("parallel execution was canceled."));
    }

    @Test(expected = Result.NotFound.class)
    public void ハンドラでResultErrorが発生したらリスローされる() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                throw new Result.NotFound();
            }
        });
        context.handleNext("input");
    }

    @Test
    public void ハンドラが例外やError以外のThrowableを返した場合は実行失敗にはなるがスローはされない() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            private final AtomicInteger count = new AtomicInteger(0);
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                if (count.incrementAndGet() == 10) {
                    return new Result.Error(new NoExceptionThrowable()) {
                    };
                } else {
                    return new Result.Success();
                }
            }
        });
        final Result result = context.handleNext("input");
        assertThat(result.isSuccess(), is(false));
    }

    @Test
    public void ハンドラのSequentialExecutionIdExtractorがnullであれば例外IllegalStateExceptionが親ハンドラにスローされる() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ControllableParallelExecutor<String> executor = new TestControllableParallelExecutorBase() {
            @Override
            public SequentialExecutionIdExtractor sequentialExecutionId(String element) {
                return null;
            }
        };

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(executor);

        // 親ハンドラは handleNext から例外を catch できる
        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> context.handleNext("input"));
        final String executorInfo =
                String.format("%s@%s", executor.getClass().getName(), Integer.toHexString(executor.hashCode()));
        final String expectMessage =
                String.format("%s: ControllableParallelExecutor.sequentialExecutionId(element) should not return null.", executorInfo);
        assertThat(exception.getMessage(), is(expectMessage));

    }

    @Test
    public void ハンドラのSequentialExecutionIdExtractor取得で例外が発生したら親ハンドラにその例外がリスローされる() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public SequentialExecutionIdExtractor sequentialExecutionId(String element) {
                throw new ArithmeticException(String.format("Could not create SequentialExecutionIdExtractor from the element(%s).", element));
            }
        });

        // 親ハンドラは handleNext から例外を catch できる
        final ArithmeticException exception =
                assertThrows(ArithmeticException.class, () -> context.handleNext("input"));
        assertThat(exception.getMessage(), is("Could not create SequentialExecutionIdExtractor from the element(1)."));

    }

    @Test
    public void ハンドラのSequentialExecutionIdExtractorで例外が発生したら親ハンドラにその例外がリスローされる() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public SequentialExecutionIdExtractor sequentialExecutionId(String element) {
                return new SequentialExecutionIdExtractor() {
                    @Override
                    public int getAsInt() {
                        throw new ArithmeticException(String.format("Could not calculate ID from the element(%s).", element));
                    }
                };
            }
        });

        // 親ハンドラは handleNext から例外を catch できる
        final ArithmeticException exception =
                assertThrows(ArithmeticException.class, () -> context.handleNext("input"));
        assertThat(exception.getMessage(), is("Could not calculate ID from the element(1)."));

    }

    @Test
    public void createReaderがnullを返す場合はデータ0件として処理する() {
        final int parallelism = 5;
        final ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory(connectionName -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(connectionName -> new TestTransactionBase());

        final AtomicInteger handledCount = new AtomicInteger(0);
        final ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return null;
            }
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                handledCount.incrementAndGet();
                return new Result.Success();
            }
        });

        final Result result = context.handleNext("input");
        assertThat(result.isSuccess(), is(true));
        assertThat(handledCount.get(), is(0));
    }

    @Test
    public void executionId毎にcommitIntervalを迎えたときと終了時に1度コミットされる() {
        int numberOfData = 100;
        int parallelism = 10;
        int commitInterval = 3;
        // 100 要素を 10 個の ExecutionId で処並列理するので、1スレッドあたり 10 要素を処理する。
        // ExecutionId ごとのコミット数は 10要素 / 3 interval = 3 コミット、ExecutionId は 10 あるので、合計 30 コミット
        // 加えて、各スレッドで終了時に 1 回コミット × 10 = 10 コミット
        // 合計 40 回コミットされるはず
        // （注意：CounterDataReader が String を出す仕様になっていて、上記パラメータの組み合わせによっては均等に要素が分散されないことがある）
        int expectedCommitCount = 40;

        AtomicInteger committedCount = new AtomicInteger(0);

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setCommitInterval(commitInterval);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void commit() {
                committedCount.incrementAndGet();
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(numberOfData);
            }
        });

        context.handleNext("input");

        assertThat(committedCount.get(), is(expectedCommitCount));
    }

    @Test
    public void トランザクション中のハンドラで例外が発生したらロールバックされる() {
        final int numberOfData = 10;
        final int parallelism = 1;
        final int commitInterval = 10;
        final int countToThrowException = 5;
        // onHandleError で 1度だけ呼ばれるため、コミット数は1になる
        final int expectedCommitCount = 1;
        final int expectedRollbackCount = 1;

        AtomicInteger handleCount = new AtomicInteger(0);
        AtomicInteger commitCount = new AtomicInteger(0);
        AtomicInteger rollbackCount = new AtomicInteger(0);

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setCommitInterval(commitInterval);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void commit() {
                commitCount.incrementAndGet();
            }
            @Override
            public void rollback() {
                rollbackCount.incrementAndGet();
            }
        });

        class DummyRuntimeException extends RuntimeException {
            public DummyRuntimeException(String message) {
                super(message);
            }
        }

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(numberOfData);
            }

            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                int count = handleCount.incrementAndGet();
                if (count == countToThrowException) {
                    throw new DummyRuntimeException("It should invoke rollback");
                }
                return super.handle(s, executionContext);
            }
        });

        assertThrows(DummyRuntimeException.class, () -> {
            context.handleNext("input");
        });

        assertThat(commitCount.get(), is(expectedCommitCount));
        assertThat(rollbackCount.get(), is(expectedRollbackCount));
    }

    @Test
    public void commitIntervalが0のときはExecutionIdごとの終了時に1度ずつだけコミットされる() {
        int numberOfData = 100;
        int parallelism = 10;
        int commitInterval = 0;

        AtomicInteger commited = new AtomicInteger(0);

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setCommitInterval(commitInterval);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void commit() {
                commited.incrementAndGet();
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(numberOfData);
            }
        });

        context.handleNext("input");

        assertThat(commited.get(), is(parallelism)); // executionId ごとに1度しかコミットされないため
    }

    @Test
    public void ハンドラキューにControllableParallelExecutorを実装するハンドラがない場合に例外が発生する() {
        ExecutionContext context = new ExecutionContext();

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(5);
        handler.setConnectionFactory(new ConnectionFactory() {
            @Override
            public TransactionManagerConnection getConnection(String s) {
                return new TestTransactionManagerConnectionBase();
            }
        });
        handler.setTransactionFactory(new TransactionFactory() {
            @Override
            public Transaction getTransaction(String s) {
                return new TestTransactionBase();
            }
        });
        context.addHandler(handler);

        class TestPlainExecutorBase implements Handler<String, Result>, DataReaderFactory<String> {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(100);
            }
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                return new Result.Success();
            }
        }
        context.addHandler(new TestPlainExecutorBase());

        IllegalStateException e =  assertThrows(IllegalStateException.class, () -> {
            context.handleNext("input");
        });

        String givenClassName = TestPlainExecutorBase.class.getName();
        String expectedSimpleClassName = ControllableParallelExecutor.class.getSimpleName();
        String expectedClassName = ControllableParallelExecutor.class.getName();
        String expectedMessage = expectedSimpleClassName + " Not Found. " + givenClassName + " may have to implement " + expectedClassName + ".";
        assertThat(e.getMessage(), is(expectedMessage));
    }

    @Test(expected = DbConnectionException.class)
    public void コネクション取得時に例外が発生したら親ハンドラにリスローされる() {
        AtomicInteger createdConnectionCount = new AtomicInteger(0);

        int parallelism = 10;
        int stopThreshold = 8; // parallelism 分だけコネクションが取得される
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> {
            if (createdConnectionCount.getAndIncrement() == stopThreshold) {
                throw new DbConnectionException("failed to get database connection.", new SQLException());
            }
            return new TestTransactionManagerConnectionBase();
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());

        // 親ハンドラは handleNext から例外を catch できる
        context.handleNext("input");
    }

    @Test
    public void コネクション取得時に例外が発生したら処理が中断する() {
        AtomicInteger connectionCount = new AtomicInteger(0);
        AtomicInteger handledCount = new AtomicInteger(0);

        int parallelism = 10;
        int stopThreshold = 8; // parallelism 分だけコネクションが取得される
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> {
            // countは1ずつ増えるわけではないので 等号 ではなく 不等号 でチェック
            if (connectionCount.incrementAndGet() > stopThreshold) {
                throw new DbConnectionException("failed to get database connection.", new SQLException());
            }
            return new TestTransactionManagerConnectionBase();
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                handledCount.getAndIncrement();
                return super.handle(s, executionContext);
            }
        });

        try {
            context.handleNext("input");
        } catch (DbConnectionException e) {
            // DBのコネクション取得時に発生させた例外は無視してテストが失敗するのを回避
        }

        assertThat("処理が中断するため取得できたコネクション数よりも多い件数は処理されない", handledCount.get(), is(lessThanOrEqualTo(stopThreshold)));
    }

    @Test(expected = DbAccessException.class)
    public void トランザクションcommit時に例外が発生したら親ハンドラにリスローされる() {
        int stopThreshold = 25;
        AtomicInteger committedCount = new AtomicInteger(0);

        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void commit() {
                if (committedCount.incrementAndGet() == stopThreshold) {
                    throw new DbAccessException("failed to commit.", new SQLException());
                }
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());

        // 親ハンドラは handleNext から例外を catch できる
        context.handleNext("input");
    }

    @Test
    public void トランザクションcommit時に例外が発生したら中断する() {
        int maxCount = 100;
        int stopThreshold = 25;
        AtomicInteger committedCount = new AtomicInteger(0);
        AtomicInteger handledCount = new AtomicInteger(0);

        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void commit() {
                int count = committedCount.incrementAndGet();
                if (count == stopThreshold) {
                    throw new DbAccessException("failed to commit.", new SQLException());
                }
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(maxCount);
            }

            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                handledCount.getAndIncrement();
                return super.handle(s, executionContext);
            }
        });

        try {
            context.handleNext("input");
        } catch (DbAccessException e) {
            // commit時に発生させた例外は無視してテストが失敗するのを回避
        }

        assertThat("中断する（＝全件処理されない）", handledCount.get(), is(not(maxCount)));

    }

    @Test
    public void トランザクションrollback時に例外が発生したら中断する() {
        int maxCount = 100;
        int stopThreshold = 25;
        AtomicInteger handledCount = new AtomicInteger(0);

        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void rollback() {
                throw new DbAccessException("failed to rollback.", new SQLException());
            }
        });

        class DummyRuntimeException extends RuntimeException {
            public DummyRuntimeException(String message) {
                super(message);
            }
        }

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(maxCount);
            }

            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                int count = handledCount.incrementAndGet();
                if (count == stopThreshold) {
                    throw new DummyRuntimeException("onUpstreamFailureにするための例外");
                }
                return super.handle(s, executionContext);
            }
        });

        assertThrows(DummyRuntimeException.class, () -> {
            // handle 内から例外がthrowされる場合、テスト自体も失敗になってしまうため catch する
            // onUpstreamFailureにするための例外は無視してテストが失敗するのを回避
            context.handleNext("input");
        });

        assertThat("中断する（＝全件処理されない）", handledCount.get(), is(not(maxCount)));

    }


    @Test
    public void トランザクションrollback時に例外が発生しても総処理件数のログが出る() {
        int maxCount = 100;
        int stopThreshold = 25;
        AtomicInteger handledCount = new AtomicInteger(0);

        int parallelism = 10;

        LogVerifier.setExpectedLogMessages(Arrays.asList(
                expectLogMessage("INFO", "TOTAL COMMIT COUNT = ")
        ));

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setCommitInterval(maxCount);
        handler.setCommitLogger(new BasicCommitLogger());
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase() {

            @Override
            public void commit() {
                throw new DbAccessException("failed to commit.", new SQLException());
            }

            @Override
            public void rollback() {
                throw new DbAccessException("failed to rollback.", new SQLException());
            }
        });

        class DummyRuntimeException extends RuntimeException {
            public DummyRuntimeException(String message) {
                super(message);
            }
        }

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(maxCount);
            }

            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                int count = handledCount.incrementAndGet();
                if (count == stopThreshold) {
                    throw new DummyRuntimeException("onUpstreamFailureにするための例外");
                }
                return super.handle(s, executionContext);
            }
        });

        try {
            // handle 内から例外がthrowされる場合、テスト自体も失敗になってしまうため catch する
            context.handleNext("input");
        } catch (DummyRuntimeException e) {
            // onUpstreamFailureにするための例外は無視してテストが失敗するのを回避
        } catch (DbAccessException e) {
            // エラーの後処理で発生する DbAccessException は無視
        }

        LogVerifier.verify("期待するログが出ていません");
    }

    private Map<String, String> expectLogMessage(String logLevel, String... messages) {
        final Map<String, String> expectedLogMessage = new HashMap<>();
        // nablarch.test.core.log.LogVerifier の仕様に基づいて Map を作成
        expectedLogMessage.put("logLevel", logLevel);
        for (int i = 0; i < messages.length; i++) {
            expectedLogMessage.put("message" + (i + 1), messages[i]);
        }
        return expectedLogMessage;
    }

    @Test(expected = DbAccessException.class)
    public void コネクションterminate時に例外が発生したら親ハンドラにリスローされる() {
        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase() {
            @Override
            public void terminate() {
                throw new DbAccessException("failed to terminate.", new SQLException());
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());

        // 親ハンドラは handleNext から例外を catch できる
        context.handleNext("input");
    }

    @Test
    public void コネクションterminate時にErrorが発生したら親ハンドラにリスローされる() {
        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase() {
            @Override
            public void terminate() {
                throw new StackOverflowError();
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());

        // 親ハンドラは handleNext から例外を catch できる
        assertThrows(StackOverflowError.class, () -> context.handleNext("input"));
    }

    @Test
    public void コネクションterminate時に例外が発生しても処理件数がログと処理数で一致する() {
        int maxCount = 100;
        AtomicInteger handledCount = new AtomicInteger(0);

        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase() {
            @Override
            public void terminate() {
                throw new DbAccessException("failed to terminate.", new SQLException());
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(maxCount);
            }

            Random random = new Random();
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                handledCount.getAndIncrement();
                try {
                    Thread.sleep(random.nextInt(100));
                } catch (Throwable e) {}
                return super.handle(s, executionContext);
            }
        });

        try {
            context.handleNext("input");
        } catch (DbAccessException e) {
            // コネクションterminate時に発生させた例外は無視してテストが失敗するのを回避
        }
        assertThat("処理件数がログとDBで一致する", handledCount.get(), is(maxCount));
    }

    @Test
    public void トランザクションbegin時にsleepしてもコネクション数が上限を超えない() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory(new ConnectionFactory() {
            AtomicInteger connectionCount = new AtomicInteger(0);

            @Override
            public TransactionManagerConnection getConnection(String s) {
                int availableConnectionCount = connectionCount.incrementAndGet();

                println("availableConnectionCount: " +  availableConnectionCount);
                assertThat(availableConnectionCount, is(lessThanOrEqualTo(parallelism)));

                return new TestTransactionManagerConnectionBase() {
                    @Override
                    public void terminate() {
                        connectionCount.getAndDecrement();
                    }
                };
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void begin() {
                try {
                    Thread.sleep(100);
                } catch (Throwable e) {}
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());
        context.handleNext("input");

    }

    @Test
    public void トランザクションcommit時にsleepしてもコネクション数が上限を超えない() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory(new ConnectionFactory() {
            AtomicInteger connectionCount = new AtomicInteger(0);

            @Override
            public TransactionManagerConnection getConnection(String s) {
                int availableConnectionCount = connectionCount.incrementAndGet();

                println("availableConnectionCount: " +  availableConnectionCount);
                assertThat(availableConnectionCount, is(lessThanOrEqualTo(parallelism)));

                return new TestTransactionManagerConnectionBase() {
                    @Override
                    public void terminate() {
                        connectionCount.getAndDecrement();
                    }
                };
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void commit() {
                try {
                    Thread.sleep(100);
                } catch (Throwable e) {}
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());
        context.handleNext("input");

    }

    @Test
    public void トランザクションrollback時にsleepしてもコネクション数が上限を超えない() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory(new ConnectionFactory() {
            AtomicInteger connectionCount = new AtomicInteger(0);

            @Override
            public TransactionManagerConnection getConnection(String s) {
                int availableConnectionCount = connectionCount.incrementAndGet();

                println("availableConnectionCount: " +  availableConnectionCount);
                assertThat(availableConnectionCount, is(lessThanOrEqualTo(parallelism)));

                return new TestTransactionManagerConnectionBase() {
                    @Override
                    public void terminate() {
                        connectionCount.getAndDecrement();
                    }
                };
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase() {
            @Override
            public void rollback() {
                try {
                    Thread.sleep(100);
                } catch (Throwable e) {}
            }
        });

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());
        context.handleNext("input");

    }

    @Test
    public void コネクションterminate時にsleepしてもコネクション数が上限を超えない() {
        int parallelism = 5;

        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory(new ConnectionFactory() {
            AtomicInteger connectionCount = new AtomicInteger(0);

            @Override
            public TransactionManagerConnection getConnection(String s) {
                int availableConnectionCount = connectionCount.incrementAndGet();

                println("availableConnectionCount: " +  availableConnectionCount);
                assertThat(availableConnectionCount, is(lessThanOrEqualTo(parallelism)));

                return new TestTransactionManagerConnectionBase() {
                    @Override
                    public void terminate() {
                        try {
                            Thread.sleep(100);
                        } catch (Throwable e) {}
                        connectionCount.getAndDecrement();
                    }
                };
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase());
        context.handleNext("input");

    }

    @Test
    public void 異常終了しても同一IDが同一streamで処理される() throws InterruptedException {
        int initialCount = 2;
        int maxCount = 100;
        int stopThreshold = 25;
        AtomicInteger handledCount = new AtomicInteger(0);

        CountDownLatch lock = new CountDownLatch(1);
        final boolean[] failed = {false};

        int parallelism = 3;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        class DummyRuntimeException extends RuntimeException {
            DummyRuntimeException(String message) {
                super(message);
            }
        }

        Function<String, Integer> calculateId = (s) -> {
            int hashCode = ControllableParallelExecutor.SequentialExecution.byHashCodes(s).getAsInt();
            int executionId = Math.abs(hashCode % parallelism);
            return executionId;
        };

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            Map<Integer, AtomicBoolean> map = new HashMap<>();
            Random random = new Random();
            @Override
            public Result handle(String s, ExecutionContext context) {
                int id = calculateId.apply(s);
                boolean previousValue = map.get(id).getAndSet(true);
                if (previousValue) {
                    failed[0] = true;
                    throw new RuntimeException("他とidが重複してる");
                }
                try {
                    if (sequentialExecutionId(s).getAsInt() % parallelism == initialCount) {
                        Thread.sleep(random.nextInt(1000));
                    }
                } catch (Throwable ignored) {}
                int count = handledCount.incrementAndGet();
                if (count == stopThreshold) {
                    throw new DummyRuntimeException("onUpstreamFailureにするための例外");
                }
                map.get(id).getAndSet(false);
                return new Result.Success();
            }
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                for (int i = 0; i < parallelism; i++) {
                    map.put(i, new AtomicBoolean(false));
                }
                return new DataReader<String>() {
                    private int count = initialCount;
                    @Override
                    public String read(ExecutionContext executionContext) {
                        count++;
                        return String.valueOf(count);
                    }
                    @Override
                    public boolean hasNext(ExecutionContext executionContext) {
                        return count < maxCount;
                    }
                    @Override
                    public void close(ExecutionContext executionContext) { }
                };
            }
            @Override
            public SequentialExecutionIdExtractor sequentialExecutionId(String element) {
                return SequentialExecution.byHashCodes(calculateId.apply(element));
            }
        });

        try {
            // handle 内から例外がthrowされる場合、テスト自体も失敗になってしまうため catch する
            context.handleNext("input");
        } catch (DummyRuntimeException e) {
            // onUpstreamFailureにするための例外は無視してテストが失敗するのを回避
        }

        assertThat("中断する（＝全件処理されない）", handledCount.get(), is(not(maxCount)));

        lock.await(2000, TimeUnit.MILLISECONDS);
        assertThat("同じexecutionIdが同時実行されていない", failed[0], is(false));
    }

    @Test
    public void ハンドラが結果として返したnullはNullResultに変換される() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(5);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public DataReader<String> createReader(ExecutionContext executionContext) {
                return new CounterDataReader(1);
            }
            @Override
            public Result handle(String s, ExecutionContext executionContext) {
                return null;
            }
        });

        Result.MultiStatus result = context.handleNext("input");
        assertThat(result.isSuccess(), is(true));
        List<Result> results = result.getResults();
        assertThat(results.size(), is(1));
        assertThat(results.get(0), is(NullResult.getInstance()));
    }

    @Test
    public void postExecutionで例外が発生したら親ハンドラにリスローされる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(5);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public void postExecution(Object o, ExecutionContext executionContext) {
                throw new ArithmeticException("postExecution failed");
            }
        });

        // 親ハンドラは handleNext から例外を catch できる
        assertThrows(ArithmeticException.class, () -> context.handleNext("input"));
    }

    @Test
    public void postExecutionでErrorが発生したら親ハンドラにリスローされる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(5);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase());
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public void postExecution(Object o, ExecutionContext executionContext) {
                throw new StackOverflowError();
            }
        });

        // 親ハンドラは handleNext から Error を catch できる
        assertThrows(StackOverflowError.class, () -> context.handleNext("input"));
    }

    @Test
    public void コネクションterminate時に例外が発生しpostExecutionでも例外が発生した場合はコネクションterminate時の例外が親ハンドラにリスローされる() {
        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase() {
            @Override
            public void terminate() {
                throw new DbAccessException("failed to terminate.", new SQLException());
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public void postExecution(Object o, ExecutionContext executionContext) {
                throw new ArithmeticException();
            }
        });

        // 親ハンドラは handleNext から例外を catch できる
        assertThrows(DbAccessException.class, () -> context.handleNext("input"));
    }

    @Test
    public void コネクションterminate時に例外が発生しpostExecutionでもErrorが発生した場合はコネクションterminate時の例外が親ハンドラにリスローされる() {
        int parallelism = 10;
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(parallelism);
        handler.setConnectionFactory((s) -> new TestTransactionManagerConnectionBase() {
            @Override
            public void terminate() {
                throw new DbAccessException("failed to terminate.", new SQLException());
            }
        });
        handler.setTransactionFactory(s -> new TestTransactionBase());

        ExecutionContext context = new ExecutionContext();
        context.addHandler(handler);
        context.addHandler(new TestControllableParallelExecutorBase() {
            @Override
            public void postExecution(Object o, ExecutionContext executionContext) {
                throw new StackOverflowError();
            }
        });

        // 親ハンドラは handleNext から Error を catch できる
        assertThrows(DbAccessException.class, () -> context.handleNext("input"));
    }

    @Test
    public void スレッドプールサイズを設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setThreadPoolSize(128);
        assertThat(handler.getThreadPoolSize(), is(128));
    }

    @Test
    public void スレッドプールサイズに0を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setThreadPoolSize(0);
        });
        assertThat(e.getMessage(), is("threadPoolSize(0) must be greater than 0."));
    }

    @Test
    public void スレッドプールサイズに負数を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, () -> {
            handler.setThreadPoolSize(-1);
        });
        assertThat(e.getMessage(), is("threadPoolSize(-1) must be greater than 0."));
    }

    @Test
    public void 並列実行スレッド数を設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setParallelism(192);
        assertThat(handler.getParallelism(), is(192));
    }

    @Test
    public void 並列実行スレッド数に0を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, () -> {
            handler.setParallelism(0);
        });
        assertThat(e.getMessage(), is("parallelism(0) must be greater than or equal to 1."));
    }

    @Test
    public void 並列実行スレッド数に負数を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, () -> {
            handler.setParallelism(-1);
        });
        assertThat(e.getMessage(), is("parallelism(-1) must be greater than or equal to 1."));
    }

    @Test
    public void バッファサイズを設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setHandleDataBufferSize(96);
        assertThat(handler.getHandleDataBufferSize(), is(96));
    }

    @Test
    public void バッファサイズに0を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, () -> {
            handler.setHandleDataBufferSize(0);
        });
        assertThat(e.getMessage(), is("handleDataBufferSize(0) must be greater than 0."));
    }

    @Test
    public void バッファサイズに負数を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, () -> {
            handler.setHandleDataBufferSize(-1);
        });
        assertThat(e.getMessage(), is("handleDataBufferSize(-1) must be greater than 0."));
    }

    @Test
    public void コミット間隔を設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setCommitInterval(100);
        assertThat(handler.getCommitInterval(), is(100));
    }

    @Test
    public void コミット間隔に0を設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setCommitInterval(0);
        assertThat(handler.getCommitInterval(), is(0));
    }

    @Test
    public void コミット間隔に負数を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setCommitInterval(-1);
        });
        assertThat(e.getMessage(), is("commitInterval(-1) must be greater than or equal to 0."));
    }

    @Test
    public void アクターシステム停止のタイムアウト秒数を設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setActorSystemTerminationTimeoutSec(3600);
        assertThat(handler.getActorSystemTerminationTimeoutSec(), is(3600L));
    }

    @Test
    public void アクターシステム停止のタイムアウト秒数に0を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setActorSystemTerminationTimeoutSec(0);
        });
        assertThat(e.getMessage(), is("actorSystemTerminationTimeoutSec(0) must be greater than 0."));
    }

    @Test
    public void アクターシステム停止のタイムアウト秒数に負数を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setActorSystemTerminationTimeoutSec(-1);
        });
        assertThat(e.getMessage(), is("actorSystemTerminationTimeoutSec(-1) must be greater than 0."));
    }

    @Test
    public void データベース接続のスレッドコンテキスト上の登録名を設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setConnectionName("my-connection-name");
        assertThat(handler.getConnectionName(), is("my-connection-name"));
    }

    @Test
    public void データベース接続のスレッドコンテキスト上の登録名にnullを設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setConnectionName(null);
        });
        assertThat(e.getMessage(), is("connectionName(null) must be not null and non empty."));
    }

    @Test
    public void データベース接続のスレッドコンテキスト上の登録名に空文字を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setConnectionName("");
        });
        assertThat(e.getMessage(), is("connectionName() must be not null and non empty."));
    }

    @Test
    public void トランザクション名を設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        handler.setTransactionName("my-transaction-name");
        assertThat(handler.getTransactionName(), is("my-transaction-name"));
    }

    @Test
    public void トランザクション名にnullを設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setTransactionName(null);
        });
        assertThat(e.getMessage(), is("transactionName(null) must be not null and non empty."));
    }

    @Test
    public void トランザクション名に空文字を設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setTransactionName("");
        });
        assertThat(e.getMessage(), is("transactionName() must be not null and non empty."));
    }

    @Test
    public void ExecutionIdAttributeを設定できる() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        ExecutionIdAttribute attr = new ExecutionIdAttribute();
        handler.setExecutionIdAttribute(attr);
        assertThat(handler.getExecutionContextAttribute(), is(attr));
    }

    @Test
    public void ExecutionIdAttributeにnullを設定できない() {
        ControllableParallelExecutionHandler handler = new ControllableParallelExecutionHandler();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            handler.setExecutionIdAttribute(null);
        });
        assertThat(e.getMessage(), is("executionIdAttribute must be not null."));
    }


    class TestControllableParallelExecutorBase implements ControllableParallelExecutor<String>, ExecutionHandlerCallback {
        @Override
        public Result handle(String s, ExecutionContext executionContext) {
            return new Result.Success();
        }

        @Override
        public DataReader<String> createReader(ExecutionContext executionContext) {
            return new CounterDataReader(100);
        }

        @Override
        public SequentialExecutionIdExtractor sequentialExecutionId(String element) {
            return SequentialExecution.byHashCodes(element);
        }

        @Override
        public void preExecution(Object o, ExecutionContext executionContext) { }

        @Override
        public void errorInExecution(Throwable throwable, ExecutionContext executionContext) { }

        @Override
        public void postExecution(Object o, ExecutionContext executionContext) { }
    }

    class CounterDataReader implements DataReader<String> {

        private int count = 0;
        private final int maxCount;

        CounterDataReader(int maxCount) {
            this.maxCount = maxCount;
        }

        @Override
        public String read(ExecutionContext executionContext) {
            count++;
            return String.valueOf(count);
        }

        @Override
        public boolean hasNext(ExecutionContext executionContext) {
            return count < maxCount;
        }

        @Override
        public void close(ExecutionContext executionContext) {

        }
    }

    void println(Object message) {
        System.out.println(message);
    }

    private static final class NoExceptionThrowable extends Throwable { }
}
