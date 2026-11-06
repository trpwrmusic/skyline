#!/bin/sh
# Nahtlose Schleife: L Sekunden ab Sekunde S, Ende mit F Sekunden Überblendung in den Anfang.
# make_loop.sh in.mp4 out.mp4 [S=1] [L=20] [F=0.8]
in=$1; out=$2; S=${3:-1}; L=${4:-20}; F=${5:-0.8}
ffmpeg -loglevel error -y -i "$in" -filter_complex "[0:v]fps=30,scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,setsar=1,trim=start=$S,setpts=PTS-STARTPTS,split[a][b];[a]trim=start=$F:end=$(awk "BEGIN{print $L+$F}"),setpts=PTS-STARTPTS,fps=30[x];[b]trim=0:$F,setpts=PTS-STARTPTS,fps=30[y];[x][y]xfade=transition=fade:duration=$F:offset=$(awk "BEGIN{print $L-$F}"),format=yuv420p[v]" -map "[v]" -an -c:v libx264 -crf 20 -preset slow -movflags +faststart "$out"
