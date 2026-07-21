# TTS setup

## Recommended configuration

1. Install RHVoice as a separate Android TTS application.
2. Install Russian and/or Ukrainian voice data in RHVoice.
3. Open AAS → Voice responses.
4. Select `RHVoice`, or leave `Automatic` to prefer it when installed.
5. Select speech speed (default 1.05×).
6. Press `Test speech output`.

RHVoice is not copied into the AAS APK. AAS communicates with it through the standard Android TextToSpeech service interface. If RHVoice is unavailable, AAS tries the system default and other installed engines.

## Audio routing

AAS uses the public navigation-guidance/media path. It does not use the private BYD BTTS stream 17 because that stream can report successful playback while remaining inaudible on some DiLink versions.
