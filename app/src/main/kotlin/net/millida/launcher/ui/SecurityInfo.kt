package net.millida.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

import net.millida.launcher.core.i18n.tr
const val SECURITY_URL = "https://millida.net/launcher/bezopasnost"

private class BrandShape(val d: String, val fill: Color, val transform: BrandTransform? = null)

private class BrandTransform(val tx: Float, val ty: Float, val scale: Float, val ox: Float, val oy: Float)

private class Brand(val name: String, val left: Float, val top: Float, val side: Float, val shapes: List<BrandShape>)

private val MICROSOFT = Brand(
    "Microsoft",
    0f,
    0f,
    24f,
    listOf(
        BrandShape("M1 1h10v10H1z", MillidaPixel.brandMicrosoft[0]),
        BrandShape("M13 1h10v10H13z", MillidaPixel.brandMicrosoft[1]),
        BrandShape("M1 13h10v10H1z", MillidaPixel.brandMicrosoft[2]),
        BrandShape("M13 13h10v10H13z", MillidaPixel.brandMicrosoft[3]),
    ),
)

private val MOJANG = Brand(
    "Mojang Studios",
    62f,
    62f,
    676f,
    listOf(
        BrandShape("M62 62h676v676H62z", MillidaPixel.brandMojang),
        BrandShape(
            "M 13.51988 41.11152 q 0 -12.31086 0.0047 -24.62173 c 0.00469 -2.87722 0.16418 -3.03875 3.01027 -3.04146 q 21.527 -0.02051 43.054 -0.01353 c 2.06114 0.0004 2.36366 0.32134 2.39718 2.42144 c 0.0238 1.49194 -0.01523 2.98567 0.03993 4.47616 a 6.77817 6.77817 0 0 0 6.69647 6.91868 c 1.65784 0.11443 3.328 0.04044 4.99213 0.07612 c 1.797 0.03852 2.04939 0.30467 2.12207 2.13943 c 0.01137 0.28664 0.01239 0.57383 0.01247 0.86076 q 0.00432 17.64834 0.00423 35.29668 c -0.00158 3.2118 -0.0499 3.24957 -3.28974 3.26017 q -3.78871 0.01236 -7.57745 -0.01166 c -2.96088 -0.02069 -3.03087 -0.09046 -3.032 -2.98084 q -0.00659 -16.87367 -0.00343 -33.74735 c 0 -0.40175 0.01018 -0.80387 -0.00321 -1.20519 a 3.329 3.329 0 0 0 -3.548 -3.56756 c -1.08962 -0.032 -2.18238 -0.03167 -3.27158 0.01034 a 3.34773 3.34773 0 0 0 -3.482 3.47659 c -0.03516 0.62965 -0.02128 1.26231 -0.02172 1.8936 q -0.00641 9.21165 -0.01056 18.42326 c -0.00046 0.68869 0.01794 1.37909 -0.02307 2.06567 c -0.075 1.25525 -0.44257 1.71282 -1.65149 1.72825 q -5.25132 0.06705 -10.50391 -0.00754 c -1.2165 -0.01879 -1.58169 -0.48679 -1.66785 -1.72683 c -0.03575 -0.51435 -0.01843 -1.03265 -0.019 -1.54914 q -0.0112 -9.81428 -0.0222 -19.62854 a 13.12088 13.12088 0 0 0 -0.05025 -1.71908 a 3.25128 3.25128 0 0 0 -3.10179 -2.93609 a 33.39881 33.39881 0 0 0 -3.95853 0.00271 a 3.1568 3.1568 0 0 0 -3.15611 3.21043 c -0.05842 1.26029 -0.03349 2.52474 -0.03457 3.78735 q -0.01343 15.66842 -0.02277 31.33681 c -0.00035 0.45913 0.01748 0.92 -0.01557 1.377 c -0.09 1.24451 -0.45629 1.713 -1.67232 1.72878 q -5.25117 0.06813 -10.50381 -0.01831 c -1.23915 -0.02079 -1.58295 -0.457 -1.6812 -1.71692 c -0.04454 -0.5709 -0.02489 -1.1472 -0.025 -1.72107 q -0.00183 -12.13872 -0.00083 -24.27744 Z",
            MillidaPixel.white,
            BrandTransform(tx = 399.77f, ty = 399.75f, scale = 7.56f, ox = -44.68f, oy = -41.16f),
        ),
    ),
)

private val GITHUB = Brand(
    "GitHub",
    0f,
    0f,
    16f,
    listOf(
        BrandShape(
            "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z",
            MillidaPixel.brandGithub,
        ),
    ),
)

private class TrustRow(val text: String, val logo: Brand? = null, val detail: String? = null)

