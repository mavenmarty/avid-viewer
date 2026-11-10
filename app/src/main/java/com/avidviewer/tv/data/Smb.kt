package com.avidviewer.tv.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.TimeUnit

/**
 * Backups on a SAMBA / Windows network share (SMB 2 and 3), e.g. a folder on a PC or NAS.
 * The address can be typed as \\server\share\folder, //server/share/folder or smb://server/share/folder.
 */
object Smb {
    data class Target(val host: String, val port: Int, val share: String, val folder: String, val user: String, val password: String, val domain: String) {
        val display get() = "\\\\$host\\$share" + (if (folder.isNotBlank()) "\\" + folder.replace('/', '\\') else "")
    }

    class BadAddress : Exception("Enter the share as \\\\server\\share (a folder after it is optional).")

    /** Parses an address plus login into a target. "DOMAIN\user" is understood too. */
    fun target(address: String, user: String, password: String): Target {
        val a = address.trim().removePrefix("smb:").replace('\\', '/').trimStart('/').trimEnd('/')
        val parts = a.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) throw BadAddress()
        val hostPort = parts[0]
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "").toIntOrNull() ?: 445
        val (domain, name) = if (user.contains('\\')) user.substringBefore('\\') to user.substringAfter('\\') else "" to user
        return Target(host, port, parts[1], parts.drop(2).joinToString("\\"), name.trim(), password, domain.trim())
    }

    private fun <T> withShare(t: Target, block: (DiskShare) -> T): T {
        val config = SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(20, TimeUnit.SECONDS).build()
        SMBClient(config).use { client ->
            client.connect(t.host, t.port).use { conn ->
                val auth = if (t.user.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(t.user, t.password.toCharArray(), t.domain.ifBlank { null })
                val session = conn.authenticate(auth)
                try {
                    (session.connectShare(t.share) as DiskShare).use { return block(it) }
                } finally {
                    runCatching { session.close() }
                }
            }
        }
    }

    private fun path(t: Target, name: String) = if (t.folder.isBlank()) name else t.folder + "\\" + name

    /** Connects and lists backups; makes the folder if it's missing. Returns how many backups are there. */
    fun test(t: Target): Int = withShare(t) { s -> ensureFolder(s, t.folder); listIn(s, t).size }

    fun write(t: Target, name: String, text: String) = withShare(t) { s ->
        ensureFolder(s, t.folder)
        s.openFile(
            path(t, name), setOf(AccessMask.GENERIC_WRITE), null, setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        ).use { f -> f.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
    }

    /** Backups in the folder, newest first: (file name, modified time in ms). */
    fun list(t: Target): List<Pair<String, Long>> = withShare(t) { s -> listIn(s, t) }

    private fun listIn(s: DiskShare, t: Target): List<Pair<String, Long>> {
        if (t.folder.isNotBlank() && !s.folderExists(t.folder)) return emptyList()
        return s.list(t.folder, "*." + Backup.EXTENSION)
            .filter { it.fileName.endsWith("." + Backup.EXTENSION) }
            .map { it.fileName to it.lastWriteTime.toEpochMillis() }
            .sortedByDescending { it.second }
    }

    fun read(t: Target, name: String): String = withShare(t) { s ->
        s.openFile(
            path(t, name), setOf(AccessMask.GENERIC_READ), null, setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN, null,
        ).use { f -> f.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
    }

    private fun ensureFolder(s: DiskShare, folder: String) {
        if (folder.isBlank()) return
        var p = ""
        folder.split('\\').filter { it.isNotBlank() }.forEach { seg ->
            p = if (p.isEmpty()) seg else "$p\\$seg"
            if (!s.folderExists(p)) s.mkdir(p)
        }
    }

    /** A plain-English reason for a failed connection. */
    fun explain(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            e is BadAddress -> e.message!!
            m.contains("STATUS_LOGON_FAILURE", true) -> "The share refused the user name or password."
            m.contains("STATUS_BAD_NETWORK_NAME", true) -> "The server is there, but it has no share by that name."
            m.contains("STATUS_ACCESS_DENIED", true) -> "This user isn't allowed to write to that share or folder."
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) -> "Couldn't find that server. Try its IP address, e.g. \\\\192.168.1.20\\Backups."
            m.contains("timed out", true) || m.contains("ConnectException", true) || m.contains("Connection refused", true) ->
                "Couldn't reach the server. Check it's on, on the same network, and that file sharing is turned on."
            else -> "Couldn't use the share: $m"
        }
    }
}
