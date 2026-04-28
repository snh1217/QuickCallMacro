# QuickCallMacro 인수인계 문서 (v11 - 모드 2 필터 우회 버그 + 자동중지)

> 작성일: 2026-04-29
> 상태: v1.0.7 도착지 필터 게이트 단일화, 슬롯 캐시 JSON 기준 무효화, 매 tap 재검증, 자동중지 자동 OFF, 단위 테스트 46건 (모두 통과)

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
app/src/main/
├── assets/districts.json     # 행정구역 데이터 (시도/시군구 → 동/읍/면)
└── java/com/quickcall/macro/
    ├── App.kt                # 콜드 스타트 시 enabled=false + DistrictRepository 사전 로드
    ├── MainActivity.kt       # 권한/모드/필터/디버그/구 설정 UI + 시작/정지 토글
    ├── MacroController.kt    # 시작/정지 로직 중앙화 (권한 검증 → 캡처 → 오버레이 시작)
    ├── CallMacroService.kt   # 핵심 AccessibilityService (모드 1/2 + 구 필터 + 도착지 파싱)
    ├── ScreenCaptureService.kt # MediaProjection 폴백 + onTaskRemoved 자동 정지
    ├── ImageMatcher.kt       # 색상 시그니처 매칭
    ├── ImageMatchBridge.kt   # Accessibility ↔ Capture 브리지
    ├── OverlayService.kt     # 오버레이 토글 + prefs 동기화 + onTaskRemoved 자동 정지
    ├── StopModalService.kt   # 모드 2 중지 모달
    ├── PreferencesManager.kt # SharedPreferences + MacroMode + debugToast + 슬롯 5개
    ├── data/
    │   └── DistrictRepository.kt # assets 로드, expandKeysForSelection (DistrictKeyGenerator 위임)
    ├── parser/                  # 안드로이드 의존성 없음 (단위 테스트 가능)
    │   ├── BBox.kt              # 좌표 박스
    │   ├── ParseNode.kt         # AccessibilityNodeInfo 미러 트리
    │   ├── DongTokenExtractor.kt # 동/읍/면 토큰 추출 + 메타 토큰 필터
    │   ├── DestinationParser.kt # 3단 폴백 도착지 추출
    │   └── DistrictKeyGenerator.kt # 다중 키 정규화
    └── ui/
        ├── DistrictSettingsActivity.kt
        └── DistrictSlotEditActivity.kt

