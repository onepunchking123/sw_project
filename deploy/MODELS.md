# AI 모델 파일 정리

서버(AWS)에는 모델이 필요 없다. 낙상 감지 추론은 카메라 역할 폰 안에서 돌고, 모델 파일은 APK 에 포함된다.
서버는 감지 결과(이벤트 메타데이터)와 영상 연결 제어만 처리한다.

## 배포되는 모델 (APK 에 포함, `CareGuardCamera/app/src/main/assets/`)

| 파일 | 크기 | SHA-256 앞 16자리 | 역할 |
|---|---|---|---|
| `yolo11n-pose.onnx` | 11.9MB | `b208ef568d6b6fec` | 사람 검출 + 17개 관절 추정 (Ultralytics YOLO11n-pose 를 ONNX 로 변환) |
| `gru.onnx` | 0.73MB | `4e8ed3c70d639e77` | 2초 창의 관절 위치·속도 시퀀스로 정상/낙상/쓰러짐 분류 |
| `model-manifest.json` | 1.5KB | — | 입력·출력 계약, 해시, 전처리/후처리 규칙 (버전 1) |

앱은 시작 시 매니페스트의 해시와 실제 파일을 대조한다. 모델을 바꾸면 매니페스트의 해시도 함께 갱신해야 한다.

## 학습 원본 (저장소 루트)

| 파일 | 크기 | git | 설명 |
|---|---|---|---|
| `models/gru.pt` | 0.73MB | 커밋됨 | 배포 모델 v1 (posvel, 5채널). `gru.onnx` 의 원본. 2026-10-04 parity 검사에서 ONNX 와 로짓 최대 차이 4e-7 로 일치 확인 |
| `models/gru_v5b.pt` | 0.73MB | 커밋됨 | 합성 lying 데이터 실험(v5b) 결과. 배포하지 않음 |
| `models/gru_pos.pt` | 0.68MB | 커밋됨 | 좌표만 쓴 이전 모델. 비교용 |
| `yolo11n-pose.pt` | 6.3MB | 제외 | Ultralytics 원본 가중치. ONNX 변환 입력 |
| `runs/` | 1.2GB | 제외 | 학습 실험 산출물 (v1~v5, vel). Drive 보관 대상 |
| `aihub_model/` | 100MB | 제외 | AI Hub 공식 베이스라인 (제3자 산출물) |
| `data/` | 5.3GB | 제외 | 데이터셋. AI Hub 재배포 금지 |

## 검증 명령

```sh
cd fall-detection
./venv_aihub/bin/python scripts/live_model_parity.py --probe-onnx --golden   # 매니페스트 해시·입출력 계약
./venv_aihub/bin/python scripts/live_model_parity.py --compare-gru           # gru.onnx 와 models/gru.pt 로짓 비교
```

## 서버 추론으로 바꾸는 경우

지금 구조에서는 해당 없음. 폰 성능 문제가 실기기에서 확인될 때만 검토한다 (`CareGuardCamera/LIVE_VIDEO_MVP.md`).
그때는 `yolo11n-pose.onnx`, `gru.onnx`, `model-manifest.json` 세 파일과 `scripts/pipeline_core.py` 의 전처리 규칙을 서버로 옮기면 된다.
