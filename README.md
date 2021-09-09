# nablarch-fw-batch-parallelizable

[![CI](https://github.com/lerna-stack/nablarch-fw-batch-parallelizable/workflows/CI/badge.svg?branch=main)](https://github.com/lerna-stack/nablarch-fw-batch-parallelizable/actions?query=workflow%3ACI+branch%3Amain)

## Overview

[nablarch-fw-batch](https://github.com/nablarch/nablarch-fw-batch) でバッチ並列実行を実現するカスタムハンドラを提供します。  
*nablarch-fw-batch* の利用方法については、[公式ドキュメント](https://nablarch.github.io/docs/LATEST/doc/application_framework/application_framework/batch/index.html) をご確認ください。

## Supported JDK versions

実行環境の Java バージョン は 8 系以上が必要です。

## Dependencies

*nablarch-fw-batch-parallelizable* は、内部で次のライブラリに依存しています。  
詳細は [pom.xml](pom.xml) を参照してください。

- [nablarch-fw-batch](https://github.com/nablarch/nablarch-fw-batch) `1.2.2`
- [akka-stream](https://doc.akka.io/docs/akka/current/stream/index.html) `2.6.9`

## Install

*Maven* を用いたライブラリ使用方法について説明します。
他のビルドツールを使用する場合は、記述方法をビルドツールに合わせて変更してください。

ライブラリをプロジェクトで使用するためには、次の依存を`pom.xml`ファイルに追加してください。
```xml
<dependencies>
    <dependency>
        <groupId>com.lerna-stack</groupId>
        <artifactId>nablarch-fw-batch-parallelizable</artifactId>
        <version>1.2.0</version>
    </dependency>
</dependencies>
```

### SNAPSHOT バージョン を使用するための設定

SNAPSHOT バージョンを使用するためには、*Sonatype* の snapshots リポジトリを追加する必要があります。
次の設定を`pom.xml`ファイルに追加してください。
```xml
<repositories>
  <repository>
    <id>sonatype-snapshots</id>
    <url>https://oss.sonatype.org/content/repositories/snapshots/</url>
    <releases>
      <enabled>false</enabled>
      <updatePolicy>always</updatePolicy>
      <checksumPolicy>fail</checksumPolicy>
    </releases>
    <snapshots>
      <enabled>true</enabled>
      <updatePolicy>always</updatePolicy>
      <checksumPolicy>fail</checksumPolicy>
    </snapshots>
  </repository>
</repositories>
```

## Usage

`nablarch-fw-batch-parallelizable` が提供する並列実⾏ハンドラは、 Nablarch 標準の⼀部ハンドラを代替する形式となります。

### 都度起動バッチ

[Nablarch 都度起動バッチの最小ハンドラ構成](https://nablarch.github.io/docs/LATEST/doc/application_framework/application_framework/batch/nablarch_batch/architecture.html#id5) にある一部のハンドラを
`nablarch-fw-batch-parallelizable` が提供する並列実行ハンドラ(`ControllableParallelExecutionHandler`)に置き換えます。
置き換え対象のハンドラは、次の表の`x`と示されている4つの標準ハンドラです。

| No. | ハンドラ                                   | Nablarch 標準 | nablarch-fw-batch-parallelizable |
| --- | ---------------------------------------- | ------------ | --------------------------------- |
| 1   | ステータスコード→プロセス終了コード変換ハンドラ   | o            | o                                |
| 2   | グローバルエラーハンドラ                      | o            | o                                |
| 3   | データベース接続管理ハンドラ(初期処理/終了処理用) | o            | o                                |
| 4   | トランザクション制御ハンドラ                   | o            | o                                |
| 5   | リクエストディスパッチハンドラ                 | o            | o                                |
| 6   | マルチスレッド実行制御ハンドラ                 | o            | x                                |
| 7   | データベース接続管理ハンドラ(業務処理用)         | o            | x                                |
| 8   | トランザクションループ制御ハンドラ              | o            | x                                |
| 9   | データリードハンドラ                         | o             | x                               |
| 10  | 並列実行ハンドラ                      | -             | o                               |


置き換えた設定は次のようになります。

```xml
<!-- ハンドラキュー構成 -->
<list name="handlerQueue">

    <!-- ステータスコードを終了コードに変換するハンドラ -->
    <component class="nablarch.fw.handler.StatusCodeConvertHandler" />

    <!-- グローバルエラーハンドラ -->
    <component class="nablarch.fw.handler.GlobalErrorHandler" />

    <!-- データベース接続ハンドラ(初期処理/終了処理用) -->
    <component-ref name="dbConnectionManagementHandler" />

    <!-- トランザクション制御ハンドラ(初期処理/終了処理用) -->
    <component-ref name="transactionManagementHandler" />

    <!-- ディスパッチ -->
    <component class="nablarch.fw.handler.RequestPathJavaPackageMapping">
        <property name="basePackage" value="${nablarch.commonProperty.basePackage}" />
        <property name="immediate" value="false" />
    </component>

    <!-- ここから置き換え対象です -->
 
    <!-- マルチスレッド実行制御ハンドラ -->
    <!--<component-ref name="multiThreadExecutionHandler" />-->

    <!-- データベース接続ハンドラ(業務処理用) -->
    <!--<component-ref name="dbConnectionManagementHandler" />-->
    
    <!-- トランザクションループ制御ハンドラ -->
    <!--<component-ref name="loopHandler" />-->
    
    <!-- データリードハンドラ -->
    <!--<component-ref name="dataReadHandler" />-->

    <!-- ここまで置き換え対象です -->
    
    <!-- 代わりにこのライブラリが提供するハンドラを使用します -->

    <!-- 並列実行ハンドラ -->
    <component class="lerna.nablarch.batch.parallelizable.handler.ControllableParallelExecutionHandler">
        <property name="parallelism" value="8" />
        <property name="handleDataBufferSize" value="20" />
    </component>

</list>
```

## 並列実行ハンドラの設定方法

`lerna.nablarch.batch.parallelizable.handler.ControllableParallelExecutionHandler` の設定項目は以下の通りです。

```xml
<component class="lerna.nablarch.batch.parallelizable.handler.ControllableParallelExecutionHandler">
    <property name="threadPoolSize" value="32" />
    <property name="parallelism" value="1" />
    <property name="handleDataBufferSize" value="2" />
    <property name="commitInterval" value="1" />
    <property name="actorSystemTerminationTimeoutSec" value="180" />
    <property name="connectionName" value="transaction" />
    <property name="transactionName" value="transaction" />
</component>
```

### `threadPoolSize`

ハンドラが使用するスレッドプールのサイズを設定します。デフォルト値は 32 です。

推奨値は `parallelism` + CPUコア数 + 2（INPUTデータ取得用 1 + 予備 1) となります。
推奨値よりもスレッドプールサイズが⼩さい場合は parallelism を上げても性能が上がりません。
逆に、推奨値よりもスレッドプールサイズが⼤きい場合はコンテキストスイッチのオーバーヘッドが⼤きくなり性能が劣化する可能性があります。

### `parallelism`

並列実行スレッド数を設定します。デフォルト値は 1 です。

指定された値でバッチを並列に処理します。
CPUバウンドな処理の場合はこの値をCPUコア数に近づけるにつれ性能が良くなることが期待できます。
I/Oバウンドな処理の場合はこの値を⼤きくするにともないCPUリソースに関わらず処理速度が向上します。
代わりに、DBなどの外部リソースにかかる負荷が⾼くなるため、外部リソースの負荷状況を⾒ながら最適値を検討してください。

※ **注意** 利用可能なデータベースコネクション数の上限によりも小さい値を設定してください。
このドキュメント記述時点では、利用可能なデータベースコネクション数以上の値を設定した場合には、
ハンドラが並列化ジョブを上手に起動できず、バッチジョブ全体がストップする問題があります。
[並列実行スレッド数とコネクションプールサイズとの関係性](docs/developers-guide.md#並列実行スレッド数とコネクションプールサイズとの関係性) には、
並列実行スレッド数とコネクションプールサイズを決める際に考慮したほうがよい有用な記述があります。

### `handleDataBufferSize`

バッファで保持できる保留データの最大数を指定します。デフォルト値は 2 です。

異なる executionId のフロー間で⼀時的な処理速度の差異が⽣じた場合、
その差異を吸収し全体の処理速度が低下するのを防ぐ目的があります。
処理速度の差異が⼤きい場合はバッファサイズを⼤きくすると処理速度の向上が期待できますが、
バッファサイズを⼤きくした分メモリ使⽤量は増えることに注意してください。

### `commitInterval`

指定された処理件数ごとにトランザクションをコミットします。
デフォルト値は 1 です。

0 を指定した場合は処理が全て終了するまでコミットしません。
ただし、コミットは parallelism によって分割された処理グループごとに実⾏されることに注意してください。
例えば parallelism に 10 が指定されていて commitInterval が 0 の場合は、合計10回コミットされます。
処理対象のデータが⼤量にある場合に 0 を指定しているとコミット時にデータベースが⾼負荷になるため注意してください。

### `actorSystemTerminationTimeoutSec`

アクターシステム停止待ちのタイムアウト秒数を設定する。デフォルト値は 180秒 です。

このライブラリでは内部依存として *Akka Streams* を使用しています。
この設定では、*Akka Streams* で使用する *ActorSystem* を停止待ちのタイムアウト秒数を指定できます。  
ほとんどのユースケースではデフォルト値で十分ですが、必要に応じて変更することができます。

### `connectionName`

データベース接続のスレッドコンテキスト上の登録名を設定します。
デフォルトでは既定のトランザクション名 [TransactionContext.DEFAULT_TRANSACTION_CONTEXT_KEY](https://nablarch.github.io/docs/LATEST/javadoc/nablarch/core/transaction/TransactionContext.html#DEFAULT_TRANSACTION_CONTEXT_KEY) を使用します。

### `transactionName`

ハンドラが管理するトランザクションの、スレッドコンテキスト上での登録名を設定します。
デフォルトでは既定のトランザクション名 [TransactionContext.DEFAULT_TRANSACTION_CONTEXT_KEY](https://nablarch.github.io/docs/LATEST/javadoc/nablarch/core/transaction/TransactionContext.html#DEFAULT_TRANSACTION_CONTEXT_KEY) を使用します。

## Examples

`nablarch-fw-batch-parallelizable` を使ったサンプルを提供しています。

- https://github.com/lerna-stack/nablarch-fw-batch-parallelizable-example/

## Documents

`nablarch-fw-batch-parallelizable` のアーキテクチャや開発ガイドを提供しています。

- [アーキテクチャ](docs/architecture.md)
- [開発ガイド](docs/developers-guide.md)

## License

`nablarch-fw-batch-parallelizable` is released under the terms of the [Apache License Version 2.0](LICENSE.txt).

© 2021 TIS Inc.