// Only what this app's code does. The desktop rows about the signed Windows installer
// and its VirusTotal report are about a file the phone never runs, so they are not here.
private val TRUST_ROWS get() = listOf(
    TrustRow(tr("Твою лицензию не используем")),
    TrustRow(tr("Вход только через Microsoft"), MICROSOFT),
    TrustRow(tr("Игра скачивается с серверов Mojang"), MOJANG, tr("Не открылись — те же файлы через наше зеркало")),
    TrustRow(tr("Файлы игры оригинальные, мы их не меняем"), detail = tr("Каждый файл сверяется с отпечатком Mojang")),
    TrustRow(tr("Код открыт на GitHub"), GITHUB, "GPL-3.0"),
)

private class SecStep(val title: String, val text: String)

private class SecSection(
    val title: String,
    val intro: List<String>,
    val steps: List<String>,
    val more: List<SecStep>,
    val honest: List<String>,
)

private val SEC_SECTIONS get() = listOf(
    SecSection(
        title = tr("Как мы не получаем твою лицензию"),
        intro = listOf(
            tr("Ты входишь на сайте Microsoft. Пароль вводишь только там."),
            tr("Лаунчер хранит ключ входа у тебя на телефоне, зашифрованным."),
            tr("Нам ключ не отправляется никогда."),
        ),
        steps = listOf(
            tr("Лаунчер показывает код"),
            tr("Открываешь microsoft.com/link"),
            tr("Вводишь код и пароль там"),
            tr("Microsoft даёт лаунчеру ключ"),
            tr("Ключ лежит у тебя, зашифрован"),
        ),
        more = listOf(
            SecStep(
                tr("Лаунчер просит у Microsoft короткий код"),
                tr("Он стучится на login.microsoftonline.com и получает код из нескольких букв и цифр. Это стандартный способ Microsoft для программ без своей страницы входа. Называется Device Code: «код устройства»."),
            ),
            SecStep(
                tr("Ты открываешь настоящую страницу Microsoft"),
                tr("Страница microsoft.com/link открывается в твоём обычном браузере, не внутри лаунчера. Адрес и замочек видны, как на любом сайте. Вводишь код и входишь так же, как заходишь в Outlook или Xbox. Пароль набираешь только там."),
            ),
            SecStep(
                tr("Лаунчер ждёт и спрашивает: «ну что?»"),
                tr("Пока ты вводишь пароль у Microsoft, лаунчер раз за разом спрашивает у неё: «человек уже вошёл?». Логина и пароля он не видит и не спрашивает. Ответ «да» приходит вместе с токеном. Токен — временный ключ вместо пароля: им можно доказать, что ты вошёл, но нельзя зайти в твой аккаунт заново."),
            ),
            SecStep(
                tr("Ключ меняется на пропуск в Minecraft"),
                tr("Токен Microsoft лаунчер по цепочке меняет на ключи Xbox Live, затем XSTS, затем Minecraft, и в конце спрашивает у Mojang твой профиль: ник и скин. Все остановки в цепочке — серверы Microsoft, Xbox и Mojang. Больше никого там нет."),
            ),
            SecStep(
                tr("Ключи ложатся в зашифрованное хранилище на телефоне"),
                tr("Они лежат в хранилище приложения, зашифрованные шифром AES-256-GCM. Ключ шифра держит Android Keystore — системный сейф ключей телефона. Другие приложения это хранилище не открывают, а на другом телефоне его не расшифровать."),
            ),
            SecStep(
                tr("К нам уходит только то, что нужно нам"),
                tr("Запросы к серверам Millida несут только токен аккаунта Millida. Токены Microsoft, Xbox и Minecraft на наши серверы не отправляются. Единственные места, куда они уходят по сети, — серверы Microsoft, Xbox и Mojang, и сама игра при запуске."),
            ),
        ),
        honest = listOf(
            tr("Это защита Android, а не отдельный сейф: на телефоне с root-доступом программа с правами суперпользователя теоретически может добраться до ключей."),
            tr("Сама игра получает токен Minecraft при запуске, это неизбежно. Мод — обычный Java-код, и технически он может этот токен прочитать. Поэтому ставь моды из каталога и проверяй их сканером."),
        ),
    ),
)

/** The desktop's trust card (`sec-trust`): a white plate of checked facts with the brands they rest on. */
@Composable
fun MSecurityTrust(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MillidaPixel.trustPlate),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(MillidaPixel.trustHeadLine, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(MillidaPixel.trustGreen),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.lock, size = 16.dp, color = MillidaPixel.white)
            }
            Text(
                tr("Безопасность лаунчера"),
                modifier = Modifier.weight(1f),
                style = MillidaType.body.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                color = MillidaPixel.trustInk,
            )
            Text(
                tr("ПРОВЕРЕНО"),
                modifier = Modifier
                    .background(MillidaPixel.trustTagPlate)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MillidaType.small.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.08.em),
                color = MillidaPixel.trustTagInk,
            )
        }
        TRUST_ROWS.forEachIndexed { index, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (index > 0) drawRect(MillidaPixel.trustRowLine, Offset.Zero, Size(size.width, 1.dp.toPx()))
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrustTick()
                Column(Modifier.weight(1f)) {
                    Text(
                        row.text,
                        style = MillidaType.body.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp),
                        color = MillidaPixel.trustInk,
                    )
                    if (row.detail != null) {
                        Text(
                            row.detail,
                            style = MillidaType.small.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            color = MillidaPixel.trustMuted,
                        )
                    }
                }
                row.logo?.let { BrandLogo(it) }
            }
        }
    }
}

