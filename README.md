# Camera2 Probe

POCO F7 Ultra などの Android 端末で、Camera2 API が公開しているカメラ構成とマルチカメラ機能を実機検証するための診断アプリです。

## 現在の機能

- Camera2 の全 Camera ID を列挙
- Lens Facing / Hardware Level / Logical Multi Camera capability を表示
- Logical Camera 配下の Physical Camera ID を表示
- `CameraManager.concurrentCameraIds` を表示
- Logical rear camera に 3 個以上の Physical Camera がある場合、3 Physical Camera 同時 640x480 プレビューを試行
- 実行ログを画面内に表示

## 実機テスト手順

1. GitHub Actions の `Build APK` から `Camera2-Probe-debug` artifact を取得してインストール
2. カメラ権限を許可
3. `SCAN` を実行
4. `Logical rear ID` と 3 個以上の `physicalIds` が表示されるか確認
5. 候補が見つかったら `START 3-CAM` を押す
6. 3画面が同時に動き、ログに `TRIPLE SESSION: SUCCESS` が出れば公開 Camera2 API だけで3物理カメラ同時ストリームが成立

失敗した場合も `CONFIGURE FAILED`、CameraDevice error、公開された physical IDs / concurrent sets がログに残るため、次の実装方針を判断できます。

## 目的

POCO F7 Ultra の超広角・メイン・望遠を Android の公開 Camera2 API から同時利用できるかを推測ではなく実機で確認します。

最初は低負荷な 640x480 × 3 で検証し、成功後に 720p / 1080p / 静止画保存へ段階的に拡張します。
