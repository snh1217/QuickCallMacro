# QuickCallMacro (콜잡이)

Android Accessibility Service 기반의 "확정추적" 자동 탭 매크로.

## 동작 방식

```
[화면 변경 이벤트] → [노드 탐색: "확정추적"] → [좌표 추출 → 즉시 탭]
                          ↓ (실패)
                  [이미지 폴백: 색상 시그니처 매칭]
                          ↓
                  [좌표 → AccessibilityService dispatchGesture]
```

- **하이브리드 매칭**: Accessibility 노드 우선, 실패시 MediaProjection 캡처 + 이미지 매칭
- **즉시 탭**: 디바운스 1.5초 외에는 지연 없음 (`duration=1ms`)
- **좌표 캐싱**: 한 번 찾은 버튼 좌표는 SharedPreferences 에 저장, 다음번엔 매칭 스킵
- **필터**: 거리(km) / 요금(원) 기준으로 조건 통과 시에만 탭
- **오버레이 토글**: 화면 위 원형 버튼 (드래그 이동, 탭 ON/OFF)

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
4. **화면 토글 버튼 켜기** → 화면에 원형 버튼 등장
5. 메인 앱으로 돌아오면 MediaProjection 권한 다이얼로그 → 허용 (이미지 폴백용)
6. 필터 조건 입력 → **저장**
7. 화면 위 토글 버튼 탭 → **ON** (초록색) 으로 변경

이제 콜 화면이 뜨면 자동으로 "확정추적" 버튼이 눌립니다.

## 파일 구조

```
app/src/main/java/com/quickcall/macro/
├── App.kt                    Application (PreferencesManager 초기화)
├── MainActivity.kt           설정 UI
├── CallMacroService.kt       핵심: Accessibility + 노드 탐색 + 탭
├── ScreenCaptureService.kt   MediaProjection 폴백 (on-demand 캡처)
├── ImageMatcher.kt           색상 시그니처 매칭
├── ImageMatchBridge.kt       Accessibility ↔ Capture 다리
├── OverlayService.kt         화면 위 토글 버튼
└── PreferencesManager.kt     설정값 저장소
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
- **MediaProjection 권한** 은 폰 재부팅이나 앱 재실행 시 매번 새로 받아야 함
- **탐지/규제** 가능성 — 사용자가 자기 책임 하에 사용

## 개발 메모

- minSdk 26 (Android 8.0)
- targetSdk 34 (Android 14)
- Kotlin 1.9.22, AGP 8.2.2, Gradle 8.5
- OpenCV 의존성 없음 (색상 시그니처 매칭으로 대체)
