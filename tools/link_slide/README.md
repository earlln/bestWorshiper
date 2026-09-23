# 시상 링크 슬라이드

예배 PPT를 **중단하지 않고** 시상 PPT를 이어서 상영하기 위한 1장짜리 브리지 슬라이드입니다.

수상자가 예배 중에 정해지면 시상 PPT를 예배 PPT에 미리 끼워넣을 수 없습니다.
이 슬라이드는 예배 PPT 안에서 시상 PPT를 **별도 파일인 채로** 불러오고, 상영이
끝나면 원래 자리로 돌아옵니다.

| 파일 | 용도 |
|---|---|
| `시상링크.pptx` | 브리지 슬라이드 1장. 이 슬라이드를 예배 PPT에 복사해 넣는다 |
| `build.js` | 슬라이드를 다시 만드는 스크립트 (pptxgenjs) |
| `relink.py` | 링크 대상 파일명·경로를 바꾸는 스크립트 |
| `install_macro.vbs` | 매크로가 든 `시상링크.pptm` 을 만들어주는 Windows 설치 스크립트 |
| `자동실행_VBA.txt` | 매크로 코드와 설치·문제 해결 안내 |

캔버스는 20 × 11.25in, 팔레트와 폰트는 `../make_award_ppt.py` 와 동일해서 예배 PPT에
그대로 붙여넣어도 크기와 톤이 맞습니다.

## 준비 (한 번만)

1. `시상링크.pptx` 의 슬라이드를 복사해 **예배 PPT의 맨 마지막에 붙여넣습니다.**
   (붙여넣기 옵션은 *원본 서식 유지*) 슬라이드에는 "Best Worshiper 시상" 글자만
   있고, 화면 전체가 시상 PPT로 연결된 링크입니다.
2. 예배 PPT를 **OneDrive ▸ EM ▸ Service** 폴더에 둡니다.

링크는 같은 폴더를 가리키는 **상대 경로**입니다. 사용자 이름이 박힌 절대 경로를
쓰지 않았기 때문에 폴더째 옮기거나 다른 PC에서 열어도 그대로 동작합니다.

## 매주 하는 일

```bash
python tools/make_award_ppt.py --backup 백업.json --out-dir "<OneDrive>/EM/Service"
```

`BestWorshiper.pptx` 가 예배 PPT와 같은 폴더에 생깁니다. **파일명이 고정이라 링크를
다시 손볼 필요가 없습니다.** 지난 주 파일을 남겨두려면 하위 폴더로 옮기거나
`--out-name "BestWorshiper_{date}.pptx"` 로 따로 한 부 더 뽑아두세요.

## 리모컨 다음 버튼으로 진입하려면 매크로가 필요합니다

PowerPoint 의 하이퍼링크는 **마우스 클릭에만** 반응합니다. 리모컨이나 키보드의
"다음"은 링크를 무시하고 슬라이드만 넘기며, 이를 우회하는 기본 기능이 없습니다.

`install_macro.vbs` 를 `시상링크.pptx` 와 같은 폴더에 두고 더블클릭하면
매크로가 든 `시상링크.pptm` 이 만들어집니다. 사전에 PowerPoint 옵션에서
*보안 센터 ▸ 매크로 설정 ▸ VBA 프로젝트 개체 모델에 안전하게 액세스* 를 켜야
합니다. 자세한 절차와 수동 설치 방법은 `자동실행_VBA.txt` 에 있습니다.

매크로는 슬라이드에 **진입하는 순간** 발동하므로 리모컨·방향키·마우스 모두
동작합니다.

## 발표 중

1. 예배 PPT를 평소처럼 상영합니다.
2. 마지막 슬라이드(이 화면)에 들어오면 시상 PPT가 전체화면으로 이어서 상영됩니다.
   예배 PPT는 종료되지 않고 뒤에 그대로 남아 있습니다.
3. 시상 PPT의 마지막 슬라이드를 넘기면 **이 화면으로 자동 복귀**합니다.

매크로가 차단되었거나 `.pptx` 를 그대로 쓰는 경우에도 **화면 아무 곳이나 마우스로
클릭하면** 동일하게 진입합니다. 슬라이드 전체가 링크라 매크로가 실패해도 손으로
넘어갈 길이 남아 있습니다.

## 링크 대상 바꾸기

```bash
python relink.py 시상링크.pptx 다른이름.pptx -o 새파일.pptx
python relink.py 시상링크.pptx "C:\Users\사용자\OneDrive\EM\Service\BestWorshiper.pptx" -o 새파일.pptx
```

## 슬라이드 다시 만들기

```bash
npm install pptxgenjs
node build.js BestWorshiper.pptx 시상링크_raw.pptx
python relink.py 시상링크_raw.pptx BestWorshiper.pptx -o 시상링크.pptx
```

`build.js` 는 그림만 그리고, 링크는 `relink.py` 가 심습니다. PowerPoint 의
*실행 설정 ▸ 하이퍼링크 ▸ 다른 PowerPoint 프레젠테이션* 과 동일한
`ppaction://hlinkpres` 액션이라야 상영 후 자동 복귀가 되기 때문입니다 —
단순 하이퍼링크로는 돌아오지 않습니다.
