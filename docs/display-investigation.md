# シフト表示の調査（2026-09-28）

実DBに接続しないJDBCテストと、実際のWebServerのHTML/CSSを使ったChromeで確認。

| 架空データ | 修正前 | 修正後 |
| --- | --- | --- |
| 11:30〜22:00の連続した30分枠 | 1本・1行 | 1本・1行 |
| 休憩で分かれた3区間（時間順） | 3本・1行 | 3本・1行 |
| 同じ3区間を逆順で描画 | 3本・3行 | 3本・1行 |
| 時間が重なる5区間（比較用） | 5本・5行 | 5本・5行 |

CSS Gridは列だけを指定したバーを入力順に自動配置するため、時間順でない区間は重複がなくても別行になる。描画処理内で開始・終了時刻順に並べるように修正した。呼び出し元のリストは変更せず、実際に重なる区間を隠すための行固定はしない。

ただし、現在の取得SQLには日付・従業員ID・開始時刻のORDER BYがあるため、通常の取得経路で順序が逆になることは今回再現していない。**この表示側の弱点を修正しただけで、ユーザーの画像の根本原因を確定したわけではない。** 正常な連続データでは、現在のコードの結合処理は正常だった。

次の切り分けは、対象ページの実際のHTMLにある `.shift-bar` の時刻と `grid-column`、本番の稼働コミット、読み取り専用で取得した勤務区間の照合。これにより本番とローカルのコード差、描画に渡る区間の順序や内容、ブラウザーだけの問題を区別できる。

検証: Javaの表示テスト9項目、既存57項目、Chromeの4ケースの配置確認が通過。本番反映は未実施。

再実行は専用のシェルで以下を実行する（DB_URLは実DBに接続しない架空のJDBCドライバー）。

```powershell
javac -encoding UTF-8 -cp 'ShiftSystem/lib/*' -d ShiftSystem/bin ShiftSystem/src/*.java ShiftSystem/tests/*.java
$env:DB_URL='jdbc:display-test'
$env:DB_PASSWORD='fixture-only'
java -cp 'ShiftSystem/bin;ShiftSystem/lib/*' ShiftDisplayTests
```

生成される `ShiftSystem/bin/display-investigation.html` を開くと、実際のアプリのCSSで4ケースを比較できる。
