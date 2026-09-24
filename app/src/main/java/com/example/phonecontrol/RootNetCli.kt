package com.example.phonecontrol

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * CLI Entrypoint executed strictly via Root Shell (`app_process`).
 * Runs as Linux UID 0 (root) with full network socket permissions,
 * completely independent of Android Manifest application permissions.
 */
object RootNetCli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("ERR: Missing arguments")
            return
        }

        val mode = args[0]
        val initialUrl = args.getOrNull(1) ?: return

        try {
            when (mode) {
                "get" -> {
                    val body = fetchWithRedirects(initialUrl)
                    print(body)
                }
                "download" -> {
                    val destPath = args.getOrNull(2)
                    if (destPath.isNullOrBlank()) {
                        System.err.println("ERR: Missing destination path")
                        return
                    }
                    val destFile = File(destPath)
                    destFile.parentFile?.mkdirs()

                    downloadWithRedirects(initialUrl, destFile)
                    println("OK_DOWNLOAD")
                }
            }
        } catch (e: Exception) {
            System.err.println("ERR: ${e.message}")
        }
    }

    private fun openConnection(urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = false // Handle redirects manually across domains & protocols
        conn.setRequestProperty("User-Agent", "PhoneControl-Android-Root")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json, application/octet-stream, */*")
        return conn
    }

    private fun fetchWithRedirects(urlStr: String, maxRedirects: Int = 6): String {
        var currentUrl = urlStr
        for (i in 0 until maxRedirects) {
            val conn = openConnection(currentUrl)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                if (!loc.isNullOrBlank()) {
                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                    conn.disconnect()
                    continue
                }
            }
            if (code !in 200..299) {
                val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
                throw RuntimeException("HTTP $code: ${errBody ?: ""}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
        throw RuntimeException("Too many redirects")
    }

    private fun downloadWithRedirects(urlStr: String, destFile: File, maxRedirects: Int = 6) {
        var currentUrl = urlStr
        for (i in 0 until maxRedirects) {
            val conn = openConnection(currentUrl)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                if (!loc.isNullOrBlank()) {
                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                    conn.disconnect()
                    continue
                }
            }
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code during download")
            }
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        throw RuntimeException("Too many redirects")
    }
}