app/src/test/java/com/quickcall/macro/
└── DestinationMatchingTest.kt  # 19건 단위 테스트
```

## 시작/정지 흐름 (v1.0.1에서 재설계됨)

핵심 원칙: **권한 부여 ≠ 매크로 시작**. 명시적 시작 액션이 있을 때만 동작.

진입점:
- MainActivity의 [매크로 시작] 큰 버튼
- OverlayService의 토글 (OFF→ON)

흐름 (MacroController):
1. 접근성/오버레이 권한 검증 → 누락 시 토스트 + 권한 화면 띄우고 종료
2. 이미 enabled면 토스트 "이미 동작 중" 후 종료
3. ScreenCaptureService 미구동이면 MediaProjection 다이얼로그 1회 표시
4. 캡처 거절 시 → AlertDialog로 "폴백 없이 시작" / "취소" 선택
5. enabled=true + OverlayService 시작 (없으면) + 토스트 "매크로 동작 시작"

정지 시:
- enabled=false
- ScreenCaptureService 정지 (MediaProjection 자원 반환)
- 진행 중 시퀀스 cancelSequence()
- 토스트 "매크로 정지"

오버레이에서 시작 시 캡처 권한 미보유 상태면 → MainActivity를 EXTRA_REQUEST_START 플래그와 함께 띄워 거기서 처리.

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

## v1.0.7 변경 요약 (2026-04-29) — 긴급 버그 수정 + 자동중지

### 긴급 버그: 모드 2 가 도착지 필터를 우회
- **원인**: `passDistrictFilter` 의 슬롯 키 캐시가 `activeSlotId` 만 기준으로 무효화되었고, 캐시가 비어있으면 (`cachedSlotKeys.isEmpty() return true`) **모든 콜 통과**로 처리. 슬롯 내용을 편집해도 같은 ID 면 캐시가 갱신 안 됐고, 빈 셋이라도 `pass` 라서 모드2 시퀀스가 슬롯 범위 밖 콜에서도 시작됨.
- **수정**: 캐시 키를 `(activeSlotId, slotJson)` 페어로 변경 → 슬롯 편집 즉시 반영. 활성 슬롯이 0이 아닌데 슬롯 키 셋이 비어있으면 **차단**으로 정책 변경.
- **순수 필터 모듈** 분리: [DistrictFilter.kt](app/src/main/java/com/quickcall/macro/parser/DistrictFilter.kt) — 안드로이드 의존성 없음, 단위 테스트 가능
  - `Result.Pass(tier, tokens, matchedKeys)`
  - `Result.BlockEmptySlot` / `BlockParseFail` / `BlockNoMatch(tier, tokens, destKeys)`
- **모드 2 매 tap 재검증**: `tapConfirmNow` / `tapTrackOnceAndFinish` 진입 직후 `passDistrictFilter` 재호출. 시퀀스 도중 다른 콜로 바뀌어도 안전 차단.
- **디버그 토스트 일관화**:
  - "활성 슬롯 없음 → 통과"
  - "필터 통과 [1단:라벨박스]: 동탄 → [동탄]"
  - "필터 차단 [1단:라벨박스]: 강남역삼동 → [강남, 강남역삼, 역삼]"
  - "슬롯 비어있음 → 차단 (슬롯 편집 필요)"
  - "도착지 노드 미발견 → 차단"

### 자동중지 버튼 자동 감지
- accessibility_service_config 에 `typeViewClicked` 추가
- `onAccessibilityEvent` 가 `TYPE_VIEW_CLICKED` 분기 → `handleClickEvent`
- `event.text` / `contentDescription` / `source.text` / `source.contentDescription` 결합 텍스트에 "자동중지" 또는 "자동 중지" 포함 시 `MacroController.stop(this)` 호출 → 매크로 OFF

### 단위 테스트 46건 (7건 추가)
- DistrictFilterTest 7건:
  - 화성시 슬롯 + 강남 도착 → 차단
  - 화성시 슬롯 + 동탄 도착 → 통과
  - 슬롯 비어있음 → 차단
  - 도착지 추출 실패 → 차단
  - 통칭 동탄 도착 → 통과
  - 시퀀스 중 다른 콜로 바뀐 시뮬레이션 → 차단
  - 출발지에 매칭 가능한 동 있어도 도착지 기준만 → 차단

## v1.0.6 변경 요약 (2026-04-27)
- **모드 2 시간 사용자 설정**
  - 홀드 시간 슬라이더: 1.0~10.0초 (0.5초 단위, 기본 5.0초)
  - 탭 간격 슬라이더: 0.3~2.0초 (0.1초 단위, 기본 1.0초)
  - 모드 2 라디오 선택 시에만 슬라이더 카드 표시
  - PreferencesManager: `mode2HoldDurationMs` (1000~10000), `mode2TapIntervalMs` (≥300, 자동 클램프)
  - CallMacroService.mode2StartSequence 가 Pref 값 사용 (상수 제거)
  - 슬라이더 놓을 때 자동 저장 + 토스트 안내
- **ScreenCaptureService 헬스체크**
  - 30초 주기로 projection/virtualDisplay/imageReader 상태 확인
  - 1프레임 acquireLatestImage 성공 시 lastCaptureSuccessAt 갱신
  - 마지막 성공 후 60초 동안 실패 지속 시 "죽음" 판정 → 알림 발송 + stopSelf
  - 알림 (NOTIF_ID_CAPTURE_DEAD) 탭 → MainActivity 가 EXTRA_RESTORE_CAPTURE 인텐트 수신
  - MainActivity: 복구 다이얼로그 ("권한 허용" → MediaProjection 재요청)
- **단위 테스트 39건** (5건 추가)
  - Mode2TimingTest: 홀드/탭 조합별 확정 탭 횟수 검증 (5/3/1/10초 + 0.3/0.5/1.0초)
  - 모든 유효 조합에서 최소 1회 탭 보장
- **시퀀스 진입 디버그 토스트 강화**
  - "모드2 시퀀스 시작 (홀드 5000ms, 간격 1000ms)" — 현재 적용된 시간 노출

## v1.0.5 변경 요약 (2026-04-27)
- **통칭 도착지 매칭** — "동탄/광교/위례/송도" 같은 신도시·택지 통칭 콜 추출.
  - DongTokenExtractor 가 단일 토큰 반환 → **후보 리스트** (3-tier) 반환으로 변경
  - Tier 1: 동/읍/면/리/가/로 접미사 토큰 (가장 강함)
  - Tier 2: 슬래시 패턴 한글 토큰 (`/ 동탄 / *`)
  - Tier 3: 일반 한글 2~5자 (라벨/메타 단어 제외)
  - Outcome.tokens: List<String> (token 은 첫 번째 후보 alias)
  - 매칭은 모든 후보의 키 union vs 슬롯 키 셋 교집합
- **동/읍/면 세부 선택** — 슬롯 데이터 구조 변경.
  - 기존: `Set<시군구 path>` (시군구 단위)
  - 변경: `Map<시군구 path, Set<동 이름>>` (동 단위 세부 선택)
  - DistrictSlot 데이터 클래스 + JSON 직렬화 ([DistrictSlot.kt](app/src/main/java/com/quickcall/macro/data/DistrictSlot.kt))
  - DistrictRepository.normalizedKeysForSlot 신규 — 선택된 동만 키 생성
  - 화성시처럼 면적 큰 시에서 동탄만 선택, 봉담/향남 제외 가능
- **v1.0.4 → v1.0.5 자동 마이그레이션**
  - 첫 진입 시 기존 시군구 셋 → "그 시군구의 모든 동 선택" 상태로 변환
  - `migration_v105_done` 플래그로 1회만 수행
  - App.onCreate 백그라운드 스레드에서 DistrictRepository 로드 후 실행
- **슬롯 편집 UI 재구성** ([DistrictSlotEditActivity.kt](app/src/main/java/com/quickcall/macro/ui/DistrictSlotEditActivity.kt))
  - 3단 트리: 시도(▶) → 시군구 (3-state ☑ + 동 갯수) → 동 ☑
  - 시군구 체크박스 클릭 → 모든 동 토글 (전체체크 → 모두해제, 부분/없음 → 모두선택)
  - 동 체크박스 변경 → 시군구 3-state 자동 갱신
  - 동 행은 시군구 펼칠 때 lazy 생성 (5015개 동 한 번에 안 만듦)
  - 검색창 — 시군구 이름 + 동 이름 부분 매칭, 매칭 시 자동 펼침
  - MaterialCheckBox.checkedState (CHECKED / UNCHECKED / INDETERMINATE) 사용
- **슬롯 요약 표시 변경**
  - 기존: "슬롯 1: name (선택 3개)"
  - 신규: "슬롯 1: name (시군구 3개 / 동·읍·면 47개)"
- **단위 테스트 34건** (7건 추가)
  - 통칭 단독 토큰 추출 (동탄/광교/위례)
  - 라벨 단어 후보 제외
  - 동탄 콜 + 화성시 슬롯 매칭
  - 화성시 동탄만 선택 + 동탄 통과 / 향남 차단

## v1.0.4 변경 요약 (2026-04-26)
- **세로 라벨 그룹 대응**: 실기기에서 발견된 두 케이스 (성수동, 유방동) 처리.
  D61 화면이 `[도착]` 라벨 행 + 다음 행에 `[서명] / 도착지 / *` 형태 → 1단 라벨박스 흐름이 자식뿐 아니라 **후속 형제 박스**까지 탐색하도록 강화.
- **DestinationParser 4단으로 확장**:
  - 1단 (`라벨박스`): 라벨 박스 자체 + 후속 형제 박스들 (출발/픽업/의뢰 라벨 만나면 컷)
  - 2단 (`박스영역`): 라벨 박스 boundsInScreen 기준 — 우측/하단 영역 (다음 stop label 박스 top 까지)
  - 3단 (`슬라이스`): "도착" 단어 이후 200자 (출발/픽업/의뢰/물품/차량/탁송료/요금/구분/형태/적요/상세/인수증 만나면 컷)
  - 4단 (`슬래시`): 화면 전체에서 `/ XX동 / *` 패턴, **마지막 매치** 채택 (도착지가 출발지 아래)
- **메타 토큰 정책 변경** (전체 거부 → 토큰 단위 정제)
  - 추출된 후보 토큰들 중 메타 토큰만 제외, 남은 토큰들에서 동/읍/면 검색
  - 메타 정확 일치 확장: 서명, 픽업, 의뢰, 물품, 차량, 탁송료, 요금, 구분, 형태, 적요, 상세, 인수증
  - 메타 prefix는 동/읍/면 접미사로 끝나는 토큰에는 적용 안 함 (짧은 동명 보호)
- **SLASH/TOKEN regex 강화**: prefix 최소 2글자(`{2,}`)로 변경 → "바로", "신용" 같은 1글자 prefix + 1글자 suffix 가짜 매칭 차단
- **단위 테스트 27건** (8건 추가)
  - caseA 세로 라벨 그룹 + 성수동 (실기기 케이스 A 모사)
  - caseB 유방동 (실기기 케이스 B 모사)
  - 짧은 동명 옥수동
  - 4단 슬래시 폴백
  - 메타 토큰 정제 (서명 라벨 사이)
  - 처인구 다른 동 슬롯에서 결합 표기 통과 vs 단일 차단 검증
  - 헤더 + 출발 + 도착 세 군데 분리 검증
- **디버그 토스트 메시지 일관화**
  - 추출: "도착 추출 [N단:방식]: {token}" (예: 1단:라벨박스 / 2단:박스영역 / 3단:슬라이스 / 4단:슬래시)
  - 추출 실패: "도착지 노드 미발견 → 차단"

## v1.0.3 변경 요약 (2026-04-26)
- **도착지 추출 전면 재작성** ([DestinationParser.kt](app/src/main/java/com/quickcall/macro/parser/DestinationParser.kt))
  - 1단: "도착" 라벨 노드의 같은 부모 형제 텍스트
  - 2단: "도착" 라벨의 같은 행 좌표(Y±100px) + 오른쪽 위치 노드들
  - 3단: 화면 전체 결합 텍스트의 "도착" 단어 이후 ~50자 슬라이스 (중간에 "출발" 나오면 컷)
  - 라벨은 정확히 `text == "도착"` 또는 `"도착:"` 만 인정. **부분 매칭 금지** ("도착시간", "도착예정" 등 무시).
- **메타 토큰 필터** ([DongTokenExtractor.kt](app/src/main/java/com/quickcall/macro/parser/DongTokenExtractor.kt))
  - "바로", "직접", "픽업", "현장", "즉시", "서명", "콜픽업" 으로 시작하는 토큰 제외
  - "도착", "출발", "*", "-", 결제 수단(신용/현금/미터/외상/선결제) 등 정확히 일치 제외
  - 전화번호 패턴 제외
- **다중 키 정규화** ([DistrictKeyGenerator.kt](app/src/main/java/com/quickcall/macro/parser/DistrictKeyGenerator.kt))
  - 슬롯 등록 한 항목 (예: 서초구 반포동) → `{반포, 서초반포, 서초}` 다중 키
  - 3단계 (예: 용인시 처인구 역북동) → `{역북, 처인역북, 처인, 용인역북, 용인}`
  - 도착지 화면 토큰 (예: 서초반포동) → `{서초, 서초반포, 반포}` 다중 키
  - 매칭은 **셋 교집합** (`intersect`) 으로 수행. 한 키라도 겹치면 통과.
- **순수 함수 모듈로 분리** — `parser/` 패키지에 안드로이드 의존성 없는 순수 코드 배치 → 단위 테스트 가능
- **단위 테스트 19건 추가** ([DestinationMatchingTest.kt](app/src/test/java/com/quickcall/macro/DestinationMatchingTest.kt))
  - 추출 시나리오 7건: 사례 1~4 (평택진위면/마곡동/서초반포동/성수동) + 좌표 폴백 + 텍스트 슬라이스 + "도착시간" 부분 매칭 거부
  - 매칭 시나리오 12건: 통과/차단 케이스 망라 (강서구·서초구·평택시·화성시·성동구·강남구·용인 처인구)
  - 실행: `./gradlew.bat --no-daemon testDebugUnitTest`
- **디버그 토스트 변경**
  - 추출 단계: "도착 추출 [1단/2단/3단]: {token}"
  - 추출 실패: "도착지 노드 미발견 → 차단"
  - 매칭 통과: "필터 통과: {token} → {매칭 키 셋}"
  - 매칭 차단: "필터 차단: {token} → {도착지 키 셋}"

## v1.0.2 변경 요약 (2026-04-25)
- **구 필터 (슬롯 5개)**: 사용자가 시/군/구를 슬롯에 미리 선택해두면 도착지 동/읍/면이 슬롯 범위일 때만 매크로 동작.
- 슬롯 한 번에 하나만 활성. "사용 안 함" 옵션 포함.
- 매칭 규칙: 화면 표기를 정규화 (숫자 제거 → 앞 2글자). 동은 동 이름 그대로, 읍/면은 시군구 prefix와 결합해서 정규화 (예: 평택시 + 진위면 → "평택진위면" → "평택").
- 데이터: 법정동코드 전체자료 (FinanceData gist)에서 가공한 `assets/districts.json` (~33KB, 249 시군구, 5015 동/읍/면).
- 빌드 스크립트: `scripts/build-districts.js` — 원본 변경 시 `node scripts/build-districts.js`로 재생성.
- 도착지 파싱 우선순위: (1) 슬래시 패턴 `/dong/`, (2) "{시도} {시군구} {동}" 정규식, (3) "도착" 라벨 이후 첫 동/읍/면 토큰. 파싱 실패 시 안전 차단.
- **앱 종료 시 자동 OFF**: OverlayService / ScreenCaptureService의 `onTaskRemoved` 오버라이드 → MacroController.stop. 최근앱 스와이프 종료 시 자동으로 매크로 정지.
- 새 액티비티 2개: `ui.DistrictSettingsActivity` (슬롯 라디오), `ui.DistrictSlotEditActivity` (시도 트리 + 검색 + 체크박스 편집).
- DistrictRepository: assets 백그라운드 로드 (App.onCreate에서 워커 스레드), 활성 슬롯 변경 시 키 셋 캐싱.

## v1.0.1 변경 요약 (2026-04-25)
- **버그 수정**: 접근성 허용 직후 onResume에서 자동으로 캡처 다이얼로그가 무한 호출되던 문제 → onResume에서 `requestProjection()` 제거.
- **시작/정지 분리**: 권한 부여와 매크로 실행이 완전히 분리. 명시적 시작 액션이 있어야만 동작.
- **MacroController 신설**: 권한 검증 → 캡처 다이얼로그 → enabled/오버레이 시작 흐름을 MainActivity와 OverlayService가 공유.
- **콜드 스타트 안전장치**: App.onCreate에서 `enabled=false` 강제 → 폰 재부팅 후 자동 동작 방지.
- **충돌 방지**: 이미 동작 중인 상태에서 시작 누르면 "이미 동작 중" 토스트.
- **온보딩 힌트**: 권한 미비 시 메인 상단에 노란 안내 박스 표시.
- **디버그 토스트**: 설정에서 ON 시 매칭 경로(노드/캐시/이미지/모드2 단계)를 토스트로 표시 → 실기기 튜닝용.
- **오버레이 라벨 동기화**: MainActivity에서 시작/정지 시 오버레이 토글 라벨 자동 갱신 (SharedPreferences 리스너).

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
