# Откуда берётся APK и как это проверить

[English version: SAFETY.md](SAFETY.md)

## Коротко

```
наш репозиторий разработки ──► наша сборочная машина ──► APK с подписью Millida ──► millida.net и автообновление
                    └──────────────► каждый выпуск: коммит «Millida Mobile X.Y.Z» здесь
```

1. Код, который вы видите здесь, — весь код лаунчера этой версии. Каждый выпуск приходит в этот репозиторий
   одним коммитом «Millida Mobile X.Y.Z».
2. Официальный APK собирает наш CI на нашей собственной сборочной машине и подписывает ключом Millida. Этот ключ не публикуется.
3. APK и список версий (`latest.json`) раздаются с `launcher-storage.millida.net`; туда ведёт кнопка «Скачать» на
   [millida.net/launcher](https://millida.net/launcher).
4. О новой версии лаунчер узнаёт из `latest.json`, скачивает APK и ставит его, только если SHA-256 совпала с указанной в
   списке ([Updates.kt](core/src/main/kotlin/net/millida/launcher/core/update/Updates.kt)). Сверх этого сам Android не даст
   поставить обновление, подписанное другим ключом.

## Проверьте сами за минуту

- **Отпечаток файла.** SHA-256 APK должна совпасть с указанной в
  [latest.json](https://launcher-storage.millida.net/mobile/latest.json): Windows — `certutil -hashfile <файл> SHA256`,
  macOS и Linux — `sha256sum <файл>`.
- **Ключ подписи.** `apksigner verify --print-certs <файл>` должен показать ту же SHA-256, что `sha256_cert_fingerprints` в
  [assetlinks.json](https://millida.net/.well-known/assetlinks.json).
- **Соберите сами:** три команды в [BUILDING.ru.md](BUILDING.ru.md).

## Что лаунчер скачивает и откуда

Всё видно в коде. Перехватите трафик и сверьте с этой таблицей.

| Что | Откуда | Чем проверяется |
| --- | --- | --- |
| Обновления лаунчера | `launcher-storage.millida.net` | SHA-256 из `latest.json`, затем проверка подписи Android |
| Java-машина, рендереры, нативные библиотеки | `launcher-storage.millida.net`; происхождение и лицензии — в [scripts/runtime_sources.json](scripts/runtime_sources.json) | SHA-256 каждого файла из манифеста рантайма |
| Minecraft, Fabric/Forge/NeoForge/Quilt | официальные сайты Mojang и загрузчиков | хеши из их собственных списков |
| Моды, модпаки, ресурспаки | Modrinth, CurseForge, каталог Millida | хеш файла, который даёт каталог |
| Мод Millida (скины и косметика в игре) | `cdn.millida.net/mod` | SHA-256 из списка версий |

Часть этих сайтов в России открывается не у всех. Тогда тот же файл идёт через наше зеркало
(`api.millida.net`, см. [Downloader.kt](core/src/main/kotlin/net/millida/launcher/core/http/Downloader.kt)) и сверяется с
тем же хешем; там, где сайт доступен, лаунчер идёт напрямую.

## Аккаунт не украдут — и вот почему

| Опасение | Как на самом деле | Где проверить |
| --- | --- | --- |
| Лаунчер увидит пароль | Пароль вводится на сайте Microsoft по коду, лаунчер его не получает | [MicrosoftAuth.kt](core/src/main/kotlin/net/millida/launcher/core/account/MicrosoftAuth.kt), `devicecode` |
| Токен уйдёт в Millida | Токены Microsoft и Minecraft уходят только на серверы Microsoft, Xbox и Mojang и передаются самой игре при запуске, как в любом лаунчере | [MicrosoftAuth.kt](core/src/main/kotlin/net/millida/launcher/core/account/MicrosoftAuth.kt) |
| Подсунут поддельное обновление | APK с несовпавшей SHA-256 не ставится, а обновление с чужой подписью Android не примет | [Updates.kt](core/src/main/kotlin/net/millida/launcher/core/update/Updates.kt) |

Токены хранятся только на вашем телефоне, в закрытом хранилище приложения, зашифрованные ключом из Android Keystore.
От вредоносной программы, у которой уже есть root на телефоне, не защитит ни один лаунчер.

Статистика использования и отчёты о вылетах уходят без паролей, токенов и файлов. И то и другое включено по умолчанию
и выключается в настройках приложения.

## Честно о том, чего здесь нет

- **Разработка** идёт во внутреннем репозитории: там задачи, черновики, CI и ключ подписи. Каждый выпуск
  приходит сюда целиком коммитом «Millida Mobile X.Y.Z».
- **Официальный APK собирается не на машинах GitHub**, и аттестации сборки у него нет. С этим кодом его связывают
  подпись нашим ключом, SHA-256 в `latest.json` и возможность собрать ту же версию самому. Ваша сборка не совпадёт
  побайтно: она подписана другим ключом.
- **В APK с millida.net рантайм уже внутри** (Java-машина и рендереры). Сборка из этого репозитория скачивает те же
  файлы при первом запуске игры и сверяет их с хешами из манифеста рантайма.

Нашли дыру в безопасности? См. [SECURITY.ru.md](SECURITY.ru.md).
