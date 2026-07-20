# Editor port — scaffolded, confirmed NOT buildable into `:app` as a same-APK module

This module contains a genuine, working scaffold for porting the real Godot Editor UI into Orion
(`EditorHostActivity`/`EditorLaunch`/`EditorProjectSeed`, mirroring `runtime:godot`'s
`GodotHostActivity`/`GodotLaunch` pattern for the game engine, plus a seed project so the editor has
a writable project directory to open — it can't write into `runtime:godot`'s read-only bundled
assets). It is **not** currently included as an `:app` dependency (see `app/build.gradle.kts`'s
comment at the `runtime:godot`/`runtime:godot-editor` dependency block) — confirmed via a real build
attempt that it cannot coexist with `runtime:godot` in one APK. Read this before resuming that work.

## The wall (confirmed 2026-07-20, not a configuration issue)

Both `org.godotengine:godot` (template/game engine, vendored in `runtime/godot/repo/`) and
`org.godotengine:godot-tools` (editor engine, vendored in the same repo, both custom SCons builds
with `disable_path_overrides=no`) ship the **identical Java/Kotlin classes** under
`org.godotengine.godot.*` — same package, same class names, same version. `GodotLib.java`
hardcodes:

```java
System.loadLibrary("godot_android");
```

No override, no parameterization. Adding both as `:app` dependencies (one via `runtime:godot`, one
via a new `runtime:godot-editor`) and building produced two confirmed failures in sequence:

1. **~50 duplicate-class errors** (`checkDebugDuplicateClasses`) — `org.godotengine.godot.GodotActivity`,
   `Godot`, `GodotLib`, `GodotService`, `RemoteGodotFragment`, etc. all collide, because both AARs
   define the same classes.
2. After excluding/deduping those (not actually attempted — the real blocker is next): **the native
   library merge itself fails** — `2 files found with path 'lib/arm64-v8a/libgodot_android.so'`, one
   from each AAR. This is the actual hard wall: **Android supports exactly one native library file
   per name per installed APK**, and every process of one app (`:app`, `:godot`, a hypothetical
   `:godot_editor`) loads from that same single installed APK's `jniLibs/` directory. There is no
   per-process or per-module native library namespacing available to a normally-installed
   (non-Play-Store-split, non-dynamic-feature) APK. Since `System.loadLibrary("godot_android")` is
   compiled into the AAR's own bytecode (not something Orion's build config can rename or redirect),
   the game engine binary and the editor engine binary genuinely cannot both live in Orion's single
   installed APK, no matter how the Gradle modules/processes/manifests are structured around them.

## What would actually work (not attempted — real scope, flagged rather than started blind)

- **A genuinely separate installed APK** for the editor (the alternative the user explicitly passed
  over in favor of "port the editor into Orion," now confirmed to be the only Android-compatible way
  to get the *real* editor engine binary on-device at all, short of the option below). Orion could
  still launch it via `Intent` (`ACTION_VIEW` on a project directory, exactly like `BaseGodotEditor`'s
  own `handleStartIntent` already does for `.godot` files) and feel reasonably integrated even though
  it's two APKs — this was already confirmed technically feasible earlier in this project's research.
- **Android App Bundle dynamic feature modules** with genuinely isolated per-module native libraries,
  installed on demand — a real AGP capability, but built around Play Store split delivery; sideloaded/
  manually-installed builds (this project's entire on-device workflow so far, per the many
  `adb install -r` steps throughout this project's history) support dynamic features far less
  reliably, and standing this up is a substantial, separate infrastructure effort with real
  uncertainty about whether it resolves the *same* underlying native-library-name collision (Play
  Feature Delivery isolates *feature* modules' own libraries from the base module and from each
  other, but has not been verified against this specific same-named-library-across-modules case) —
  flagged, not attempted.

## What's kept here

`EditorHostActivity`/`EditorLaunch`/`EditorProjectSeed` and the `editor_seed/` bundled project are
real, correct code — they would work as-is if this module were ever built as its own standalone APK,
or if a dynamic-feature-module approach is pursued and confirmed workable. Kept as a starting point,
not wired into anything right now.
