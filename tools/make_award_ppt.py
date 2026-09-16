#!/usr/bin/env python3
"""Builds the Best Worshiper award deck.

One beat per slide, so a single click advances the ceremony:

    title -> nominees -> [suspense -> reveal] x winners -> finale

Canvas matches the EM Service template (20 x 11.25in) so the deck can be
inserted into the Sunday service PPT without rescaling.

Winners come from the app's backup JSON (settings -> export), which carries the
photos as data URLs, or from names passed on the command line.
"""

import argparse
import base64
import io
import json
import re
from datetime import date, datetime
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Emu, Inches, Pt

# Palette lifted from the app so the deck and the phone screen match.
BG = RGBColor(0x0D, 0x0F, 0x14)
GOLD = RGBColor(0xFF, 0xD7, 0x40)
ACCENT = RGBColor(0x6C, 0x63, 0xFF)
TEXT = RGBColor(0xE8, 0xEA, 0xF6)
DIM = RGBColor(0x8A, 0x8D, 0x9E)

KR_FONT = 'Malgun Gothic'
EN_FONT = 'Segoe UI'

SLIDE_W = Inches(20)
SLIDE_H = Inches(11.25)


def new_deck():
    prs = Presentation()
    prs.slide_width = SLIDE_W
    prs.slide_height = SLIDE_H
    return prs


def blank(prs):
    """A blank slide painted with the app's dark background."""
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, SLIDE_W, SLIDE_H)
    bg.fill.solid()
    bg.fill.fore_color.rgb = BG
    bg.line.fill.background()
    bg.shadow.inherit = False
    return slide


def text_box(slide, x, y, w, h, text, size, color=TEXT, bold=False,
             font=KR_FONT, align=PP_ALIGN.CENTER, spacing=1.0):
    box = slide.shapes.add_textbox(x, y, w, h)
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    for i, line in enumerate(str(text).split('\n')):
        para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        para.alignment = align
        para.line_spacing = spacing
        run = para.add_run()
        run.text = line
        run.font.size = Pt(size)
        run.font.bold = bold
        run.font.color.rgb = color
        run.font.name = font
    return box


def meet_line(count):
    return ("Let's meet today's Best Worshiper!" if count == 1
            else f"Let's meet today's {count} Best Worshipers!")


def slide_title(prs, when, count):
    slide = blank(prs)
    text_box(slide, 0, Inches(1.8), SLIDE_W, Inches(2.2), '🏆', 150)
    text_box(slide, 0, Inches(4.2), SLIDE_W, Inches(1.8),
             'Best Worshiper', 96, GOLD, True, EN_FONT)
    text_box(slide, 0, Inches(6.2), SLIDE_W, Inches(1.0), when, 40, DIM, font=EN_FONT)
    text_box(slide, 0, Inches(7.6), SLIDE_W, Inches(1.2),
             meet_line(count), 40, TEXT)
    return slide


