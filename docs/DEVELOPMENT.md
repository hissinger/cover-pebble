# Cover Pebble 개발 문서

사용법은 [README](../README.md)를 보세요. 이 문서에는 빌드 방법, 크레마 페블 내부 동작 분석, 앱 구조를 정리합니다.

## 빌드

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

- 결과물: `app/build/outputs/apk/release/CoverPebble-<버전>-release.apk`. 앱 이름과 버전이 파일 이름에 붙습니다(`app/build.gradle.kts`).
- 버전은 `app/build.gradle.kts`의 `versionName`에 있습니다. 앱 설정 화면 맨 아래에도 표시됩니다.
- 개인 배포용이라 릴리스도 디버그 키로 서명합니다.
- 디버그 빌드(`assembleDebug`)에서만 설정에 **기기 진단**이 보입니다(`BuildConfig.DEBUG`).

### 설치 (adb)

```bash
adb install -r -g app/build/outputs/apk/release/CoverPebble-1.0.0-release.apk
```

> `adb shell am force-stop com.woody.cremacover`를 쓰지 마세요. 접근성 서비스가 켜진 앱을 강제 종료하면 안드로이드가 접근성 권한을 자동으로 끕니다. 업데이트 설치(`install -r`)만으로는 꺼지지 않습니다.

## 크레마 페블 내부 동작

기기에서 꺼낸 APK(`com.wetao.usersettings`, `com.wetao.cremalauncher`, `com.yes24.ebook.einkstore`, `com.android.settings`)와 시스템 파일(`services.jar`, `framework.jar`, `hwcomposer.rk30board.so`, init rc, SELinux 정책)을 분석한 결과입니다.

### 실제로 화면에 쓰이는 파일

| 화면 | 파일 | 기본 이미지 |
|---|---|---|
| 슬립화면 | `/data/misc/eink/standby.png` | `/vendor/media/standby.png` |
| 종료화면 | `/data/misc/eink/poweroff.png` | `/vendor/media/poweroff.png` |

- 디스플레이 드라이버(`hwcomposer.rk30board.so`)가 위 파일을 읽어 화면에 그립니다.
- `/data/misc/eink`의 SELinux 라벨은 `rk_eink_data_file`입니다. 접근할 수 있는 것은 `hal_graphics_composer`(읽기), `system_server`, `system_app`, `platform_app`뿐입니다. 일반 앱(`untrusted_app`)과 adb(`shell`)는 폴더 목록조차 읽지 못합니다.
- init rc(`/vendor/etc/init/hw/init.rk356x.rc`)가 부팅 시 두 파일을 `0666`으로 바꾸지만, SELinux 때문에 의미가 없습니다.
- 시스템 서비스 `EinkManager`에는 화면 모드 전환과 대기 진입 같은 기능만 있습니다. 이미지를 지정하는 API는 없습니다.

### 크레마 설정 앱 (`com.wetao.usersettings`, `android.uid.system`)

- 설정 > 화면 설정(`MainActivity`, 외부에서 열 수 있는 유일한 화면)
  - 슬립화면 이미지(`StandbyConfigActivity`): 기본 이미지 / 도서 표지 이미지 / 사용자 지정 이미지
  - 종료화면 이미지(`ShutDownConfigActivity`): 사용자 지정 종료 화면
- 사용자 지정 목록(`CustomStandbyWallpaperSettingActivity`, `CustomShutdownSettingWallpaperActivity`)
  - 0번은 기본 이미지이고, 그 뒤로 `/sdcard/Wallpaper` 최상위 파일이 `listFiles()` 순서대로 옵니다. 확장자는 jpg/png/gif/jpeg/bmp이고, `.`으로 시작하는 파일은 빠집니다.
  - 한 페이지에 6개씩(3열 × 2행) 보이고, 좌우로 밀어 페이지를 넘깁니다.
  - 목록 터치는 `GestureDetector.onSingleTapUp`으로 처리합니다. 1초 안의 연속 탭은 무시합니다.
  - 이미지를 고르면 270° 회전하고 1448×1072로 줄여 위 `/data/misc/eink/*.png`에 저장합니다. 원본 경로는 `persist.wetao.wallpaper.standby` / `.shutdown` 속성에 기록합니다. 기본 이미지를 고르면 복사본을 지웁니다.
  - **고르는 순간에만 복사**하므로, 원본 파일을 덮어써도 반영되지 않습니다. 같은 파일을 다시 고르면 매번 새로 저장합니다.
- 슬립화면 모드는 `Settings.System`의 `enable_standby_book` 값입니다. 0은 기본, 1은 도서 표지, 2는 사용자 지정입니다.
  - 사용자 지정 스위치(`setCustomStandbyWallpaper`)는 토글입니다. 이미 켜져 있을 때 누르면 꺼지기만 하고 목록이 열리지 않습니다.

