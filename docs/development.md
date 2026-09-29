# 開発・検証ガイド

[READMEに戻る](../README.md)

## ローカルでの起動

### 必要なもの

- JDK 17以上
- 接続可能なMySQL互換データベースと、アプリが使用するテーブル
- `lib/` 内のMySQL Connector/J

リポジトリには `shift_system_dump.sql` と `shift_system_tidb.sql` があります。これらはデータを含むダンプであり、空のDBを作るためだけのファイルではありません。検証には専用DBを用意し、内容を確認してから利用してください。

起動時にログイン用の `app_users` と期間設定用の `app_settings` は自動作成されます。従業員・希望・勤務シフトなどの業務テーブルは事前に用意する必要があります。

### 環境変数

| 変数 | 内容 | 省略時 |
| --- | --- | --- |
| `DB_URL` | JDBC接続URL | `jdbc:mysql://localhost:3306/shift_system` |
| `DB_USER` | DBユーザー名 | `root` |
| `DB_PASSWORD` | DBパスワード | 必須 |
| `APP_USERNAME_1` / `APP_USERNAME_2` | 2つのログイン名（別々の名前を指定） | 両方必須 |
| `APP_PASSWORD_1` / `APP_PASSWORD_2` | 各ログインの初期パスワード（8文字以上） | 両方必須 |
| `PORT` | HTTP待受ポート | `8080`（Dockerでは`10000`） |

ログイン情報は初回登録時にDBへ保存されます。登録済みユーザーのパスワードは環境変数を変えても上書きされません。変更には画面の「パスワード変更」を使います。

### Windows / PowerShell

プロジェクトのルートで、接続先とパスワードを実際の値に置き換えて実行します。

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/shift_system"
$env:DB_USER = "root"
$env:DB_PASSWORD = "<DBのパスワード>"
$env:APP_USERNAME_1 = "manager"
$env:APP_PASSWORD_1 = "<8文字以上のパスワード1>"
$env:APP_USERNAME_2 = "staff"
$env:APP_PASSWORD_2 = "<8文字以上のパスワード2>"

New-Item -ItemType Directory -Force bin | Out-Null
javac --release 17 -encoding UTF-8 -cp "lib/*" -d bin src/*.java
java -cp "bin;lib/*" WebServer
```

起動後に `http://localhost:8080` を開きます。停止するときは起動したターミナルで `Ctrl+C` を押します。

### Docker

必要な環境変数を設定した `--env-file` 用ファイルを用意して実行します。

```shell
docker build -t shift-system .
docker run --rm -p 10000:10000 --env-file .env shift-system
```

`http://localhost:10000` でアクセスできます。DB接続先には、コンテナから到達できるホストを指定します。パスワードを含む `.env` はGitに登録しないでください。

## 主なファイル

```text
src/
  WebServer.java          Web画面・ログイン・各種登録処理
  GenerationService.java 非同期生成・進捗管理
  ShiftGenerator.java    シフトの自動生成・保存
  PositionAllocator.java 担当の不足補正
  CoverageRepair.java    連続勤務の追加による不足補正
  DBConnection.java      環境変数を使ったDB接続
  Main.java              コンソール版の操作メニュー
tests/                実DBに接続しない回帰テスト
lib/                  JDBCドライバー
Dockerfile            ビルド・公開用のコンテナ設定
docs/images/          README掲載用の画面画像
```


## 進捗管理と運用上の注意

生成ボタンを押すとバックグラウンドで生成し、画面の進捗を約1.5秒ごとに更新します。処理済みの時間枠に基づく進捗率、配置処理済みの日数、処理中の日付・時刻を表示します。最後の勤務時間調整まで終わると100%となり、結果画面へのリンクを表示します。進捗率は残り時間の予測ではありません。

生成処理が例外で終了した場合は「エラーが発生しました」と表示します。通信の問題は生成エラーと区別し、自動で再接続します。ログイン期限が切れた場合は、再ログインして確認してください。生成中は同じサーバー内での重複実行を防ぎます。進捗情報はサーバー再起動時にリセットされます。進捗と重複実行防止はプロセス内で管理しているため、現在は1インスタンスでの運用を前提としています。

生成エラー時は一部のシフトだけが保存されている可能性があります。サーバーログで原因を確認し、対象期間を再生成してください。

## テスト

Java 17向けコンパイルと、実DBに接続しない89項目のテストを確認しています。

| テスト | 項目数 | 主な確認内容 |
| --- | ---: | --- |
| `RegressionTests` | 28 | 期間入力、新人の配置条件など |
| `GenerationTests` | 29 | 進捗、生成失敗、再試行、重複実行防止 |
| `ShiftDisplayTests` | 9 | 連続勤務・休憩・逆順区間・重なりの表示 |
| `PositionAllocationTests` | 10 | 担当変更による不足補正、資格・レベル条件の維持 |
| `CoverageRepairTests` | 13 | 18時半の不足への4時間配置、社員の延長、休み・新人制約 |

プロジェクトのルートから実行します。

```powershell
New-Item -ItemType Directory -Force bin | Out-Null
javac --release 17 -encoding UTF-8 -cp "lib/*" -d bin src/*.java tests/*.java
java -cp "bin;lib/*" RegressionTests
java -cp "bin;lib/*" GenerationTests
java -cp "bin;lib/*" PositionAllocationTests
java -cp "bin;lib/*" CoverageRepairTests
```

表示テストは架空のJDBCドライバーを使います。出力先が `ShiftSystem/bin/` 固定のため、次のように一時作業フォルダーから実行できます。

```powershell
$projectDir = (Get-Location).Path
$fixtureDir = Join-Path $env:TEMP ("shift-display-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force (Join-Path $fixtureDir "ShiftSystem/bin") | Out-Null
$savedDbUrl = $env:DB_URL
$savedDbPassword = $env:DB_PASSWORD
Push-Location $fixtureDir
try {
    $env:DB_URL = "jdbc:display-test"
    $env:DB_PASSWORD = "fixture-only"
    java -cp "$projectDir/bin;$projectDir/lib/*" ShiftDisplayTests
} finally {
    $env:DB_URL = $savedDbUrl
    $env:DB_PASSWORD = $savedDbPassword
    Pop-Location
}
```

`GenerationTests` は意図的な失敗のログも出力します。これらのテストは、実DBを使う生成処理全体の検証ではありません。
