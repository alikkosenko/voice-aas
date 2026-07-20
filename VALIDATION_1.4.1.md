# Validation 1.4.1

Проверено локально:

- XML всех ресурсов разбирается без ошибок.
- Все `@string` существуют в RU и UK ресурсах.
- Все ViewBinding ID, используемые `MainActivity`, присутствуют в `activity_main.xml`.
- Все sealed-команды `Vehicle` и `System` покрыты соответствующими dispatcher/adapter `when`.
- Все действия OpenAI schema имеют локальное преобразование в `VoiceCommand`.
- `CommandParser` скомпилирован и прогнан на командах климата, музыки, YouTube, окон, переднего обдува и диапазонов сидений 3/5.
- `OpenAiCommandPlanner`, `CommandDispatcher`, `AndroidActions` и `BydVehicleAdapter` прошли отдельную Kotlin-компиляцию со стабами Android/API-зависимостей.
- Полная Gradle-сборка не выполнялась: среда не имеет DNS-доступа к `services.gradle.org` для загрузки Gradle 8.9.
- Новые FID `501219362` и `501219394` взяты из предоставленного BYDMate и требуют физической проверки в автомобиле.
