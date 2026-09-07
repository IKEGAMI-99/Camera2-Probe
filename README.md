# TRI // CAM

POCO F7 Ultra のリア3眼カメラを、Android の公開 `Camera2 API` から同時利用するためのトリプルカメラアプリです。

もともとは Camera2 の Logical / Physical Camera 構成を調査するための **Camera2 Probe** として開始しましたが、POCO F7 Ultra で超広角・標準・望遠の3眼同時プレビューと同時JPEG撮影が実機で成立したため、現在は実用カメラアプリ **TRI // CAM** として開発しています。

## APK ダウンロード

### 最新リリース

**[TRI // CAM v0.7.0 APK をダウンロード](https://github.com/IKEGAMI-99/Camera2-Probe/releases/download/v0.7.0/TRI-CAM-v0.7.0.apk)**

GitHub Releases:

https://github.com/IKEGAMI-99/Camera2-Probe/releases/latest

v0.7.0 以降は固定署名された **release APK** を配布しています。対応バージョン間では、アプリの設定画面から最新版を確認して上書き更新できます。

## POCO F7 Ultra で確認済みの構成

| Camera | Physical ID | Focal length | Still JPEG |
|---|---:|---:|---:|
| ULTRA / 超広角 | 3 | 1.86 mm | 3280 × 2464 |
| MAIN / 標準 | 2 | 5.85 mm | 4096 × 3072 |
| TELE / 望遠 | 4 | 9.00 mm | 4096 × 3072 |

POCO F7 Ultra では以下を実機確認済みです。

- Logical Rear Camera から3つの Physical Camera を同時利用
- 3眼同時プレビュー
- 1920 × 1080 × 3 の常時プレビュー
- 2880 × 2160 × 3 で約30 fpsの同時ストリーム
- 3眼同時最大解像度JPEG撮影
- 撮影後の3眼プレビュー自動復帰
- MAIN / TELE のAF制御
- ULTRA の固定焦点動作

> 他のAndroid端末での動作は Camera HAL / Logical Multi Camera の公開状況に依存します。

## 主な機能

### 3眼同時プレビュー

超広角・標準・望遠を同時表示します。

- ULTRA
- MAIN
- TELE
- 3:4ベースのプレビューカード
- アスペクト比を維持したセンタークロップ
- 縦画面 / 横画面対応
- FPS表示のON / OFF

### 3眼同時撮影

1回のシャッター操作で3つのPhysical Cameraを同時撮影します。

保存方式は設定から個別に切り替え可能です。

- **個別3枚を保存**
- **横並びマージを保存**

マージ画像は以下の順です。

`ULTRA → MAIN → TELE`

両方ONの場合は、個別3枚 + マージ1枚の合計4枚を保存します。

### AF

- MAIN / TELE のAFに対応
- `ALL AF` ボタンでフォーカス可能な全カメラを同時に再AF
- ULTRA はPOCO F7 Ultraでは固定焦点
- タッチAFは使用しません

### 共通露出補正

3つのカメラへ同じ露出補正値を適用します。

- 3眼共通EV補正
- **1/3 EVステップ**
- 各1/3 EVポイントへスナップ
- 設定値を保存

### ギャラリー

ギャラリーボタンから端末のネイティブギャラリーを開きます。

POCO / Xiaomi / HyperOS では Xiaomi Gallery (`com.miui.gallery`) を優先します。

### アプリ内アップデート

設定画面の **アップデート確認** から GitHub Releases の最新版を確認できます。

最新版がある場合は、

1. APKをダウンロード
2. パッケージ名・バージョン・署名を検証
3. Androidインストーラを起動
4. 既存アプリへ上書き更新

という流れで更新します。

## UI

TRI // CAM は、紫・シアン・グリーンをアクセントにしたダークSF/HUD系UIです。

- MAIN: Purple
- ULTRA: Cyan
- TELE: Green
- 3レンズをモチーフにしたアプリアイコン
- シャッター時のフラッシュ / リングエフェクト
- 縦画面 / 横画面専用レイアウト

## 保存先

撮影したJPEGは Android の MediaStore を通して以下に保存します。

`Pictures/Camera2Probe/`

## ビルド

Android Studio または Gradle からビルドできます。

```bash
gradle assembleRelease
```

GitHub Actions でも release APK を自動生成し、GitHub Releases に公開します。

## 動作環境

- Android 9 (API 28) 以上
- Camera2 API
- Logical Multi Camera 対応端末
- 3つ以上のリアPhysical Cameraを公開している端末を推奨

### 実機検証端末

- **POCO F7 Ultra**
- Snapdragon 8 Elite
- HyperOS / Android

## 技術概要

TRI // CAM は1つのLogical Rear Cameraから、Physical Cameraごとに `OutputConfiguration.setPhysicalCameraId()` を割り当てています。

概念的には以下の構成です。

```text
Logical Rear Camera
        |
        +-- Physical ULTRA ---- Preview / JPEG
        +-- Physical MAIN ----- Preview / JPEG
        +-- Physical TELE ----- Preview / JPEG
```

端末側のCamera HALが許可する場合、3つのプレビューと3つのJPEG出力を同一 `CameraCaptureSession` に保持し、AF / AE / AWB の状態を維持したまま静止画撮影を行います。

## 注意

このアプリはPOCO F7 Ultraを中心に開発・検証しています。

Camera2 のマルチカメラ機能は、SoC自体が対応していてもメーカーのCamera HALがPhysical Cameraを公開していない場合があります。そのため、別端末で同じ3眼同時動作が保証されるわけではありません。
