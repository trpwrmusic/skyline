#!/bin/sh
# Bildschirmfoto vom Beamer:  SKYLINE_DEVICE=192.168.x.y:5555 tools/shot.sh name  -> shots/name.png (+ verkleinert .jpg)
export MSYS_NO_PATHCONV=1
D=${SKYLINE_DEVICE:?Bitte SKYLINE_DEVICE=<ip>:5555 setzen}
OUT="$(cd "$(dirname "$0")/.." && pwd)/shots"; mkdir -p "$OUT"
adb -s $D shell screencap -p /sdcard/_shot.png >/dev/null 2>&1
adb -s $D pull /sdcard/_shot.png "$OUT/$1.png" >/dev/null
adb -s $D shell rm /sdcard/_shot.png
python -c "from PIL import Image; im=Image.open('$OUT/$1.png').convert('RGB'); im.resize((960,540)).save('$OUT/$1.jpg',quality=80)" 2>/dev/null || true