def slide_nominees(prs, names):
    slide = blank(prs)
    text_box(slide, 0, Inches(0.9), SLIDE_W, Inches(1.2),
             f"Today's Nominees · {len(names)}", 54, ACCENT, True, EN_FONT)

    # Lay the names out in up to four columns so a long list still fits.
    cols = 2 if len(names) <= 8 else (3 if len(names) <= 15 else 4)
    rows = -(-len(names) // cols)
    col_w = (SLIDE_W - Inches(2.0)) / cols
    top = Inches(2.6)
    row_h = min(Inches(1.1), (SLIDE_H - top - Inches(0.8)) / max(rows, 1))
    size = 40 if len(names) > 15 else 48

    for i, name in enumerate(names):
        c, r = i // rows, i % rows
        text_box(slide, Inches(1.0) + col_w * c, top + row_h * r, col_w, row_h,
                 f'· {name}', size, TEXT)
    return slide


def slide_suspense(prs, index, total):
    slide = blank(prs)
    text_box(slide, 0, Inches(1.5), SLIDE_W, Inches(1.2),
             f'Best Worshiper  {index} / {total}', 52, ACCENT, True, EN_FONT)
    text_box(slide, 0, Inches(3.4), SLIDE_W, Inches(2.4), '🥁', 170)
    text_box(slide, 0, Inches(6.2), SLIDE_W, Inches(1.6),
             'Drumroll, please...', 96, GOLD, True, EN_FONT)
    text_box(slide, 0, Inches(8.6), SLIDE_W, Inches(1.0),
             'Who could it be?', 40, DIM, font=EN_FONT)
    return slide


def slide_reveal(prs, name, index, total, photo=None):
    slide = blank(prs)
    text_box(slide, 0, Inches(0.7), SLIDE_W, Inches(0.9),
             f'Best Worshiper  {index} / {total}', 34, DIM, font=EN_FONT)

    photo_box = Inches(6.0)
    px, py = Inches(2.2), Inches(2.6)

    # Gold ring behind the portrait, and a stand-in when there is no photo yet.
    ring = slide.shapes.add_shape(MSO_SHAPE.OVAL,
                                  px - Inches(0.18), py - Inches(0.18),
                                  photo_box + Inches(0.36), photo_box + Inches(0.36))
    ring.fill.solid()
    ring.fill.fore_color.rgb = GOLD
    ring.line.fill.background()
    ring.shadow.inherit = False

    if photo:
        pic = slide.shapes.add_picture(io.BytesIO(photo), px, py,
                                       width=photo_box, height=photo_box)
        # Square crop keeps faces centred instead of stretching them.
        pic.crop_left = pic.crop_right = pic.crop_top = pic.crop_bottom = 0.0
    else:
        inner = slide.shapes.add_shape(MSO_SHAPE.OVAL, px, py, photo_box, photo_box)
        inner.fill.solid()
        inner.fill.fore_color.rgb = RGBColor(0x1C, 0x1F, 0x2A)
        inner.line.fill.background()
        inner.shadow.inherit = False
        text_box(slide, px, py, photo_box, photo_box, '🧒', 190)

    right_x = px + photo_box + Inches(1.4)
    right_w = SLIDE_W - right_x - Inches(1.2)
    text_box(slide, right_x, Inches(3.6), right_w, Inches(1.2),
             '🏆  Congratulations!', 48, GOLD, True, EN_FONT, align=PP_ALIGN.LEFT)
    text_box(slide, right_x, Inches(5.0), right_w, Inches(2.2),
             name, 130, TEXT, True, align=PP_ALIGN.LEFT)
    return slide


def slide_finale(prs, names):
    slide = blank(prs)
    text_box(slide, 0, Inches(1.2), SLIDE_W, Inches(1.6),
             'Congratulations!', 84, GOLD, True, EN_FONT)
    text_box(slide, 0, Inches(3.0), SLIDE_W, Inches(1.0),
             "Today's Best Worshipers", 40, DIM, font=EN_FONT)

    size = 80 if len(names) <= 4 else (64 if len(names) <= 6 else 48)
    text_box(slide, Inches(1.0), Inches(4.3), SLIDE_W - Inches(2.0), Inches(4.0),
             '\n'.join(names), size, TEXT, True, spacing=1.3)
    text_box(slide, 0, Inches(9.4), SLIDE_W, Inches(1.0),
             'See you next week 🙌', 40, ACCENT, font=EN_FONT)
    return slide


def decode_photo(data_url):
    """The app stores media as a data URL; PowerPoint needs the raw bytes."""
    if not data_url or not isinstance(data_url, str):
        return None
    m = re.match(r'data:(image/[^;]+);base64,(.*)$', data_url, re.S)
    if not m:
        return None
    return base64.b64decode(m.group(2))


def load_from_backup(path, week_date=None):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    kids = {k['id']: k for k in data.get('kids', [])}
    weeks = sorted(data.get('weeks', []), key=lambda w: w['date'])
    if not weeks:
        raise SystemExit('백업에 주간 기록이 없습니다.')

    week = next((w for w in weeks if w['date'] == week_date), None) if week_date else weeks[-1]
    if week is None:
        raise SystemExit(f'{week_date} 기록을 백업에서 찾을 수 없습니다.')

    winners = [(kids[i]['name'], decode_photo(kids[i].get('media')))
               for i in week['winnerIds'] if i in kids]
    nominees = [kids[i]['name'] for i in week['candidateIds'] if i in kids]
    return week['date'], winners, nominees


def build(out_path, when, winners, nominees):
    prs = new_deck()
    slide_title(prs, when, len(winners))
    if nominees:
        slide_nominees(prs, nominees)
    for i, (name, photo) in enumerate(winners, 1):
        slide_suspense(prs, i, len(winners))
        slide_reveal(prs, name, i, len(winners), photo)
    slide_finale(prs, [n for n, _ in winners])
    prs.save(out_path)
    return len(prs.slides.__iter__.__self__._sldIdLst)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--backup', help="앱 설정 → 내보내기로 받은 JSON")
    ap.add_argument('--week', help='백업에서 쓸 주간 날짜 (YYYY-MM-DD, 기본: 가장 최근)')
    ap.add_argument('--winners', help='이름을 쉼표로 구분 (백업 대신 직접 지정)')
    ap.add_argument('--nominees', help='후보 이름을 쉼표로 구분')
    ap.add_argument('--date', help='제목/파일명에 쓸 날짜 (기본: 오늘)')
    ap.add_argument('--out-dir', default='.', help='저장 폴더')
    args = ap.parse_args()

    nominees = []
    if args.backup:
        week_date, winners, nominees = load_from_backup(args.backup, args.week)
        when = args.date or week_date
    elif args.winners:
        winners = [(n.strip(), None) for n in args.winners.split(',') if n.strip()]
        nominees = [n.strip() for n in (args.nominees or '').split(',') if n.strip()]
        when = args.date or date.today().isoformat()
    else:
        raise SystemExit('--backup 또는 --winners 중 하나가 필요합니다.')

    if not winners:
        raise SystemExit('선정된 아이가 없습니다.')

    stamp = (args.date or when or date.today().isoformat()).replace('-', '')
    out = Path(args.out_dir) / f'BestWorshiper_{stamp}.pptx'
    out.parent.mkdir(parents=True, exist_ok=True)

    build(out, when, winners, nominees)
    photos = sum(1 for _, p in winners if p)
    print(f'생성: {out}')
    print(f'  날짜   : {when}')
    print(f'  수상자 : {len(winners)}명 ({", ".join(n for n, _ in winners)})')
    print(f'  사진   : {photos}/{len(winners)}장')
    print(f'  후보   : {len(nominees)}명')
    print(f'  슬라이드: {2 + (2 * len(winners)) + (1 if nominees else 0)}장')


if __name__ == '__main__':
    main()
