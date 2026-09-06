# Camera2 Probe

POCO F7 Ultra などの Android 端末で、Camera2 API が公開しているカメラ構成とマルチカメラ機能を実機検証するための診断アプリです。

## 現在の機能

- Camera2 の全 Camera ID を列挙
- Lens Facing / Hardware Level / Logical Multi Camera capability を表示
- Logical Camera 配下の Physical Camera ID を表示
- `CameraManager.concurrentCameraIds` を表示
- Logical rear camera に 3 個以上の Physical Camera がある場合、3 Physical Camera 同時 640x480 プレビューを試行
- 実行ログを画面内に表示

## 目的

POCO F7 Ultra の超広角・メイン・望遠を Android の公開 Camera2 API から同時利用できるかを推測ではなく実機で確認します。

最初は低負荷な 640x480 × 3 で検証し、成功後に 720p / 1080p / 静止画保存へ段階的に拡張します。
