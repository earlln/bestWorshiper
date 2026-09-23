const pptxgen = require("pptxgenjs");

// ─── 설정 ────────────────────────────────────────────────
// 연결할 시상 PPT 파일명. 예배 PPT와 같은 폴더(OneDrive▸EM▸Service)에 둔다 → 상대경로.
const TARGET = process.argv[2] || "BestWorshiper.pptx";
const OUT    = process.argv[3] || "시상링크.pptx";
// ─────────────────────────────────────────────────────────

// 팔레트·폰트·캔버스 모두 tools/make_award_ppt.py 와 동일하게 맞춘다.
// (20 x 11.25in = EM Service 템플릿 규격. 예배 PPT에 그대로 붙여넣을 수 있다)
const BG     = "0D0F14";
const GOLD   = "FFD740";
const TEXT   = "E8EAF6";
const DIM    = "8A8D9E";
const KFONT  = "Malgun Gothic";
const EFONT  = "Segoe UI";

const pres = new pptxgen();
pres.defineLayout({ name: "EM", width: 20, height: 11.25 });
pres.layout = "EM";

const s = pres.addSlide();
s.background = { color: BG };

// 장식: 우측 대형 별
s.addShape(pres.ShapeType.star5, {
  x: 12.9, y: 2.3, w: 5.9, h: 5.9,
  fill: { color: GOLD, transparency: 86 },
  line: { type: "none" },
});
// 모티프: 금색 원 + 별
s.addShape(pres.ShapeType.ellipse, {
  x: 1.2, y: 1.04, w: 1.04, h: 1.04,
  fill: { color: GOLD }, line: { type: "none" },
});
s.addShape(pres.ShapeType.star5, {
  x: 1.41, y: 1.25, w: 0.62, h: 0.62,
  fill: { color: BG }, line: { type: "none" },
});

s.addText("EM SERVICE", {
  x: 2.54, y: 1.04, w: 8.0, h: 1.04, isTextBox: true, margin: 0,
  fontFace: EFONT, fontSize: 24, bold: true, charSpacing: 6,
  color: GOLD, valign: "middle",
});

s.addText("Best Worshiper 시상", {
  x: 1.2, y: 2.70, w: 11.5, h: 1.50, isTextBox: true, margin: 0,
  fontFace: KFONT, fontSize: 80, bold: true, color: TEXT, valign: "middle",
});

s.addText("아무 곳이나 클릭하면 시상 슬라이드로 넘어가고,\n끝나면 이 화면으로 자동으로 돌아옵니다.", {
  x: 1.2, y: 4.40, w: 11.4, h: 1.60, isTextBox: true, margin: 0,
  fontFace: KFONT, fontSize: 28, color: DIM, lineSpacing: 44, valign: "top",
});

// ── 클릭 단추 (objectName 으로 후처리에서 찾는다) ──
s.addShape(pres.ShapeType.roundRect, {
  x: 1.2, y: 6.55, w: 8.7, h: 2.10, rectRadius: 0.28,
  fill: { color: GOLD }, line: { type: "none" },
  objectName: "BtnAward",
  shadow: { type: "outer", angle: 90, offset: 6, blur: 20, color: "000000", opacity: 0.45 },
});
s.addText("▶   클릭하면 시상 시작", {
  x: 1.2, y: 6.55, w: 8.7, h: 2.10, isTextBox: true, margin: 0,
  fontFace: KFONT, fontSize: 42, bold: true, color: BG,
  align: "center", valign: "middle",
  objectName: "BtnAwardLabel",
});

s.addText(
  [
    { text: "연결 대상  ", options: { color: DIM, bold: true } },
    { text: TARGET, options: { color: TEXT } },
    { text: "   ·   OneDrive ▸ EM ▸ Service (예배 PPT와 같은 폴더)", options: { color: DIM } },
  ],
  { x: 1.2, y: 9.30, w: 17.6, h: 0.80, isTextBox: true, margin: 0,
    fontFace: KFONT, fontSize: 22, valign: "middle" }
);

s.addNotes(
  "예배 PPT의 마지막 슬라이드입니다. 시상 순서가 되면 화면 아무 곳이나 클릭하세요.\n" +
  "시상 PPT가 전체화면으로 이어서 상영되고, 마지막 슬라이드 다음에 이 화면으로 자동 복귀합니다.\n" +
  "리모컨의 다음 버튼으로는 링크가 실행되지 않습니다(하이퍼링크는 마우스 클릭에만 반응). " +
  "리모컨만으로 진입하려면 자동실행_VBA.txt 를 참고하세요.\n" +
  "동작하지 않으면 " + TARGET + " 파일이 예배 PPT와 같은 폴더에 있는지 확인하세요."
);

// ── 슬라이드 전체를 덮는 투명 클릭 영역 (가장 마지막 = 맨 위) ──
s.addShape(pres.ShapeType.rect, {
  x: 0, y: 0, w: 20, h: 11.25,
  fill: { color: "FFFFFF", transparency: 100 },   // 완전 투명하지만 클릭은 잡힌다
  line: { type: "none" },
  objectName: "BtnAwardFull",
});

pres.writeFile({ fileName: OUT }).then(() => console.log("wrote", OUT));
