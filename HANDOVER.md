# QuickCallMacro 인수인계 문서 (v4 - 빌드 + GitHub 배포 완료 시점)

> 작성일: 2026-04-25
> 상태: v1.0.0 Release 발행 완료, 자동 빌드 동작 검증 완료, **실기기 테스트 미수행**

## 한 줄 요약
Android Accessibility 기반 퀵서비스 콜잡이 매크로 앱. 빌드 가능 / GitHub Public 저장소 / Release 자동화 / 다운로드 URL까지 확보된 상태. 다음 단계는 실기기 검증과 튜닝.

## 환경
- 사용자: 한국어
- PC: Windows 11, JDK 21 (Microsoft), Android SDK `D:\Android\Sdk`
- 작업 경로: `D:\SNH\Develop\QuickCallMacro`
- gh CLI: 로그인 됨 (계정 `snh1217`)

## 저장소 / 다운로드
- 저장소: https://github.com/snh1217/QuickCallMacro (Public, main)
- Release: https://github.com/snh1217/QuickCallMacro/releases/tag/v1.0.0
- APK 직접 다운로드: https://github.com/snh1217/QuickCallMacro/releases/download/v1.0.0/QuickCallMacro-v1.0.0.apk

## 기술 스택
AGP 8.2.2 / Kotlin 1.9.22 / Gradle 8.5 / compileSdk 34 / minSdk 26 / ViewBinding / OpenCV 미사용

## 빌드 / 실행 방법
```bash
cd D:\SNH\Develop\QuickCallMacro
./gradlew.bat --no-daemon assembleDebug
```
APK 출력: `app/build/outputs/apk/debug/app-debug.apk`

`local.properties`는 .gitignore 처리됨. 다른 PC에서는 `sdk.dir=` 한 줄 설정 필요.

## 자동 배포 흐름
1. 코드 수정 → `git push`
2. `git tag vX.Y.Z && git push origin vX.Y.Z`
3. GitHub Actions가 약 2-3분 안에 APK 빌드 + Release 자동 생성

워크플로우 파일: `.github/workflows/build-release.yml` (Node 20 기반 액션, 2026-09-16 전 업데이트 예약됨)

## 코드 구조 (현재 시점)
```
app/src/main/java/com/quickcall/macro/
├── App.kt                    # Application, PrefsManager 초기화
├── MainActivity.kt           # 권한/모드/필터 설정 UI (ViewBinding)
├── CallMacroService.kt       # 핵심 AccessibilityService (모드 1/2 구현)
├── ScreenCaptureService.kt   # MediaProjection 폴백 (on-demand 캡처)
├── ImageMatcher.kt           # 색상 시그니처 매칭 (OpenCV 미사용)
├── ImageMatchBridge.kt       # AccessibilityService↔ScreenCaptureService 브리지
├── OverlayService.kt         # 화면 위 ON/OFF 토글 버튼
├── StopModalService.kt       # 모드 2 시퀀스 동작중 중지 모달
└── PreferencesManager.kt     # SharedPreferences 래퍼 + MacroMode enum
```

## 동작 모드
### 모드 1 (즉시 추적, 기본값)
콜 상세 화면 등장 → "확정추적" 즉시 탭. 좌표 캐싱 사용 (다음 회차부터 노드 탐색 스킵).

### 모드 2 (홀드 후 추적)
콜 상세 화면 등장 → "확정" 즉시 탭 → 1초 간격으로 5초간 "확정" 반복 → 마지막에 "확정추적" 1회 탭.
- 도중에 콜 상세 화면 이탈 시 자동 시퀀스 중단
- 사용자가 화면 상단 중지 모달의 [중지] 누르면 즉시 중단
- 모드 2는 좌표 캐싱 안 함 (UI가 중간에 변할 수 있어서)

## 가장 중요한 미검증 이슈 (실기기 테스트 시 1순위)

