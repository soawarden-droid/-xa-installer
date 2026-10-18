package com.xa.installer

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

object PackageReader {
    fun read(context: Context, uri: Uri): Pair<List<File>, String> {
        val workspace = File(context.cacheDir, "package-${System.nanoTime()}").apply { mkdirs() }
        val inputFile = File(workspace, "input")
        context.contentResolver.openInputStream(uri)?.use { input ->
            inputFile.outputStream().use { input.copyTo(it) }
        } ?: error("Could not read selected file")
        require(inputFile.length() <= 2L * 1024 * 1024 * 1024) { "Package is too large" }
        val magic = inputFile.inputStream().use { stream -> ByteArray(4).also { stream.read(it) } }
        require(magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte()) { "Unsupported file format" }
        val apk = context.packageManager.getPackageArchiveInfo(inputFile.absolutePath, 0)
        if (apk != null) {
            return listOf(inputFile) to "APK detected. Package: ${apk.packageName}\nVersion: ${apk.versionName ?: "Unknown"}\nSize: ${inputFile.length() / 1024} KB"
        }
        val components = mutableListOf<File>()
        var total = 0L
        var count = 0
        ZipInputStream(BufferedInputStream(inputFile.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= 2000) { "Too many archive entries" }
                val name = entry.name
                require(!name.startsWith("/") && name.split('/').none { it == ".." }) { "Unsafe archive path" }
                if (!entry.isDirectory && name.endsWith(".apk", ignoreCase = true)) {
                    require(components.size < 100) { "Too many APK components" }
                    val out = File(workspace, "split-${components.size}.apk")
                    out.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 2L * 1024 * 1024 * 1024) { "Archive exceeds extraction limit" }
                            output.write(buffer, 0, n)
                        }
                    }
                    require(context.packageManager.getPackageArchiveInfo(out.absolutePath, 0) != null) { "Invalid APK component: $name" }
                    components += out
                }
                zip.closeEntry()
            }
        }
        require(components.isNotEmpty()) { "No installable APK components found in archive" }
        val names = components.mapNotNull { context.packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.packageName }.distinct()
        require(names.size == 1) { "Archive contains components from different apps" }
        return components to "Archive detected. ${components.size} APK component(s).\nPackage: ${names.single()}\nCheck that all required splits are included."
    }
}