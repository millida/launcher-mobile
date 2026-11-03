package net.millida.launcher.core.hosting

import net.millida.launcher.core.i18n.tr

/**
 * The «Настройки» tab of a server, field for field as the desktop panel has it
 * (src/screens/HostingManage.tsx): what each rule is called, what it holds
 * when the service has not set it, and the bounds the service accepts.
 */
sealed interface HostingField {
    val key: String
    val title: String
    val hint: String?

    data class Toggle(override val key: String, override val title: String, val default: Boolean, override val hint: String? = null) : HostingField

    data class Choice(
        override val key: String,
        override val title: String,
        val options: List<Pair<String, String>>,
        val default: String,
        override val hint: String? = null,
    ) : HostingField

    data class Number(
        override val key: String,
        override val title: String,
        val min: Int,
        val max: Int,
        val default: Int,
        override val hint: String? = null,
    ) : HostingField

    data class Text(override val key: String, override val title: String, val placeholder: String, override val hint: String? = null) : HostingField
}

data class HostingFieldGroup(val title: String?, val fields: List<HostingField>)

object HostingSettingsSpec {
    const val DEFAULT_MAX_PLAYERS = 20

    val JAVA: List<Pair<String, String>> get() = listOf(
        "0" to tr("Автоматически"),
        "8" to "Java 8",
        "11" to "Java 11",
        "16" to "Java 16",
        "17" to "Java 17",
        "21" to "Java 21",
        "25" to "Java 25",
    )

    fun main(maxSlots: Int): HostingFieldGroup = HostingFieldGroup(
        null,
        listOf(
            HostingField.Choice("gamemode", tr("Режим"), listOf("survival" to tr("Выживание"), "creative" to tr("Творческий"), "adventure" to tr("Приключение")), "survival"),
            HostingField.Choice("difficulty", tr("Сложность"), listOf("peaceful" to tr("Мирная"), "easy" to tr("Лёгкая"), "normal" to tr("Норм"), "hard" to tr("Сложная")), "normal"),
            HostingField.Toggle("pvp", "PvP", false),
            HostingField.Toggle("whitelist", tr("Вайтлист"), false, tr("Только приглашённые")),
            HostingField.Number("maxPlayers", tr("Максимум игроков"), 1, maxSlots, minOf(DEFAULT_MAX_PLAYERS, maxSlots)),
        ),
    )

