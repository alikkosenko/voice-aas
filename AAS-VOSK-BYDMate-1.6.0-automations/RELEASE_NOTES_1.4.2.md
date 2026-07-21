# AAS 1.4.2

Based on the working 1.4.1 command/runtime branch.

## TTS

- Spoken command results are enabled again.
- Uses the standard Android `TextToSpeech` API; no neural voice model is bundled.
- RHVoice (`com.github.olga_yakovleva.rhvoice.android`) is preferred in automatic mode.
- User can select any installed Android TTS engine.
- Russian and Ukrainian locales follow the AAS language setting.
- Configurable speech rate: 0.85×–1.25×.
- TTS is initialized with the process and reused to reduce first-response latency.
- Full result text is spoken, including multi-command summaries and errors.
- Audio is routed through `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and `STREAM_MUSIC`.
- The private BYD stream 17 is no longer used for normal TTS.
- Audio focus uses transient ducking so music is lowered while AAS speaks.
- Recognition stops any active response before reopening the microphone.
- If every TTS engine fails, the small bundled success/error acknowledgement remains as a final fallback.

## Upgrade behavior

Versions 1.3.4–1.4.1 forcibly disabled voice responses. On the first 1.4.2 launch, voice responses are enabled once and become user-controlled through the settings switch.

## Unchanged

- HTTP password authorization.
- OpenAI command planner and local fallback parser.
- YouTube/ReVanced integration.
- Seat heat/vent dual-channel logic.
- Expanded 1.4.1 climate/window/seat commands.
- ADB helper, Vosk runtime and steering-wheel activation.
