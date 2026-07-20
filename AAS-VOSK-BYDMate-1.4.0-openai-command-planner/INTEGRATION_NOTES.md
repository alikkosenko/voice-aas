# AAS Voice for BYD — BYDMate runtime integration

Version: 1.1.0-bydmate-voice-core

## Kept from AAS

- Offline Vosk recognition (Russian/Ukrainian)
- AAS command parser and dispatcher
- App launch commands
- BYD autoservice command catalogue
- Voice responses and UI
- VIN display

## Runtime mechanics adapted from BYDMate

- Persistent raw RSA ADB keypair under app files
- Binary ADB protocol to `127.0.0.1:5555`
- AUTH signature and ADB-format RSA public key flow
- 60-second wait for the native DiLink authorization dialog
- `app_process` shell helper lifecycle
- ServiceManager Binder bridge to `autoservice`
- remove/wait/re-add Accessibility secure-setting activation
- real AccessibilityService liveness flag
- steering-wheel learn mode and key consumption
- background watchdog that re-asserts helper/accessibility

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman)

See `LICENSE-BYDMATE`. The BYDMate-derived parts are licensed for noncommercial purposes.

## Validation performed in this environment

- Python build helper syntax check
- all Android XML files parsed successfully
- Kotlin source delimiter/truncation checks
- required integration files and component names verified

A full Android/DiLink runtime test cannot be performed without an Android SDK and the target BYD head unit.
