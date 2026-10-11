# Как собрать Millida Launcher для Android самому

<p align="center">
  <a href="https://github.com/millida/launcher-mobile/raw/main/.github/readme/build-guide.mp4"><img src=".github/readme/build-guide.gif" alt="Сборка APK из исходников: клонирование и сборка" width="100%"></a>
  <br>
  <a href="https://github.com/millida/launcher-mobile/raw/main/.github/readme/build-guide.mp4"><b>Смотреть видео целиком (MP4, 75 с)</b></a>
</p>

Три команды. Первая сборка дольше: Gradle один раз скачивает зависимости. Дальше — пара минут.

## 1. Поставь инструменты

Проще всего — [Android Studio](https://developer.android.com/studio): в ней уже есть JDK и Android SDK. Дальше «Settings» → «Languages & Frameworks» → «Android SDK»:

- вкладка «SDK Platforms» — **Android 15 (API 35)**;
- вкладка «SDK Tools» — **NDK (Side by side) 27** и **CMake 3.22.1**.

Без Android Studio — JDK 17 или новее и [command-line tools](https://developer.android.com/studio#command-line-tools-only):

```bash
sdkmanager "platforms;android-35" "ndk;27.0.12077973" "cmake;3.22.1"
```

Укажи, где лежит SDK, — переменной `ANDROID_HOME` или файлом `local.properties` в корне репозитория:

```properties
sdk.dir=C\:\\Users\\<имя>\\AppData\\Local\\Android\\Sdk
```

Android Studio создаёт этот файл сама, когда открываешь в ней проект.

## 2. Собери

```bash
git clone https://github.com/millida/launcher-mobile.git
cd launcher-mobile
./gradlew :app:assembleDebug
```

В PowerShell — `.\gradlew :app:assembleDebug`.

Хочешь собрать ровно ту версию, что раздаётся на сайте, — это последний коммит «Millida Mobile X.Y.Z» в [истории](https://github.com/millida/launcher-mobile/commits/main).

## 3. Забери результат

`app/build/outputs/apk/debug/app-debug.apk` — скопируй на телефон и открой или поставь по кабелю:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Нужен телефон на Android 8.0 и новее с 64-битным процессором (arm64).

## Чем твоя сборка отличается от нашей

| | Твоя сборка | С millida.net |
| --- | --- | --- |
| Код | Тот же, что в этом репозитории | Тот же: каждый выпуск здесь — коммит «Millida Mobile X.Y.Z» |
| Подпись | Отладочный ключ Android SDK | Ключ Millida. Его не публикует ни один разработчик: с ним любой выпустил бы приложение от нашего имени |
| Приложение | Отдельное — `net.millida.launcher.debug`: ставится рядом с официальным и его не трогает | `net.millida.launcher` |
| Java и графика | Скачаются при первом запуске игры | Уже внутри APK |

## Как проверить официальный APK

- Скачивай только с [millida.net/launcher](https://millida.net/launcher). Всё остальное — не наше.
- SHA-256 файла должна совпасть с опубликованной в [latest.json](https://launcher-storage.millida.net/mobile/latest.json): Windows — `certutil -hashfile <файл> SHA256`, macOS и Linux — `sha256sum <файл>`.
- Отпечаток ключа подписи — `apksigner verify --print-certs <файл>` — должен совпасть с `sha256_cert_fingerprints` в [assetlinks.json](https://millida.net/.well-known/assetlinks.json).

## Если не собирается

| Ошибка | Что сделать |
| --- | --- |
| `SDK location not found` | Не указан путь к SDK — `ANDROID_HOME` или `local.properties`, шаг 1 |
| `Android Gradle plugin requires Java 17 to run` | Поставь JDK 17 или новее либо укажи `JAVA_HOME` на папку `jbr` из Android Studio |
| Ошибка про NDK или CMake | Поставь NDK 27 и CMake 3.22.1 в «SDK Tools», шаг 1 |
| Что-то другое | [Заведи issue](https://github.com/millida/launcher-mobile/issues/new) и приложи вывод команды |
