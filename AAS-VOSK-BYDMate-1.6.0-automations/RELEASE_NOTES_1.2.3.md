# AAS VOSK BYDMate 1.2.3

## Overlay latency

- The accessibility overlay is attached once when the accessibility service connects and kept hidden between voice sessions.
- A steering-wheel activation now reveals the pre-attached overlay synchronously, before Vosk/model/audio work is queued.
- Removed the 120 ms fade-in from the critical activation path.
- Hiding the overlay no longer removes its WindowManager view; the view is removed only when the accessibility service is unbound or destroyed.
- Existing voice-response toggle, seat controls, refrigerator mode selection, Waze navigation and Wi-Fi changes are retained.