### 1. OEM별 접근성 ID 형식
[MainActivity.isAccessibilityEnabled()](app/src/main/java/com/quickcall/macro/MainActivity.kt#L137) 가 삼성/LG/샤오미 등에서 ID 표기 다를 가능성. Settings.Secure 직접 조회 + AccessibilityManager 폴백 2단으로 깔아뒀지만 실기기에서 검증 필요.

### 2. 타겟 앱이 Compose/Canvas 렌더링이면 노드 탐색 실패
이때 ImageMatcher 폴백으로 가는데, 색상 임계값이 실제 앱 화면과 다를 수 있음:
- `isRedText`: R>150, G<90, B<90, R-G>60, R-B>60
- `isDarkBg`: R,G,B 모두 <70
- 영역: 화면 하단 25% (y=0.75~0.97)
- 임계값: redRatio>0.005 AND darkRatio>0.30
실기기 화면 캡처 받아서 조정 필요. [ImageMatcher.kt](app/src/main/java/com/quickcall/macro/ImageMatcher.kt)

### 3. 모드 2 "확정" 노드 정규식
`^확정(\s*\(\s*\d+\s*\))?\s*$` 로 `확정`, `확정(8)` 패턴만 잡음. 실제 D61 앱이 `즉시확정`, `확정 (8명)` 같은 표기를 쓰면 [CallMacroService.findConfirmNode()](app/src/main/java/com/quickcall/macro/CallMacroService.kt#L184) 보강 필요.

### 4. dispatchGesture duration=1ms
이론적으로 가장 빠르지만 일부 OEM(특히 갤럭시)에서 너무 짧아 입력 무시 가능성. 만약 탭이 안 먹히면 `tap()` 함수의 stroke duration을 10ms로 올려 시도.

## 알려진 마이너 이슈 (당장 영향 없음)
- [ScreenCaptureService.kt:67](app/src/main/java/com/quickcall/macro/ScreenCaptureService.kt#L67) `getParcelableExtra(String)` deprecated 경고 (API 33+). 동작엔 영향 없음. 향후 `getParcelableExtra(name, Intent::class.java)` 분기 처리 권장.
- GitHub Actions 액션들이 모두 Node 20 기반. 2026-08-15 자동 PR 예약 걸려있음 (routine `trig_01W8BhfG7GFmWt2wiAiN3HDC`).
- 디버그 키로 서명된 APK. 정식 배포 시 release keystore 작업 필요.

## 추천 다음 작업 (우선순위 순)

### A. 실기기 검증 (필수, 다른 작업 전 선행)
1. APK 폰 설치 → 접근성/오버레이/캡처 권한 차례로 ON
2. 매크로 ON 상태에서 D61 앱 콜 상세 화면이 뜰 때 Logcat 필터 `CallMacroService` 로 어느 경로(노드/캐시/이미지)로 잡히는지 관찰
3. 좌표가 안 잡히면 → 노드 매칭 실패 → 이미지 폴백 진입 → 실패 시 ImageMatcher 임계값 조정
4. 모드 2 → 5초 시퀀스 정상 동작 + 화면 이탈 시 즉시 중단되는지 확인
5. 모드 2 → 화면 상단 중지 모달 표시/터치 정상 동작 확인

### B. 안전장치 추가 (실기기 검증 후)
- "자동중지" 버튼 자동 감지 → 매크로 자동 OFF (인수인계 v1 문서에 권장사항으로 적힌 것)
- 시간대별 자동 ON/OFF (예: 새벽 시간 자동 비활성)
- 일정 시간 내 N번 이상 탭 시 자동 정지 (오작동 방지)
- 매크로 통계 화면 (오늘 탭 수, 마지막 매칭 경로)

### C. 코드 품질
- ScreenCaptureService getParcelableExtra deprecated 경고 해소
- ImageMatcher unit test 추가 (실기기에서 받은 화면 캡처 PNG로 회귀 테스트)
- Mode 2 시퀀스 상태머신 정리 (현재 sequenceRunning + Runnable 리스트로 관리, 약간 fragile)

### D. 배포
- Release keystore 만들고 release APK 빌드 (Play Store 배포 안 한다면 디버그도 충분)
- README.md에 다운로드 배지 추가
- v1.0.0 release notes에 한글 변경사항 더 풍성하게

## 사용자 정보 (메모리)
- 한국어 사용자
- Android 매크로 앱 개발 첫 시도, PC + 폰 페어 환경
- 자동화/배포 파이프라인 선호 (이미 GitHub Actions 도입함)
- 매크로 동작 모드를 명시적으로 두 가지로 분리하는 식의 UX 결정에 적극적

## 예약된 백그라운드 작업
- **2026-08-15 09:00 KST**: Node 20→24 액션 마이그레이션 PR 자동 오픈
  - routine ID: `trig_01W8BhfG7GFmWt2wiAiN3HDC`
  - 페이지: https://claude.ai/code/routines/trig_01W8BhfG7GFmWt2wiAiN3HDC

## 다음 Claude에게 (요약)
1. 사용자가 실기기 결과를 가지고 오면 → A 항목부터 진행
2. 그 외 새 기능 요청 → B 항목 우선순위 참고
3. 코드 변경 후 항상 `./gradlew.bat --no-daemon assembleDebug` 통과 확인 → 푸시
4. 새 버전 배포 시 `git tag vX.Y.Z && git push origin vX.Y.Z` 한 줄로 끝
5. **빌드 가능 상태를 깨지 않을 것** — wrapper(gradle-wrapper.jar 포함), .gitattributes(LF/CRLF), local.properties(자동 생성 필요) 변경 시 주의
