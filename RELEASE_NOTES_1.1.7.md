# AAS VOSK BYDMate 1.1.7

## Window command routing

- «Открой окно» / «Закрой окно» controls only the driver's window.
- «Открой все окна» / «Закрой все окна» controls all four windows.
- Added «Открой передние окна» / «Закрой передние окна» for the two front windows.
- Added «Открой задние окна» / «Закрой задние окна» for the two rear windows.
- Individual driver, passenger, rear-left and rear-right commands remain supported.

## Исправление громкости
- Управление громкостью теперь сначала выполняется через shell-uid helper.
- Команды «громче» и «тише» инжектируют системные аппаратные keyevent 24/25.
- Абсолютная громкость пробует `cmd media_session`, `media volume` и `cmd audio`, затем использует универсальный keyevent fallback.
- При недоступном helper сохраняется резервное управление через Android AudioManager.
