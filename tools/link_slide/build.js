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

s.addText("Best Worshiper 시상", {
  x: 1.2, y: 4.60, w: 17.6, h: 2.05, isTextBox: true, margin: 0,
  fontFace: KFONT, fontSize: 96, bold: true, color: TEXT,
  align: "center", valign: "middle",
});

s.addNotes(
  "예배 PPT의 마지막 슬라이드입니다. 이 화면에 들어오면 매크로가 " + TARGET + " 를 자동으로 실행합니다.\n" +
  "매크로가 차단되었거나 .pptx 로 저장된 경우에는 화면 아무 곳이나 마우스로 클릭하면 같은 동작을 합니다.\n" +
  "시상 PPT의 마지막 슬라이드를 넘기면 이 화면으로 자동 복귀합니다.\n" +
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
