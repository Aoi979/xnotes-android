<p align="center">
  <img src="docs/readme-banner.png" width="100%" alt="xnotes: a handwriting-first notebook for Android" />
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest"><img src="https://img.shields.io/github/v/release/shardulvs/xnotes-android?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://img.shields.io/f-droid/v/com.xnotes?style=flat-square" alt="F-Droid" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/sponsors/shardulvs"><img src="https://img.shields.io/badge/sponsor-%E2%9D%A4-ec6cb9?style=flat-square&logo=githubsponsors&logoColor=white" alt="Sponsor" /></a>
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="70" alt="Get it on F-Droid" /></a>
  <a href="https://play.google.com/store/apps/details?id=com.xnotes"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="70" alt="Get it on Google Play" /></a>
</p>

<p align="center">
  <img src="docs/screenshots/editor.png" width="90%" alt="The xnotes editor with a PDF open in dark mode, annotated by hand with a note, an arrow, a highlight and an underline, and page thumbnails down the left" />
  <br />
  <em>Annotating a PDF in dark mode, with the pages inverted</em>
</p>

<p align="center">
  <img src="docs/screenshots/explorer.png" width="90%" alt="The xnotes file explorer in gallery view, showing a grid of imported book covers with page counts" />
  <br />
  <em>Browsing imported PDFs in the gallery view</em>
</p>

---

## Features

- **Ink that feels like a pen**: pressure-sensitive and low latency, with pens and highlighters.
- **Neon glow**: make any pen glow.
- **Disappearing ink**: ink that fades away on its own, great for teaching and presenting.
- **Shape snapping**: draw a rough shape, hold, and it snaps clean.
- **Sharp at any zoom**: ink and PDFs stay crisp however far you zoom in.
- **Notebooks and an infinite canvas**: pages when you want structure, endless space when you don't.
- **Book layouts**: single, double or cover pages, scrolling or page flips.
- **PDF import**: bring in any PDF and write all over it.
- **Room for side notes**: widen a PDF's margins on any edge and take notes on the side.
- **PDF text markups**: highlight, underline and strike out text, add comments, search it.
- **PDF colour filters**: invert, sepia, contrast, brightness, multiply and screen.
- **Images stay untouched**: filter a PDF and its photos and images keep their true colours.
- **Dark and OLED modes**: easy on the eyes at night, true black on OLED screens.
- **Real PDF export**: sharp vector output with selectable text, links and bookmarks.
- **Markdown**: write in markdown and it formats as you type.
- **Typed notes**: tables, LaTeX equations and code highlighting, right on the page.
- **Audio recording**: record a lecture as you write, and your ink replays in sync with it.
- **20+ page templates**: Cornell, planners, music staves, isometric and more.
- **Endless themes**: any accent colour, eight styles for each, dual tone and Material You.
- **Make it yours**: rearrange the toolbar, float it or dock it on any edge, and pick its size.
- **Split view**: two notes side by side.
- **A real file explorer**: grid, gallery, list, column and timeline views, with plenty to customise.
- **Your notes, your folders**: notes are saved as files in a folder you choose, not locked away in an app's database, so you stay in full control of them.
- **Works with stylus pens**: S Pen and many others.
- **Always editable**: nothing gets flattened, so every stroke can be moved, restyled or erased later.
- **Private**: open source, no account, no ads, no tracking, no internet access.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/shardulvs/xnotes-android/releases/latest) | Signed APK |
| [F-Droid](https://f-droid.org/en/packages/com.xnotes) | Built reproducibly from source |
| [Google Play](https://play.google.com/store/apps/details?id=com.xnotes) | Automatic updates |

GitHub and F-Droid ship the same signed APK, so you can switch between them without reinstalling.

## Build from source

Use JDK 21 to run the pinned Gradle 9.3.1 / Android Gradle Plugin 9.1.1 build.
The app's Java and Kotlin bytecode target remains Java 17. Install Android SDK
Platform 37 (`platforms;android-37.0`), NDK 27.0.12077973 and CMake 3.22.1.

```bash
git clone https://github.com/Aoi979/xnotes-android.git
cd xnotes-android
./scripts/build-android.sh
```

The script uses `$HOME/Android/Sdk` and `/opt/android-studio/jbr` by default,
and respects `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_SDK_ROOT`. Override this
machine's paths in the gitignored `.android-build.env` file if needed:

```bash
XNOTES_ANDROID_SDK_ROOT=/path/to/Android/Sdk
XNOTES_JAVA_HOME=/path/to/jdk-21
```

If Android Studio bundles a newer JBR (for example, JBR 25), keep a separate
JDK 21 for this build and point `XNOTES_JAVA_HOME` at it. The IDE can continue
to use its own bundled runtime.

For Android Studio, set the Gradle JDK to `GRADLE_LOCAL_JAVA_HOME` and put
`java.home=/path/to/jdk-21` in the gitignored `.gradle/config.properties` file
([JDK configuration](https://developer.android.com/build/jdks)). The Android SDK
path goes in the gitignored `local.properties` file as `sdk.dir=/path/to/Android/Sdk`.

Builds default to one Gradle worker, a 1536 MiB JVM heap, a 512 MiB metaspace
limit, and Kotlin compilation in the Gradle process. A shared Ninja job pool
also serializes native compilation and linking, including Android Studio builds
([CMake job pools](https://cmake.org/cmake/help/v3.22/prop_gbl/JOB_POOLS.html)).
These settings bound compiler concurrency; other running applications still
contribute to total memory usage.

To build only for an ARM64 device or an x86_64 emulator:

```bash
./scripts/build-android.sh :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a
./scripts/build-android.sh :app:assembleDebug -Pandroid.injected.build.abi=x86_64
```

The unrestricted debug build includes ARM64, ARMv7 and x86_64; release builds
target ARM64. Release signing uses a separate gitignored `keystore.properties`
and keystore. Debug builds use the normal local Android debug key.
