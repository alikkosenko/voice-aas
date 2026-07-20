# AAS 1.3.0

- Added remote password authorization screen.
- Password is sent only to the configured HTTPS endpoint and is never stored in the APK preferences.
- Main UI, boot receiver, foreground voice service and steering-wheel activation are all gated by authorization.
- Successful authorization is stored in device-protected preferences for a configurable period (default: 30 days).
- Added a FastAPI password server with PBKDF2-SHA256 hashing and basic per-IP rate limiting.
