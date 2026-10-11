package net.millida.launcher.core.maintenance

import net.millida.launcher.core.Hashing
import java.io.File

data class DuplicateGroup(
    val hash: String,
    val size: Long,
    val files: List<File>,
) {
    /** What is actually freed: every copy after the first one. */
    val reclaimable: Long get() = size * (files.size - 1).coerceAtLeast(0)
}

data class DedupeReport(
    val groups: List<DuplicateGroup>,
    val scannedFiles: Int,
    val reclaimable: Long,
)

data class DedupeResult(
    val linked: Int,
    val freed: Long,
    val failed: Int,
)

/**
 * The same mod sits in five builds as five copies, and on a phone that is real
 * money in storage. Identical files are collapsed into one through hard links:
 * the game still sees a file in every build, and a build that replaces its copy
 * gets its own again.
 */
class Dedupe(private val roots: List<File>) {

    fun scan(minSize: Long = MIN_SIZE): DedupeReport {
        val bySize = HashMap<Long, MutableList<File>>()
        var scanned = 0
        roots.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.length() >= minSize && it.extension in KINDS }
                .forEach { file ->
                    scanned += 1
                    bySize.getOrPut(file.length()) { ArrayList() }.add(file)
                }
        }

        val groups = ArrayList<DuplicateGroup>()
        // Hashing is the expensive half, so only files that already share a
        // size are ever read.
        bySize.values.filter { it.size > 1 }.forEach { candidates ->
            val byHash = HashMap<String, MutableList<File>>()
            candidates.forEach { file ->
                val hash = runCatching { Hashing.sha1(file) }.getOrNull() ?: return@forEach
                byHash.getOrPut(hash) { ArrayList() }.add(file)
            }
            byHash.forEach { (hash, files) ->
                if (files.size > 1) {
                    groups += DuplicateGroup(hash, files.first().length(), files.sortedBy { it.path })
                }
            }
        }

        val sorted = groups.sortedByDescending { it.reclaimable }
        return DedupeReport(sorted, scanned, sorted.sumOf { it.reclaimable })
    }

    /**
     * The original is never removed before the link stands: a failed link
     * leaves the build exactly as it was, with its own copy in place.
     */
    fun run(report: DedupeReport): DedupeResult {
        var linked = 0
        var freed = 0L
        var failed = 0
        report.groups.forEach { group ->
            val keep = group.files.first()
            if (!keep.isFile) return@forEach
            group.files.drop(1).forEach { copy ->
                if (!copy.isFile) return@forEach
                val backup = File(copy.parentFile, copy.name + ".dedupe")
                val moved = runCatching { copy.renameTo(backup) }.getOrDefault(false)
                if (!moved) {
                    failed += 1
                    return@forEach
                }
                val ok = runCatching {
                    java.nio.file.Files.createLink(copy.toPath(), keep.toPath())
                    true
                }.getOrDefault(false)
                if (ok) {
                    backup.delete()
                    linked += 1
                    freed += group.size
                } else {
                    backup.renameTo(copy)
                    failed += 1
                }
            }
        }
        return DedupeResult(linked, freed, failed)
    }

    /** Leftovers of an interrupted run: the copy is put back where it was. */
    fun collectGarbage(): Int {
        var restored = 0
        roots.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".dedupe") }.forEach { backup ->
                val original = File(backup.parentFile, backup.name.removeSuffix(".dedupe"))
                if (!original.exists() && backup.renameTo(original)) restored += 1 else backup.delete()
            }
        }
        return restored
    }

    private companion object {
        /** Below this the link costs as much as the file itself. */
        const val MIN_SIZE = 64L * 1024
        val KINDS = setOf("jar", "zip", "png", "ogg", "json")
    }
}
