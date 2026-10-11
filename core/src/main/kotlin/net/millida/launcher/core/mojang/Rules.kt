package net.millida.launcher.core.mojang

data class HostProfile(
    val osName: String,
    val osArch: String,
    val osVersion: String,
    val features: Set<String>,
) {
    companion object {
        val ANDROID_ARM64 = HostProfile(
            osName = "linux",
            osArch = "aarch64",
            osVersion = "5.15",
            features = emptySet(),
        )
    }
}

object Rules {
    fun allowed(rules: List<Rule>, host: HostProfile): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (!ruleApplies(rule, host)) continue
            allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun ruleApplies(rule: Rule, host: HostProfile): Boolean {
        rule.os?.let { os ->
            os.name?.let { if (!it.equals(host.osName, ignoreCase = true)) return false }
            os.arch?.let { if (!archMatches(it, host.osArch)) return false }
            os.version?.let { if (!Regex(it).containsMatchIn(host.osVersion)) return false }
        }
        for ((feature, expected) in rule.features) {
            val present = host.features.contains(feature)
            if (present != expected) return false
        }
        return true
    }

    private fun archMatches(required: String, host: String): Boolean = when (required.lowercase()) {
        "x86" -> host == "x86"
        "x86_64", "amd64" -> host == "x86_64" || host == "amd64"
        "arm64", "aarch64" -> host == "arm64" || host == "aarch64"
        "arm32", "arm" -> host == "arm" || host == "arm32"
        else -> required.equals(host, ignoreCase = true)
    }
}

object Maven {
    /** A malformed coordinate from a third-party loader manifest must not take
     * the whole build down, so it is reported as an unusable library instead. */
    fun pathOf(name: String): String {
        val parts = name.split(':')
        if (parts.size < 3) throw java.io.IOException("непонятная координата библиотеки: $name")
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        // The extension rides on the last element: `client:1.20.1:mappings@txt`
        // is a text file, not a jar with "@txt" in its classifier.
        val last = classifier ?: version
        val extension = if (last.contains('@')) last.substringAfter('@') else "jar"
        val cleanVersion = version.substringBefore('@')
        val tail = buildString {
            append(artifact).append('-').append(cleanVersion)
            if (classifier != null) append('-').append(classifier.substringBefore('@'))
            append('.').append(extension)
        }
        return "$group/$artifact/$cleanVersion/$tail"
    }

    /**
     * The classifier is part of the identity: since 26.x the native bundles are
     * separate entries like `com.mojang:jtracy:1.0.37:natives-linux`, and folding
     * them onto the plain artifact replaced the classes with shared libraries.
     */
    fun coordinateWithoutVersion(name: String): String {
        val parts = name.split(':')
        if (parts.size < 2) return name
        val base = "${parts[0]}:${parts[1]}"
        val classifier = parts.getOrNull(3)?.substringBefore('@')
        return if (classifier.isNullOrEmpty()) base else "$base:$classifier"
    }

    fun classifierOf(name: String): String =
        name.split(':').getOrNull(3)?.substringBefore('@').orEmpty()

    fun groupArtifact(name: String): String {
        val parts = name.split(':')
        return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else name
    }


    fun versionOf(name: String): String = name.split(':').getOrNull(2)?.substringBefore('@') ?: ""
}
