# QuickCallMacro (콜잡이)

Android Accessibility Service 기반의 "확정추적" 자동 탭 매크로.

## 다운로드 (즉시 설치)

[![Latest Release](https://img.shields.io/github/v/release/snh1217/QuickCallMacro)](https://github.com/snh1217/QuickCallMacro/releases/latest)

폰 브라우저로 [Releases](https://github.com/snh1217/QuickCallMacro/releases/latest) 페이지의 `.apk` 파일 다운로드 → "출처를 알 수 없는 앱 설치" 허용 → 설치.

## 동작 방식

```
[화면 변경 이벤트] → [노드 탐색: 모드별 타겟] → [좌표 추출 → 즉시 탭]
                          ↓ (실패)
                  [이미지 폴백: 색상 시그니처 매칭]
                          ↓
                  [좌표 → AccessibilityService dispatchGesture]
```

### 두 가지 동작 모드
- **모드 1 (즉시 추적)** — 콜 상세 화면 진입 즉시 "확정추적" 탭. 좌표 캐싱 사용.
- **모드 2 (홀드 후 추적)** — "확정" 즉시 탭 → 사용자 설정 간격으로 반복 → 홀드 시간 후 "확정추적" 1회 탭.
  - 홀드 시간 1~10초 / 탭 간격 0.3~2초 슬라이더로 조절 (기본 5초 + 1초)
  - 화면 이탈 시 시퀀스 자동 중단
  - 화면 상단 [중지] 모달로 즉시 취소 가능

### 공통
- **하이브리드 매칭**: Accessibility 노드 우선, 실패시 MediaProjection 캡처 + 이미지 매칭
- **즉시 탭**: `dispatchGesture duration=1ms`
- **거리/요금 필터**: 옵션
- **구 필터(슬롯 5개)**: 시/군/구 + 동/읍/면 세부 선택 → 도착지가 슬롯 범위일 때만 동작 (전국 법정동 데이터 내장)
  - 도착지 추출은 "도착" 라벨 위치 기준 4단 폴백 (라벨박스 → 박스영역 → 슬라이스 → 슬래시)
  - 후보 리스트 + 다중 키 정규화 매칭 — 결합 표기("서초반포동"), 통칭("동탄"/"광교"/"위례"), 짧은 동명("유방동") 모두 커버
  - 시군구 체크 시 모든 동 자동 선택 → 사용자가 빼고 싶은 동만 해제 가능 (예: 화성시에서 동탄만 활성, 봉담/향남 해제)
- **오버레이 토글**: 화면 위 원형 버튼 (드래그 이동, 탭으로 시작/정지)
- **디버그 토스트**: 매칭 경로(노드/캐시/이미지/구필터)를 토스트로 표시 — 튜닝 시 활용
- **앱 종료 시 자동 OFF**: 최근앱에서 스와이프해 닫으면 매크로도 정지

## 시작/정지 흐름

권한 부여와 매크로 실행은 분리되어 있습니다. 권한만 켰다고 매크로가 도는 게 아닙니다.

매크로 시작 진입점:
- 메인 화면의 큰 [매크로 시작] 버튼
- 화면 위 오버레이 토글 (OFF → ON)

시작 시: 접근성/오버레이 권한 검증 → 캡처 권한 다이얼로그 1회 → 매크로 ON.
정지 시: 캡처 서비스 같이 정지, 진행 중 시퀀스 즉시 취소.

폰 재부팅 후엔 항상 OFF 상태로 시작합니다 (안전장치).

## 빌드 방법

### 1. Android Studio 로 열기
1. Android Studio 최신 버전 설치 (Hedgehog 이상 권장)
2. **File → Open** → `QuickCallMacro` 폴더 선택
3. Gradle Sync 자동 실행 (인터넷 필요)
4. 처음 열면 Gradle wrapper 가 없을 수 있음 → 터미널에서:
   ```
   gradle wrapper
   ```
   또는 Android Studio 가 자동 생성

### 2. 폰에 설치
1. 폰 → 개발자 옵션 → USB 디버깅 ON
2. USB 로 PC 연결
3. Android Studio 상단 ▶ Run 버튼

### 3. APK 만 뽑고 싶다면
```
./gradlew assembleDebug
```
→ `app/build/outputs/apk/debug/app-debug.apk` 생성

## 사용 순서

1. 앱 실행
2. **접근성 권한 열기** → 설정에서 "콜잡이 매크로" 켜기
3. **오버레이 권한 열기** → 다른 앱 위에 표시 허용
4. (옵션) **화면 토글 버튼 켜기** → 화면에 원형 버튼 등장
5. 동작 모드 / 필터 / 디버그 토스트 설정 → **저장**
6. **[매크로 시작]** 버튼 탭 → 캡처 권한 다이얼로그 허용 → 매크로 ON

이제 콜 상세 화면이 뜨면 모드 설정대로 자동 동작합니다. 정지하려면 [매크로 정지] 또는 화면 위 토글 OFF.

## 파일 구조

```
app/src/main/java/com/quickcall/macro/
├── App.kt                    Application (콜드 스타트 시 enabled=false)
├── MainActivity.kt           설정 UI + 시작/정지 버튼
├── MacroController.kt        시작/정지 로직 중앙화 (권한 검증 + 캡처 요청)
├── CallMacroService.kt       핵심: Accessibility + 노드 탐색 + 모드 1/2 시퀀스
├── ScreenCaptureService.kt   MediaProjection 폴백 (on-demand 캡처)
├── ImageMatcher.kt           색상 시그니처 매칭
├── ImageMatchBridge.kt       Accessibility ↔ Capture 다리
├── OverlayService.kt         화면 위 토글 버튼 (시작/정지 진입점)
├── StopModalService.kt       모드 2 시퀀스 동작중 표시되는 [중지] 모달
└── PreferencesManager.kt     설정값 저장소 + MacroMode enum
```

## 주요 튜닝 포인트

### 더 빠르게 하고 싶다면
- `CallMacroService.tapCooldownMs` (기본 1500ms) 축소
- `findTargetNode` 의 secondary 키워드 리스트 단축

### 오탐이 많다면
- `ImageMatcher.isRedText` 의 RGB 임계값 조정
- `isDarkBg` 임계값 조정
- 필터(거리/요금)를 켜서 의도 외 콜은 거르기

### 다른 텍스트도 누르고 싶다면
- 메인 화면 "탐지할 텍스트" 입력란을 변경 (예: "확정", "수락" 등)

## 알려진 한계

- **앱이 자체 OpenGL/Canvas 렌더링** 이라 노드가 안 잡히면 이미지 폴백에 의존
- **MediaProjection 권한** 은 폰 재부팅이나 앱 재실행 시 매번 새로 받아야 함 (재부팅 시 매크로는 자동 OFF 로 시작)
- **탐지/규제** 가능성 — 사용자가 자기 책임 하에 사용
- 디버그 키 서명 APK — 정식 배포는 아님

## 개발 메모

- minSdk 26 (Android 8.0)
- targetSdk 34 (Android 14)
- Kotlin 1.9.22, AGP 8.2.2, Gradle 8.5
- OpenCV 의존성 없음 (색상 시그니처 매칭으로 대체)
- 행정구역 데이터: 법정동코드 전체자료 ([FinanceData gist](https://gist.github.com/FinanceData/4b0a6e1818cea9e77496e57b84bb4565))를 `scripts/build-districts.js`로 가공해 `assets/districts.json` 으로 번들 (~33KB)
- 데이터 갱신: `node scripts/build-districts.js` 다시 실행
