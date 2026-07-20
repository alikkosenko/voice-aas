# Сборка AAS на macOS

Проект использует Android Gradle Plugin 8.7.3, Gradle 8.9 и JDK 17.

## Через Android Studio

1. Распакуй архив.
2. `File → Open` и выбери корневую папку `AAS-ADB`, не папку `app`.
3. `Settings → Build, Execution, Deployment → Build Tools → Gradle`.
4. Выбери JDK 17.
5. Если wrapper ещё не создан, выбери локальный Gradle 8.x или создай wrapper командами ниже.
6. Выполни `File → Sync Project with Gradle Files`.
7. `Build → Build Bundle(s) / APK(s) → Build APK(s)`.

Готовый APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Создание Gradle Wrapper

```bash
brew install gradle@8 openjdk@17

export PATH="/opt/homebrew/opt/gradle@8/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

cd /путь/к/AAS-ADB
rm -rf .gradle
/opt/homebrew/opt/gradle@8/bin/gradle wrapper \
  --gradle-version 8.9 \
  --distribution-type bin

chmod +x gradlew
./gradlew clean assembleDebug
```

На Intel Mac Homebrew обычно находится в `/usr/local`, а не `/opt/homebrew`.
