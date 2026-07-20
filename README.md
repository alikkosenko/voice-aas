# AAS-VOSK-BYDMate 1.4.1

AAS — офлайн-голосовой помощник для мультимедийных систем BYD/DiLink:

```text
кнопка руля → Vosk → локальный парсер или OpenAI JSON-план → Android/BYD-действия
```

Приложение не использует облачное распознавание и не ведёт постоянную запись. Vosk распознаёт речь локально. Опционально итоговый текст можно отправить в OpenAI, чтобы понять косвенную фразу или разбить её на несколько последовательных команд.

## Основные возможности

### Голос и локализация

- отдельные офлайн-модели Vosk для русского и украинского языков;
- полноценные русские и украинские варианты команд;
- локализованный интерфейс, оверлей, состояния, ошибки и ответы;
- голосовые ответы полностью отключены; остаются оверлей и короткие сигналы распознавания;
- опциональное понимание свободной речи через OpenAI;
- до 12 разрешённых команд из одной фразы с последовательным выполнением;
- автоматический fallback на локальный парсер 1.3.4;
- полный двуязычный список команд внутри приложения, разбитый по категориям.

### Управление автомобилем через BYD autoservice

- климат: питание, автоматический режим, температура 16–30 °C, вентилятор 1–7, рециркуляция, вентиляция без охлаждения и передний обдув;
- задний обогрев/обогрев зеркал;
- обогрев и вентиляция передних сидений с выбираемым диапазоном 1–3 или 1–5 и одновременной отправкой в каналы dev=1000/dev=1001;
- обогрев руля, уровни 1–3;
- четыре окна, группы окон, положение 0–100%, проветривание на 10% и открытие наполовину;
- люк и шторка;
- замки дверей;
- салонный свет, атмосферная подсветка и ДХО;
- передний и задний багажники;
- холодильник: охлаждение −6…+15 °C и нагрев 35…50 °C;
- чтение SOC и давления четырёх шин;
- 54 явных action_name, включая импортированные из BYDMate команды переднего обдува и вентиляции без охлаждения, через формат `BYDMate <action_name> [value]`.

Команда запаса хода распознаётся, но прямой проверенный autoservice-параметр для неё пока не подключён.

### Android и мультимедиа

- точная системная громкость по уровням, явные проценты, относительное изменение, mute, play/pause и переключение треков;
- Bluetooth и Wi-Fi;
- запуск выбранных приложений музыки, навигации, YouTube и радио; отдельные режимы поиска и воспроизведения для YouTube/музыки;
- маршруты через Waze, повтор последнего маршрута и поиск ближайших объектов;
- переход на главный экран.

## Безопасность

- запись в автомобиль выключена по умолчанию и включается только после подтверждения;
- чтение SOC и шин не требует разрешения на запись;
- команды окон, люка и потенциально опасные действия выполняются только после проверки нулевой скорости там, где это предусмотрено адаптером;
- AAS не перебирает неизвестные `fid` и не даёт приложению произвольный shell;
- четыре fallback-канала сидений из competitor-v80 помечены как непроверенные.

Идентификаторы BYD могут отличаться между моделями и версиями DiLink. Первую проверку выполняй на стоящем автомобиле: SOC и шины, затем климат/сиденья, затем окна и остальные функции.

## Первый запуск

1. Установить AAS и разрешить микрофон.
2. Нажать «Подключить ADB helper» и подтвердить RSA-ключ на DiLink, если появится запрос.
3. Включить или восстановить Accessibility через приложение.
4. Выбрать русский либо украинский язык.
5. При необходимости сохранить OpenAI API key, проверить API и включить AI-режим.
6. Проверить команды чтения: «Сколько заряда» и «Давление в шинах».
7. После успешного чтения включить разрешение команд автомобилю.
8. Назначить кнопку руля и тестировать управление только на неподвижном автомобиле.

## Справочник команд

В приложении нажми **«Все голосовые команды»**. Диалог содержит:

- естественные команды по разделам;
- переключение между русским и украинским справочником;
- полный список импортированных BYDMate action_name с диапазонами значений.

Тот же справочник находится в [`COMMANDS.md`](COMMANDS.md).

## Основные файлы

```text
app/src/main/java/com/aas/app/commands/CommandParser.kt
app/src/main/java/com/aas/app/voice/VoiceController.kt
app/src/main/java/com/aas/app/voice/SpeechOutput.kt
app/src/main/java/com/aas/app/UkrainianTranslator.kt
app/src/main/java/com/aas/app/vehicle/BydVehicleAdapter.kt
app/src/main/java/com/aas/app/vehicle/BydWriteAllowlist.kt
app/src/main/java/com/aas/app/helper/HelperDaemon.kt
app/src/main/java/com/aas/app/accessibility/AasAccessibilityService.kt
```

## Сборка

Требования:

- Android Studio;
- JDK 17;
- Android SDK 35;
- Android Gradle Plugin 8.7.3;
- Gradle 8.9.

Перед `preBuild` скрипт `scripts/prepare_vosk_models.py` помещает обе модели в assets. Интернет требуется только на машине разработчика при первой подготовке моделей, если их ещё нет в кэше.

```text
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

Результат debug-сборки:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Происхождение BYDMate-кода

Механика локального ADB, shell helper, Binder-транспорта и AccessibilityService адаптирована из BYDMate.

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman)

Для этих частей действует `LICENSE-BYDMATE`. Архив предназначен для некоммерческого тестирования и личного использования, если автор BYDMate не предоставил отдельное разрешение.

## YouTube ReVanced и музыка

В настройках AAS выбери установленный YouTube/ReVanced и музыкальное приложение.

- `Найди на YouTube обзор BYD Seal` — открывает результаты поиска.
- `Включи на YouTube Linkin Park Numb` — отправляет Android media-search intent, который на поддерживаемых сборках запускает верхний результат; иначе открывается поиск.
- `Найди песню Кино Группа крови` — поиск в выбранном музыкальном приложении.
- `Включи песню Кино Группа крови` — попытка запустить найденный трек.

Выбранный пакет всегда имеет приоритет, затем используются известные fallback-пакеты.


## Remote password gate

1. Разверни Python-сервер из `server/`.
2. Укажи HTTP-адрес сервера на экране входа, например `http://IP/api/check-password`.
3. После успешной проверки запускаются Vosk, ADB helper и обработка кнопки руля.

## Version 1.3.4

Voice replies are disabled. YouTube search uses results-page deep links. Seat heating and ventilation use dual-channel BYD compatibility writes with levels 1–3.

## Voice responses in 1.4.2

AAS can speak full command results through an installed Android TTS engine. Automatic mode prefers RHVoice, then falls back to the system default and other installed engines. The engine and speech rate are selectable in the app. See `TTS_SETUP.md`.

## Local-first hybrid routing in 1.5.0

AAS now tries the built-in parser before OpenAI. Direct commands and completely
recognized command chains execute offline. OpenAI is used only for natural,
ambiguous or partially unknown requests. See `LOCAL_FIRST_ROUTING.md` and
`RELEASE_NOTES_1.5.0.md`.
