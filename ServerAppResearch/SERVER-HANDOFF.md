# First-client USB ownership failure

Device: Xiaomi 15 / Android 17 / Air 2 Ultra, 2026-11-02.

This is separate from Stable Screen 0.1.x's rejected external Activity launch. A clean Server 1.0.0 process reached READY, switched the glasses to 3840×1080, and launched Spatial Debug 1.0.3 from its own 6DoF button. Binder connected and runningClients briefly became 1, but the client then failed four attempts to open its MCU control channel with errno 16 / EBUSY. NativeGlasses Start returned 101 / ControlChannelInitFail. The glasses display had no application content and could only mirror the phone. Evidence: `evidence/server-first-ar-start.log`.

Official Control Glasses' GlassesManager registers activity lifecycle callbacks. With its default autoPauseResume=true, Activity pause calls NRServiceControl.onNRPause and resume calls onNRResume. The rebuilt Server called neither operation. Its initial device control remained active as the first client tried to claim the same USB interface.

Server 1.0.1 restores those native ownership transitions on the existing runtime worker. Its launch button explicitly awaits successful onNRPause before starting a client; an 8-second failure/timeout leaves the client unlaunched. Handoff intent prevents zero-client callbacks from reclaiming the control channel during launch. Returning to Server resumes native control only when no running/paused clients exist. A background Server also pauses after delayed SDK startup completes. The Binder service and proprietary native files are preserved; pausing native device control does not stop the shared service.

The stop path also attempts pause before stopSdk. In the 1.0.0 baseline, stopSdk remained blocked until the Server process was restarted. In the 1.0.1 test below, the same stop button returned to IDLE normally.

Delivery: `Build/Android/XrealServer-v1.0.1.apk`, package dev.xreal.server, versionCode 11, versionName 1.0.1-handoff. Signature remains the existing local Server development certificate. No root, firmware changes or system setting mutation is part of the fix.

Validation before installation: Java compilation, DEX packaging, APK signature, alignment, preservation of all 6,381 original runtime class definitions and 15 native libraries, no additional duplicate classes.

## Device validation

The user manually installed Server 1.0.1, versionCode 11, on 2026-11-02. The installation's first native service start reached READY. The first 6DoF launch recorded onNRPause=true before startActivity; the client then opened Generic Control successfully, MCU/IMU/gray camera started, CreateSession completed and stereo rendering began. No EBUSY / ControlChannelInitFail occurred. The external screenshot `evidence/server-v101-first-ar.png` shows actual spatial-demo stereo output at 59 fps, MODE_6DOF, position and rotation true, SessionTracking. This is distinct AR content, not a mirror of the phone UI.

After ending the client, Server returned to zero clients and onNRResume=true. The stop button then recorded onNRPause=true followed by IDLE (service stopped), without restarting the Server process. Relevant files: `evidence/server-v101-first-client-native.log`, `evidence/server-v101-lifecycle.log` and `evidence/server-v101-first-start.log`.

Physical unplug/reconnect was then confirmed by the user's action and the Server's display/USB removal and addition events. The glasses returned in 1920×1080 mode and USB consent was renewed. The same Server process started its runtime, switched to 3840×1080, paused its own control channel, and launched the first client without retry. MCU/IMU initialized successfully; `evidence/server-v101-reconnect-first.png` shows 60 fps stereo output with valid position/rotation and SessionTracking. Native details are in `evidence/server-v101-reconnect-native.log`.

Stable Screen 0.2.0 also started under Server 1.0.1. Whole-screen sharing remained active while Android Settings was foreground. Actual external screenshots show the settings content in both eyes. Rotation changed the existing capture display from 718×1600 to 1600×718, then back, without a new Unity process; the original phone rotation policy was restored. Additional results are kept in `evidence/stable-screen-device-validation.json`.
