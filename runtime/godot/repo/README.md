# Vendored Godot Android library

`org/godotengine/godot/4.7.1-stable/godot-4.7.1-stable.aar` — **custom-built from source**, not the
stock upstream release. Built 2026-07-20 from a clean `git clone --branch 4.7.1-stable --depth 1
https://github.com/godotengine/godot.git` via `scons platform=android target=template_release
arch=arm64 disable_path_overrides=no generate_apk=no` (NDK 29.0.14206865, the exact version pinned by
this tag's `platform/android/detect.py`/`config.gradle`), then packaged into a real AAR via
`./gradlew :lib:assembleTemplateRelease` from `platform/android/java` (Gradle picks up the
already-built `.so` from `lib/libs/release/arm64-v8a/` rather than re-invoking scons itself — see
`excludeSconsBuildTasks()` in `platform/android/java/build.gradle` — so the two steps together are the
documented official workflow, not a workaround).

**Why**: the stock GitHub-release AAR (previously vendored here) is compiled with
`disable_path_overrides` at its default (`true`), which hard-rejects `--path`/`--main-pack` at
startup ("this Godot binary was compiled without support for path overrides", `main/main.cpp`) —
confirmed on-device on the Honor Magic 7 Pro. That flag is a hard blocker for both AI-generated
dynamic Godot projects (each needs its own project directory) and the on-device Editor port (which
must open more than one fixed project). Verified fixed: the custom `.so` contains zero occurrences of
that error string (`grep -a -c "path overrides"`), vs. 5 in the old stock `.so` — confirmed via a
byte-level diff, not just "no crash in logcat."

Single-ABI (arm64-v8a only, matching this module's `abiFilters` and the Magic 7 Pro test device) —
the stock release AAR was multi-ABI (arm32/arm64/x86/x86_64); confirmed via AAR entry-list diff that
this is the *only* structural difference (all Java/Kotlin classes, manifest, resources identical).

## `org/godotengine/godot-tools/4.7.1.stable/godot-tools-4.7.1.stable.aar` (editor flavor)

Also **custom-built from source**, same checkout/NDK as above, via `scons platform=android
target=editor arch=arm64 disable_path_overrides=no generate_apk=no` (note: no `store_release`/
`dev_build`/`debug_symbols` flags passed — SCons places editor output under
`platform/android/java/lib/libs/tools/debug/` regardless, since `store_release=true` is reserved for
Godot's own officially-signed Play Store editor publish, not general internal builds), packaged via
`./gradlew :lib:assembleEditorDebug`.

Previously vendored from Maven Central's `org.godotengine:godot-tools:4.7.1.stable` — **that published
artifact turned out to ALSO reject `--path`** (5 occurrences of the "compiled without support" string
in its `.so`, identical count to the old stock template build), despite being editor-flavored classes.
Whatever build flags Maven Central's publish pipeline used for that artifact, `disable_path_overrides`
was evidently left enabled there too — this custom build supersedes it and is confirmed clean (0
occurrences of that string).

To rebuild either flavor: same `godot-src` checkout and NDK install, just `target=editor` vs.
`target=template_release` in the scons invocation, and `:lib:assembleEditorDebug` vs.
`:lib:assembleTemplateRelease` for packaging.

Historical stock-release provenance (superseded, kept for reference):
- Original source: https://github.com/godotengine/godot/releases/download/4.7.1-stable/godot-lib.4.7.1.stable.template_release.aar
- Original SHA-256: `9c4bc60f915095a4f75f306340023c4519a1975e7886ffd5cdccb9a594fadd81`, size 103,241,219 bytes

Not resolved via Maven Central: Godot's published `org.godotengine:godot` Central coordinate lags
upstream releases (currently stuck at `4.4.1.stable` as of this writing) — GitHub Releases was
upstream's stock distribution channel, though this project now builds from source instead of relying
on either.

Laid out as a local Maven repository (declared in the root `settings.gradle.kts`'s
`dependencyResolutionManagement.repositories`, `maven { url = uri(...runtime/godot/repo) }`) rather
than a plain `files(...)`/flatDir dependency: AGP rejects a direct local `.aar` file dependency
inside a module that itself produces an AAR (`runtime:godot` is a library module), and Gradle's
flatDir repository type didn't resolve reliably here. The accompanying POM is minimal/hand-written
and does not declare the AAR's own transitive dependencies — those (`androidx.fragment:fragment-ktx`,
`androidx.documentfile:documentfile`, confirmed from `platform/android/java/lib/build.gradle` at the
same tag) are declared explicitly in `runtime/godot/build.gradle.kts` instead.

To upgrade: create a new `4.<version>/` directory here with the renamed AAR and an updated POM
(bump the `<version>`), update the coordinate in `build.gradle.kts`'s
`implementation("org.godotengine:godot:...")`, and update this README's tag/size.
