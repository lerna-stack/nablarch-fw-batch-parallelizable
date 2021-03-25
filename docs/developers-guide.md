# 開発ガイド

## 業務アクションの実装

ハンドラキューで設定した `ControllableParallelExecutionHandler` で処理される業務アクションは
必ず `ControllableParallelExecutor` を実装する必要がある。
実装されていない場合は業務アクションの起動時に `ControllableParallelExecutionHandler` で行われるチェックで実行時例外となる。

例えば次のように `ControllableParallelExecutionHandler` を実装する。

```java
import lerna.nablarch.batch.parallelizable.handler.ControllableParallelExecutor;
import nablarch.core.db.statement.SqlRow;

public class SampleBatchAction extends BatchAction<SqlRow> implements ControllableParallelExecutor<SqlRow> {
    // 実装を行う
}
```

### `sequentialExecutionId` を実装する

`ControllableParallelExecutor` を実装する業務アクションは `sequentialExecutionId` というメソッドを実装する必要がある。
このメソッドで、どの単位で逐次処理するかを宣言できる。
このメソッドは、`SequentialExecutionIdExtractor` を返すよう実装する。
`SequentialExecutionIdExtractor` はハンドラの入力データを引数にとり、`int` を返す関数である。
`SequentialExecutionIdExtractor` が返す int の値が同じになる入力データは逐次処理されることが保証される。

デフォルトの実装としては、`SequentialExecution#byHashCodes` が提供されている。
これは引数に渡されたオブジェクトの `hashCode` から `int` の値を求める `SequentialExecutionIdExtractor` を生成する。
`SequentialExecution#byHashCodes` を使った実装の例を以下に示す。

```java
public class SampleBatchAction extends BatchAction<SqlRow> implements ControllableParallelExecutor<SqlRow> {
    // ... truncated
    
    @Override
    public ControllableParallelExecutor.SequentialExecutionIdExtractor sequentialExecutionIdExtractor(SqlRow element) {
        // 逐次処理を行う単位を宣言する
        // 同じ文字列を持つ String はインスタンスが異なっていても同じ hashCode を返すため、
        // 同じ CUSTOMER_ID を持つ要素は逐次処理されることが保証される
        return SequentialExecution.byHashCodes(element.getString("CUSTOMER_ID"));
    }
}
```

`SequentialExecutionIdExtractor` は単なる関数のため、要件に合わせて独自に実装することもできる。

### `ControllableParallelExecutor` を実装する業務アクションのインスタンス変数をスレッドセーフにする

`ControllableParallelExecutor` を実装する業務アクションのインスタンスは、シェルからの 1 呼び出しにつき、1つだけ生成される。
しかし、`ControllableParallelExecutor#handle` の呼び出しは複数のスレッドから並列で行われる。
つまり、`ControllableParallelExecutor` が持つインスタンス変数は複数のスレッドから参照されうるため、スレッドセーフになるよう注意する必要がある。
インスタンス変数をスレッドセーフにする方法としては、下記のような方法がある。

- `java.util.concurrent.atomic` のオブジェクトを利用する
- `ThreadLocal` を利用する
- etc...


## 並列実行スレッド数とコネクションプールサイズとの関係性

並列実行ハンドラ(`ControllableParallelExecutionHandler`) は
並列実行スレッド数(`parallelism`) で指定した分だけ、データベースコネクションを使⽤する。
最⼩ハンドラ構成の場合は少なくとも `parallelism + 1` のコネクションが必要である。

| No. | ハンドラ                                    | ハンドラDBコネクション利⽤数 |
| --- | ----------------------------------------- | ----------------------- |
| 1   | ステータスコード→プロセス終了コード変換ハンドラ    | 0                       |
| 2   | グローバルエラーハンドラ                       | 0                       |
| 3   | データベース接続管理ハンドラ (初期処理/終了処理⽤) | 1                       |
| 4   | トランザクション制御ハンドラ (初期処理/終了処理⽤) | 0                       |
| 5   | リクエストディスパッチハンドラ                  | 0                       |
| 6   | `ControllableParallelExecutionHandler`    | `parallelism` と同数       |
