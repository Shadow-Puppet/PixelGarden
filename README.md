# PixelSprite

A mobile-first pixel art & animation editor for Android, in the spirit of
Aseprite. Native Kotlin, FOSS-only dependencies, F-Droid-ready structure.

**Status: v0.1 scaffold — implements the core of the project plan (build steps
1–5). Written to spec but not yet compiled/tested on-device; expect a
first-build pass.**

## What's implemented

**Architecture (the "expensive to retrofit" parts, per the plan):**
- Aseprite-style data model: `Document → Layer × Frame → sparse Cel` with
  per-cel offset/size (`model/Document.kt`).
- Undo/redo via command pattern with **dirty-rect pixel diffs** — never
  full-canvas snapshots (`history/UndoManager.kt`). Structural ops
  (layer/frame add/remove/reorder) are undoable too.
- Custom `SurfaceView` canvas (not Compose): composites into an in-memory
  Bitmap, blits with nearest-neighbor scaling (`canvas/PixelCanvasView.kt`).
- Gesture arbitration: 1 pointer draws, 2-finger pinch zooms/pans (cancelling
  a partial stroke), 2-finger quick tap = undo.
- Stylus: tool-type detection, palm rejection (finger ignored while stylus
  active), **pressure/tilt captured in the input pipeline from v1** (dynamics
  land in the QoL pass).

**Editing:**
- Pencil (adjustable size), eraser, contiguous bucket fill, eyedropper.
- **Pixel-perfect stroke mode** (L-corner removal) for size-1 brushes.
- RGBA color mode + **Indexed mode** (up to 256 entries, index 0 transparent,
  live palette remapping, RGBA↔Indexed conversion).
- Layers: add/delete/reorder, opacity, visibility, lock.
- Frames + timeline strip with per-frame duration display.
- Swatch/palette bar; RGB color picker (edits palette entries in Indexed mode).

**Persistence:**
- Native `.pxs` format: zip of `manifest.json` + per-cel PNGs (RGBA) or raw
  index bytes (Indexed) (`io/NativeFormat.kt`).
- Flattened-frame PNG export. Save/Open/Export via SAF (no storage permission).

## Not yet implemented (per plan sequencing)

- QoL pass: onion skinning, playback, frame tags, pressure dynamics, cursor
  offset, grids/symmetry, copy/paste, resize/flip/rotate, palette
  import/export, reference layer, autosave, collapsible panels, per-frame
  duration editing UI.
- Advanced (future ideas §6–7): selections, shape tools, blend modes, GIF /
  sprite-sheet export, `.aseprite` interop, etc.
- Cel shrink-to-content (the model supports offsets/sizes; `celForEditing`
  currently allocates full-canvas cels for simplicity).

## Building

Standard Gradle Android build (Android Studio or `./gradlew assembleRelease`
with a generated wrapper). No exotic build steps, no proprietary deps —
compatible with F-Droid's reproducible-build requirements.

- `minSdk 24`, `targetSdk 35` — adjust per open decisions.
- No Gradle wrapper binary is committed; run `gradle wrapper --gradle-version 8.9`
  once, or open in Android Studio.

**CI:** `.github/workflows/build.yml` builds debug + unsigned-release APKs on
every push/PR (uploaded as workflow artifacts) and attaches them to the GitHub
Release when you push a `v*` tag. Release APKs are unsigned — add a signing
config + repo secrets (keystore) before distributing outside F-Droid, which
signs builds itself.

## Decisions taken (flagged in the plan as open — placeholders, change freely)

| Decision | Placeholder used | Notes |
|---|---|---|
| License | **GPL-3.0-or-later** | Recommended in plan; add the official text as `LICENSE` |
| minSdk | **24** | As suggested |
| App name / package id | **PixelSprite / `dev.pixelsprite`** | Rename before publishing |