    fun advanced(free: Boolean): List<HostingFieldGroup> = listOf(
        HostingFieldGroup(
            tr("Сервер"),
            listOf(
                HostingField.Text("motd", tr("Описание в списке серверов"), tr("Мой сервер")),
                HostingField.Text("motdLine2", tr("Вторая строка описания"), "—", if (free) tr("На бесплатном — подпись Millida") else null),
            ),
        ),
        HostingFieldGroup(
            tr("Режим игры"),
            listOf(
                HostingField.Toggle("keepInventory", tr("Сохранять инвентарь"), false, tr("Предметы остаются после смерти")),
                HostingField.Toggle("alwaysDay", tr("Вечный день"), false),
                HostingField.Toggle("mobGriefing", tr("Мобы ломают блоки"), true),
                HostingField.Toggle("hardcore", tr("Хардкор"), false, tr("Одна жизнь на всех")),
                HostingField.Toggle("forceGamemode", tr("Возвращать режим при входе"), false),
            ),
        ),
        HostingFieldGroup(
            tr("Игроки и доступ"),
            listOf(
                HostingField.Toggle("onlineMode", tr("Только с лицензией"), true),
                HostingField.Toggle("millidaAuth", tr("Авторизация Millida"), false, tr("Пускать игроков Millida по нику")),
                HostingField.Toggle("enforceSecureProfile", tr("Подписанный чат"), true, tr("Сообщения с подписью Mojang")),
                HostingField.Number("playerIdleTimeout", tr("Кик за простой, минут"), 0, 180, 0, tr("0 — не кикать")),
                HostingField.Number("opPermissionLevel", tr("Уровень прав оператора"), 1, 4, 4, tr("1–4, стандарт 4")),
                HostingField.Number("functionPermissionLevel", tr("Уровень прав функций"), 1, 4, 2, tr("1–4, стандарт 2")),
                HostingField.Toggle("broadcastConsoleToOps", tr("Команды видны операторам"), true),
            ),
        ),
        HostingFieldGroup(
            tr("Мир"),
            listOf(
                HostingField.Choice(
                    "levelType",
                    tr("Тип мира"),
                    listOf("normal" to tr("Обычный"), "flat" to tr("Плоский"), "large_biomes" to tr("Большие биомы"), "amplified" to tr("Гористый")),
                    "normal",
                ),
                HostingField.Text("levelSeed", tr("Сид мира"), tr("случайный")),
                HostingField.Text("levelName", tr("Папка мира"), "world"),
                HostingField.Number("worldBorder", tr("Граница мира, блоков"), 0, 100_000, 0, tr("0 — без ограничения")),
                HostingField.Toggle("allowNether", tr("Ад и Край"), true),
                HostingField.Toggle("generateStructures", tr("Деревни и постройки"), true),
                HostingField.Toggle("spawnMonsters", tr("Монстры"), true),
                HostingField.Toggle("spawnAnimals", tr("Животные"), true),
                HostingField.Toggle("spawnNpcs", tr("Жители деревень"), true),
                HostingField.Number("spawnProtection", tr("Защита спавна, блоков"), 0, 10_000, 0),
                HostingField.Toggle("allowFlight", tr("Полёт"), false, tr("Не кикать за полёт")),
                HostingField.Toggle("commandBlocks", tr("Командные блоки"), false),
            ),
        ),
        HostingFieldGroup(
            tr("Производительность"),
            listOf(
                HostingField.Number("viewDistance", tr("Прорисовка, чанков"), 3, 32, 10, tr("Сильнее всего грузит сервер")),
                HostingField.Number("simulationDistance", tr("Дальность активности, чанков"), 3, 32, 10),
                HostingField.Number("entityBroadcastRange", tr("Видимость сущностей, %"), 10, 1000, 100),
                HostingField.Number("networkCompressionThreshold", tr("Сжатие пакетов, байт"), -1, 65_536, 256, tr("-1 — выключить")),
                HostingField.Number("maxTickTime", tr("Сторож зависаний, мс"), -1, 600_000, 60_000, tr("-1 — выключить")),
                HostingField.Number("rateLimit", tr("Антифлуд, пакетов/с"), 0, 10_000, 0, tr("0 — выключить")),
                HostingField.Toggle("syncChunkWrites", tr("Синхронная запись чанков"), false, tr("Надёжнее, но медленнее")),
                HostingField.Toggle("useNativeTransport", tr("Оптимизированная сеть"), true),
                HostingField.Toggle("paperNoPhantoms", tr("Фантомы только к бессонным"), false, tr("Только Paper")),
                HostingField.Toggle("paperAntiXray", tr("Прятать руды от читов"), false, tr("Только Paper")),
            ),
        ),
        HostingFieldGroup(
            "Java",
            listOf(
                HostingField.Choice("javaVersion", tr("Версия Java"), JAVA, "0", tr("Авто — под версию игры")),
                HostingField.Choice("jvmProfile", tr("Флаги JVM"), listOf("shared" to tr("Общий"), "aikar" to "Aikar", "vanilla" to tr("Без флагов")), "shared"),
            ),
        ),
        HostingFieldGroup(
            tr("Ресурспак и видимость"),
            listOf(
                HostingField.Text("resourcePack", tr("Ссылка на ресурспак"), "https://…"),
                HostingField.Text("resourcePackSha1", tr("SHA1 ресурспака"), "—", tr("Без него клиент качает пак каждый вход")),
                HostingField.Toggle("requireResourcePack", tr("Ресурспак обязателен"), false),
                HostingField.Toggle("enableStatus", tr("Виден в списке серверов"), true),
                HostingField.Toggle("hideOnlinePlayers", tr("Прятать список игроков"), false),
            ),
        ),
    )

    /** «Авторизация Millida» only means something on a server that lets unlicensed players in. */
    fun visible(field: HostingField, rules: Map<String, String>): Boolean =
        field.key != "millidaAuth" || !toggle(rules, "onlineMode", true)

    /** A rule the service never set reads as absent, and JSON null arrives as the word "null". */
    fun raw(rules: Map<String, String>, key: String): String? = rules[key]?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun toggle(rules: Map<String, String>, key: String, default: Boolean): Boolean = when (raw(rules, key)?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

    fun number(rules: Map<String, String>, field: HostingField.Number): Int =
        raw(rules, field.key)?.toDoubleOrNull()?.toInt() ?: field.default

    fun choice(rules: Map<String, String>, field: HostingField.Choice): String {
        val value = raw(rules, field.key)?.let { it.toDoubleOrNull()?.toInt()?.toString() ?: it } ?: return field.default
        return value.takeIf { wanted -> field.options.any { it.first == wanted } } ?: field.default
    }

    /** Typed input is accepted only inside the bounds the service validates; anything else is refused before sending. */
    fun parseNumber(text: String, field: HostingField.Number): Int? = text.trim().toIntOrNull()?.takeIf { it in field.min..field.max }
}
