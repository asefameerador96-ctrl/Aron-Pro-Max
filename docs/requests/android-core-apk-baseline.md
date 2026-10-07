# android-core: SR release APK baseline after the in-app camera (for infra, owner of tools/ci/apk-size-gate.py)

**What:** lane/android-core wires the F-SYS-030 camera (android-sys `core-media`: CameraX core, camera2, lifecycle and
view, plus exifinterface) into ARON SR. CI run 37598814032 measured sr-release armeabi-v7a at 4.37 MB, +16.1 % over
the 3.76 MB baseline (gate fails at +15 %); the other SR ABIs warn. The growth is the camera itself: every CameraX
artifact in core-media is used (`CameraSelector`, `ImageCapture`, `Preview`, `ResolutionSelector`,
`ProcessCameraProvider`, `PreviewView`), and CameraX carries a small JNI library per ABI. Release builds already use R8
and resource shrinking. The absolute budget (30 MB download, 70 MB installed) is far away.

**AMO and TSO** no longer link core-media (no camera there yet), so their growth from the first push is gone.

**Ask:** once the lane head's Release APKs job has run, refresh the `sr-release` entries of
`tools/ci/apk-size-baseline.json` from that run (`apk-size-gate.py --write-baseline`), as the F-SYS-030 camera's
planned cost (docs/31 s1: a feature that adds size raises the baseline with a reason; this file is the reason).
android-core does not edit the infra lane's baseline itself.
