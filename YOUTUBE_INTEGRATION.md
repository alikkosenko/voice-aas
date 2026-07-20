# YouTube ReVanced integration — AAS 1.2.9

## Voice forms

Russian:

- `Ютуб Linkin Park`
- `YouTube обзор BYD Seal`
- `Ютуб найди ремонт подвески`
- `Найди на ютубе ремонт подвески`
- `Открой YouTube` — open without search

Ukrainian:

- `Ютуб Linkin Park`
- `YouTube огляд BYD Seal`
- `Ютуб знайди ремонт підвіски`
- `Знайди на ютубі ремонт підвіски`
- `Відкрий YouTube` — open without search

## Dispatch sequence

1. The package selected in AAS settings is tried first.
2. AAS sends the dedicated YouTube action `com.google.android.youtube.action.open.search`.
3. If it is unavailable, AAS opens the HTTPS results page explicitly in the same package.
4. Native `vnd.youtube` and generic Android `ACTION_SEARCH` are used as additional fallbacks.
5. Common original/ReVanced/ReVanced Extended package IDs are tried after the selected package.

## Validation performed

- `VoiceCommand.kt` and `CommandParser.kt` compiled with `kotlinc`.
- Russian and Ukrainian parser smoke tests passed.
- `UkrainianTranslator.kt` compiled and dynamic YouTube response translations passed.
- `CommandDispatcher.kt` compiled against interface stubs to verify exhaustive dispatch.
- Android XML resources parsed successfully.
- Full Android APK build was not run because the environment has no Android SDK.
