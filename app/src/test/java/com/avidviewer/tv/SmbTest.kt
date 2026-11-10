package com.avidviewer.tv

import com.avidviewer.tv.data.Smb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Saves, lists and reads a backup on a real Samba server (the build starts one). Skipped when
 * no test server is configured.
 */
class SmbTest {
    private val address = System.getenv("SMB_TEST_ADDRESS")

    @Test
    fun parsesShareAddresses() {
        val a = Smb.target("\\\\NAS\\Backups\\Avid\\TV", "HOME\\marty", "pw")
        assertEquals("NAS", a.host); assertEquals(445, a.port); assertEquals("Backups", a.share); assertEquals("Avid\\TV", a.folder)
        assertEquals("HOME", a.domain); assertEquals("marty", a.user)
        val b = Smb.target("smb://192.168.1.20:1445/backups", "", "")
        assertEquals("192.168.1.20", b.host); assertEquals(1445, b.port); assertEquals("backups", b.share); assertEquals("", b.folder)
    }

    @Test
    fun backupRoundTripOnARealShare() {
        assumeTrue("no test Samba server", !address.isNullOrBlank())
        val t = Smb.target(address!!, "test", "test")
        Smb.test(t)
        val name = "AvidViewer-backup-test-${System.currentTimeMillis()}.avbak"
        val text = "{\"avidViewerBackup\":1,\"hello\":\"share\"}"
        Smb.write(t, name, text)
        val list = Smb.list(t)
        assertTrue("backup not listed: $list", list.any { it.first == name })
        assertEquals(text, Smb.read(t, name))
    }

    @Test
    fun wrongPasswordGetsAPlainExplanation() {
        assumeTrue("no test Samba server", !address.isNullOrBlank())
        val e = runCatching { Smb.test(Smb.target(address!!, "test", "wrong")) }.exceptionOrNull()
        assertTrue("wrong password was accepted", e != null)
        assertTrue(Smb.explain(e!!), Smb.explain(e).contains("refused") || Smb.explain(e).contains("Couldn't"))
    }
}
