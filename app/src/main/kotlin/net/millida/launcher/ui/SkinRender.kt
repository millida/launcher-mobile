package net.millida.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.millida.launcher.core.cosmetics.Pixels

@Composable
fun SkinFigure(
    url: String?,
    slim: Boolean,
    modifier: Modifier = Modifier,
    background: Color = millida.inset,
) {
    Box(
        modifier
            .clip(MillidaShape.card)
            .background(background),
    ) {
        SkinStage(
            skinUrl = url,
            slim = slim,
            modifier = Modifier.fillMaxSize(),
            pose = SkinPose.Still,
            interactive = false,
        )
    }
}

@Composable
fun CapeFigure(
    url: String?,
    modifier: Modifier = Modifier,
    background: Color = millida.inset,
) {
    val texture by rememberTexture(url, null, ::capeFront)
    val silhouette = millida.fgFaint

    Box(
        modifier
            .clip(MillidaShape.control)
            .background(background),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = minOf(size.width / CAPE_COLUMNS, size.height / CAPE_ROWS)
            val origin = Offset(
                (size.width - cell * CAPE_COLUMNS) / 2f,
                (size.height - cell * CAPE_ROWS) / 2f,
            )
            val area = Size(cell * CAPE_COLUMNS, cell * CAPE_ROWS)
            when (val state = texture) {
                is TextureLoad.Ready -> {
                    val unit = state.value.unit
                    drawImage(
                        image = state.value.image,
                        srcOffset = IntOffset(unit, unit),
                        srcSize = IntSize(CAPE_COLUMNS * unit, CAPE_ROWS * unit),
                        dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()),
                        dstSize = IntSize(area.width.toInt(), area.height.toInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
                TextureLoad.Pending -> drawRect(silhouette.copy(alpha = silhouette.alpha * PENDING_TINT), origin, area)
                TextureLoad.Absent -> drawRect(silhouette, origin, area)
            }
        }
    }
}

internal sealed interface TextureLoad<out T> {
    data object Pending : TextureLoad<Nothing>
    data object Absent : TextureLoad<Nothing>
    class Ready<out T>(val value: T) : TextureLoad<T>
}

internal const val PENDING_TINT = 0.55f

@Composable
internal fun <T : Any> rememberTexture(
    url: String?,
    variant: Any?,
    prepare: (Bitmap) -> T?,
): State<TextureLoad<T>> {
    val context = LocalContext.current
    val address = url?.trim().orEmpty()
    return produceState<TextureLoad<T>>(
        initialValue = if (address.isEmpty()) TextureLoad.Absent else TextureLoad.Pending,
        key1 = address,
        key2 = variant,
    ) {
        if (address.isEmpty()) {
            value = TextureLoad.Absent
            return@produceState
        }
        if (value !is TextureLoad.Ready<*>) value = TextureLoad.Pending
        val source = loadTexture(context, address)
        val prepared = source?.let { bitmap ->
            withContext(Dispatchers.Default) { prepareTexture(address, bitmap, prepare) }
        }
        value = if (prepared == null) TextureLoad.Absent else TextureLoad.Ready(prepared)
    }
}

internal suspend fun loadTexture(context: Context, address: String): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(address)
        .allowHardware(false)
        .build()
    return when (val result = context.imageLoader.execute(request)) {
        is SuccessResult -> (result.drawable as? BitmapDrawable)?.bitmap.also { bitmap ->
            if (bitmap == null) Log.w(TEXTURE_LOG_TAG, "Texture is not a bitmap: $address")
        }
        is ErrorResult -> {
            Log.w(TEXTURE_LOG_TAG, "Texture did not load: $address", result.throwable)
            null
        }
    }
}

internal fun <T : Any> prepareTexture(address: String, source: Bitmap, prepare: (Bitmap) -> T?): T? {
    val pixels = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    if (pixels == null) {
        Log.w(TEXTURE_LOG_TAG, "Texture could not be copied to memory: $address")
        return null
    }
    return try {
        prepare(pixels).also { prepared ->
            if (prepared == null) {
                Log.w(TEXTURE_LOG_TAG, "Texture has an unsupported size ${pixels.width}x${pixels.height}: $address")
            }
        }
    } catch (error: RuntimeException) {
        Log.w(TEXTURE_LOG_TAG, "Texture could not be prepared: $address", error)
        null
    }
}

/** Skin masks of the outfit as pixels, under the key of the address list they came from. */
internal class MaskSheets(val key: String, val sheets: List<Pixels>) {
    companion object {
        val NONE = MaskSheets("", emptyList())
    }
}

/** A mask that does not load leaves the skin under it whole, as on the desktop, and is only logged. */
@Composable
internal fun rememberMaskSheets(urls: List<String>): State<MaskSheets> {
    val context = LocalContext.current
    val key = urls.joinToString("\n")
    return produceState(initialValue = MaskSheets.NONE, key) {
        if (urls.isEmpty()) {
            value = MaskSheets.NONE
            return@produceState
        }
        val sheets = urls.mapNotNull { url ->
            val bitmap = loadTexture(context, url) ?: return@mapNotNull null
            withContext(Dispatchers.Default) { prepareTexture(url, bitmap, ::pixelsOf) }
        }
        value = MaskSheets(key, sheets)
    }
}

internal fun pixelsOf(bitmap: Bitmap): Pixels {
    val data = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(data, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return Pixels(data, bitmap.width, bitmap.height)
}

/** Pictures of worn pieces by address; ones still on the figure stay while the next set loads. */
@Composable
internal fun rememberPieceTextures(urls: List<String>): State<Map<String, Bitmap>> {
    val context = LocalContext.current
    return produceState<Map<String, Bitmap>>(initialValue = emptyMap(), urls) {
        val wanted = urls.distinct()
        val kept = value.filterKeys { it in wanted }.toMutableMap()
        value = kept.toMap()
        for (url in wanted) {
            if (url in kept) continue
            val bitmap = loadTexture(context, url) ?: continue
            val software = withContext(Dispatchers.Default) { prepareTexture(url, bitmap) { it } } ?: continue
            kept[url] = software
            value = kept.toMap()
        }
    }
}

private class CapeFront(val image: ImageBitmap, val unit: Int)

private fun capeFront(source: Bitmap): CapeFront? {
    val unit = (source.width / CAPE_SHEET_WIDTH).coerceAtLeast(1)
    if (source.width < (CAPE_COLUMNS + 1) * unit || source.height < (CAPE_ROWS + 1) * unit) return null
    return CapeFront(source.asImageBitmap(), unit)
}

private const val TEXTURE_LOG_TAG = "SkinTexture"
private const val CAPE_COLUMNS = 10
private const val CAPE_ROWS = 16
private const val CAPE_SHEET_WIDTH = 64
