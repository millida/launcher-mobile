package net.millida.launcher

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.profile.ProfileStore
import java.io.File
import java.io.FileNotFoundException

/**
 * The builds' folders as a root of the system «Files» app and of every file
 * picker. Since Android 11 nobody can open Android/data, so this is how a mod
 * author drops a jar into a build. Document ids are paths relative to the
 * builds folder; every one goes through SafePath before it touches the disk.
 */
class GameFilesProvider : DocumentsProvider() {

    private val builds: File
        get() = GameLayout(File(requireNotNull(context).filesDir, GAME_DIR)).profiles

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        builds.mkdirs()
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
            add(Root.COLUMN_TITLE, "Millida")
            add(Root.COLUMN_SUMMARY, tr("Папки сборок"))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_AVAILABLE_BYTES, builds.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        addRow(cursor, documentId, fileOf(documentId))
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val parent = fileOf(parentDocumentId)
        parent.listFiles().orEmpty()
            .filterNot { it.name.endsWith(".part") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { child -> addRow(cursor, childId(parentDocumentId, child.name), child) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        if (file.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val name = freeName(parent, SafePath.fileName(displayName))
        val target = SafePath.join(parent, name)
        val made = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!made) throw FileNotFoundException(name)
        return childId(parentDocumentId, name)
    }

    override fun deleteDocument(documentId: String) {
        // A whole build goes only through deleting the build in the launcher, which also drops it from the list.
        if (documentId == ROOT_DOCUMENT || !documentId.removePrefix("$ROOT_DOCUMENT/").contains('/')) {
            throw UnsupportedOperationException(documentId)
        }
        val file = fileOf(documentId)
        val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!gone) throw FileNotFoundException(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (documentId == ROOT_DOCUMENT || !documentId.removePrefix("$ROOT_DOCUMENT/").contains('/')) {
            throw UnsupportedOperationException(documentId)
        }
        val file = fileOf(documentId)
        val parentId = documentId.substringBeforeLast('/')
        val name = SafePath.fileName(displayName)
        val target = SafePath.join(file.parentFile ?: throw FileNotFoundException(documentId), name)
        if (target.exists() || !file.renameTo(target)) throw FileNotFoundException(name)
        return childId(parentId, name)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

    private fun fileOf(documentId: String): File {
        if (documentId == ROOT_DOCUMENT) return builds
        if (!documentId.startsWith("$ROOT_DOCUMENT/")) throw FileNotFoundException(documentId)
        val relative = documentId.removePrefix("$ROOT_DOCUMENT/")
        val file = runCatching { SafePath.join(builds, relative) }.getOrNull() ?: throw FileNotFoundException(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        val writable = documentId != ROOT_DOCUMENT
        var flags = 0
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (writable) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        val removable = writable && documentId.removePrefix("$ROOT_DOCUMENT/").contains('/')
        if (removable) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, displayName(documentId, file))
            add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    /** A build's folder is named by its id; the player knows it by the build's name. */
    private fun displayName(documentId: String, file: File): String {
        if (documentId == ROOT_DOCUMENT) return "Millida"
        val relative = documentId.removePrefix("$ROOT_DOCUMENT/")
        if (relative.contains('/')) return file.name
        val name = runCatching {
            ProfileStore(GameLayout(builds.parentFile ?: return file.name)).load().firstOrNull { it.id == file.name }?.name
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: file.name
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        return when (extension) {
            "jar" -> "application/java-archive"
            "txt", "log", "cfg", "properties", "toml", "json5", "snbt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }

    private fun freeName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 2
        while (File(dir, "$stem ($index)$extension").exists()) index++
        return "$stem ($index)$extension"
    }

    private fun childId(parent: String, name: String): String = "$parent/$name"

    companion object {
        const val ROOT_ID = "millida-builds"
        const val ROOT_DOCUMENT = "builds"
        private const val GAME_DIR = "game"

        fun authority(packageName: String): String = "$packageName.documents"

        fun buildDocumentId(profileId: String): String = "$ROOT_DOCUMENT/$profileId"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** The build's folder as a document of this provider, for the system «Files» app. */
        fun folderUri(packageName: String, profileId: String) =
            DocumentsContract.buildDocumentUri(authority(packageName), buildDocumentId(profileId))
    }
}
