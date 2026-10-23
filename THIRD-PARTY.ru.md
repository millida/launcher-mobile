# Сторонние компоненты

[English version: THIRD-PARTY.md](THIRD-PARTY.md)

Millida Launcher для Android распространяется под лицензией [GPL-3.0-only](LICENSE).
Ниже — компоненты третьих сторон, которые попадают в приложение или в рантайм игры,
который оно скачивает, и их лицензии.

Точное происхождение каждого файла рантайма (адрес скачивания, SHA-256, ревизия,
файлы лицензий) записано в [scripts/runtime_sources.json](scripts/runtime_sources.json).
Когда рантайм меняется, меняется и этот файл, и при расхождении прав он, а не этот
список.

## Рантайм игры

Java-машина, рендереры и нативные библиотеки, которые нужны игре, не собираются из
исходников этого репозитория. Приложение скачивает их при первом запуске игры и
сверяет каждый файл с его SHA-256; в APK с millida.net они уже внутри. В папке
каждого компонента лежат тексты лицензий и пояснение.

**Java**

| Компонент | Лицензия | Источник |
| --- | --- | --- |
| OpenJDK 8 для Android | GPL-2.0 с Classpath Exception | [AngelAuraMC/angelauramc-openjdk-build](https://github.com/AngelAuraMC/angelauramc-openjdk-build) |
| OpenJDK 21 и 25 для Android | GPL-2.0 с Classpath Exception | рантайм из [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2); исходники — openjdk/jdk21u, openjdk/jdk25u |

**Оконный слой и рендереры**

| Компонент | Лицензия | Источник |
| --- | --- | --- |
| Оконный слой и помощники (`libpojavexec`, `libdriver_helper`, `libexithook`, `liblinkerhook`) | GPL-3.0 / LGPL-3.0 | [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2) и PojavLauncher. `libpojavexec` изменён: рендерер читается из `MILLIDA_RENDER` |
| Классы совместимости GLFW и LWJGL 2 (`lwjgl-glfw-classes.jar`) | BSD-3-Clause (LWJGL 3.3.6) и GPL-3.0 (ZalithLauncher2) | изменены так же; собственный `millida-glfw.jar` лаунчера собирается из `bridge/` этого репозитория |
| gl4es | MIT | [ptitSeb/gl4es](https://github.com/ptitSeb/gl4es) |
| Krypton Wrapper (NG-GL4ES) | MIT | [BZLZHH/NG-GL4ES](https://github.com/BZLZHH/NG-GL4ES) |
| MobileGlues 2.0.0 | LGPL-2.1-only | [MobileGL-Dev/MobileGlues](https://github.com/MobileGL-Dev/MobileGlues), неизменённый двоичный файл из официального выпуска; это отдельная библиотека, её можно заменить сборкой из тех же исходников. Внутри статически слинкованы glslang (BSD-3-Clause и другие), SPIRV-Cross (Apache-2.0), cJSON (MIT), FidelityFX FSR 1 (MIT) и xxHash (BSD-2-Clause) |
| ANGLE | BSD-3-Clause | Google ANGLE |
| Mesa (Zink, `libEGL_mesa`, `libglapi`) | MIT | Mesa 3D |
| OpenAL Soft | LGPL-2.0-or-later | [kcat/openal-soft](https://github.com/kcat/openal-soft) |
| Нативные библиотеки LWJGL 3.3.6 | BSD-3-Clause | [LWJGL/lwjgl3](https://github.com/LWJGL/lwjgl3) |
| shaderc, SPIRV-Cross, FreeType, JNA (`libjnidispatch`), bytehook, `libc++_shared`, `libcutils` | Apache-2.0; Apache-2.0; FreeType License или GPL-2.0; Apache-2.0 или LGPL-2.1; MIT; Apache-2.0 с исключением LLVM; Apache-2.0 | их исходные проекты |

**Рантайм SDL 3** (собирается из исходников скриптом [scripts/build_sdl_runtime.py](scripts/build_sdl_runtime.py))

| Компонент | Лицензия | Источник |
| --- | --- | --- |
| SDL 3.4.12 | zlib | [libsdl-org/SDL](https://github.com/libsdl-org/SDL). Это изменённая сборка: `SDL_PATCHES` в скрипте сборки перечисляет каждую правку, в исходнике каждая помечена «Millida» |
| LWJGL 3.4.2 (нативные библиотеки и jar) | BSD-3-Clause | [LWJGL/lwjgl3](https://github.com/LWJGL/lwjgl3); jar — из Maven Central |
| libffi 3.7.1 | MIT | [libffi/libffi](https://github.com/libffi/libffi) |
| libspng, miniz | BSD-2-Clause, MIT | из исходников LWJGL |
| Nuklear 4.13.3 | MIT или общественное достояние | из исходников LWJGL |
| tiny file dialogs | zlib | из исходников LWJGL |
| Zstandard, xxHash | BSD-3-Clause, BSD-2-Clause | из исходников LWJGL |
| shaderc 2026.2 с glslang, SPIRV-Tools, SPIRV-Headers | Apache-2.0; BSD-3-Clause и другие; Apache-2.0; MIT | [google/shaderc](https://github.com/google/shaderc) |
| SPIRV-Cross vulkan-sdk-1.4.357.0 | Apache-2.0 | [KhronosGroup/SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross) |

## Библиотеки внутри приложения

| Библиотека | Лицензия |
| --- | --- |
| Стандартная библиотека Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| OkHttp, Okio | Apache-2.0 |
| AndroidX: Core, Activity, Lifecycle, DataStore, Security Crypto (с Google Tink) | Apache-2.0 |
| Jetpack Compose, Material 3 | Apache-2.0 |
| Coil | Apache-2.0 |
| Stream WebRTC Android (WebRTC для голосового чата) | Apache-2.0; сам WebRTC — BSD-3-Clause |

## Шрифты

| Шрифт | Лицензия |
| --- | --- |
| Geist | SIL Open Font License 1.1 |
| Unbounded | SIL Open Font License 1.1 |
| Monocraft | SIL Open Font License 1.1 |

Тексты лицензий — в [app/src/main/assets/licenses](app/src/main/assets/licenses). Шрифты урезаны
до латиницы и кириллицы.

## Что не распространяется вместе с приложением

- Сам Minecraft, его библиотеки и ассеты скачиваются с серверов Mojang на телефоне.
  Звуки интерфейса берутся из тех же ассетов игры и в этот репозиторий не входят.
- Моды, модпаки, ресурспаки и шейдеры скачиваются из Modrinth, CurseForge и
  каталога Millida по вашему выбору и распространяются по лицензиям их авторов.

## Только для сборки и тестов

В APK не попадают: Gradle wrapper и Android Gradle Plugin (Apache-2.0), JUnit 4
(EPL-1.0), Robolectric (MIT), Roborazzi (Apache-2.0), MockWebServer (Apache-2.0).
