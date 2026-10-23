# Third-party components

Russian version: [THIRD-PARTY.ru.md](THIRD-PARTY.ru.md)

Millida Launcher for Android is distributed under [GPL-3.0-only](LICENSE). Below are the
components of third parties that end up in the app or in the game runtime it downloads,
together with their licenses.

The exact source of every runtime file (download address, SHA-256, revision, license
files) is recorded in [scripts/runtime_sources.json](scripts/runtime_sources.json). When the
runtime changes, that file changes with it, and it wins over this list.

## Game runtime

The Java machine, the renderers and the native libraries the game needs are not built from
this repository's source. The app downloads them on the first game launch and checks each
file against its SHA-256; the APK from millida.net already has them inside. Each component
folder carries its license texts and a notice.

**Java**

| Component | License | Source |
| --- | --- | --- |
| OpenJDK 8 for Android | GPL-2.0 with the Classpath Exception | [AngelAuraMC/angelauramc-openjdk-build](https://github.com/AngelAuraMC/angelauramc-openjdk-build) |
| OpenJDK 21 and 25 for Android | GPL-2.0 with the Classpath Exception | the runtime bundled in [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2); sources: openjdk/jdk21u, openjdk/jdk25u |

**Window layer and renderers**

| Component | License | Source |
| --- | --- | --- |
| Window layer and helpers (`libpojavexec`, `libdriver_helper`, `libexithook`, `liblinkerhook`) | GPL-3.0 / LGPL-3.0 | [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2) and PojavLauncher. `libpojavexec` is modified: it reads the renderer from `MILLIDA_RENDER` |
| GLFW and LWJGL 2 compatibility classes (`lwjgl-glfw-classes.jar`) | BSD-3-Clause (LWJGL 3.3.6) and GPL-3.0 (ZalithLauncher2) | modified the same way; the launcher's own `millida-glfw.jar` comes from `bridge/` of this repository |
| gl4es | MIT | [ptitSeb/gl4es](https://github.com/ptitSeb/gl4es) |
| Krypton Wrapper (NG-GL4ES) | MIT | [BZLZHH/NG-GL4ES](https://github.com/BZLZHH/NG-GL4ES) |
| MobileGlues 2.0.0 | LGPL-2.1-only | [MobileGL-Dev/MobileGlues](https://github.com/MobileGL-Dev/MobileGlues), unmodified binary from the official release; it is a separate library and can be replaced by a build of the same source. It links glslang (BSD-3-Clause and others), SPIRV-Cross (Apache-2.0), cJSON (MIT), FidelityFX FSR 1 (MIT) and xxHash (BSD-2-Clause) |
| ANGLE | BSD-3-Clause | Google ANGLE |
| Mesa (Zink, `libEGL_mesa`, `libglapi`) | MIT | Mesa 3D |
| OpenAL Soft | LGPL-2.0-or-later | [kcat/openal-soft](https://github.com/kcat/openal-soft) |
| LWJGL 3.3.6 natives | BSD-3-Clause | [LWJGL/lwjgl3](https://github.com/LWJGL/lwjgl3) |
| shaderc, SPIRV-Cross, FreeType, JNA (`libjnidispatch`), bytehook, `libc++_shared`, `libcutils` | Apache-2.0; Apache-2.0; FreeType License or GPL-2.0; Apache-2.0 or LGPL-2.1; MIT; Apache-2.0 with LLVM exception; Apache-2.0 | their upstream projects |

**SDL 3 runtime** (built from source by [scripts/build_sdl_runtime.py](scripts/build_sdl_runtime.py))

| Component | License | Source |
| --- | --- | --- |
| SDL 3.4.12 | zlib | [libsdl-org/SDL](https://github.com/libsdl-org/SDL). This is an altered build: `SDL_PATCHES` in the build script lists every change, each marked "Millida" in the source |
| LWJGL 3.4.2 (natives and jars) | BSD-3-Clause | [LWJGL/lwjgl3](https://github.com/LWJGL/lwjgl3); jars from Maven Central |
| libffi 3.7.1 | MIT | [libffi/libffi](https://github.com/libffi/libffi) |
| libspng, miniz | BSD-2-Clause, MIT | from the LWJGL sources |
| Nuklear 4.13.3 | MIT or public domain | from the LWJGL sources |
| tiny file dialogs | zlib | from the LWJGL sources |
| Zstandard, xxHash | BSD-3-Clause, BSD-2-Clause | from the LWJGL sources |
| shaderc 2026.2 with glslang, SPIRV-Tools, SPIRV-Headers | Apache-2.0; BSD-3-Clause and others; Apache-2.0; MIT | [google/shaderc](https://github.com/google/shaderc) |
| SPIRV-Cross vulkan-sdk-1.4.357.0 | Apache-2.0 | [KhronosGroup/SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross) |

## Libraries inside the app

| Library | License |
| --- | --- |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| OkHttp, Okio | Apache-2.0 |
| AndroidX: Core, Activity, Lifecycle, DataStore, Security Crypto (with Google Tink) | Apache-2.0 |
| Jetpack Compose, Material 3 | Apache-2.0 |
| Coil | Apache-2.0 |
| Stream WebRTC Android (WebRTC for voice chat) | Apache-2.0; WebRTC itself is BSD-3-Clause |

## Fonts

| Font | License |
| --- | --- |
| Geist | SIL Open Font License 1.1 |
| Unbounded | SIL Open Font License 1.1 |
| Monocraft | SIL Open Font License 1.1 |

The license texts are in [app/src/main/assets/licenses](app/src/main/assets/licenses). The fonts are
cut down to Latin and Cyrillic.

## Not distributed with the app

- Minecraft itself, its libraries and assets are downloaded from Mojang servers on your phone.
  The interface sounds come from the same game assets and are not part of this repository.
- Mods, modpacks, resource packs and shaders are downloaded from Modrinth, CurseForge and
  the Millida catalog at your request, under their authors' licenses.

## Used only for building and testing

These do not end up in the APK: the Gradle wrapper and the Android Gradle Plugin
(Apache-2.0), JUnit 4 (EPL-1.0), Robolectric (MIT), Roborazzi (Apache-2.0),
MockWebServer (Apache-2.0).
