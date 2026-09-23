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

### 키가 맞는지 먼저 확인하세요

기존에 배포한 APK 를 내려받아 지문을 비교합니다. **두 SHA-256 이 같아야 합니다.**

```powershell
# 배포된 APK 의 인증서
keytool -printcert -jarfile bestWorshiper-v2.0.0.apk

# 내 키스토어의 인증서
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android
```

다르면 그 키로는 업데이트가 안 됩니다. 올바른 키스토어를 찾은 뒤 진행하세요.

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
