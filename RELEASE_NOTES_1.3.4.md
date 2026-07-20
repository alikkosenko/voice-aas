# AAS 1.3.4

## Changes

- Voice replies are fully disabled. The assistant still uses short recognition beeps and the visual overlay.
- The voice-reply switch and speech test button are hidden.
- YouTube/ReVanced search now opens a standard YouTube results URL in the selected package first, then tries known packages and Android URL resolution.
- Seat heating and ventilation now send compatible commands to both known BYD seat-control channels instead of trusting a cached transport-level success.
- Seat levels are normalized to 1–3; zero disables heating or ventilation.
- The primary seat channel applies the level before the enable switch for DiLink climate compatibility.
- HTTP password authorization and runtime/ADB recovery remain enabled.
