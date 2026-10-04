package app.lawnchair.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A destination becomes a backup only after closing and verifying every exported byte. */
internal object VerifiedBackupWriter {
    fun <T> publish(source: File, openOutput: () -> OutputStream, openInput: () -> InputStream, publish: () -> T): T {
        openOutput().use { output -> source.inputStream().use { it.copyTo(output) } }
        val remoteHash = openInput().use(::digest)
        val localHash = source.inputStream().use(::digest)
        if (!MessageDigest.isEqual(localHash, remoteHash)) throw IOException("Backup verification failed")
        return publish()
    }

    private fun digest(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }
}
