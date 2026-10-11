package net.millida.launcher.core.runtime

/**
 * Flags from a version description, a pack or the player that the phone's
 * Java refuses to start with, or that only hurt there. The launcher picks the
 * collector itself, and a second collector on the same command line stops
 * the machine before the game prints a line.
 */
object PhoneJvmArgs {
    private const val FIRST_MODULAR_JAVA = 9

    private val COLLECTORS = listOf(
        "-xx:+useg1gc", "-xx:+usezgc", "-xx:+useshenandoahgc", "-xx:+useparallelgc", "-xx:+useparalleloldgc",
        "-xx:+useconcmarksweepgc", "-xx:+useparnewgc", "-xx:+useepsilongc", "-xx:+zgenerational",
    )

    /**
     * Collector tuning of the removed CMS, Graal's compiler switches the
     * runtime's HotSpot lacks, the macOS window flag, and memory flags that
     * make the phone commit the whole heap at once or ask for huge pages it
     * does not give.
     */
    private val DROPPED_PREFIXES = listOf(
        "-xx:+cms", "-xx:-cms", "-xx:cms", "-xx:+usecmsinitiatingoccupancyonly",
        "-xx:+enablejvmci", "-xx:+usejvmcicompiler", "-xx:+usejvmcinativelibrary", "-xx:jvmci",
        "-xstartonfirstthread",
        "-xx:+alwayspretouch", "-xx:+uselargepages", "-xx:largepagesizeinbytes", "-xx:+usetransparenthugepages",
        "-xx:+usenuma", "-xx:+usestringdeduplication", "-xx:permsize", "-xx:maxpermsize",
    )

    /** Java 8 does not know the module system and refuses to start on any of these. */
    private val MODULAR_PREFIXES = listOf(
        "--add-opens", "--add-exports", "--add-reads", "--add-modules", "--enable-native-access",
        "--illegal-access", "--enable-preview", "-xx:+enabledynamicagentloading",
    )

    fun keep(arg: String, javaMajor: Int): Boolean {
        val low = arg.trim().lowercase()
        if (low.isEmpty()) return false
        if (COLLECTORS.any { low == it }) return false
        if (DROPPED_PREFIXES.any { low.startsWith(it) }) return false
        if (javaMajor in 1 until FIRST_MODULAR_JAVA && MODULAR_PREFIXES.any { low.startsWith(it) }) return false
        return true
    }

    /** A modular flag written as two tokens takes its value along, or Java 8 reads the value as the main class. */
    fun filter(args: List<String>, javaMajor: Int): List<String> {
        val out = ArrayList<String>(args.size)
        var skipValue = false
        for (arg in args) {
            if (skipValue) {
                skipValue = false
                if (!arg.startsWith("-")) continue
            }
            if (keep(arg, javaMajor)) {
                out += arg
                continue
            }
            val low = arg.trim().lowercase()
            skipValue = !low.contains('=') && MODULAR_PREFIXES.any { low == it }
        }
        return out
    }
}
