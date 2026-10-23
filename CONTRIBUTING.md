# How to contribute

Russian version: [CONTRIBUTING.ru.md](CONTRIBUTING.ru.md)

Millida Launcher for Android is a Minecraft Java Edition launcher written in Kotlin with
Jetpack Compose. `core` installs and starts the game and knows nothing about Android,
`runtime` hosts the Java machine on the phone, and `app` is the interface on top of them.

Thank you for wanting to help. Below is everything you need to set up the project,
make your change look like the rest of the code, and write a pull request that
can be reviewed quickly.

## How this repository works

Development happens in our internal repository. Every release arrives here as one
"Millida Mobile X.Y.Z" commit with all the code of that version, and that commit replaces
the previous tree. Because of this, a pull request here is not merged directly: if we
accept the change, we carry it over into the development repository, and it arrives
here with the next release.

## What to install

- JDK 17 or newer.
- Android SDK with **Android 15 (API 35)**, **NDK 27** and **CMake 3.22.1**.

The easiest way to get all of it is Android Studio. Step-by-step instructions are in
[BUILDING.md](BUILDING.md).

## Run the project

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The debug build is a separate app, `net.millida.launcher.debug`. It installs next to the
official launcher and does not touch its data. The Java machine and the renderers are not
inside the debug APK: they are downloaded on the first game launch.

Logic that does not need a phone (installation, profiles, accounts, launch
arguments) lives in `core` and runs on desktop Java, so most changes can be checked
with tests, without a device.

## What to run before a pull request

```bash
./gradlew :core:test                 # core logic on desktop Java
./gradlew :app:testDebugUnitTest     # interface and app logic (Robolectric)
./gradlew :bridge:jar                # the GLFW replacement used inside the game
./gradlew :app:assembleDebug         # the APK itself
```

These are the same commands our CI runs on every change. If your change touches
starting the game, rendering or input, also check it on a real phone and say which one
in the pull request: tests do not catch a black screen.

## Code style

There is no formatter config, so the reference is the neighboring code:

- Kotlin with 4-space indent, `camelCase` for functions and variables, `PascalCase` for
  classes and composables, `UPPER_SNAKE_CASE` for constants.
- Keep the module boundaries: `core` must not depend on Android, `runtime` must not
  depend on the interface, and `app` must not work with the game files directly.
- Put a new function next to related ones, not in a general module.
- Data from outside (network, archives, file names, manifests) is untrusted: check
  paths and hashes before you use them.

**Comments**

The code should read on its own: clear names, small functions, explicit types instead of
explanations. Leave a comment where it explains a non-obvious "why" and does not retell
the "what": a workaround for a driver or Android bug, a requirement of the Minecraft
protocol or an external API, the reason for a non-intuitive order of steps.

Write new comments in English, so that everyone who looks into the repository can
read the code equally. This rule is only about comments in code.

## Pull request

In the description, write:

- **What and why**: which problem the change solves. If there is an issue, link to it.
- **How you checked**: which commands you ran and what you did by hand: on which phone
  and Android version, with which Minecraft version and loader, which scenario you
  reproduced.
- **A screenshot or a short recording**: required for anything that changes the interface.

Say separately if the change alters the format of user data: settings, profiles,
accounts, the layout of game files. Such things break installed launchers, so they
need a migration and a clear note in the description.

## Where to write

- **A bug or an idea**: an [issue](https://github.com/millida/launcher-mobile/issues/new).
  For a bug, attach the report from the app: after a crash, the "Send report" button; in
  other cases, the "Logs" tab of the instance.
- **A vulnerability**: not in an issue. The procedure is in [SECURITY.md](SECURITY.md).

In communication we follow the [code of conduct](CODE_OF_CONDUCT.md).
