package nablarch.fw.handler;

/**
 * Nablarch の {@link ExecutionHandler} の可視性が package private になっているのを public にするためのインターフェイス
 *
 * @since 2018/04/09
 */
public interface ParallelExecutionHandler<TData, TResult, TSelf extends ExecutionHandler> extends ExecutionHandler<TData, TResult, TSelf> {
}
