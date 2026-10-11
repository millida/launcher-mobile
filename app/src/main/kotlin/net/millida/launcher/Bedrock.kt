package net.millida.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import net.millida.launcher.core.bedrock.BEDROCK_PACKAGE
import net.millida.launcher.core.bedrock.BEDROCK_STORE_URL
import net.millida.launcher.core.bedrock.BedrockAddress
import net.millida.launcher.core.bedrock.bedrockAddServerUri
import net.millida.launcher.core.bedrock.bedrockConnectUri

enum class BedrockHandoff { CONNECTED, ADDED, NOT_INSTALLED, FAILED }

fun bedrockInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(BEDROCK_PACKAGE, 0) }.isSuccess

/**
 * The Bedrock client is a separate, closed application: the launcher cannot run
 * it, but it can hand the server over. "connect" joins at once; clients that do
 * not know that handler still accept a saved server, so the player finishes in
 * the server list instead of nowhere.
 */
fun playOnBedrock(context: Context, name: String, address: BedrockAddress): BedrockHandoff {
    if (!bedrockInstalled(context)) return BedrockHandoff.NOT_INSTALLED
    if (startBedrock(context, bedrockConnectUri(address))) return BedrockHandoff.CONNECTED
    if (startBedrock(context, bedrockAddServerUri(name, address))) return BedrockHandoff.ADDED
    return BedrockHandoff.FAILED
}

/** Opens the client on its own server list, so a saved server is one tap away. */
fun openBedrockApp(context: Context): Boolean {
    if (!bedrockInstalled(context)) return false
    if (startBedrock(context, "minecraft://openServersTab")) return true
    val launch = context.packageManager.getLaunchIntentForPackage(BEDROCK_PACKAGE) ?: return false
    return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

fun openBedrockStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$BEDROCK_PACKAGE"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.packageManager.resolveActivity(market, 0) != null) {
        runCatching { context.startActivity(market) }.onSuccess { return }
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(BEDROCK_STORE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun startBedrock(context: Context, uri: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .setPackage(BEDROCK_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return false
    return runCatching { context.startActivity(intent) }.isSuccess
}
