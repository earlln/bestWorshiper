# 릴리즈 방법

APK 빌드와 릴리즈는 `.github/workflows/android.yml` 이 합니다.
태그를 밀면 APK 가 빌드되어 릴리즈에 붙습니다.

## ⚠ 먼저: 서명 키를 맞춰야 합니다

안드로이드는 **서명이 다른 APK 의 덮어쓰기 설치를 거부**합니다. 기존 v2.0.0 과
다른 키로 서명하면 사용자는 앱을 **지우고 다시 깔아야 하고, 그러면 아이들 명단과
선정 이력이 사라집니다.** 그러니 지금까지 APK 를 만들던 그 PC 의 키를 써야 합니다.

지금까지 Android Studio 로 사이드로드용 빌드를 만드셨다면 그 키는 보통
`%USERPROFILE%\.android\debug.keystore` 이고, 암호는 아래 기본값입니다.

| 항목 | 기본값 |
|---|---|
| 키스토어 암호 | `android` |
| 키 별칭(alias) | `androiddebugkey` |
| 키 암호 | `android` |

### 어떤 키였는지는 이미 확인했습니다

배포된 v2.0.0 APK 의 서명 인증서를 열어본 결과입니다.

```
subject  : CN = Android Debug, O = Android, C = US
발급      : 2026-05-23
SHA-256  : 3D:CF:9C:26:4B:5E:1C:62:F6:AC:18:76:08:FE:B8:8F:
           EC:A3:25:23:12:02:29:41:74:7D:2C:07:7A:DB:F5:6A
```

안드로이드 빌드 도구가 자동으로 만드는 **디버그 키스토어**입니다. Android Studio
뿐 아니라 명령줄 gradle 빌드도 이 파일을 자동 생성하므로, **2026-05-23 에 이
앱을 처음 빌드한 그 컴퓨터**의 아래 경로에 있습니다.

| OS | 경로 |
|---|---|
| Windows | `%USERPROFILE%\.android\debug.keystore` |
| macOS / Linux | `~/.android/debug.keystore` |

암호는 디버그 키스토어 표준값입니다: 키스토어 `android`, 별칭
`androiddebugkey`, 키 `android`.

이 지문은 `android/release-cert-sha256.txt` 에 적혀 있고, CI 가 빌드한 APK 와
대조해 **다르면 릴리즈를 중단합니다.** 그래서 손으로 확인하지 않아도 잘못된 키로
배포될 일은 없습니다. 시크릿만 등록하고 빌드를 한 번 돌려보면 됩니다.

### 키스토어를 못 찾으면

그 컴퓨터가 없거나 파일이 사라졌다면 **같은 키로 서명할 방법이 없습니다.**
새 키로 낸 APK 는 기존 앱 위에 설치되지 않으므로, 사용자는

1. 앱에서 **설정 ▸ 내보내기** 로 백업 파일을 먼저 받고,
2. 앱을 지운 뒤 새 APK 를 설치하고,
3. **가져오기** 로 백업을 복원해야

합니다. 이 경우 `android/release-cert-sha256.txt` 를 새 키의 지문으로 바꿔주세요.

## 시크릿 등록 (한 번만)

GitHub 저장소 ▸ **Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret**

| 이름 | 값 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 키스토어 파일을 base64 로 바꾼 문자열 (아래 참고) |
| `ANDROID_KEYSTORE_PASSWORD` | 키스토어 암호 (디버그 키면 `android`) |
| `ANDROID_KEY_ALIAS` | 키 별칭 (디버그 키면 `androiddebugkey`) |
| `ANDROID_KEY_PASSWORD` | 키 암호 (디버그 키면 `android`) |

base64 만들기 — PowerShell 에서 실행하면 클립보드에 복사됩니다:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
```

> 키스토어 파일 자체는 **저장소에 커밋하지 마세요.** 시크릿으로만 올립니다.

## 릴리즈하기

1. 버전을 세 군데 **함께** 올립니다. 하나라도 어긋나면 CI 가 먼저 막아줍니다.

   | 파일 | 항목 |
   |---|---|
   | `bestWorshiper.html` | `APP_VERSION` |
   | `sw.js` | `APP_CACHE_VERSION` |
   | `android/app/build.gradle` | `versionName`, `versionCode`(1 올림) |

   확인: `./tools/check_version.sh`

2. main 에 커밋하고 태그를 밉니다.

   ```bash
   git tag v2.1.0
   git push origin v2.1.0
   ```

3. Actions 탭에서 빌드를 지켜봅니다. 끝나면 릴리즈에 `bestWorshiper-v2.1.0.apk`
   가 붙어 있습니다.

## 확인할 것

빌드 로그의 **Report the signing certificate** 단계에 SHA-256 지문이 찍힙니다.
이전 릴리즈와 같은 값인지 한 번 보고 배포하세요. 다르면 사용자가 덮어설치를
하지 못합니다.

## 태그 없이 빌드만

Actions 탭 ▸ **Android** ▸ *Run workflow*. 릴리즈는 만들지 않고 APK 를
아티팩트로만 올립니다. main 으로의 푸시와 PR 도 같은 빌드를 돌립니다.
