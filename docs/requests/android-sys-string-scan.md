# android-sys: add core-system and core-media to the hard-coded string gate (for android-core-ui)

`HardcodedStringScanner.isScannedModule` (core-ui test sources) scans app-*, feature-*, core-ui, core-printing, core-sync and
dpc. The new android-sys modules show user-visible text too:

- `android/core-system` (permission gate screens, later update, support upload, logout dialogs)
- `android/core-media` (camera screen)

Please add `"core-system", "core-media"` to the set in `isScannedModule` and to the `it.inputs.files(...)` include list in
`android/core-ui/build.gradle.kts`. Both modules keep every string in `res/values` + `res/values-bn` today, so the gate
should pass on the first run. Until then android-sys checks the twins by hand in review.
