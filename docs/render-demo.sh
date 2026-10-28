#!/usr/bin/env bash
# Compose real CLI checkpoints into a short, smoothly looping README demo.
# Requires ffmpeg. Run after docs/demo.tape.
set -euo pipefail
cd "$(dirname "$0")/.."

capture_dir="$PWD/build/demo"
# Keep the CLI layout; trim machine-specific setup output from the success frame.
# shortcut: the success crop assumes the tape's 1000x560 layout; adjust it when that layout changes.
ffmpeg -y -v error \
    -t 2.0 -i "$capture_dir/raw.gif" \
    -loop 1 -framerate 25 -t 3.0 -i "$capture_dir/project-info.png" \
    -loop 1 -framerate 25 -t 1.8 -i "$capture_dir/localization.png" \
    -loop 1 -framerate 25 -t 1.8 -i "$capture_dir/addons.png" \
    -loop 1 -framerate 25 -t 1.0 -i "$capture_dir/filtered.png" \
    -loop 1 -framerate 25 -t 1.4 -i "$capture_dir/selected.png" \
    -loop 1 -framerate 25 -t 1.8 -i "$capture_dir/location.png" \
    -loop 1 -framerate 25 -t 3.0 -i "$capture_dir/success.png" \
    -filter_complex "
        [0:v]fps=25,format=yuv444p,settb=AVTB,split=2[s1][loop];
        [1:v]format=yuv444p,settb=AVTB[s2];
        [2:v]format=yuv444p,settb=AVTB[s3];
        [3:v]format=yuv444p,settb=AVTB[s4];
        [4:v]format=yuv444p,settb=AVTB[s5];
        [5:v]format=yuv444p,settb=AVTB[s6];
        [6:v]format=yuv444p,settb=AVTB[s7];
        [7:v]crop=1000:452:0:64,pad=1000:560:0:20:color=0x1e1f29,format=yuv444p,settb=AVTB[s8];
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
