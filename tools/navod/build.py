"""Builds the walkthrough from content.txt.

    python3 build.py              -> navod.html next to this script (for the PDF, A4 layout)
    python3 build.py app OUTDIR   -> OUTDIR/index.html + img/ + fonts/ (the page shown in the app)

Markup of content.txt: '# ' title, '## ' chapter, '### ' section, 'CRED ' / 'ENDCRED ' credits,
'COLS' / 'ENDCOLS' two columns (PDF only), 'TABLE' ... 'ENDTABLE' (cells split by |, first row is
the header), 'AND ' note box, 'IMG a.jpg [b.jpg ...]', '- ' list item, empty line = new paragraph.
"""
import os, re, sys, shutil
from PIL import Image

d = os.path.dirname(os.path.abspath(__file__))
APP = len(sys.argv) > 1 and sys.argv[1] == 'app'
outdir = sys.argv[2] if APP else d
FONTS = os.path.join(d, 'fonts') if os.path.isdir(os.path.join(d, 'fonts')) else os.path.join(d, '..', 'fonts')
APP_IMG_WIDTH = 1200          # maps stay readable with zoom, the APK stays small

lines = open(os.path.join(d, 'content.txt'), encoding='utf-8').read().split('\n')
out = []
para = []
items = []
toc = []                      # (level, id, text) for the app's table of contents
in_navod = False


def flush():
    global para, items
    if para:
        out.append('<p>' + ' '.join(para) + '</p>')
        para = []
    if items:
        out.append('<ul>' + ''.join(f'<li>{i}</li>' for i in items) + '</ul>')
        items = []


def src_of(name):
    # best available picture: upscaled (img_hd, not in the repository), the app's copy, the original
    for p in (os.path.join(d, 'img_hd', name),
              os.path.join(outdir, 'img', name) if APP else None,
              os.path.join(d, 'img', name)):
        if p and os.path.exists(p):
            return p
    return os.path.join(d, 'img', name)


def img(name):
    orig = os.path.join(d, 'img', name)
    src = src_of(name)
    if not os.path.exists(src):
        return f'<figure><div class="ph">Obrázek {name} (ceskehry.net)</div></figure>'
    if APP:
        os.makedirs(os.path.join(outdir, 'img'), exist_ok=True)
        dst = os.path.join(outdir, 'img', name)
        if os.path.abspath(src) != os.path.abspath(dst):    # the app's copy is already final
            im = Image.open(src)
            if im.width > APP_IMG_WIDTH:
                im = im.resize((APP_IMG_WIDTH, round(im.height * APP_IMG_WIDTH / im.width)), Image.LANCZOS)
            if name.lower().endswith('.webp'):
                im.save(dst, 'WEBP', quality=82)
            else:
                im.convert('RGB').save(dst, 'JPEG', quality=78, optimize=True)
        w, h = Image.open(orig if os.path.exists(orig) else src).size
        return f'<figure><img src="img/{name}" alt="" style="width:{int(w * 1.5)}px"></figure>'
    w, h = Image.open(orig if os.path.exists(orig) else src).size  # keep the size of the original picture on the page
    w = min(w * 1.2, 450 * w / h)                                 # ...but never taller than 120 mm (keeps the aspect ratio)
    rel = os.path.relpath(src, d)
    return f'<figure><img src="{rel}" style="width:{int(w)}px;height:auto"></figure>'


def anchor(text):
    t = re.sub(r'<[^>]+>', '', text).lower()
    return 'k-' + re.sub(r'[^0-9a-záčďéěíňóřšťúůýž]+', '-', t).strip('-')


intable = False
first = False
for l in lines:
    if intable:
        if l == 'ENDTABLE':
            out.append('</table></div>')
            intable = False
            continue
        cells = l.split('|')
        tag = 'th' if first else 'td'
        first = False
        out.append('<tr>' + ''.join(f'<{tag}>{c}</{tag}>' for c in cells) + '</tr>')
        continue
    if not l.strip():
        flush(); continue
    if l.startswith('# '):
        flush(); out.append(f'<h1>{l[2:]}</h1>'); continue
    if l.startswith('## '):
        flush()
        a = anchor(l[3:])
        in_navod = l[3:].strip() == 'NÁVOD'
        toc.append((2, a, l[3:]))
        out.append(f'<h2 id="{a}">{l[3:]}</h2>')
        continue
    if l.startswith('### '):
        flush()
        a = anchor(l[4:])
        toc.append((3, a, l[4:]))
        out.append(f'<h3 id="{a}">{l[4:]}</h3>')
        continue
    if l.startswith('CRED '):
        flush(); out.append(f'<p class="cred">{l[5:]}</p>')
        if APP:
            out.append('<!--TOC-->')
        continue
    if l.startswith('ENDCRED '):
        flush(); out.append(f'<p class="src">{l[8:]}</p>'); continue
    if l == 'COLS':
        flush(); out.append('<div class="cols best">'); continue
    if l == 'ENDCOLS':
        flush(); out.append('</div>'); continue
    if l == 'TABLE':
        flush(); out.append('<div class="tw"><table>'); intable = True; first = True; continue
    if l.startswith('AND '):
        flush(); out.append(f'<div class="and">{l[4:]}</div>'); continue
    if l.startswith('- '):
        if para:
            out.append('<p>' + ' '.join(para) + '</p>')
            para.clear()
        items.append(l[2:])
        continue
    if l.startswith('IMG '):
        flush()
        names = l[4:].split()
        if len(names) == 1:
            out.append(img(names[0]))
        else:
            out.append('<div class="pair">' + ''.join(img(n) for n in names) + '</div>')
        continue
    para.append(l)
