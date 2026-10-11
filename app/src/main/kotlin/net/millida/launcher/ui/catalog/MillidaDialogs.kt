package net.millida.launcher.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel

/**
 * The windows a purchase, a paid pack's key or a phone refusal opens. They
 * belong to no screen: an install from a link, «Играть» on a server, an update
 * on the build screen all lead here, so they are drawn once above everything.
 */
@Composable
fun MillidaDialogsHost(model: LauncherViewModel) {
    val shelf = model.millidaShelf
    val content = model.millidaContent
    val ms by shelf.state.collectAsStateWithLifecycle()
    val cs by content.state.collectAsStateWithLifecycle()

    ms.buy?.let { ask -> BuySheet(ask = ask, onConfirm = shelf::confirmBuy, onDismiss = shelf::closeBuy) }

    ms.key?.let { ask ->
        KeySheet(
            ask = ask,
            onRedeem = shelf::redeem,
            onBuy = shelf::buyOnSellerPage,
            onPlan = shelf::subscribePlan,
            onDismiss = shelf::closeKey,
        )
    }

    ms.refusal?.let { refusal -> PhoneRefusalDialog(refusal = refusal, onDismiss = shelf::dismissRefusal) }

    cs.ask?.let { ask ->
        ContentAskDialog(
            ask = ask,
            owned = ms.access[ask.slug] == true,
            onConfirm = content::confirmAsk,
            onDismiss = content::dismissAsk,
        )
    }
}
