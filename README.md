# FusenizeTxtWidget

付箋からtxtへ、txtから付箋へ、いつでも安全にメモしたい付箋ウィジェット for Android。

- アプリ名: **FusenizeTxt**（Kotlin + Jetpack Glance / Jetpack Compose）
- メモは常に普通のtxtファイルとして保存されます（保存フォルダは自由に選べます。未設定なら `Documents/FusenizeTxtWidget`）
- ビルドは GitHub Actions のみ。成果物は Actions の Artifacts にある `FusenizeTxtWidget-build<ビルド番号>-debug.apk`

## ビルド

`main` に push すると `.github/workflows/build.yml` が走り、デバッグ版APKがArtifactsに出力されます（署名設定は不要）。

## 機能

- ウィジェット: 複数配置可（ウィジェットごとに別メモ）、1×1から自由にリサイズ、最大5ページ（最下段で切替・ページごとに背景色）
- 編集: 元に戻す/やり直し、チェックボックス（☐/☑）、履歴、文字コードの確認（UTF-8以外）、スリープ防止
- 一覧: 開いたファイル / フォルダ内、更新日時・作成日時・名前ソート、お気に入り
- バックアップ: zipのエクスポート/インポート、定時バックアップ（残す個数は最大100）

## アプリアイコンの変更

**いちばん簡単な方法**: 正方形のPNG（1024×1024px推奨）を `icon/icon.png` としてリポジトリにアップロードするだけ。
ビルド時に自動でアプリアイコンに変換されます（`icon/icon.png` を削除すると元のアイコンに戻ります）。

- アダプティブアイコンのため、端末によって外側約1/6が丸や四角に切り取られます。大事な図柄は中央60%以内に収めてください。
- 透明部分には `app/src/main/res/values/colors.xml` の `ic_launcher_background` の色が入ります。

手動で変える場合:

- 背景色: `app/src/main/res/values/colors.xml` の `ic_launcher_background`
- 前景（図柄）: `app/src/main/res/drawable/ic_launcher_foreground.xml`（ベクター）
- 構成: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
