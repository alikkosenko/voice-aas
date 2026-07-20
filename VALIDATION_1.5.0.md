# Validation 1.5.0

- `VoiceCommand.kt`, `CommandParser.kt` and `LocalCommandPlanner.kt` compiled with the installed Kotlin compiler.
- Tested local direct routing, fully local multi-command routing, AI fallback on natural language, AI fallback on incomplete chains, YouTube titles containing conjunctions, and Ukrainian multi-command phrases.
- `versionCode=150`, `versionName=1.5.0`.
- Full Android Gradle compilation was not possible in the build environment because the wrapper attempted to download Gradle 8.9 from `services.gradle.org`, which was unavailable.
