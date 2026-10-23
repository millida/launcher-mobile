# Where the APK comes from and how to verify it

Russian version: [SAFETY.ru.md](SAFETY.ru.md)

## In short

```
our development repository ──► our build machine ──► APK signed with the Millida key ──► millida.net and auto-update
                    └──────────────► every release: a "Millida Mobile X.Y.Z" commit here
```

1. The code you see here is all of the launcher's code for that version. Every release arrives in this repository
   as one "Millida Mobile X.Y.Z" commit.
2. The official APK is built by our CI on our own build machine and signed with the Millida key. That key is never published.
3. The APK and the version list (`latest.json`) are served from `launcher-storage.millida.net`; the "Download" button on
   [millida.net/launcher](https://millida.net/launcher) leads there.
4. The launcher learns about a new version from `latest.json`, downloads the APK and installs it only if its SHA-256 matches
   the one in the list ([Updates.kt](core/src/main/kotlin/net/millida/launcher/core/update/Updates.kt)). On top of that,
   Android itself refuses to install an update signed with a different key.

## Verify it yourself in a minute

- **The file fingerprint.** The SHA-256 of the APK must match the one in
  [latest.json](https://launcher-storage.millida.net/mobile/latest.json): on Windows `certutil -hashfile <file> SHA256`,
  on macOS and Linux `sha256sum <file>`.
- **The signing key.** `apksigner verify --print-certs <file>` must show the same SHA-256 as `sha256_cert_fingerprints` in
  [assetlinks.json](https://millida.net/.well-known/assetlinks.json).
- **Build it yourself:** three commands in [BUILDING.md](BUILDING.md).

## What the launcher downloads and from where

Everything is visible in the code. Intercept the traffic and compare it with this table.

| What | From where | How it is checked |
| --- | --- | --- |
| Launcher updates | `launcher-storage.millida.net` | SHA-256 from `latest.json`, then Android's signature check |
| Java machine, renderers, native libraries | `launcher-storage.millida.net`; origin and licenses in [scripts/runtime_sources.json](scripts/runtime_sources.json) | SHA-256 of every file from the runtime manifest |
| Minecraft, Fabric/Forge/NeoForge/Quilt | the official sites of Mojang and the loaders | hashes from their own lists |
| Mods, modpacks, resource packs | Modrinth, CurseForge, the Millida catalog | the hash the catalog gives for the file |
| The Millida mod (skins and cosmetics in game) | `cdn.millida.net/mod` | SHA-256 from the version list |

Some of these sites do not open for everyone in Russia. In that case the same file goes through our mirror
(`api.millida.net`, see [Downloader.kt](core/src/main/kotlin/net/millida/launcher/core/http/Downloader.kt)) and is checked
against the same hash; where a site is reachable, the launcher connects directly.

## Your account cannot be stolen, and here is why

| The fear | How it really works | Where to check |
| --- | --- | --- |
| The launcher will see your password | You enter the password on the Microsoft site using a code; the launcher never receives it | [MicrosoftAuth.kt](core/src/main/kotlin/net/millida/launcher/core/account/MicrosoftAuth.kt), `devicecode` |
| The token will go to Millida | The Microsoft and Minecraft tokens go only to Microsoft, Xbox and Mojang servers and are handed to the game itself at launch, as in any launcher | [MicrosoftAuth.kt](core/src/main/kotlin/net/millida/launcher/core/account/MicrosoftAuth.kt) |
| A fake update is slipped in | An APK whose SHA-256 does not match is not installed, and Android rejects an update signed with someone else's key | [Updates.kt](core/src/main/kotlin/net/millida/launcher/core/update/Updates.kt) |

Tokens are stored only on your phone, in the app's private storage, encrypted with a key kept in the Android Keystore.
No launcher can protect you from malware that already has root on your phone.

Usage statistics and crash reports are sent without passwords, tokens or files. Both are on by default and can be turned
off in the app settings.

## Honestly, about what is not here

- **Development** happens in an internal repository: it holds tasks, drafts, CI and the signing key. Every release
  arrives here in full as a "Millida Mobile X.Y.Z" commit.
- **The official APK is not built on GitHub's machines** and has no build attestation. What ties it to this code is
  the signature with our key, the SHA-256 in `latest.json` and the option to build the same version yourself. Your own
  build will not be byte-identical: it is signed with a different key.
- **The APK from millida.net already contains the runtime** (Java machine and renderers). A build from this repository
  downloads the same files on the first game launch, checked against the hashes in the runtime manifest.

Found a security hole? See [SECURITY.md](SECURITY.md).
