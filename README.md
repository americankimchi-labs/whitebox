# White Box (화이트박스)

사고와 분쟁의 순간 운전자가 실제로 한 조작(브레이크·방향지시등·조향·기어·속도·위치)을 차량이 **위변조 불가능하게** 기록하는 Pleos Connect 앱입니다.
모든 기록은 SHA-256 해시 체인으로 연결되고, 급제동·충격이 감지되면 사고 전 최대 120초를 **기기 서명으로 봉인한 캡슐**로 남깁니다.
기록은 운전자 본인의 증거이며, 한 바이트만 바뀌어도 검증이 실패합니다.

> 2026 HMG App Dev Contest 출품작 · 패키지 `com.americankimchilabs.whitebox`

---

## 실행 방법

### 방법 1: APK 설치 (권장)

```bash
adb install -r apk/americankimchi_WhiteBox_release.apk
adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity
```

디버그 빌드가 이미 설치되어 있으면 서명이 달라 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 나므로 먼저 `adb uninstall com.americankimchilabs.whitebox`를 실행합니다.
앱 목록의 **White Box** 아이콘으로 실행해도 됩니다. 첫 실행 시 주차(P) 상태에서 안내 화면 → 권한 4개(차량 속도, 위치, 위치 '항상 허용', 알림) 순서로 진행됩니다.

### 방법 2: 소스 빌드

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- JDK 17, Android SDK 35가 필요합니다. SDK 위치는 `ANDROID_HOME` 환경변수 또는 `local.properties`의 `sdk.dir`로 지정합니다.
- Pleos Vehicle SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받으므로 네트워크가 필요합니다.
- 단위 테스트: `./gradlew test` (39개 — 에스컬레이션 엔진, 해시 체인 내구성·변조 속성 테스트, 데모 주행 엔드투엔드 재생)
- 릴리스 빌드는 `local.properties`의 서명 정보를 읽습니다(`app/build.gradle.kts` 주석 참고). 서명 정보가 없으면 서명되지 않은 릴리스가 만들어지고 디버그 빌드에는 영향이 없습니다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (API 34 Automotive, `pleos_car_emulator` arm64) |
| 부팅 | **Cold boot** (Quick boot는 VHAL을 비활성화함) |
| AVD RAM | 4 GB |
| 화면 | 2560×1440, 160 dpi |
| Vehicle SDK | `ai.pleos.playground:Vehicle:2.3.2` |
| 빌드 | JDK 17 · Gradle 8.10.2 · AGP 8.8.2 · Kotlin 2.0.0 · compileSdk/targetSdk 35 · minSdk 28 |
| 앱 사용자 | 헤드리스 시스템 사용자 0 위의 운전자 사용자 10 |

## 데모

### A. 데모 재생 (에뮬레이터 신호 없이, 녹화용)

앱 하단 **데모 재생** 버튼, 또는:

```bash
adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source REPLAY
```

약 70초짜리 주행이 재생됩니다: 출발 → 좌측 차선 변경(방향지시등) → 일반 제동 → 우측 차선 변경 → **급제동(경보)** → **충돌 프로파일 50→25 km/h (사고 감지·봉인)** → 정차 10초 후 최종 봉인. 재생 기록은 실차 기록과 별도의 체인에 저장됩니다.

### B. 실시간 (Virtual Console)

같은 시나리오를 에뮬레이터의 Virtual Console HTTP API로 실제 차량 신호 경로(VHAL → SDK → 앱)를 통해 주입합니다.

```bash
adb forward tcp:18088 tcp:8088
tools/virtual-console/heartbeat.sh &        # 콘솔 세션 유지 (콘솔 페이지를 열어둔 것과 동일)
adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source LIVE
python3 tools/virtual-console/demo.py
```

### C. 변조 검증

```bash
tools/tamper-demo/run.sh
```

에뮬레이터에서 실제로 기록된 체인(`docs/artifacts/live-run2-demo`)을 앱과 같은 검증 코드로 확인합니다: 원본은 **PASS**, 속도 값 1바이트를 바꾼 사본은 해당 레코드에서 **FAIL**, 이후 해시를 모두 다시 계산한 위조본도 서명된 봉인과 헤드가 달라 **FAIL**.

## 에뮬레이터 한계 (정직하게)

