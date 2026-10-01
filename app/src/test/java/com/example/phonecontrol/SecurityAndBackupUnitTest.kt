package com.example.phonecontrol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SecurityAndBackupUnitTest {

    @Test
    fun testShellQuote_basicAndSpecialCharacters() {
        assertEquals("''", ShellUtils.shellQuote(""))
        assertEquals("'hello'", ShellUtils.shellQuote("hello"))
        assertEquals("'foo'\\''bar'", ShellUtils.shellQuote("foo'bar"))
        assertEquals("'; rm -rf /; echo '", ShellUtils.shellQuote("; rm -rf /; echo "))
        assertEquals("'/path/with space/file.apk'", ShellUtils.shellQuote("/path/with space/file.apk"))
        assertEquals("'\"double\" and '\\''single'\\'''", ShellUtils.shellQuote("\"double\" and 'single'"))
    }

    @Test
    fun testPackageNameValidation_validAndInvalid() {
        // Valid package names
        assertTrue(ShellUtils.isValidPackageName("com.example.phonecontrol"))
        assertTrue(ShellUtils.isValidPackageName("com.android.settings"))
        assertTrue(ShellUtils.isValidPackageName("org.lsposed.manager"))
        assertTrue(ShellUtils.isValidPackageName("a.b"))
        assertTrue(ShellUtils.isValidPackageName("com.foo_bar.baz_123"))

        // Invalid package names
        assertFalse(ShellUtils.isValidPackageName(null))
        assertFalse(ShellUtils.isValidPackageName(""))
        assertFalse(ShellUtils.isValidPackageName("singleword"))
        assertFalse(ShellUtils.isValidPackageName(".leading.dot"))
        assertFalse(ShellUtils.isValidPackageName("trailing.dot."))
        assertFalse(ShellUtils.isValidPackageName("com..consecutive.dots"))
        assertFalse(ShellUtils.isValidPackageName("com.123numeric.segment"))
        assertFalse(ShellUtils.isValidPackageName("com.example;rm -rf"))
        assertFalse(ShellUtils.isValidPackageName("com/example/path"))
        assertFalse(ShellUtils.isValidPackageName("com.example\$inner"))
        assertFalse(ShellUtils.isValidPackageName("com.example phone"))
    }

    @Test
    fun testHostnameValidation_validAndInvalid() {
        // Valid hostnames
        assertTrue(ShellUtils.isValidHostname("dns.google"))
        assertTrue(ShellUtils.isValidHostname("one.one.one.one"))
        assertTrue(ShellUtils.isValidHostname("github.com"))
        assertTrue(ShellUtils.isValidHostname("raw.githubusercontent.com"))
        assertTrue(ShellUtils.isValidHostname("api.github.com"))
        assertTrue(ShellUtils.isValidHostname("dot.adguard.com"))

        // Invalid hostnames
        assertFalse(ShellUtils.isValidHostname(null))
        assertFalse(ShellUtils.isValidHostname(""))
        assertFalse(ShellUtils.isValidHostname("   "))
        assertFalse(ShellUtils.isValidHostname("https://dns.google"))
        assertFalse(ShellUtils.isValidHostname("dns.google/path"))
        assertFalse(ShellUtils.isValidHostname("-leading-hyphen.com"))
        assertFalse(ShellUtils.isValidHostname("trailing-hyphen-.com"))
        assertFalse(ShellUtils.isValidHostname("has_underscore.com"))
        assertFalse(ShellUtils.isValidHostname("host name with spaces.com"))
        assertFalse(ShellUtils.isValidHostname("dns;google.com"))
        assertFalse(ShellUtils.isValidHostname("a".repeat(64) + ".com"))
        assertFalse(ShellUtils.isValidHostname("a".repeat(254)))
    }

    @Test
    fun testPathContainment_validAndTraversal() {
        val baseDir = "/sdcard/PHONE_CONTROL/Config_Backups"
        assertTrue(ShellUtils.isPathContained("$baseDir/backup_123.json", baseDir))
        assertTrue(ShellUtils.validatePathInBaseDir("$baseDir/backup_123.json", baseDir))

        assertFalse(ShellUtils.isPathContained("$baseDir/../../data/local/tmp", baseDir))
        assertFalse(ShellUtils.validatePathInBaseDir("$baseDir/../../etc/passwd", baseDir))
        assertFalse(ShellUtils.isPathContained(null, baseDir))
        assertFalse(ShellUtils.isPathContained("$baseDir/backup.json", null))
    }

    @Test
    fun testBackupSerialization_roundTripStructure() {
        // Build sample typed backup JSON document
        val originalDoc = JSONObject()
        originalDoc.put("format_version", BackupManager.FORMAT_VERSION)
        originalDoc.put("created_at", 1700000000000L)

        val namespaces = JSONObject()
        val prefsNs = JSONObject()

        val intEntry = JSONObject().apply {
            put("type", "int")
            put("value", 42)
        }
        val longEntry = JSONObject().apply {
            put("type", "long")
            put("value", 9876543210123L)
        }
        val floatEntry = JSONObject().apply {
            put("type", "float")
            put("value", 30.5)
        }
        val boolEntry = JSONObject().apply {
            put("type", "boolean")
            put("value", true)
        }
        val stringEntry = JSONObject().apply {
            put("type", "string")
            put("value", "rbBalance")
        }
        val setEntry = JSONObject().apply {
            put("type", "string_set")
            put("value", JSONArray().apply {
                put("com.example.app1")
                put("com.example.app2")
            })
        }

        prefsNs.put("cpu_cap", intEntry)
        prefsNs.put("timestamp", longEntry)
        prefsNs.put("refresh_rate", floatEntry)
        prefsNs.put("tcp_bbr", boolEntry)
        prefsNs.put("active_mode", stringEntry)
        prefsNs.put("frozen_pkgs", setEntry)

        namespaces.put("prefs", prefsNs)
        originalDoc.put("namespaces", namespaces)

        val jsonString = originalDoc.toString(4)
        assertNotNull(jsonString)
        assertTrue(jsonString.isNotBlank())

        // Validate deserialization and type preservation from round trip
        val parsedDoc = JSONObject(jsonString)
        assertEquals(BackupManager.FORMAT_VERSION, parsedDoc.getInt("format_version"))

        val parsedNamespaces = parsedDoc.getJSONObject("namespaces")
        assertTrue(parsedNamespaces.has("prefs"))

        val parsedPrefs = parsedNamespaces.getJSONObject("prefs")

        // 1. Int
        val parsedInt = parsedPrefs.getJSONObject("cpu_cap")
        assertEquals("int", parsedInt.getString("type"))
        assertEquals(42, parsedInt.getInt("value"))

        // 2. Long
        val parsedLong = parsedPrefs.getJSONObject("timestamp")
        assertEquals("long", parsedLong.getString("type"))
        assertEquals(9876543210123L, parsedLong.getLong("value"))

        // 3. Float
        val parsedFloat = parsedPrefs.getJSONObject("refresh_rate")
        assertEquals("float", parsedFloat.getString("type"))
        assertEquals(30.5f, parsedFloat.getDouble("value").toFloat(), 0.001f)

        // 4. Boolean
        val parsedBool = parsedPrefs.getJSONObject("tcp_bbr")
        assertEquals("boolean", parsedBool.getString("type"))
        assertTrue(parsedBool.getBoolean("value"))

        // 5. String
        val parsedString = parsedPrefs.getJSONObject("active_mode")
        assertEquals("string", parsedString.getString("type"))
        assertEquals("rbBalance", parsedString.getString("value"))

        // 6. StringSet
        val parsedSetObj = parsedPrefs.getJSONObject("frozen_pkgs")
        assertEquals("string_set", parsedSetObj.getString("type"))
        val array = parsedSetObj.getJSONArray("value")
        val restoredSet = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            restoredSet.add(array.getString(i))
        }
        assertEquals(setOf("com.example.app1", "com.example.app2"), restoredSet)
    }
}
