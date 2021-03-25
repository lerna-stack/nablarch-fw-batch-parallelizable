package lerna.nablarch.batch.parallelizable.handler;

import nablarch.fw.ExecutionContext;
import nablarch.fw.Handler;
import nablarch.fw.Result;


/**
 * 下位ハンドラの{@link Handler#handle(Object, ExecutionContext)}が null を返却したことを表す{@link Result}
 */
class NullResult extends Result.Success {

    private static final NullResult INSTANCE = new NullResult();

    private NullResult() {
        super();
    }

    public static NullResult getInstance() {
        return INSTANCE;
    }
}