package net.millida.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private class OnboardingStep(
    val icon: MillidaIcon,
    val tone: MHeroTone,
    val title: String,
    val text: String,
)

private fun onboardingSteps(bundled: Boolean, accentTone: MHeroTone): List<OnboardingStep> = listOf(
    OnboardingStep(
        icon = MIcons.box,
        tone = MHeroTone.Daily,
        title = "Всё ставится само",
        text = if (bundled) {
            "Java и графика распакуются при первом запуске — один раз"
        } else {
            "Java и графика скачаются при первом запуске игры — один раз"
        },
    ),
    OnboardingStep(
        icon = MIcons.chest,
        tone = accentTone,
        title = "Сборка — твоя игра",
        text = "Версия, моды и память у каждой свои",
    ),
    OnboardingStep(
        icon = MIcons.users,
        tone = MHeroTone.Wardrobe,
        title = "Аккаунт Millida",
        text = "Скин и плащ на серверах, друзья и облако сборок",
    ),
    OnboardingStep(
        icon = MIcons.hand,
        tone = MHeroTone.Shop,
        title = "Управление под тебя",
        text = "В игре: «Меню» → «Настроить управление»",
    ),
)

/** The first run: what the launcher does by itself and where things live. */
@Composable
fun Onboarding(bundledRuntime: Boolean, onDone: () -> Unit, onTour: (() -> Unit)? = null) {
    val colors = millida
    val accentTone = MHeroTone.server(colors.accent)
    val steps = remember(bundledRuntime, accentTone) { onboardingSteps(bundledRuntime, accentTone) }
    val pager = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == steps.lastIndex

    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = screenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Millida",
                    modifier = Modifier.weight(1f),
                    style = MillidaType.brand.copy(fontSize = MillidaType.barTitle.fontSize),
                    color = colors.fg,
                )
                if (!last) MTextButton("Пропустить", onClick = onDone)
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                StepPage(steps[page], current = page == pager.currentPage)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = screenPadding, end = screenPadding, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProgressSquares(count = steps.size, current = pager.currentPage)
                Spacer(Modifier.height(20.dp))
                MPrimaryButton(
                    text = if (last) "Начать" else "Дальше",
                    onClick = {
                        if (last) {
                            onDone()
                        } else {
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        }
                    },
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth(),
                    icon = if (last) MIcons.play else MIcons.arrowRight,
                    size = MButtonSize.Large,
                )
                if (last && onTour != null) {
                    Spacer(Modifier.height(8.dp))
                    MTextButton("Показать лобби", onClick = onTour, icon = MIcons.info)
                }
            }
        }
    }
}

@Composable
private fun StepPage(step: OnboardingStep, current: Boolean) {
    val colors = millida
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StepScene(step, current)
        Spacer(Modifier.height(32.dp))
        Text(
            step.title,
            style = MillidaType.screenTitle,
            color = colors.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            step.text,
            style = MillidaType.body.copy(fontSize = MillidaType.rowTitle.fontSize),
            color = colors.fgMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepScene(step: OnboardingStep, current: Boolean) {
    val reduced = LocalReducedMotion.current
    val rest = remember { mutableFloatStateOf(0f) }
    val hop: State<Float>? = if (current && !reduced) {
        rememberInfiniteTransition(label = "onboarding-hop").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(MillidaMotion.hop, easing = LinearEasing)),
            label = "onboarding-hop-phase",
        )
    } else {
        null
    }
    Box(Modifier.size(width = 196.dp, height = 176.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .size(width = 196.dp, height = 150.dp)
                .pixelSole(rest)
                .pixelSurface(
                    fill = step.tone.plate,
                    cut = MillidaCut.plate,
                    frame = PixelFrame(
                        outline = step.tone.outline,
                        outlineWidth = 3.dp,
                        top = MillidaPixel.tileTop,
                        topWidth = 4.dp,
                        bottom = MillidaPixel.tileBottom,
                        bottomWidth = 5.dp,
                    ),
                    dots = true,
                ),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(
                step.icon,
                size = 96.dp,
                color = MillidaPixel.white,
                modifier = Modifier.graphicsLayer { translationY = hopAt(hop?.value ?: 0f).dp.toPx() * 2f },
            )
        }
    }
}

@Composable
private fun ProgressSquares(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until count) {
            ProgressSquare(on = index == current)
        }
    }
}

@Composable
private fun ProgressSquare(on: Boolean) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val stretch by animateDpAsState(
        targetValue = if (on) 28.dp else 10.dp,
        animationSpec = tween(if (reduced) 1 else MillidaMotion.base, easing = MillidaMotion.steps(3)),
        label = "onboarding-square",
    )
    Box(
        Modifier
            .size(width = stretch, height = 10.dp)
            .pixelSurface(if (on) colors.accent else colors.borderStrong, 2.dp),
    )
}
