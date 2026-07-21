# AAS 1.5.1 — offline smart comfort presets

## Added

- Local recognition of natural temperature complaints in Russian and Ukrainian.
- Four deterministic comfort profiles: `HOT`, `VERY_HOT`, `COLD`, `VERY_COLD`.
- Exact requested extreme profiles:
  - very cold: 28 °C, fan 3, all supported seat heaters 2, steering heat 1;
  - very hot: 20 °C, all supported seat ventilation 2, all available heating off.
- Milder local defaults for ordinary `Мне жарко` and `Мне холодно` phrases.
- Spoken result summaries for every comfort profile.
- Ukrainian translations for the new spoken summaries.

## Routing changes

- Smart comfort phrases execute before the generic parser and before OpenAI.
- Smart presets can participate in fully local multi-command chains.
- Mixed incomplete chains are escalated to OpenAI as a whole; recognized parts
  are not executed separately.
- Questions, negated complaints and contradictory hot/cold statements are not
  treated as comfort commands.

## Compatibility

- `Seat.ALL` uses the current validated driver and front-passenger channels.
- Existing YouTube, TTS, authentication, ADB helper, BYDMate and OpenAI logic is
  unchanged.

## Version

- `versionCode=151`
- `versionName=1.5.1`
