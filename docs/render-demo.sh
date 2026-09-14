#!/usr/bin/env bash
# Compose real CLI checkpoints into a short, smoothly looping README demo.
# Requires ffmpeg. Run after docs/demo.tape.
set -euo pipefail
cd "$(dirname "$0")/.."

capture_dir="$PWD/build/demo"
# Keep the CLI's own progress bar, align search and keyboard hints, and focus
# the final panels on the selected location and the successful generation
# (created line, next steps, and the equivalent non-interactive command).
# ponytail: crops assume the tape's 1000x560 layout; update them if its font or size changes.
ffmpeg -y -v error \
    -loop 1 -framerate 25 -t 2.0 -i "$capture_dir/welcome.png" \
    -loop 1 -framerate 25 -t 3.0 -i "$capture_dir/project-info.png" \
    -loop 1 -framerate 25 -t 1.8 -i "$capture_dir/localization.png" \
    -loop 1 -framerate 25 -t 1.8 -i "$capture_dir/addons.png" \
    -loop 1 -framerate 25 -t 1.0 -i "$capture_dir/filtered.png" \
    -loop 1 -framerate 25 -t 1.4 -i "$capture_dir/selected.png" \
    -loop 1 -framerate 25 -t 1.8 -i "$capture_dir/location.png" \
    -loop 1 -framerate 25 -t 3.0 -i "$capture_dir/success.png" \
    -filter_complex "
        [0:v]format=yuv444p,settb=AVTB,split=2[s1][loop];
        [1:v]format=yuv444p,settb=AVTB[s2];
        [2:v]format=yuv444p,settb=AVTB[s3];
        [3:v]split=3[catalog][header][footer];
        [catalog]format=yuv444p,settb=AVTB[s4];
        [header]crop=1000:64:0:0,split=2[h1][h2];
        [footer]crop=1000:64:0:496,split=2[f1][f2];
        [4:v]crop=1000:134:0:362,pad=1000:560:0:64:color=0x1e1f29[filtered];
        [5:v]crop=1000:134:0:362,pad=1000:560:0:64:color=0x1e1f29[selected];
        [filtered][h1]overlay=0:0:shortest=1[a];
        [a][f1]overlay=0:496:shortest=1,format=yuv444p,settb=AVTB[s5];
        [selected][h2]overlay=0:0:shortest=1[b];
        [b][f2]overlay=0:496:shortest=1,format=yuv444p,settb=AVTB[s6];
        [6:v]split=3[locationHeader][locationBody][locationFooter];
        [locationHeader]crop=1000:60:0:0,pad=1000:64:0:0:color=0x1e1f29[lh];
        [locationFooter]crop=1000:64:0:496[lf];
        [locationBody]crop=1000:68:0:384,pad=1000:560:0:64:color=0x1e1f29[lc];
        [lc][lh]overlay=0:0:shortest=1[ld];
        [ld][lf]overlay=0:496:shortest=1,format=yuv444p,settb=AVTB[s7];
        [7:v]split=2[successHeader][successBody];
        [successHeader]crop=1000:44:0:140,pad=1000:64:0:20:color=0x1e1f29[sh];
        [successBody]crop=1000:184:0:186,pad=1000:560:0:64:color=0x1e1f29[sc];
        [sc][sh]overlay=0:0:shortest=1,format=yuv444p,settb=AVTB[s8];
        [s1][s2]xfade=transition=fade:duration=0.4:offset=1.6[x1];
        [x1][s3]xfade=transition=fade:duration=0.4:offset=4.2[x2];
        [x2][s4]xfade=transition=fade:duration=0.4:offset=5.6[x3];
        [x3][s5]xfade=transition=fade:duration=0.4:offset=7.0[x4];
        [x4][s6]xfade=transition=fade:duration=0.16:offset=7.84[x5];
        [x5][s7]xfade=transition=fade:duration=0.4:offset=8.84[x6];
        [x6][s8]xfade=transition=fade:duration=0.4:offset=10.24[ending];
        [ending][loop]xfade=transition=fade:duration=0.4:offset=12.84,
            scale=800:-1:flags=lanczos,
            split[frames][colors];
        [colors]palettegen=stats_mode=full[palette];
        [frames][palette]paletteuse=dither=none:diff_mode=rectangle
    " -t 13.28 -loop 0 docs/demo.gif
