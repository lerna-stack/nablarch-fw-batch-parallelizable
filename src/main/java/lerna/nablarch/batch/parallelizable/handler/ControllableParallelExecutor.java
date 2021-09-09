package lerna.nablarch.batch.parallelizable.handler;

import java.util.Arrays;
import java.util.function.IntSupplier;

import nablarch.fw.DataReaderFactory;
import nablarch.fw.Handler;
import nablarch.fw.Result;

/**
 * <p>
 * {@link ControllableParallelExecutionHandler} から呼び出されるエグゼキューターのインターフェイス
 * </p>
 * <p>
 * このインターフェイスを実装するクラスは {@link #sequentialExecutionId(Object)} を実装する必要がある。
 * このメソッドで入力データをどの単位で逐次処理するかを定義できる。
 * </p>
 *
 * @since 2018/04/09
 *
 * @param <IN> 処理対象の入力データの型
 */
public interface ControllableParallelExecutor<IN> extends Handler<IN, Result>, DataReaderFactory<IN> {

    /**
     * 入力データをどの単位で逐次処理するかを定義するためのメソッド。
     *
     * <p>
     * このメソッドの実装で例外を throw してはならない。
     * このメソッドで例外が throw された場合、バッチ処理は中止される。
     * このメソッドで受け取った element 以外の要素も処理されない可能性がある。
     *
     * @param element 処理対象の入力データ
     * @return 逐次処理する {@link SequentialExecutionIdExtractor}。
     *         null を返してはならない。このメソッドが null を返す場合、バッチ処理は中止される。
     *         このメソッドで受け取った element 以外の要素も処理されない可能性がある。
     */
    SequentialExecutionIdExtractor sequentialExecutionId(IN element);

    /**
     * 逐次処理する単位を定義する関数。
     *
     * <p>
     * 入力データの種類を識別する ID は int で表される。同じ ID を持つ入力データはそれぞれが並列に処理されず、逐次的に処理されることが保証される。
     *
     * <p>
     * {@link #getAsInt} で例外を throw してはならない。
     * 例外が throw された場合、バッチ処理は中止される。
     *
     * @since 2018/04/09
     */
    interface SequentialExecutionIdExtractor extends IntSupplier {
    }

    /**
     * 事前定義された {@link SequentialExecutionIdExtractor}。
     *
     * @since 2018/04/09
     */
    class SequentialExecution {

        /**
         * このクラスは静的メソッドによるファクトリ機能のみを提供するためインスタンス化しない
         */
        private SequentialExecution() {
            // Do nothing
        }

        /**
         * <p>
         * 引数で与えられたオブジェクトの hashCode から逐次処理する入力データを識別する {@link SequentialExecutionIdExtractor} を作成する。
         *
         * <p>
         * 例）
         *
         * <pre>
         * public SequentialExecutionIdExtractor sequentialExecutionId(SqlRow element) {
         *     // 同じ文字列を持つ String はインスタンスが異なっていても同じ hashCode を返すため、
         *     // 同じ CUSTOMER_ID を持つ要素は逐次処理されることが保証される
         *     SequentialExecution.byHashCodes(element.getString("CUSTOMER_ID"));
         * }
         * </pre>
         *
         * @param objects 入力データを識別
         * @return 引数で与えられたオブジェクトから逐次処理する入力データを識別する {@link SequentialExecutionIdExtractor}
         */
        public static SequentialExecutionIdExtractor byHashCodes(Object... objects) {
            return new SequentialExecutionIdExtractor() {
                @Override
                public int getAsInt() {
                    return Arrays.hashCode(objects);
                }
            };
        }
    }
}
