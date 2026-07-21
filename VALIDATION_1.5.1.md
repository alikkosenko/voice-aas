# Validation 1.5.1

Validated with the installed Kotlin/JVM compiler:

- `VoiceCommand.kt`
- `CommandParser.kt`
- `SmartComfortPresetPlanner.kt`
- `LocalCommandPlanner.kt`

Tested phrases and routes:

- `Мне очень холодно` → `VERY_COLD`, 8 local commands, no AI.
- `Мне холодно` → `COLD`, 8 local commands, no AI.
- `Мне очень жарко` → `VERY_HOT`, 8 local commands, no AI.
- `Мне жарко` → `HOT`, 10 local commands, no AI.
- Ukrainian equivalents for hot and cold profiles.
- Strong synonyms: `Я замерзаю`, `Я плавлюсь`.
- Negation, questions and contradictory statements do not trigger a preset.
- `Мне очень жарко и включи музыку` executes as a complete local chain.
- `Мне жарко и сделай как вчера` escalates the complete phrase to AI.
- YouTube titles containing `и` remain a single YouTube command.

`UkrainianTranslator.kt` was separately compiled and the four new exact
translations were checked.

- `versionCode=151`, `versionName=1.5.1`.
- Full Android Gradle compilation was not possible in the build environment
  because no cached Gradle 8.9 distribution or Android Gradle Plugin was present.
