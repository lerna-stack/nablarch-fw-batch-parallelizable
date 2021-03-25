## コントリビュータ向け

ビルドツールには Maven を使っており、一般的な Maven コマンドが使えます。

### テストの実行方法

テストを実行するには次のコマンドを実行します。

```shell
mvn clean test-compile test
```

### アーティファクトのビルド方法

アーティファクトをビルドするには次のコマンドを実行します。  
`target/` に次のアーティファクトが生成されます。

- `nablarch-fw-batch-parallelizable-<version>.jar`
- `nablarch-fw-batch-parallelizable-<version>-javadoc.jar`
- `nablarch-fw-batch-parallelizable-<version>-sources.jar`

```shell
mvn clean package
```

### テストカバレッジの取得方法

テストカバレッジを取得するためには次のコマンドを実行します。  
`target/site/jacoco` にカバレッジレポートが生成されます。

```shell
mvn clean jacoco:prepare-agent test jacoco:report
```

### *Akka* の設定方法

ライブラリの依存として *Akka Streams* を使用しています。
*Akka* に関係する設定は、[Typesafe Config](https://github.com/lightbend/config) を使用し、
[reference.conf](src/main/resources/reference.conf) で実施しています。
*Typesafe Config* では他のライブラリの設定項目を上書きする機能はサポートされていないため、
`akka.*` から始まる設定項目をこのライブラリの`reference.conf`や`application.conf`から直接上書きすることはできません。
代わりに、`lerna.nablarch.batch.parallelizable.akka` に上書きしたい設定項目を定義します。
このライブラリで使用する`ActorSystem`を作成する際には、
定義してある`lerna.nablarch.batch.parallelizable.akka` の内容と `akka`の内容をマージした`Config`オブジェクトを使うことで、
*Akka*関連の設定値を上書きしています。
関連コードは [ControllableParallelExecutionHandler](src/main/java/lerna/nablarch/batch/parallelizable/handler/ControllableParallelExecutionHandler.java) を参照してください。


## メンテナ向け

### アーティファクトのデプロイ手順

次の手順を実行することで、アーティファクト(jar,javadoc,source)をOSSRHにデプロイできます。
バージョンがスナップショットの場合(x.x.x-SNAPSHOT の形式)には、`mvn deploy`実行完了後にアーティファクトが利用可能になります。
バージョンがリリースの場合(x.x.x の形式)には、`mvn deploy` 後に追加でいくつかの[手順](#リリースビルドを公開する手順)が必要です。

1. `~/.m2/settings.xml` に [OSSRH](https://oss.sonatype.org/) の認証情報を設定します。
    ```xml
      <servers>
        <server>
          <id>ossrh</id>
          <username>your-jira-id</username>
          <password>your-jira-pwd</password>
        </server>
      </servers>
    ```

1. GPGキーリングに`9AE72A11453054CAAD7CCD0820DC5645D15A7854`の秘密鍵をインポートします。
   ```shell
   gpg --import 9AE72A11453054CAAD7CCD0820DC5645D15A7854.gpg
   ```

1. アーティファクトをビルドし、OSSRHにデプロイします。
   ```shell
   mvn clean deploy
   ```

### リリースアーティファクトを公開する手順

リリースアーティファクトを公開するには次のコマンドを実行します。
```shell
mvn nexus-staging:release
```

リリースアーティファクトを公開せずに破棄する場合には次のコマンドを実行します。
```shell
mvn nexus-staging:drop
```

※より詳細な情報は、[Apache Maven](https://central.sonatype.org/pages/apache-maven.html) をご覧ください。
