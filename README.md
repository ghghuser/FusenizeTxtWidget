# FusenizeTxtWidget

付箋からtxtへ、txtから付箋へ、いつでも安全にメモしたい付箋ウィジェット for Android。

- アプリ名: **FusenizeTxt**（Kotlin + Jetpack Glance / Jetpack Compose）
- メモは常に普通のtxtファイルとして保存されます（保存フォルダは自由に選べます。未設定なら `Documents/FusenizeTxtWidget`）
- ビルドは GitHub Actions のみ。成果物は Actions の Artifacts にある `FusenizeTxtWidget-build<ビルド番号>-debug.apk`

## ビルド

`main` に push すると `.github/workflows/build.yml` が走り、デバッグ版APKがArtifactsに出力されます（署名設定は不要）。

## アプリアイコンの変更

- 背景色: `app/src/main/res/values/colors.xml` の `ic_launcher_background`
- 前景（図柄）: `app/src/main/res/drawable/ic_launcher_foreground.xml`（ベクター）
  - PNGにする場合は `drawable/ic_launcher_foreground.png`（432×432px、図柄は中央288px以内）を置き、上記xmlを削除
- 構成: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
