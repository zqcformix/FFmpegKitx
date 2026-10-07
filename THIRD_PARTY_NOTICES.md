# Third-Party Notices

FFmpegKitx bundles prebuilt native libraries that are **not** covered by the
project's MIT license (see [LICENSE](LICENSE)). Applications that ship
FFmpegKitx must meet the terms of the licenses below as well.

The AAR contains, for each ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`), the
shared libraries `libavcodec.so`, `libavformat.so`, `libavfilter.so`,
`libavutil.so`, `libswresample.so` and `libswscale.so`. libass, FreeType,
FriBidi and HarfBuzz are statically linked into them.

| Component | Version | License | Full text |
| --- | --- | --- | --- |
| FFmpeg | 8.0.1 | LGPL-2.1-or-later | [licenses/LGPL-2.1.txt](licenses/LGPL-2.1.txt), [licenses/FFmpeg-LICENSE.md](licenses/FFmpeg-LICENSE.md) |
| libass | 0.17.3 | ISC | [licenses/libass-ISC.txt](licenses/libass-ISC.txt) |
| FreeType | not recorded | FreeType License (FTL), chosen over GPLv2 | [licenses/FreeType-FTL.txt](licenses/FreeType-FTL.txt) |
| FriBidi | 1.0.16 | LGPL-2.1-or-later | [licenses/LGPL-2.1.txt](licenses/LGPL-2.1.txt) |
| HarfBuzz | 10.1.0 (inferred) | "Old MIT" | [licenses/HarfBuzz-MIT.txt](licenses/HarfBuzz-MIT.txt) |

Versions were read from strings embedded in the shipped binaries. FreeType
reports its version only at run time, and the HarfBuzz version is inferred
from the only matching version string in `libavfilter.so`.

## FFmpeg

This software uses code of [FFmpeg](https://ffmpeg.org) licensed under the
[LGPLv2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).
FFmpeg is a trademark of Fabrice Bellard, originator of the FFmpeg project.

The libraries were built from the `8.0.1` release without `--enable-gpl`,
`--enable-version3` or `--enable-nonfree`, and are shipped as separate shared
libraries so they can be replaced. Source code:

- FFmpeg 8.0.1: <https://ffmpeg.org/releases/ffmpeg-8.0.1.tar.xz>
- libass 0.17.3: <https://github.com/libass/libass/releases/tag/0.17.3>
- FriBidi 1.0.16: <https://github.com/fribidi/fribidi/releases/tag/v1.0.16>
- HarfBuzz 10.1.0: <https://github.com/harfbuzz/harfbuzz/releases/tag/10.1.0>
- FreeType: <https://download.savannah.gnu.org/releases/freetype/>

Configure flags (arm64-v8a; the other ABIs differ only in `--arch`, `--cpu`,
the compiler triple and, for `armeabi-v7a`, the extra flags
`-march=armv7-a -mfloat-abi=softfp -mfpu=neon`), built with NDK r29 for
API 24:

```text
--enable-cross-compile --target-os=android --arch=arm64 --cpu=armv8-a
--enable-shared --disable-static --disable-doc --disable-programs
--disable-avdevice --disable-symver --enable-mediacodec --enable-jni
--enable-libass --enable-libfreetype --enable-libfribidi
--extra-cflags='-O3 -fPIC'
--extra-ldflags='-Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384'
```

The exact flags of each shipped binary can be read at run time with
`FFmpegKit.getBuildConfiguration()`.

## libass

Copyright (C) 2006-2016 libass contributors. Licensed under the ISC License.

## FreeType

Portions of this software are copyright © The FreeType Project
(<https://freetype.org>). All rights reserved.

## FriBidi

Copyright (C) FriBidi authors, see the `AUTHORS` file in the FriBidi source.
Licensed under the GNU Lesser General Public License, version 2.1 or later.

## HarfBuzz

Copyright © 2010-2022 Google, Inc. and the other HarfBuzz contributors listed
in the full license text. Licensed under the "Old MIT" license.
