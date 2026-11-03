package net.millida.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The store page of this very application: the RuStore app when it is installed, the site otherwise. */
object StoreLinks {
    private const val RUSTORE_APP = "rustore://apps.rustore.ru/app/"
    private const val RUSTORE_WEB = "https://apps.rustore.ru/app/"

    fun openRuStore(context: Context): Boolean {
        val id = context.packageName
        listOf(RUSTORE_APP + id, RUSTORE_WEB + id).forEach { address ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
