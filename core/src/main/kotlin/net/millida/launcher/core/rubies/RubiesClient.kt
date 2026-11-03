package net.millida.launcher.core.rubies

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.friends.FriendsErrors
import net.millida.launcher.core.http.quietRetry
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.mojang.MojangJson
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.security.SecureRandom

open class RubiesError(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Packs are paid from the Millida wallet: this one means "top the wallet up first". */
class WalletShort(message: String, cause: Throwable? = null) : RubiesError(message, cause)

/** The chest is already opened or never existed: showing it any longer only confuses. */
class ChestGone(message: String, cause: Throwable? = null) : RubiesError(message, cause)

class RubiesClient(
    private val auth: MillidaAuth,
    private val seed: () -> String = ::freshClientSeed,
) {

    suspend fun balance(): RubyBalance =
        read("/rubies/balance", RubyBalance.serializer(), tr("Баланс рубинов не загрузился — нажми «Повторить»"))

    /** Open to guests: the packs are shown before the sign-in. */
    suspend fun rules(): RubyRules = guarded(tr("Шансы сундуков не загрузились — нажми «Повторить»")) {
        decode(RubyRules.serializer(), auth.publicCall("/rubies/rules", "GET", null))
    }

    suspend fun daily(): DailyStatus =
        read("/rubies/daily", DailyStatus.serializer(), tr("Бонус за вход не загрузился — нажми «Повторить»"))

    suspend fun claimDaily(day: Int, row: TrackRow): DailyClaim = guarded(CLAIM_FAILED) {
        if (day !in 1..MAX_TRACK_DAY) throw RubiesError(tr("Такой клетки нет — обнови магазин"))
        val payload = buildJsonObject {
            put("day", day)
            put("row", row.wire)
        }
        claimOf(auth.authorized("/rubies/daily/claim", "POST", payload))
    }

    suspend fun claimAll(): DailyClaim = guarded(CLAIM_FAILED) {
        claimOf(auth.authorized("/rubies/daily/claim-all", "POST", buildJsonObject { }))
    }

    suspend fun chests(): ChestList =
        read("/rubies/chests", ChestList.serializer(), tr("Сундуки не загрузились — нажми «Повторить»"))

    /**
     * The client seed is the player's share in a fair roll: the server commits to
     * the hash of its own seed first, so it cannot tune the roll to a person.
     */
    suspend fun openChest(id: String): ChestOpenResult = guarded(tr("Сундук не открылся — попробуй ещё раз")) {
        val path = "/rubies/chests/" + segment(id) + "/open"
        val payload = buildJsonObject { put("clientSeed", seed()) }
        try {
            decode(ChestOpenResult.serializer(), auth.authorized(path, "POST", payload))
        } catch (refused: IOException) {
            if (refused !is Unauthorized && CHEST_GONE.containsMatchIn(refused.message.orEmpty())) {
                throw ChestGone(refused.message ?: tr("Этот сундук уже открыт"), refused)
            }
            throw refused
        }
    }

    suspend fun shop(): ShopDay =
        read("/rubies/shop", ShopDay.serializer(), tr("Витрина не загрузилась — нажми «Повторить»"))

    suspend fun buy(code: String, source: ShopSource): PurchaseResult = guarded(tr("Покупка не прошла — повтори")) {
        val payload = buildJsonObject {
            put("code", requireCode(code))
            put("source", source.wire)
        }
        decode(PurchaseResult.serializer(), auth.authorized("/rubies/shop/buy", "POST", payload))
    }

    suspend fun claimGift(): GiftClaim = guarded(tr("Подарок не забрался — повтори")) {
        val response = auth.authorized("/rubies/shop/gift/claim", "POST", buildJsonObject { })
        val totals = decode(GiftTotals.serializer(), response)
        val granted = when (val list = response["granted"]) {
            is JsonArray -> list.map { decode(RubyReward.serializer(), it) }
            is JsonObject -> listOf(decode(RubyReward.serializer(), list))
            else -> emptyList()
        }
        GiftClaim(totals.balance, totals.shards, granted)
    }

    suspend fun wish(code: String, on: Boolean): List<String> = guarded(tr("«Хочу» не сохранилось — повтори")) {
        val payload = buildJsonObject {
            put("code", requireCode(code))
            put("on", on)
        }
        decode(WishResult.serializer(), auth.authorized("/rubies/shop/wish", "POST", payload)).wishlist
    }

    suspend fun buyPack(code: String): PackPurchase = guarded(tr("Пакет не оплатился — повтори")) {
        val payload = buildJsonObject { put("pack", requireCode(code)) }
        decode(PackPurchase.serializer(), auth.authorized("/rubies/packs/buy", "POST", payload))
    }

    suspend fun topUp(rubies: Int): PackPurchase = guarded(tr("Рубины не докупились — повтори")) {
        if (rubies < EXACT_MIN_RUBIES) throw RubiesError(tr("Докупить можно от {EXACT_MIN_RUBIES} рубинов", EXACT_MIN_RUBIES))
        val payload = buildJsonObject { put("rubies", rubies) }
        decode(PackPurchase.serializer(), auth.authorized("/rubies/topup", "POST", payload))
    }

    suspend fun workshop(): Workshop =
        read("/rubies/shards", Workshop.serializer(), tr("Мастерская не загрузилась — нажми «Повторить»"))

    suspend fun craft(code: String): CraftResult = guarded(tr("Осколки не обменялись — повтори")) {
        val payload = buildJsonObject { put("code", requireCode(code)) }
        decode(CraftResult.serializer(), auth.authorized("/rubies/shards/craft", "POST", payload))
    }

    suspend fun weekly(): WeeklyAny = guarded(tr("Задания недели не загрузились — нажми «Повторить»")) {
        weeklyOf(auth.authorizedElement("/rubies/weekly", "GET", null))
    }

    suspend fun claimWeeklyStep(at: Int): WeeklyStepClaim = guarded(tr("Награда недели не забралась — повтори")) {
        if (at <= 0) throw RubiesError(tr("Такой ступени нет — обнови магазин"))
        decode(WeeklyStepClaim.serializer(), auth.authorized("/rubies/weekly/claim", "POST", buildJsonObject { put("at", at) }))
    }

    suspend fun boostWeekly(stars: Int): WeeklyBoostResult = guarded(tr("Звёзды не докупились — повтори")) {
        if (stars <= 0) throw RubiesError(tr("Докупать нечего — обнови магазин"))
        decode(WeeklyBoostResult.serializer(), auth.authorized("/rubies/weekly/boost", "POST", buildJsonObject { put("stars", stars) }))
    }

    suspend fun buyXray(id: String): XrayPurchase = guarded(tr("Кейс не купился — повтори")) {
        val payload = buildJsonObject { put("id", requireCode(id)) }
        decode(XrayPurchase.serializer(), auth.authorized("/rubies/xray/buy", "POST", payload))
    }

    suspend fun quests(): List<PackQuest> = guarded(tr("Задания сборок не загрузились — нажми «Повторить»")) {
        questsOf(auth.authorizedElement("/rubies/quests", "GET", null))
    }

    suspend fun claimQuest(code: String): QuestClaim = guarded(tr("Награда не забралась — повтори")) {
        val path = "/rubies/quests/" + segment(code) + "/claim"
        decode(QuestClaim.serializer(), auth.authorized(path, "POST", buildJsonObject { }))
    }

    /** The only way to finish a started item: the rubies for the missing fragments. */
    suspend fun completeFragments(code: String): FragmentsCompletion = guarded(tr("Фрагменты не докупились — повтори")) {
        val payload = buildJsonObject { put("code", requireCode(code)) }
        decode(FragmentsCompletion.serializer(), auth.authorized("/rubies/fragments/complete", "POST", payload))
    }

    suspend fun claimWelcome(): WelcomeGift = guarded(tr("Подарок новичку не забрался")) {
        decode(WelcomeGift.serializer(), auth.authorized("/rubies/welcome/claim", "POST", buildJsonObject { }))
    }

    suspend fun inventory(): Inventory =
        read("/rubies/inventory", Inventory.serializer(), tr("Инвентарь не загрузился — нажми «Повторить»"))

    suspend fun claimWeekly(code: String): RubyItem = guarded(tr("Посылка не забралась — повтори")) {
        val payload = buildJsonObject { put("code", requireCode(code)) }
        decode(ItemGrant.serializer(), auth.authorized("/rubies/weekly/claim", "POST", payload)).item
    }

    suspend fun progress(): EconomyProgress =
        read("/rubies/progress", EconomyProgress.serializer(), tr("Награды за игру не загрузились — нажми «Повторить»"))

    suspend fun claimProgress(code: String): PlaytimeReward = guarded(tr("Награда не забралась — повтори")) {
        val path = "/rubies/progress/" + segment(code) + "/claim"
        decode(PlaytimeReward.serializer(), auth.authorized(path, "POST", buildJsonObject { }))
    }

    suspend fun plus(): PlusStatus =
        read("/launcher/plus", PlusStatus.serializer(), tr("PLUS не загрузился — нажми «Повторить»"))

    /** The gateway page, checked before it is handed to a browser: a forged answer must not lead to a phishing page. */
    suspend fun subscribePlus(tier: String = PLUS_TIER): String = guarded(tr("Подписка не оформилась — повтори")) {
        val wire = tier.trim().uppercase()
        if (wire != PLUS_TIER && wire != DIAMOND_TIER) throw RubiesError(tr("Такого тарифа нет — обнови приложение"))
        val payload = buildJsonObject { put("tier", wire) }
        val started = decode(PaymentStart.serializer(), auth.authorized("/launcher/plus/subscribe", "POST", payload))
        if (!isPaymentUrl(started.paymentUrl)) {
            throw RubiesError(tr("Ссылка на оплату не прошла проверку — напиши в поддержку"))
        }
        started.paymentUrl.trim()
    }

    suspend fun sets(): SetsDay =
        read("/rubies/sets", SetsDay.serializer(), tr("Наборы не загрузились — нажми «Повторить»"))

    /** [expect] is the price the player saw: when it moved, the service refuses and the shop reloads. */
    suspend fun buySet(setId: String, colorway: String, expect: Long): SetPurchase = guarded(tr("Набор не купился — повтори")) {
        val payload = buildJsonObject {
            put("setId", requireCode(setId))
            put("colorway", colorway.trim())
            put("expect", expect)
        }
        decode(SetPurchase.serializer(), auth.authorized("/rubies/sets/buy", "POST", payload))
    }

    suspend fun cases(): CasesDay =
        read("/rubies/cases", CasesDay.serializer(), tr("Ящики не загрузились — нажми «Повторить»"))

    suspend fun caseContents(id: String): CaseContents =
        read("/rubies/cases/" + segment(id), CaseContents.serializer(), tr("Состав ящика не загрузился — нажми «Повторить»"))

    /**
     * [requestId] is replayed by the service: a retry with the same key never
     * charges twice, so the caller keeps one key per opening, not per attempt.
     */
    suspend fun openCase(caseId: String, requestId: String, mode: CaseMode): CaseOpened = guarded(tr("Ящик не открылся — повтори")) {
        val payload = buildJsonObject {
            put("caseId", requireCode(caseId))
            put("requestId", requireCode(requestId))
            when (mode) {
                CaseMode.TEN -> put("count", CASE_TEN)
                CaseMode.FREE -> put("free", true)
                CaseMode.ONE -> Unit
            }
        }
        decode(CaseOpened.serializer(), auth.authorized("/rubies/cases/open", "POST", payload))
    }

    suspend fun playChests(): PlayChests =
        read("/rubies/play-chests", PlayChests.serializer(), tr("Сундуки за игру не загрузились — нажми «Повторить»"))

    suspend fun claimPlayChest(): PlayChestClaim = guarded(tr("Сундук не забрался, попробуй позже")) {
        decode(PlayChestClaim.serializer(), auth.authorized("/rubies/play-chests/claim", "POST", buildJsonObject { }))
    }

    suspend fun telegram(): TelegramReward =
        read("/rubies/telegram", TelegramReward.serializer(), tr("Награда за Telegram не загрузилась"))

    suspend fun claimTelegram(): TelegramReward = guarded(tr("Подписка не проверилась — повтори")) {
        decode(TelegramReward.serializer(), auth.authorized("/rubies/telegram/claim", "POST", buildJsonObject { }))
    }

    suspend fun discord(): DiscordReward =
        read("/rubies/discord", DiscordReward.serializer(), tr("Награда за Discord не загрузилась"))

    suspend fun claimDiscord(): DiscordReward = guarded(tr("Сервер Discord не проверился — повтори")) {
        decode(DiscordReward.serializer(), auth.authorized("/rubies/discord/claim", "POST", buildJsonObject { }))
    }

    /** The account link runs through Discord's own OAuth page: any other host is refused before the browser. */
    suspend fun discordLinkUrl(): String = guarded(tr("Не получилось открыть привязку Discord, попробуй ещё раз")) {
        val url = decode(LinkUrl.serializer(), auth.authorized("/users/me/discord/link-url", "GET", null)).url
        discordLinkPage(url) ?: throw RubiesError(tr("Не получилось открыть привязку Discord, попробуй ещё раз"))
    }

    suspend fun redeemPromo(code: String): PromoResult = guarded(tr("Промокод не применился — повтори")) {
        val payload = buildJsonObject { put("code", requireCode(code)) }
        decode(PromoResult.serializer(), auth.authorized("/rubies/promo", "POST", payload))
    }

    /** The wallet in the form purchases are checked with; a shape we do not know is a failure, not a zero balance. */
    suspend fun money(): MoneyView = guarded(tr("Баланс Millida не загрузился")) {
        val view = decode(MoneyView.serializer(), auth.authorizedElement("/me/money", "GET", null))
        if (!view.valid) throw RubiesError(tr("Баланс Millida не загрузился"))
        view
    }

    /** Open to guests: the PLUS page shows Milli's limits before the sign-in. */
    suspend fun milliLimits(): MilliLimits = guarded(tr("Лимиты Милли не загрузились")) {
        decode(MilliLimits.serializer(), openRead("/catalog/milli/limits"))
    }

    suspend fun launcherStats(): LauncherStats = guarded(tr("Статистика лаунчера не загрузилась")) {
        decode(LauncherStats.serializer(), openRead("/launcher/stats"))
    }

    private suspend fun openRead(path: String): JsonElement = quietRetry { auth.publicCall(path, "GET", null) }

    private suspend fun <T> read(path: String, serializer: DeserializationStrategy<T>, fallback: String): T =
        guarded(fallback) { quietRetry { decode(serializer, auth.authorizedElement(path, "GET", null)) } }

    private fun claimOf(json: JsonObject): DailyClaim {
        val status = decode(DailyStatus.serializer(), json)
        val wire = decode(ClaimWire.serializer(), json)
        return DailyClaim(status, wire.granted, wire.balance)
    }

    private fun <T> decode(serializer: DeserializationStrategy<T>, element: JsonElement): T =
        MojangJson.decodeFromJsonElement(serializer, element)

    private inline fun <T> guarded(fallback: String, block: () -> T): T = try {
        block()
    } catch (already: RubiesError) {
        throw already
    } catch (expired: Unauthorized) {
        throw expired
    } catch (unreadable: SerializationException) {
        throw RubiesError(tr("Магазин ответил непонятно — обнови приложение"), unreadable)
    } catch (error: IOException) {
        val raw = error.message.orEmpty()
        if (WALLET_SHORT.containsMatchIn(raw)) {
            throw WalletShort(tr("На счёте Millida не хватает денег — пополни его и повтори"), error)
        }
        throw RubiesError(FriendsErrors.text(error, fallback), error)
    }

    private fun requireCode(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CODE) throw RubiesError(tr("Неверный код вещи — обнови магазин"))
        return trimmed
    }

    /** Ids and codes reach us from the network: encoded, never trusted as a path. */
    private fun segment(value: String): String {
        val trimmed = requireCode(value)
        if (trimmed.contains('/') || trimmed.contains("..")) throw RubiesError(tr("Неверный код — обнови магазин"))
        return URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
    }

    @Serializable
    private data class ClaimWire(
        val balance: Long = 0,
        val granted: List<RubyReward> = emptyList(),
    )

    @Serializable
    private data class GiftTotals(val balance: Long = 0, val shards: Long = 0)

    @Serializable
    private data class ItemGrant(val item: RubyItem = RubyItem())

    @Serializable
    private data class PaymentStart(val paymentUrl: String = "")

    @Serializable
    private data class LinkUrl(val url: String = "")

    companion object {
        const val MAX_TRACK_DAY = 28
        const val EXACT_MIN_RUBIES = 7
        const val PLUS_TIER = "PLUS"
        const val DIAMOND_TIER = "DIAMOND"
        private const val CASE_TEN = 10
        private const val MAX_CODE = 128
        private val CLAIM_FAILED: String get() = tr("Награда не забралась — повтори")
        private val WALLET_SHORT = Regex("insufficient_funds|не хватает денег|недостаточно средств", RegexOption.IGNORE_CASE)
        private val CHEST_GONE = Regex("уже открыт|сундука нет|already", RegexOption.IGNORE_CASE)
    }
}

private val PAY_DOMAINS = listOf("antilopay.com", "enot.io", "millida.net")

fun isPaymentUrl(url: String?): Boolean {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return false
    val uri = try {
        URI(text)
    } catch (malformed: URISyntaxException) {
        return false
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
    val host = uri.host?.lowercase() ?: return false
    return PAY_DOMAINS.any { host == it || host.endsWith(".$it") }
}

private const val SEED_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val SEED_LENGTH = 10
private val seedRandom = SecureRandom()

fun freshClientSeed(): String = buildString(SEED_LENGTH) {
    repeat(SEED_LENGTH) { append(SEED_ALPHABET[seedRandom.nextInt(SEED_ALPHABET.length)]) }
}