### 도서 표지 모드 (쓰지 않는 경로)

- 런처(`com.wetao.cremalauncher`, system uid)가 `/sdcard/Pictures/.bookcover/`를 `FileObserver`로 감시합니다. MODIFY·CREATE·DELETE 이벤트가 오고 `enable_standby_book == 1`이면 `bookcover.png`를 `standby.png`로 저장합니다.
  - 읽은 비트맵이 null인지 확인하지 않습니다. 빈 파일이나 쓰는 도중의 파일을 읽으면 런처가 죽을 수 있습니다.
- YES24 앱(`ViewerRunner.copyBookCover`)은 책을 열 때마다 그 책 표지를 같은 파일에 덮어씁니다. 그래서 이 모드에서는 고른 표지가 유지되지 않습니다.
- YES24 앱이 보내는 `com.haoqing.action.SET_WALLPAPER` 브로드캐스트는 슬립화면 설정 화면이 열려 있는 동안에만 수신자가 등록됩니다.
- 이런 이유로 Cover Pebble은 슬립·종료 모두 **사용자 지정 + 자동 탭** 방식을 씁니다.

## 앱 구조

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 내 보관함(첫 화면)과 검색 결과 그리드 |
| `CoverSearch.kt` | YES24 검색 페이지 HTML 파싱. 오래된 Chrome UA는 메인으로 리다이렉트되어 Safari UA를 씀 |
| `Images.kt` | 이미지 다운로드·디코딩, 1072×1448 흑백 변환(`CoverRenderer`) |
| `CoverActivity.kt` | 표지 미리보기, [슬립화면 등록]/[종료화면 등록], 보관함 저장·삭제 |
| `StorageDirs.kt` | `/sdcard/Wallpaper`에 표지 저장(파일 이름은 `Target.fileName`: `bookcover_sleep.png`, `bookcover_poweroff.png`, 덮어쓰기) |
| `CremaAutomationService.kt` | 접근성 서비스. 크레마 설정 화면 자동 탭, 가리개 오버레이, 초기화. `CremaWallpaper`는 목록 위치 계산과 페블 판별 |
| `SettingsActivity.kt` | 표지 배치·여백 색, 접근성 상태, 슬립·종료화면 초기화, 버전 표시 |
| `SavedCovers.kt` | 보관함(앱 전용 저장소의 원본 표지 + JSON 목록) |
| `DiagnosticsActivity.kt`, `DeviceProbe.kt` | (디버그 전용) 이미지·설정값 변화 기록, 시스템 앱 APK 내보내기 |

### 자동 적용 흐름 (`CremaAutomationService`)

1. 표지를 `Wallpaper`의 `target.fileName`으로 저장한 뒤 `run(target)`을 호출합니다. 초기화는 `run(target, reset = true)`입니다.
2. 화면 전체에 `TYPE_ACCESSIBILITY_OVERLAY` 가리개를 띄웁니다. `FLAG_NOT_TOUCHABLE`이라 제스처가 아래 설정 화면으로 전달됩니다.
3. `com.wetao.usersettings/.MainActivity`를 새 태스크로 엽니다.
4. 화면을 보고 단계를 진행합니다. 클릭은 `performAction`으로, 목록은 `dispatchGesture` 탭과 스와이프로 처리합니다. 단계 사이에는 0.7초를 기다리고, 30초가 지나면 실패로 끝냅니다.
   - 첫 화면: `sleep_wallpaper` 또는 `shutdown_wallpaper`
   - 종료화면: `rl_custom` → 목록에서 위치 계산 후 탭
   - 슬립화면: `bt_custom`이 켜져 있으면 `rl_custom`을 눌러 한 번 끄고, 다시 눌러 목록을 엽니다 → 목록 탭
   - 슬립화면 초기화: `rl_default`를 누르면 바로 완료
   - 종료화면 초기화: 목록 0번 탭
   - 목록을 탭한 뒤에는 크레마 설정의 완료 토스트(`TYPE_NOTIFICATION_STATE_CHANGED`)를 성공 신호로 봅니다. 저장 중 표시(`loading`)가 보였다가 사라지는 것도 완료로 봅니다. 8초 안에 둘 다 없으면 "확인하지 못했습니다"로 끝냅니다.
5. 앱 화면으로 돌아온 뒤 가리개를 걷습니다. 결과는 `takeResult()`로 표지 화면이나 설정 화면에 보여줍니다.

## 저장소 권한

내장메모리 루트(`/sdcard/Wallpaper`)에 쓰기 위해 `targetSdk 29` + `requestLegacyExternalStorage`를 씁니다. Play 스토어에는 올릴 수 없고 사이드로딩 전용입니다.