@Composable
private fun TrustTick() {
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(width = 2.6f * unit)
        drawCircle(MillidaPixel.trustGreen, radius = 9f * unit, style = stroke)
        val tick = Path().apply {
            moveTo(8f * unit, 12.5f * unit)
            lineTo(11f * unit, 15.5f * unit)
            lineTo(16f * unit, 9.5f * unit)
        }
        drawPath(tick, MillidaPixel.trustGreen, style = stroke)
    }
}

@Composable
private fun BrandLogo(brand: Brand) {
    val paths = remember(brand) { brand.shapes.map { PathParser().parsePathString(it.d).toPath() } }
    Canvas(
        Modifier
            .size(22.dp)
            .semantics { contentDescription = brand.name },
    ) {
        val k = size.width / brand.side
        scale(k, k, pivot = Offset.Zero) {
            translate(-brand.left, -brand.top) {
                brand.shapes.forEachIndexed { index, shape ->
                    val t = shape.transform
                    if (t == null) {
                        drawPath(paths[index], shape.fill)
                    } else {
                        withTransform({
                            translate(t.tx, t.ty)
                            scale(t.scale, t.scale, pivot = Offset.Zero)
                            translate(t.ox, t.oy)
                        }) {
                            drawPath(paths[index], shape.fill)
                        }
                    }
                }
            }
        }
    }
}

/**
 * «Как это работает?» from the desktop's account window (`modals/SecurityInfo`):
 * the trust card, then each chapter in three short lines with the steps, the
 * details and the honest caveats behind «Подробнее», and the full page on the site.
 */
@Composable
fun SecurityInfo(onBack: () -> Unit, onSite: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        MTextButton(tr("Назад"), onClick = onBack, icon = MIcons.chevronLeft)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            MSecurityTrust()
            SEC_SECTIONS.forEach { SecChapter(it) }
        }
        Spacer(Modifier.height(14.dp))
        MPrimaryButton(tr("Подробнее на сайте"), onClick = onSite, icon = MIcons.externalLink, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SecChapter(section: SecSection) {
    val colors = millida
    var open by rememberSaveable(section.title) { mutableStateOf(false) }
    Column {
        Text(
            section.title,
            style = MillidaType.sectionTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
            color = colors.fg,
        )
        Spacer(Modifier.height(10.dp))
        section.intro.forEach { line ->
            Text(
                line,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .background(colors.surface2)
                    .drawBehind { drawRect(colors.accent, Offset.Zero, Size(3.dp.toPx(), size.height)) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MillidaType.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp),
                color = colors.fg,
            )
        }
        Spacer(Modifier.height(4.dp))
        MoreToggle(open) { open = !open }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                section.steps.forEachIndexed { index, step -> SecItem(index + 1, step, null, short = true) }
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                section.more.forEachIndexed { index, step -> SecItem(index + 1, step.title, step.text, short = false) }
            }
            if (section.honest.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.warningSoft)
                        .drawBehind { drawRect(colors.warning, Offset.Zero, Size(3.dp.toPx(), size.height)) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        tr("Честно"),
                        style = MillidaType.small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold),
                        color = colors.warning,
                    )
                    section.honest.forEach { line ->
                        Text(line, style = MillidaType.small.copy(fontSize = 12.5.sp, lineHeight = 19.sp), color = colors.fgMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreToggle(open: Boolean, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(if (open) MIcons.chevronDown else MIcons.chevronRight, size = 14.dp, color = colors.fgMuted)
        Text(
            tr("Подробнее"),
            style = MillidaType.small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold),
            color = colors.fgMuted,
        )
    }
}

@Composable
private fun SecItem(number: Int, title: String, text: String?, short: Boolean) {
    val colors = millida
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface2)
            .padding(start = 10.dp, end = 12.dp, top = if (short) 7.dp else 10.dp, bottom = if (short) 8.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MillidaType.small.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                color = colors.onAccent,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MillidaType.body.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 18.sp),
                color = colors.fg,
            )
            if (text != null) {
                Spacer(Modifier.height(4.dp))
                Text(text, style = MillidaType.small.copy(fontSize = 12.5.sp, lineHeight = 19.sp), color = colors.fgSubtle)
            }
        }
    }
}
