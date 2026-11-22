"""Erweiterungen packen (extensions/<id>/ mit manifest.json).

- legt jede Erweiterung unverändert in handy/assets/ext/<id>/ (mitgeliefert in der APK, Store-Ersatz ohne Internet)
- baut dist/ext-<id>.zip, dist/ext-<id>.svg und den Katalog dist/extensions.json für das GitHub-Release
  RELEASE_BASE = Download-Adresse des Releases, z. B. https://github.com/trpwrmusic/skyline/releases/download/build-1010/
"""
import hashlib, json, os, shutil, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'extensions')
ASSETS = os.path.join(ROOT, 'handy', 'assets', 'ext')
DIST = os.path.join(ROOT, 'dist')
BASE = os.environ.get('RELEASE_BASE', '').rstrip('/') + '/'

shutil.rmtree(ASSETS, ignore_errors=True)
shutil.rmtree(DIST, ignore_errors=True)
os.makedirs(ASSETS); os.makedirs(DIST)
catalog = []
for name in sorted(os.listdir(SRC)):
    d = os.path.join(SRC, name)
    mf = os.path.join(d, 'manifest.json')
    if not os.path.isfile(mf):
        continue
    m = json.load(open(mf, encoding='utf-8'))
    for k in ('id', 'name', 'version'):
        if not m.get(k):
            sys.exit(f'{name}: manifest.json ohne {k}')
    if m['id'] != name:
        sys.exit(f'{name}: id im Manifest ist {m["id"]}, Ordner heißt {name}')
    # Prüfsumme über alle Dateien: so übernimmt die App geänderte mitgelieferte Erweiterungen auch bei gleicher Version
    h = hashlib.sha256()
    for dp, dns, fns in sorted(os.walk(d)):
        dns.sort()
        for fn in sorted(fns):
            if not fn.startswith('.'):
                h.update(os.path.relpath(os.path.join(dp, fn), d).replace(os.sep, '/').encode()); h.update(open(os.path.join(dp, fn), 'rb').read())
    m['build'] = h.hexdigest()[:12]
    dest = os.path.join(ASSETS, m['id'])
    shutil.copytree(d, dest, ignore=shutil.ignore_patterns('.*', '*.map', 'node_modules'))
    json.dump(m, open(os.path.join(dest, 'manifest.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    zpath = os.path.join(DIST, f'ext-{m["id"]}.zip')
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        for dp, dns, fns in os.walk(d):
            dns[:] = sorted(x for x in dns if not x.startswith('.') and x != 'node_modules')
            for fn in sorted(fns):
                if fn.startswith('.'):
                    continue
                full = os.path.join(dp, fn)
                rel = os.path.relpath(full, d).replace(os.sep, '/')
                zi = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, json.dumps(m, ensure_ascii=False, indent=2).encode() if rel == 'manifest.json' else open(full, 'rb').read())
    data = open(zpath, 'rb').read()
    icon = m.get('icon', 'icon.svg')
    shutil.copy(os.path.join(d, icon), os.path.join(DIST, f'ext-{m["id"]}.svg'))
    entry = {k: m[k] for k in ('id', 'name', 'version', 'build', 'hub', 'author', 'summary', 'description', 'permissions', 'tab', 'home', 'links', 'featured') if k in m}
    entry.update(url=BASE + f'ext-{m["id"]}.zip', iconUrl=BASE + f'ext-{m["id"]}.svg', sha256=hashlib.sha256(data).hexdigest(), size=len(data))
    catalog.append(entry)
    print(f'{m["id"]} {m["version"]}: {len(data) // 1024} kB')
json.dump({'hub': 1, 'extensions': catalog}, open(os.path.join(DIST, 'extensions.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
