# Validation — AAS 1.6.0

## Выполнено

- Kotlin-компиляция `AutomationModels`, `AutomationRepository` и
  `AutomationEngine` с контрактными Android-стабами;
- Kotlin-компиляция `AutomationsActivity` с UI/ViewBinding-стабами;
- разбор всех XML-файлов стандартным XML-парсером;
- проверка уникальности Android string resources;
- проверка существования всех ViewBinding ID окна автоматизаций;
- проверка регистрации `AutomationsActivity` в manifest;
- поиск запрещённых голосовых automation API и trigger-кодов;
- проверка, что action `COMMAND` вызывает
  `TextCommandExecutor.execute(..., allowAiFallback = false)`;
- проверка версии `1.6.0` / versionCode `160`;
- проверка ZIP командой `unzip -t`.

## Ограничение среды

Полная Android Gradle-сборка не выполнена: wrapper требует Gradle 8.9 и Android
SDK/AGP, которых нет в текущей изолированной среде, а загрузка с
`services.gradle.org` недоступна. Поэтому перед установкой требуется собрать
проект в Android Studio и проверить события на конкретной версии DiLink.
