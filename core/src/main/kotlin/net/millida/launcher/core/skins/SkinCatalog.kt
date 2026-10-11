package net.millida.launcher.core.skins

import net.millida.launcher.core.account.MILLIDA_API

/**
 * A catalogue card. Either the texture URL is known outright (Mojang default
 * skins), or the skin is taken from a player nickname and rendered by our own
 * head service - the nickname form keeps the list alive when a player changes
 * their skin.
 */
data class CatalogSkin(
    val key: String,
    val label: String,
    val nick: String = "",
    val url: String = "",
)

data class CatalogGroup(val title: String, val items: List<CatalogSkin>)

data class CatalogCape(val id: String, val name: String, val hash: String) {
    val url: String get() = SkinCatalog.mojangTexture(hash)
}

object SkinCatalog {
    const val TEXTURES_HOST: String = "textures.minecraft.net"

    fun mojangTexture(hash: String): String = "https://$TEXTURES_HOST/texture/$hash"

    fun textureHash(url: String): String = url.trimEnd('/').substringAfterLast('/').substringBefore('?')

    fun skinUrl(nick: String, apiBase: String = MILLIDA_API): String =
        "${apiBase.trimEnd('/')}/heads/skin/$nick"

    /** Without fallback=none the head service answers an unknown nick with Steve and a 200. */
    fun skinUrlStrict(nick: String, apiBase: String = MILLIDA_API): String =
        skinUrl(nick, apiBase) + "?fallback=none"

    fun headUrl(nick: String, size: Int = 128, apiBase: String = MILLIDA_API): String =
        "${apiBase.trimEnd('/')}/heads/avatar/$nick?size=${size.coerceIn(8, 512)}"

    /** The texture behind a card, whichever of the two forms it has. */
    fun textureUrl(skin: CatalogSkin, apiBase: String = MILLIDA_API): String =
        skin.url.ifEmpty { skinUrl(skin.nick.ifEmpty { "MHF_Steve" }, apiBase) }

    val OFFICIAL_SKINS: List<CatalogSkin> = listOf(
        skin("off-steve", "Стив", "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb"),
        skin("off-alex", "Алекс", "46acd06e8483b176e8ea39fc12fe105eb3a2a4970f5100057e9d84d4b60bdfa7"),
        skin("off-ari", "Ари", "6ac6ca262d67bcfb3dbc924ba8215a18195497c780058a5749de674217721892"),
        skin("off-efe", "Эфе", "fece7017b1bb13926d1158864b283b8b930271f80a90482f174cca6a17e88236"),
        skin("off-makena", "Макена", "7cb3ba52ddd5cc82c0b050c3f920f87da36add80165846f479079663805433db"),
        skin("off-noor", "Нур", "6c160fbd16adbc4bff2409e70180d911002aebcfa811eb6ec3d1040761aea6dd"),
        skin("off-zuri", "Зури", "eee522611005acf256dbd152e992c60c0bb7978cb0f3127807700e478ad97664"),
    )

    val DEFAULT_SKIN_URL: String = OFFICIAL_SKINS.first().url

    val GROUPS: List<CatalogGroup> = listOf(
        CatalogGroup("Официальные Minecraft", OFFICIAL_SKINS),
        CatalogGroup(
            "Классические",
            listOf(
                byNick("MHF_Steve", "Классический Стив"),
                byNick("MHF_Alex", "Классическая Алекс"),
                byNick("MHF_Herobrine", "Херобрин"),
            ),
        ),
        CatalogGroup(
            "Легенды Minecraft",
            listOf(
                byNick("Notch"),
                byNick("jeb_"),
                byNick("Technoblade"),
                byNick("Herobrine"),
                byNick("hypixel", "Hypixel"),
                byNick("deadmau5"),
                byNick("Dream"),
                byNick("Skeppy"),
            ),
        ),
        CatalogGroup(
            "Русские блогеры",
            listOf(
                byNick("MrLololoshka"),
                byNick("Lololoshka"),
                byNick("TheBrainDit"),
                byNick("DILLERON", "Диллерон"),
                byNick("DILLER", "Diller"),
                byNick("Kompot", "Компот"),
                byNick("Compot"),
                byNick("Edison"),
                byNick("Marmok", "Мармок"),
                byNick("EeOneGuy", "Ивангай"),
                byNick("EvgenBro", "ЕвгенБро"),
                byNick("FixEye", "Фиксай"),
                byNick("Frost", "Фрост"),
                byNick("kubik", "Кубик"),
                byNick("Pozzy", "Поззи"),
                byNick("NuBiK", "Нубик"),
                byNick("Affka", "Аффка"),
                byNick("hrustik", "Хрустик"),
            ),
        ),
        CatalogGroup(
            "Хардкор и технари",
            listOf(
                byNick("Grian"),
                byNick("Mumbo_Jumbo", "Mumbo Jumbo"),
                byNick("iskall85"),
                byNick("Etho"),
                byNick("Docm77"),
                byNick("xisumavoid"),
                byNick("VintageBeef"),
                byNick("CaptainSparklez"),
            ),
        ),
        CatalogGroup(
            "Dream SMP",
            listOf(
                byNick("TommyInnit"),
                byNick("Tubbo_", "Tubbo"),
                byNick("Ranboo"),
                byNick("Quackity"),
                byNick("Purpled"),
                byNick("Antfrost"),
                byNick("BadBoyHalo"),
                byNick("Sapnap"),
                byNick("PhilzA", "Philza"),
            ),
        ),
    )

    /** Offline copy of the server's open cape list; the ids are the ones the apply endpoint accepts. */
    val OFFICIAL_CAPES: List<CatalogCape> = listOf(
        CatalogCape("off:migrator", "Переселенец", "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933"),
        CatalogCape("off:vanilla", "Vanilla", "f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4"),
        CatalogCape("off:cherry", "Сакура", "afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb"),
        CatalogCape("off:pan", "Pan", "28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336"),
        CatalogCape("off:mojang", "Mojang Studios", "9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7"),
        CatalogCape("off:anniv15", "15 лет", "cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"),
        CatalogCape("off:cobalt", "Cobalt", "ca35c56efe71ed290385f4ab5346a1826b546a54d519e6a3ff01efa01acce81"),
        CatalogCape("off:minecon2011", "MineCon 2011", "953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6"),
        CatalogCape("off:minecon2012", "MineCon 2012", "a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6"),
        CatalogCape("off:minecon2013", "MineCon 2013", "153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da"),
        CatalogCape("off:minecon2015", "MineCon 2015", "b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635"),
        CatalogCape("off:minecon2016", "MineCon 2016", "e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980"),
    )

    private val OFFICIAL_HASHES: Set<String> = OFFICIAL_CAPES.map { it.hash }.toSet()

    /**
     * A cape the account already owns and one from this list are the same
     * picture under two URLs; without this the wardrobe shows every Mojang
     * cape twice.
     */
    fun officialCapeOf(url: String): CatalogCape? {
        val hash = textureHash(url)
        if (hash !in OFFICIAL_HASHES) return null
        return OFFICIAL_CAPES.firstOrNull { it.hash == hash }
    }

    private fun skin(key: String, label: String, hash: String) =
        CatalogSkin(key = key, label = label, url = mojangTexture(hash))

    private fun byNick(nick: String, label: String = nick) =
        CatalogSkin(key = nick, label = label, nick = nick)
}
