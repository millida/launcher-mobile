package net.millida.launcher.core.packs

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.profile.DISABLED_SUFFIX
import net.millida.launcher.core.profile.Profile
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Folders a build shares: the game's own state stays on the phone. */
val PACK_SHARED_DIRS = listOf("mods", "config", "resourcepacks", "shaderpacks", "datapacks", "kubejs", "scripts")

/**
 * Writes a build out as a .mrpack. Files a catalogue can serve go into
 * modrinth.index.json by address and digest; everything else — hand-added jars,
 * edited configs — travels inside overrides/, so the archive is complete even
 * when nothing is recognised.
 */
object PackExport {

    @Throws(IOException::class)
    fun exportMrpack(
        profile: Profile,
        profileDir: File,
        out: File,
        name: String = "",
        version: String = "",
        summary: String = "",
        known: Map<String, PackManifestFile> = emptyMap(),
        hosts: PackHostPolicy = PackHostPolicy(),
    ): File {
        val indexed = LinkedHashMap<String, PackManifestFile>()
        known.forEach { (path, file) ->
            val target = SafePath.join(profileDir, path)
            if (target.isFile && hosts.allows(file.url) && file.sha1.length == 40) {
                indexed[path] = file.copy(
                    path = path,
                    size = if (file.size > 0) file.size else target.length(),
                    sha512 = file.sha512.ifEmpty { Hashing.sha512(target) },
                )
            }
        }

        return writeArchive(profileDir, out, "modrinth.index.json", index(profile, indexed.values.toList(), name, version, summary), indexed.keys)
    }

    /**
     * Writes a build out as a CurseForge zip. Mods that came from a CurseForge
     * pack go into manifest.json by project and file id; the rest travels in
     * overrides/, which the CurseForge app copies over the instance.
     */
    @Throws(IOException::class)
    fun exportCurseForge(
        profile: Profile,
        profileDir: File,
        out: File,
        name: String = "",
        version: String = "",
        known: Map<String, CurseForgeRef> = emptyMap(),
    ): File {
        val listed = known.filter { (path, _) -> SafePath.join(profileDir, path).isFile }
        return writeArchive(profileDir, out, "manifest.json", curseForgeManifest(profile, listed.values.toList(), name, version), listed.keys)
    }

    /** A file of a CurseForge pack by its numeric ids. */
    data class CurseForgeRef(val projectId: Long, val fileId: Long)

    private fun writeArchive(profileDir: File, out: File, manifestName: String, manifest: String, listed: Set<String>): File {
        out.parentFile?.mkdirs()
        val temp = File(out.parentFile, out.name + ".part")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(manifestName))
                zip.write(manifest.toByteArray())
                zip.closeEntry()

                PACK_SHARED_DIRS.forEach { folder ->
                    val source = File(profileDir, folder)
                    if (!source.isDirectory) return@forEach
                    source.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = file.relativeTo(profileDir).path.replace(File.separatorChar, '/')
                        if (relative in listed) return@forEach
                        if (file.name.endsWith(DISABLED_SUFFIX) || file.name.startsWith("millida-")) return@forEach
                        zip.putNextEntry(ZipEntry("overrides/$relative"))
                        file.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } catch (failure: Exception) {
            temp.delete()
            throw IOException(tr("не удалось собрать файл сборки: {message}", failure.message), failure)
        }
        if (out.exists()) out.delete()
        if (!temp.renameTo(out)) {
            temp.copyTo(out, overwrite = true)
            temp.delete()
        }
        return out
    }

    private fun curseForgeManifest(profile: Profile, files: List<CurseForgeRef>, name: String, version: String): String {
        val loaderId = when (profile.loader) {
            LoaderKind.VANILLA -> null
            else -> profile.loader.id + profile.loaderVersion.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
        }
        val json = buildJsonObject {
            putJsonObject("minecraft") {
                put("version", profile.gameVersion)
                put(
                    "modLoaders",
                    buildJsonArray {
                        if (loaderId != null) {
                            add(
                                buildJsonObject {
                                    put("id", loaderId)
                                    put("primary", true)
                                },
                            )
                        }
                    },
                )
            }
            put("manifestType", "minecraftModpack")
            put("manifestVersion", 1)
            put("name", name.ifBlank { profile.name })
            put("version", version.ifBlank { "1.0.0" })
            put("author", "")
            put(
                "files",
                buildJsonArray {
                    files.forEach { ref ->
                        add(
                            buildJsonObject {
                                put("projectID", ref.projectId)
                                put("fileID", ref.fileId)
                                put("required", true)
                            },
                        )
                    }
                },
            )
            put("overrides", "overrides")
        }
        return MojangJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json)
    }

    private fun index(
        profile: Profile,
        files: List<PackManifestFile>,
        name: String,
        version: String,
        summary: String,
    ): String {
        val json = buildJsonObject {
            put("formatVersion", 1)
            put("game", "minecraft")
            put("versionId", version.ifBlank { "1.0.0" })
            put("name", name.ifBlank { profile.name })
            put("summary", summary)
            put(
                "files",
                buildJsonArray {
                    files.forEach { file ->
                        add(
                            buildJsonObject {
                                put("path", file.path)
                                putJsonObject("hashes") {
                                    put("sha1", file.sha1)
                                    put("sha512", file.sha512)
                                }
                                put(
                                    "downloads",
                                    buildJsonArray { add(file.url) },
                                )
                                put("fileSize", file.size)
                                putJsonObject("env") {
                                    put("client", "required")
                                    put("server", "required")
                                }
                            },
                        )
                    }
                },
            )
            putJsonObject("dependencies") {
                put("minecraft", profile.gameVersion)
                when (profile.loader) {
                    LoaderKind.FABRIC -> put("fabric-loader", profile.loaderVersion.ifEmpty { "*" })
                    LoaderKind.QUILT -> put("quilt-loader", profile.loaderVersion.ifEmpty { "*" })
                    LoaderKind.FORGE -> put("forge", profile.loaderVersion.ifEmpty { "*" })
                    LoaderKind.NEOFORGE -> put("neoforge", profile.loaderVersion.ifEmpty { "*" })
                    LoaderKind.VANILLA -> Unit
                }
            }
        }
        return MojangJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json)
    }
}
