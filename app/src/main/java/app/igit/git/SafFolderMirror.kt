package app.igit.git

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.igit.data.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Mirrors repository files to one user-granted SAF directory; the .git database stays private. */
class SafFolderMirror(private val context: Context) {
    suspend fun importChanges(worktree: File, treeUri: Uri, baselineFile: File): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("The selected folder is no longer available. Re-select it.")
        val baseline = readBaseline(baselineFile)
        val current = linkedMapOf<String, DocumentFile>()
        collectDocuments(root, "", current, 0)
        var imported = 0

        current.forEach { (relative, document) ->
            val previousHash = baseline[relative]
            val currentHash = hash(document)
            if (previousHash == null) {
                val existing = safeFile(worktree, relative)
                if (existing.isFile && sha256(existing) != currentHash) {
                    error("Folder conflict at $relative. igit stopped before replacing either copy.")
                }
            }
            if (previousHash == null || previousHash != currentHash) {
                val destination = safeFile(worktree, relative)
                destination.parentFile?.mkdirs()
                context.contentResolver.openInputStream(document.uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Cannot read $relative from the selected folder")
                imported++
            }
        }

        baseline.keys.filter { it !in current }.forEach { relative ->
            safeFile(worktree, relative).let { file ->
                if (file.isFile) file.delete()
            }
        }
        imported
    }

    suspend fun export(worktree: File, treeUri: Uri, baselineFile: File): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("The selected folder is no longer available. Re-select it.")
        val previous = readBaseline(baselineFile)
        val sourceFiles = collectWorktreeFiles(worktree)

        previous.keys.filter { it !in sourceFiles }.forEach { relative ->
            findDocument(root, relative)?.delete()
        }

        var written = 0
        sourceFiles.forEach { (relative, source) ->
            var parent = root
            val parts = relative.split('/')
            parts.dropLast(1).forEach { part ->
                parent = parent.findFile(part)?.takeIf { it.isDirectory } ?: parent.createDirectory(part)
                    ?: error("Could not create folder $part")
            }
            val name = parts.last()
            val target = parent.findFile(name)?.takeIf { it.isFile }
                ?: parent.createFile("application/octet-stream", name)
                ?: error("Could not create file $name")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not write $relative")
            written++
        }

        val newBaseline = JSONObject()
        sourceFiles.forEach { (relative, source) -> newBaseline.put(relative, sha256(source)) }
        baselineFile.parentFile?.mkdirs()
        baselineFile.writeText(newBaseline.toString())
        written
    }

    private fun readBaseline(file: File): Map<String, String> = runCatching {
        val json = JSONObject(file.takeIf { it.isFile }?.readText().orEmpty())
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (Validation.isSafeRelativePath(key)) put(key, json.optString(key))
            }
        }
    }.getOrDefault(emptyMap())

    private fun collectDocuments(folder: DocumentFile, prefix: String, output: MutableMap<String, DocumentFile>, depth: Int) {
        require(depth <= MAX_DEPTH) { "The selected folder is too deeply nested." }
        folder.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (name == ".git" || name == ".igit-mirror-baseline.json") return@forEach
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            if (!Validation.isSafeRelativePath(relative)) return@forEach
            if (child.isDirectory) collectDocuments(child, relative, output, depth + 1)
            else if (child.isFile && output.size < MAX_FILES) output[relative] = child
        }
    }

    private fun collectWorktreeFiles(root: File): Map<String, File> = buildMap {
        root.walkTopDown().forEach { file ->
            if (file.isDirectory && file != root && file.name == ".git") return@forEach
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (relative.startsWith(".git/") || relative == ".igit-mirror-baseline.json") return@forEach
            if (Validation.isSafeRelativePath(relative) && size < MAX_FILES) put(relative, file)
        }
    }

    private fun findDocument(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        val parts = relative.split('/')
        parts.dropLast(1).forEach { part -> current = current.findFile(part)?.takeIf { it.isDirectory } ?: return null }
        return current.findFile(parts.last())?.takeIf { it.isFile }
    }

    private fun safeFile(root: File, relative: String): File {
        require(Validation.isSafeRelativePath(relative)) { "Unsafe relative path." }
        val result = File(root, relative).canonicalFile
        require(result.toPath().startsWith(root.canonicalFile.toPath())) { "Path escapes the repository." }
        return result
    }

    private fun hash(document: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(document.uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: error("Cannot read ${document.name}")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_FILES = 20_000
    }
}
