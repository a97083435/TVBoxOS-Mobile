#!/usr/bin/env python3
"""Generate synthetic playback fixtures (ffmpeg + Shaka Packager), optionally push to a test device.

Example: python scripts/prepare_playback_fixtures.py --packager /path/to/packager --adb adb --serial DEVICE
All media and ClearKey material are synthetic, public test data. No accounts or remote media are used.
"""
import argparse
from pathlib import Path
import subprocess

ASS = r"""[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,sans-serif,64,&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,7,0,0,0,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:08.00,Default,,0,0,0,,{\move(100,200,1200,200)\fad(300,300)\c&H00FFFF&}ASS MOVING TEST
Dialogue: 1,0:00:00.00,0:00:08.00,Default,,0,0,0,,{\pos(300,500)\p1\c&H00FF00&}m 0 0 l 180 0 l 90 100{\p0}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    parser.add_argument('--packager', required=True)
    parser.add_argument('--output', type=Path, default=Path('build/playback-fixtures'))
    parser.add_argument('--adb')
    parser.add_argument('--serial')
    args = parser.parse_args()
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'core-effects.ass').write_text(ASS, encoding='utf-8')
    (folder / 'internal.srt').write_text('1\n00:00:00,000 --> 00:00:29,000\nINTERNAL CORE\n', encoding='utf-8')
    (folder / 'core-external.srt').write_text('1\n00:00:00,000 --> 00:00:29,000\nEXTERNAL CORE\n', encoding='utf-8')

    def ffmpeg(*command):
        subprocess.run([args.ffmpeg, '-y', '-v', 'error', *map(str, command)], check=True)

    base = folder / 'tvbox-regression.mp4'
    ffmpeg('-f', 'lavfi', '-i', 'color=c=navy:s=640x360:r=25:d=30', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=30',
           '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-c:a', 'aac', base)
    ffmpeg('-i', base, '-i', folder / 'core-effects.ass', '-map', '0', '-map', '1', '-c', 'copy',
           '-disposition:s:0', 'default', '-metadata:s:s:0', 'language=eng', folder / 'core-effects.mkv')
    ffmpeg('-i', base, '-f', 'lavfi', '-i', 'sine=frequency=660:duration=30', '-i', folder / 'internal.srt',
           '-map', '0', '-map', '1:a', '-map', '2:s', '-c:v', 'copy', '-c:a', 'aac', '-c:s', 'srt',
           '-metadata:s:a:0', 'language=eng', '-metadata:s:a:1', 'language=zho', '-metadata:s:s:0', 'language=zho', folder / 'core-tracks.mkv')
    # Use fragmented CENC; ordinary ffmpeg-encrypted nonfragmented MP4 is not a valid Exo DRM regression fixture.
    subprocess.run([args.packager, f'input={base},stream=video,output={folder / "clearkey.mp4"}',
                    '--enable_raw_key_encryption', '--keys',
                    'label=:key_id=11223344556677889900aabbccddeeff:key=00112233445566778899aabbccddeeff',
                    '--protection_systems', 'CommonSystem', '--clear_lead', '0'], check=True)
    if args.adb:
        adb = [args.adb] + (['-s', args.serial] if args.serial else [])
        target = '/sdcard/TVboxOSC-regression'
        subprocess.run(adb + ['shell', 'mkdir', '-p', target], check=True)
        for name in ['tvbox-regression.mp4', 'core-effects.ass', 'core-effects.mkv', 'core-tracks.mkv', 'core-external.srt', 'clearkey.mp4']:
            subprocess.run(adb + ['push', str(folder / name), target + '/' + name], check=True)
    print(folder)


if __name__ == '__main__':
    main()
