package net.millida.launcher.core.telemetry

/**
 * What a game log says about a start that drew nothing.
 *
 * A black screen is not a crash: the game runs, the player sees his controls over
 * a black picture and closes the app. Nothing reported it, so nobody knew which
 * phones and renderers it happens on (owner, 13.10.2026: «у клиентов чёрный
 * экран, проверь по логам»). The whole log does not fit an event, so two lines
 * go: the last one that names the graphics stack and an error, and the line the
 * log stopped at, which is where a hung start stands.
 *
 * The first hour of reports (1.9.23) was mostly noise: the class path of the
 * launch command and LWJGL's remark about its library hash, which every start
 * on our own build of the natives prints. Both are skipped.
 */
object BlackScreenClue {
    const val MAX = 300
    private const val ERROR_SHARE = 170
    private const val LONGEST_LINE = 600

    private val GRAPHICS = Regex(
        "(?i)(opengl|gl_?error|gl_invalid|glsl|shader|\\begl|vulkan|\\bvk_|zink|mobileglues|krypton|gl4es|" +
            "framebuffer|\\bfbo\\b|render|swapchain|surface|lwjgl|glfw|\\bsdl)",
    )
    private val TROUBLE = Regex("(?i)(error|exception|fail|fatal|unsupported|invalid|cannot|could not|not supported|unable)")
    private val NOISE = listOf(
        "Incompatible Java and native library versions detected",
        ".jar:",
        "[millida]",
    )

    fun of(log: String?): String? {
        val lines = log.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && line.length <= LONGEST_LINE && NOISE.none { it in line } }
            .toList()
        if (lines.isEmpty()) return null
        val last = lines.last()
        val error = lines.lastOrNull { GRAPHICS.containsMatchIn(it) && TROUBLE.containsMatchIn(it) }
            ?: lines.lastOrNull { TROUBLE.containsMatchIn(it) }
        if (error == null || error == last) return last.take(MAX)
        return (error.take(ERROR_SHARE) + SEPARATOR + last).take(MAX)
    }

    const val SEPARATOR = " | "
}
