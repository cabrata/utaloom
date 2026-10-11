package com.utaloom.platform

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** The file is chosen locally. Its contents never authorize opening any referenced path or URL. */
data class PlaylistFile(val name: String, val extension: String, val text: String)

private suspend fun chooseFile(save: Boolean, name: String, description: String, vararg extensions: String): File? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        isAcceptAllFileFilterUsed = false
        fileFilter = FileNameExtensionFilter(description, *extensions)
        if (save) selectedFile = File(name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "utaloom" } + ".${extensions.first()}")
    }
    if ((if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)) != JFileChooser.APPROVE_OPTION) null
    else {
        val selected = chooser.selectedFile
        val file = if (save && !selected.name.endsWith(".${extensions.first()}", true)) File(selected.path + ".${extensions.first()}") else selected
        if (save && file.exists() && JOptionPane.showConfirmDialog(null, "Replace ${file.name}?", "Confirm overwrite", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) null
        else file
    }
}

private suspend fun readText(file: File, maxBytes: Int): String = withContext(Dispatchers.IO) {
    require(maxBytes in 1 until Int.MAX_VALUE && file.isFile && file.length() <= maxBytes) { "File is too large or is not a regular file" }
    val bytes = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
    require(bytes.size <= maxBytes) { "File exceeds the ${maxBytes / 1024 / 1024} MiB limit" }
    currentCoroutineContext().ensureActive()
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

suspend fun readPlaylistText(maxBytes: Int): String? {
    val file = chooseFile(false, "playlist", "Utaloom playlist JSON", "json") ?: return null
    return readText(file, maxBytes)
}

suspend fun readPlaylistSource(maxBytes: Int): PlaylistFile? {
    val file = chooseFile(false, "playlist", "Spotify CSV or YouTube M3U", "csv", "m3u", "m3u8") ?: return null
    return PlaylistFile(file.nameWithoutExtension, file.extension.lowercase(), readText(file, maxBytes))
}

suspend fun readBackupText(maxBytes: Int): String? {
    val file = chooseFile(false, "utaloom-backup", "Utaloom desktop backup JSON", "json") ?: return null
    return readText(file, maxBytes)
}

/** No non-atomic fallback: a failed export leaves the previous destination intact. */
fun writeTextAtomically(file: File, text: String, maxBytes: Int) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes) { "Export exceeds the ${maxBytes / 1024 / 1024} MiB limit" }
    val target = file.toPath().toAbsolutePath()
    val tmp = Files.createTempFile(target.parent, ".utaloom-", ".json")
    try {
        runCatching { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(tmp) }
}

suspend fun writePlaylistText(name: String, text: String): Boolean {
    val file = chooseFile(true, name, "Utaloom playlist JSON", "json") ?: return false
    withContext(Dispatchers.IO) { writeTextAtomically(file, text, 10 * 1024 * 1024) }
    return true
}

suspend fun writeBackupText(text: String, maxBytes: Int): Boolean {
    val file = chooseFile(true, "utaloom-backup-${java.time.LocalDate.now()}", "Utaloom desktop backup JSON", "json") ?: return false
    withContext(Dispatchers.IO) { writeTextAtomically(file, text, maxBytes) }
    return true
}
