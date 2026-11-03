package net.millida.launcher.ui.shop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import net.millida.launcher.CaseRun
import net.millida.launcher.caseCard
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.rubies.CaseMode
import net.millida.launcher.core.rubies.ShopCase
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits

private const val CASE_HITS = 3

/** The guarantee the climb starts from; a free first case is at least rare, as the service rolls it. */
internal fun caseFloor(case: ShopCase, mode: CaseMode): Rarity? {
    val floor = Rarity.of(case.guarantee?.minRarity) ?: return null
    return if (mode == CaseMode.FREE && floor < Rarity.RARE) Rarity.RARE else floor
}

/**
 * A case opening on the shared reveal: the request is already paid and sent,
 * so the result is known before the first hit; «Ещё раз» opens the same case
 * the same way (a free first one is followed by a paid one).
 */
@Composable
internal fun CaseScene(
    run: CaseRun,
    balance: Long?,
    shards: Long?,
    onRetry: () -> Unit,
    onAgain: () -> Unit,
    onShort: (Long) -> Unit,
    onWardrobe: () -> Unit,
    onFail: (String) -> Unit,
    onClose: () -> Unit,
) {
    key(run.serial) {
        val result = run.result
        val opens = remember(result) {
            result?.openList?.map { open ->
                RevealOpen(open.rewards.map(::caseCard), open.kind.ifEmpty { OPEN_ITEM }, Rarity.of(open.rarity))
            }
        }
        val look = caseChest(run.case.id)
        val next = if (run.mode == CaseMode.FREE) CaseMode.ONE else run.mode
        val price = run.case.priceOf(next)
        val wallet = if (run.case.paidInShards) shards else balance
        val affordable = wallet == null || price <= wallet
        val again = if (run.case.exhausted) {
            null
        } else {
            RevealAgain(
                label = if (next == CaseMode.TEN) tr("Ещё ×10 · {0}", groupDigits(price)) else tr("Ещё раз · {0}", groupDigits(price)),
                icon = if (run.case.paidInShards) MIcons.shard else MIcons.ruby,
                primary = affordable,
                onClick = { if (affordable) onAgain() else onShort(price - (wallet ?: 0)) },
            )
        }
        CaseReveal(
            title = run.case.title,
            tier = look.tier,
            tint = look.tint,
            color = caseTone(run.case),
            hits = CASE_HITS,
            floor = caseFloor(run.case, run.mode),
            deal = run.mode == CaseMode.TEN,
            asked = true,
            attempt = run.attempt,
            opens = opens,
            error = run.error,
            onBegin = {},
            onRetry = onRetry,
            onFail = onFail,
            onFinished = {},
            onClose = onClose,
            onWear = onWardrobe,
            again = again,
        )
    }
}
