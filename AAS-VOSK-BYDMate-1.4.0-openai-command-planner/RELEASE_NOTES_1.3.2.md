# AAS 1.3.2

Runtime startup regression fix after adding remote password authorization.

- Restored unconditional process-wide `AasRuntime` construction.
- Restored early Vosk model preload from the proven 1.2.8 sequence.
- Authorization still gates the foreground service, steering-wheel event handling and commands.
- Successful login now explicitly starts `VoiceReadyService` with `ACTION_REPAIR_NOW`.
- `MainActivity` also requests an immediate runtime repair.
- `VoiceReadyService` calls `startForeground()` before runtime/preload work to avoid Android's foreground-service startup timeout.
- HTTP/HTTPS password check and editable server URL remain unchanged.
