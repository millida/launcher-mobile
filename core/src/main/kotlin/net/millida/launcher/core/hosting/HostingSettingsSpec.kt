package net.millida.launcher.core.hosting

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

    val JAVA: List<Pair<String, String>> = listOf(
        "0" to "Автоматически",
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
            HostingField.Choice("gamemode", "Режим", listOf("survival" to "Выживание", "creative" to "Творческий", "adventure" to "Приключение"), "survival"),
            HostingField.Choice("difficulty", "Сложность", listOf("peaceful" to "Мирная", "easy" to "Лёгкая", "normal" to "Норм", "hard" to "Сложная"), "normal"),
            HostingField.Toggle("pvp", "PvP", false),
            HostingField.Toggle("whitelist", "Вайтлист", false, "Только приглашённые"),
            HostingField.Number("maxPlayers", "Максимум игроков", 1, maxSlots, minOf(DEFAULT_MAX_PLAYERS, maxSlots)),
        ),
    )

    fun advanced(free: Boolean): List<HostingFieldGroup> = listOf(
        HostingFieldGroup(
            "Сервер",
            listOf(
                HostingField.Text("motd", "Описание в списке серверов", "Мой сервер"),
                HostingField.Text("motdLine2", "Вторая строка описания", "—", if (free) "На бесплатном — подпись Millida" else null),
            ),
        ),
        HostingFieldGroup(
            "Режим игры",
            listOf(
                HostingField.Toggle("keepInventory", "Сохранять инвентарь", false, "Предметы остаются после смерти"),
                HostingField.Toggle("alwaysDay", "Вечный день", false),
                HostingField.Toggle("mobGriefing", "Мобы ломают блоки", true),
                HostingField.Toggle("hardcore", "Хардкор", false, "Одна жизнь на всех"),
                HostingField.Toggle("forceGamemode", "Возвращать режим при входе", false),
            ),
        ),
        HostingFieldGroup(
            "Игроки и доступ",
            listOf(
                HostingField.Toggle("onlineMode", "Только с лицензией", true),
                HostingField.Toggle("millidaAuth", "Авторизация Millida", false, "Пускать игроков Millida по нику"),
                HostingField.Toggle("enforceSecureProfile", "Подписанный чат", true, "Сообщения с подписью Mojang"),
                HostingField.Number("playerIdleTimeout", "Кик за простой, минут", 0, 180, 0, "0 — не кикать"),
                HostingField.Number("opPermissionLevel", "Уровень прав оператора", 1, 4, 4, "1–4, стандарт 4"),
                HostingField.Number("functionPermissionLevel", "Уровень прав функций", 1, 4, 2, "1–4, стандарт 2"),
                HostingField.Toggle("broadcastConsoleToOps", "Команды видны операторам", true),
            ),
        ),
        HostingFieldGroup(
            "Мир",
            listOf(
                HostingField.Choice(
                    "levelType",
                    "Тип мира",
                    listOf("normal" to "Обычный", "flat" to "Плоский", "large_biomes" to "Большие биомы", "amplified" to "Гористый"),
                    "normal",
                ),
                HostingField.Text("levelSeed", "Сид мира", "случайный"),
                HostingField.Text("levelName", "Папка мира", "world"),
                HostingField.Number("worldBorder", "Граница мира, блоков", 0, 100_000, 0, "0 — без ограничения"),
                HostingField.Toggle("allowNether", "Ад и Край", true),
                HostingField.Toggle("generateStructures", "Деревни и постройки", true),
                HostingField.Toggle("spawnMonsters", "Монстры", true),
                HostingField.Toggle("spawnAnimals", "Животные", true),
                HostingField.Toggle("spawnNpcs", "Жители деревень", true),
                HostingField.Number("spawnProtection", "Защита спавна, блоков", 0, 10_000, 0),
                HostingField.Toggle("allowFlight", "Полёт", false, "Не кикать за полёт"),
                HostingField.Toggle("commandBlocks", "Командные блоки", false),
            ),
        ),
        HostingFieldGroup(
            "Производительность",
            listOf(
                HostingField.Number("viewDistance", "Прорисовка, чанков", 3, 32, 10, "Сильнее всего грузит сервер"),
                HostingField.Number("simulationDistance", "Дальность активности, чанков", 3, 32, 10),
                HostingField.Number("entityBroadcastRange", "Видимость сущностей, %", 10, 1000, 100),
                HostingField.Number("networkCompressionThreshold", "Сжатие пакетов, байт", -1, 65_536, 256, "-1 — выключить"),
                HostingField.Number("maxTickTime", "Сторож зависаний, мс", -1, 600_000, 60_000, "-1 — выключить"),
                HostingField.Number("rateLimit", "Антифлуд, пакетов/с", 0, 10_000, 0, "0 — выключить"),
                HostingField.Toggle("syncChunkWrites", "Синхронная запись чанков", false, "Надёжнее, но медленнее"),
                HostingField.Toggle("useNativeTransport", "Оптимизированная сеть", true),
                HostingField.Toggle("paperNoPhantoms", "Фантомы только к бессонным", false, "Только Paper"),
                HostingField.Toggle("paperAntiXray", "Прятать руды от читов", false, "Только Paper"),
            ),
        ),
        HostingFieldGroup(
            "Java",
            listOf(
                HostingField.Choice("javaVersion", "Версия Java", JAVA, "0", "Авто — под версию игры"),
                HostingField.Choice("jvmProfile", "Флаги JVM", listOf("shared" to "Общий", "aikar" to "Aikar", "vanilla" to "Без флагов"), "shared"),
            ),
        ),
        HostingFieldGroup(
            "Ресурспак и видимость",
            listOf(
                HostingField.Text("resourcePack", "Ссылка на ресурспак", "https://…"),
                HostingField.Text("resourcePackSha1", "SHA1 ресурспака", "—", "Без него клиент качает пак каждый вход"),
                HostingField.Toggle("requireResourcePack", "Ресурспак обязателен", false),
                HostingField.Toggle("enableStatus", "Виден в списке серверов", true),
                HostingField.Toggle("hideOnlinePlayers", "Прятать список игроков", false),
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
