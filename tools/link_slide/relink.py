#!/usr/bin/env python3
"""시상 링크 슬라이드의 연결 대상 PPT 파일명을 바꿔치기한다.

사용법:
    python relink.py 시상링크_자동.pptx BestWorshiper.pptx [-o 출력.pptx]

파일명을 고정해 두면 매주 파일만 덮어쓰면 되고 이 스크립트는 쓸 일이 없다.
대상 파일명이나 위치를 바꿀 때만 이 스크립트로 링크를 갈아끼운다.
PowerPoint 의 '실행 설정 > 하이퍼링크 > 다른 PowerPoint 프레젠테이션' 과 동일한
ppaction://hlinkpres 액션을 심기 때문에, 상영이 끝나면 원래 슬라이드로 자동 복귀한다.
"""
import argparse, re, shutil, sys, zipfile

SLIDE = "ppt/slides/slide1.xml"
RELS  = "ppt/slides/_rels/slide1.xml.rels"
NOTES = "ppt/notesSlides/notesSlide1.xml"
HYPERLINK_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
ACTION = "ppaction://hlinkpres?slideindex=1&amp;slidetitle="
BUTTON_SHAPES = ("BtnAwardFull",)


def strip_hlink(cnvpr: str) -> str:
    return re.sub(r"<a:hlinkClick\b[^>]*?(?:/>|>.*?</a:hlinkClick>)", "", cnvpr, flags=re.S)


def patch_cnvpr(slide_xml: str, name: str, rid: str) -> str:
    """이름이 name 인 도형의 <p:cNvPr> 안에 hlinkClick 을 넣는다 (첫 자식이어야 스키마에 맞음)."""
    hlink = f'<a:hlinkClick r:id="{rid}" action="{ACTION}"/>'
    pat = re.compile(r'(<p:cNvPr\b[^>]*\bname="%s"[^>]*?)(/>|>)' % re.escape(name))
    m = pat.search(slide_xml)
    if not m:
        sys.exit(f"[!] '{name}' 도형을 찾지 못했습니다. build.js 의 objectName 을 확인하세요.")
    head, close = m.group(1), m.group(2)
    if close == "/>":                       # 자식 없음 → 열고 닫는다
        new = f"{head}>{hlink}</p:cNvPr>"
        return slide_xml[:m.start()] + new + slide_xml[m.end():]
    # 자식 있음 → 기존 hlinkClick 제거 후 맨 앞에 삽입
    end = slide_xml.index("</p:cNvPr>", m.end())
    inner = strip_hlink(slide_xml[m.end():end])
    return slide_xml[:m.end()] + hlink + inner + slide_xml[end:]


def patch_rels(rels_xml: str, target: str) -> tuple[str, str]:
    """기존 .pptx 하이퍼링크 관계를 지우고 새로 추가. (새 xml, rId) 반환."""
    rels_xml = re.sub(
        r'<Relationship\b(?=[^>]*Type="%s")[^>]*/>' % re.escape(HYPERLINK_TYPE), "", rels_xml)
    used = {int(n) for n in re.findall(r'Id="rId(\d+)"', rels_xml)}
    rid = f"rId{max(used, default=0) + 1}"
    rel = (f'<Relationship Id="{rid}" Type="{HYPERLINK_TYPE}" '
           f'Target="{target}" TargetMode="External"/>')
    return rels_xml.replace("</Relationships>", rel + "</Relationships>"), rid


def swap_filename(xml: str, new: str) -> str:
    """슬라이드/노트에 적힌 이전 파일명을 새 파일명으로 바꾼다."""
    return re.sub(r"BestWorshiper[0-9A-Za-z._\-]*?\.pptx", new, xml)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("pptx"); ap.add_argument("target")
    ap.add_argument("-o", "--out")
    a = ap.parse_args()
    if not a.target.lower().endswith(".pptx"):
        sys.exit("[!] 연결 대상은 .pptx 파일이어야 합니다.")
    out = a.out or a.pptx
    if out != a.pptx:
        shutil.copyfile(a.pptx, out)

    with zipfile.ZipFile(a.pptx) as z:
        parts = {n: z.read(n) for n in z.namelist()}

    rels, rid = patch_rels(parts[RELS].decode(), a.target)
    parts[RELS] = rels.encode()
    slide = parts[SLIDE].decode()
    for shape in BUTTON_SHAPES:
        slide = patch_cnvpr(slide, shape, rid)
    parts[SLIDE] = swap_filename(slide, a.target).encode()
    if NOTES in parts:
        parts[NOTES] = swap_filename(parts[NOTES].decode(), a.target).encode()

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)
    print(f"OK  {out}  →  링크 대상: {a.target}  ({rid})")


if __name__ == "__main__":
    main()