flush()
body = '\n'.join(out)

if not APP:
    head = open(os.path.join(d, 'head.html'), encoding='utf-8').read()
    open(os.path.join(d, 'navod.html'), 'w', encoding='utf-8').write(head + body + '\n</body></html>')
    sys.exit(0)

# ---- the page in the app --------------------------------------------------------------------
os.makedirs(os.path.join(outdir, 'fonts'), exist_ok=True)
for f in ('Almendra-Regular.ttf', 'Almendra-Bold.ttf', 'Almendra-Italic.ttf'):
    shutil.copy(os.path.join(FONTS, f), os.path.join(outdir, 'fonts', f))

# table of contents: chapters, and the places of the walkthrough
def nice(t):
    t = t.strip()
    if not t.isupper():
        return t
    t = t[0] + t[1:].lower()
    for w in ('androidu', 'zenox', 'skeldal', 'caredbar'):
        t = re.sub(r'\b' + w + r'\b', w.capitalize(), t)
    return t

chapters = []                 # [ [id, title, [(id, title), ...]], ... ]
for lvl, a, t in toc:
    if lvl == 2:
        chapters.append([a, nice(t), []])
    elif chapters and chapters[-1][1] == 'Návod':
        chapters[-1][2].append((a, nice(t)))
lis = []
for a, t, subs in chapters:
    sub = ('<ul class="sub">' + ''.join(f'<li><a href="#{sa}">{st}</a></li>' for sa, st in subs) + '</ul>') if subs else ''
    lis.append(f'<li><a href="#{a}">{t}</a>{sub}</li>')
html_toc = '<nav class="toc" id="obsah"><b>Obsah</b><ul>' + ''.join(lis) + '</ul></nav>'
body = body.replace('<!--TOC-->', html_toc)

CSS = """
@font-face{font-family:Alm;src:url(fonts/Almendra-Regular.ttf);font-weight:400}
@font-face{font-family:Alm;src:url(fonts/Almendra-Bold.ttf);font-weight:700}
@font-face{font-family:Alm;src:url(fonts/Almendra-Italic.ttf);font-style:italic}
html,body{background:#2A2927;color:#E9DCAE;margin:0}
body{font-family:Alm,serif;font-size:17px;line-height:1.45;padding:12px 16px 40px;max-width:900px;margin:0 auto;
  -webkit-text-size-adjust:100%;overflow-wrap:break-word}
h1{text-align:center;font-size:28px;margin:8px 0 6px}
h2{font-size:22px;margin:28px 0 8px;border-bottom:1px solid #6d6553;padding-bottom:3px}
h3{font-size:19px;margin:18px 0 6px}
p{margin:0 0 10px}
ul{margin:0 0 10px 20px;padding:0}
li{margin:0 0 4px}
a{color:#f3e6b8}
.cred{text-align:center;font-size:14px;margin:0 0 14px;color:#cfc29b}
.src{font-style:italic;font-size:14px;margin-top:24px;color:#cfc29b}
.and{border:1px solid #8a7f63;padding:8px 10px;margin:8px 0 12px;font-size:16px}
.and b:first-child{color:#f3e6b8}
figure{margin:10px 0 14px;text-align:center}
figure img{max-width:100%;height:auto;border:1px solid #6d6553}
.pair{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;align-items:flex-start}
.pair figure{flex:1 1 280px;margin:6px 0}
.tw{overflow-x:auto;margin:6px 0 14px}
table{border-collapse:collapse;width:100%;font-size:15px}
th,td{border:1px solid #6d6553;padding:5px 7px;text-align:left;vertical-align:top}
th{color:#2A2927;background:#E9DCAE}
.toc{border:1px solid #8a7f63;padding:10px 14px;margin:0 0 18px;font-size:16px}
.toc ul{margin:6px 0 0 18px}.toc li{margin:3px 0}
.toc .sub{columns:2;column-gap:20px;margin:4px 0 4px 16px}
.toc .sub li{break-inside:avoid}
.top{position:fixed;right:14px;bottom:14px;background:#E9DCAE;color:#2A2927;text-decoration:none;
  font-weight:700;padding:8px 12px;border-radius:20px;opacity:.85;font-size:15px}
@media (max-width:420px){body{font-size:16px}.toc .sub{columns:1}}
"""
page = ('<!doctype html><html lang="cs"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<title>Brány Skeldalu – návod</title><style>' + CSS + '</style></head><body>\n'
        + body + '\n<a class="top" href="#obsah">↑ Obsah</a>\n</body></html>')
open(os.path.join(outdir, 'index.html'), 'w', encoding='utf-8').write(page)
print('app page written to', outdir)
