package lerna.nablarch.batch.parallelizable.source;

import akka.NotUsed;
import akka.stream.Attributes;
import akka.stream.Outlet;
import akka.stream.SourceShape;
import akka.stream.javadsl.Source;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import akka.stream.stage.OutHandler;
import nablarch.fw.DataReader;
import nablarch.fw.ExecutionContext;

/**
 * {@link DataReader} からデータを取得する {@link Source} を作成するクラス
 *
 * @since 2018/04/09
 */
public class DataReaderSource {

    /**
     * このクラスは静的メソッドによるファクトリ機能のみを提供するためインスタンス化しない
     */
    private DataReaderSource() {
        // Do nothing
    }

    /**
     * {@link ExecutionContext} に設定された {@link DataReader} から {@link Source} を作成する。
     *
     * @param <T> {@link DataReader} から取得できるデータの型
     * @param context {@link DataReader} が設定された {@link ExecutionContext}
     * @return {@link DataReader} から作成された {@link DataReader}
     */
    public static <T> Source<T, NotUsed> create(ExecutionContext context) {
        return Source.fromGraph(DataReaderSourceGraphStage.<T> create(context));
    }

    /**
     * {@link DataReader} からデータを取得する {@link GraphStage}
     *
     * @since 2018/04/09
     *
     * @param <T> {@link DataReader} から取得できるデータの型
     */
    public static class DataReaderSourceGraphStage<T> extends GraphStage<SourceShape<T>> {

        /**
         * {@link ExecutionContext} に設定された {@link DataReader} から {@link DataReaderSourceGraphStage}
         * を作成する。
         *
         * @param <T> {@link DataReader} から取得できるデータの型
         * @param context {@link DataReader} が設定された {@link ExecutionContext}
         * @return {@link DataReader} から作成された {@link DataReaderSourceGraphStage}
         */
        public static <T> DataReaderSourceGraphStage<T> create(ExecutionContext context) {
            return new DataReaderSourceGraphStage<T>(context);
        }

        private final Outlet<T> out = Outlet.create("DataReaderSourceGraphStage");

        private final ExecutionContext context;

        /**
         * コンストラクタ
         *
         * @param context {@link DataReader} が設定された {@link ExecutionContext}
         */
        private DataReaderSourceGraphStage(ExecutionContext context) {
            this.context = context;
        }

        @Override
        public SourceShape<T> shape() {
            return SourceShape.of(out);
        }

        @Override
        public GraphStageLogic createLogic(Attributes inheritedAttributes) {
            return new GraphStageLogic(shape()) {

                {
                    setHandler(out, new OutHandler() {
                        @Override
                        public void onPull() {
                            if (context.hasNextData()) {
                                push(out, context.readNextData());
                            } else {
                                completeStage();
                            }
                        }
                    });
                }

                @Override
                public void postStop() {
                    context.closeReader();
                }
            };
        }
    }

}