- **에어백 신호 없음**: v2.1.0 이미지의 VHAL에는 `SEAT_AIRBAGS_DEPLOYED` 속성이 없고(SDK 호출은 `ERROR_NOT_SUPPORTED_PROPERTY`), Virtual Console에도 에어백 카드가 없습니다. 그래서 시연은 **급제동 트리거**(브레이크 ON + 속도 급감)로 합니다. 에어백 경로는 실차용으로 코드에 남아 있습니다.
- **ABS 경고등은 읽을 수 없음**: 경고등 조회는 실행 시 `PRIVATE_CAR_SAFETY` 권한을 요구합니다(인터페이스 문서의 `CAR_SAFETY`와 다름). 그래서 트리거에 쓰지 않습니다.
- **콘솔 페달만으로는 사고 감지가 되지 않음**: 콘솔의 제동 물리 모델은 최대 42 km/h/s(약 1.2 g)로, 실제 최대 제동과 같은 수준이라 일부러 사고 기준(16 km/h/300 ms)에 못 미칩니다. 시연 스크립트는 콘솔 API로 속도 급감을 직접 주입합니다. 콘솔의 `speed` 값은 **정수 km/h**만 받습니다.
- **설치 직후 재부팅은 70초 이상 기다릴 것**: 설치 직후의 앱은 'stopped' 상태라 부팅 브로드캐스트를 받지 못하고, 첫 실행으로 풀린 상태가 저장되기까지 시간이 걸립니다(7.8초 뒤 재부팅 시 재현, 70초 뒤에는 정상). '강제 종료'한 경우에도 앱을 다시 열 때까지 자동 시작되지 않으며, 그 공백은 다음 기록 시작 시 체인에 남습니다.
- 공식 가이드에 따르면 실차는 시동을 끄면 완전히 종료되지 않고 저전력 Suspend 모드로 들어갑니다. 이 에뮬레이터 이미지는 시동을 끄면 완전 종료하므로 Suspend·Resume 동작은 에뮬레이터로 검증할 수 없습니다.
- 시스템 권한 창의 문구는 에뮬레이터 언어 설정을 따릅니다(영어 설정에서는 'Allow all the time' = '항상 허용').
- 조향각 부호: SDK는 콘솔 각도를 반대 부호로 전달하며, 공식 문서에 부호 규약이 없어 증거 패킷은 좌/우 표기 없이 부호 있는 각도로만 표시합니다.

## 기록과 무결성

- **추가 전용 체인**: 이벤트마다 한 줄, 쓰기마다 fsync. 각 줄은 이전 줄의 SHA-256 해시를 포함합니다. 쓰는 도중 프로세스가 죽으면 마지막 미완성 줄만 버려집니다(테스트로 검증).
- **캡슐 봉인**: 사고 감지 즉시 임시 봉인(전 120초 포함), 정차 10초 뒤 최종 봉인. 봉인은 Android Keystore의 ECDSA P-256 키로 서명합니다.
- **기록 공백도 기록**: 서비스 재시작(재시도 횟수 포함), 권한 저하·복구, 강제 종료 후 복구가 체인에 남아 타임라인에서 "기록하지 못한 시간"이 보입니다.
- 실제 기록 체인: [`docs/artifacts/`](docs/artifacts/README.md)

## 사용 SDK와 권한

| 사용 | 권한 |
|---|---|
| Vehicle SDK 2.3.2: 브레이크, 방향지시등, 조향각, 에어백 | `pleos.car.permission.CAR_DRIVE`, `CAR_LIGHTS`, `CAR_SAFETY` (normal) |
| AAOS CarPropertyManager: 속도, 기어 | `android.car.permission.CAR_SPEED` (런타임), `CAR_POWERTRAIN` |
| 플랫폼 위치 | 위치, 위치 '항상 허용' |
| 부팅 시 자동 기록 | `RECEIVE_BOOT_COMPLETED`, 포그라운드 서비스(`specialUse`, `location`) |

차량 **제어** API(`CONTROL_CAR_*`)는 사용하지 않습니다. 읽기 전용입니다.

## 모듈 구조

| 모듈 | 역할 |
|---|---|
| `:domain` | 순수 Kotlin. 에스컬레이션 엔진(정상→경보→사고 감지), 기록 공백·권한 판정 |
| `:data` | 추가 전용 해시 체인 저장소, 캡슐 봉인·검증, 기록 파이프라인 |
| `:vehicle-core` | 포트(인터페이스)만 |
| `:vehicle-pleos` | Pleos SDK·AAOS 어댑터 (Pleos 클래스는 이 모듈에서만 사용) |
| `:vehicle-fake` | 데모 주행 스크립트(재생 모드·테스트 공용) |
| `:app` | 포그라운드 기록 서비스, Compose UI |
| `tools/` | Virtual Console 스크립트, 변조 검증 CLI |
