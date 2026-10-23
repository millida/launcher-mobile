# Security

Russian version: [SECURITY.ru.md](SECURITY.ru.md)

Thank you for taking the time to report a problem. The launcher starts the game, uses the
network, unpacks archives from external catalogs, downloads native libraries and stores the
user's tokens. A bug in any of these places can cost a person their account or files, so
we handle such reports first.

## Where to report

**security@millida.net**

Please do not open a public issue. Do not describe the vulnerability in a pull
request, discussions or chats until it is fixed. A public description before
the update is out gives a ready attack scenario against users who have an
old version. Give us time to fix it and ship a release. After that we
do not mind a publication, and we will credit you if you want that.

Copyright and DMCA notices are not security reports. Send them to [dmca@millida.net](mailto:dmca@millida.net).

What helps us understand faster:

- the launcher version, the phone model and the Android version;
- steps to reproduce, or a minimal proof of concept;
- the practical impact: what exactly the attacker gets;
- logs or files, if they are needed to understand the problem. Please do not send tokens
  or the contents of the app's private storage.

## Timing

We give the first answer within **72 hours**. In it we confirm that we got your report and say
whether we reproduced the problem. After that we keep you updated during the review and tell you
when the fix is in a release.

## In scope

The launcher itself: the code in this repository and the official APK built from it:

- handling content from external catalogs (Modrinth, CurseForge, modpacks,
  archives, worlds, resource packs): everything that is unpacked and run;
- path handling: leaving the app's data directory during unpacking or import,
  replacing game files, symbolic links;
- storing and passing credentials: the encrypted secret storage, Minecraft and
  Microsoft sessions, the Millida account session;
- the update mechanism: the SHA-256 check of a new APK, replacing the file before
  installation;
- the runtime: downloading and checking the Java machine, renderers and other native
  libraries, loading them into the game process;
- starting the game: building JVM arguments, choosing Java, the GLFW bridge, work with
  the server and `servers.dat`;
- Android integration: links that open the app, exported components, files shared with
  other apps, requested permissions.

## Out of scope

- Our servers and services: `api.millida.net`, `millida.net`, the update CDN,
  sites and panels. Testing them is not agreed at this address. Report problems in
  them to the same address, but in a separate message and without active scanning.
- Vulnerabilities in third-party services and catalogs (Modrinth, CurseForge, Microsoft)
  and in third-party runtime components (OpenJDK, renderers, SDL). Report them to their
  owners; tell us if the way we use them makes things worse.
- Social engineering, phishing, physical access to someone else's unlocked phone, attacks on
  employees.
- Reports from automatic scanners and linters without confirmed exploitation:
  a list of "vulnerable" dependencies, scores without a working scenario, Play Protect
  warnings about an app installed from outside Google Play.
- Scenarios where the attacker already has root on the phone or can install apps
  signed with any key in place of ours.
- A missing protection that we never promised. For example, the launcher is not meant to
  stop a user who changes their own game files.

## Supported versions

Fixes ship only in the latest release. The launcher updates itself, so
before you send a report, check that the problem still happens on the current
version. The number is shown in Android's app info.

| Version | Support |
| --- | --- |
| latest release | yes |
| anything older | no |
