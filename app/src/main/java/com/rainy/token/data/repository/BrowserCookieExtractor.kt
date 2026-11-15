package com.rainy.token.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rainy.token.data.debug.FileDebugLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从本机浏览器的 Cookie 数据库直接取 Cookie —— **需要 root**。
 *
 * Android 没有跨 App 读 Cookie 的 API。已 root 时用 `su` 把库（含 -wal / -shm）
 * 拷到应用缓存，只读查询 `moz_cookies`，查完立即删除，不修改浏览器数据。
 *
 * 包名用 `pm list packages` 发现（实测 IceRaven 的包名是
 * `io.github.forkmaintainers.iceraven`，硬编码猜不到），
 * 库文件用 `find -name cookies.sqlite` 定位（不猜 profile 目录层级）。
 */
object BrowserCookieExtractor {

    private val KNOWN_PACKAGES = listOf(
        "io.github.forkmaintainers.iceraven",
        "org.iceraven.firefox",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fennec_fdroid",
        "us.spotco.fennec_dos",
        "net.mullvad.mullvadbrowser",
        "org.ironfoxoss.ironfox",
        "org.torproject.torbrowser"
    )

    private val BROWSER_HINTS = listOf(
        "iceraven", "firefox", "fennec", "mull", "ironfox", "browser"
    )

    data class Extraction(
        val cookies: List<CookieFileImport.ImportedCookie> = emptyList(),
        val browserPackage: String = "",
        val profile: String = "",
        val error: String = "",
        val detail: String = ""
    ) {
        val ok: Boolean get() = error.isEmpty() && cookies.isNotEmpty()
    }

    object Error {
        const val SU_UNAVAILABLE = "su-unavailable"
        const val NO_BROWSER = "no-browser"
        const val NO_DATABASE = "no-database"
        const val COPY_FAILED = "copy-failed"
        const val NO_COOKIE = "no-cookie"
    }

    fun extract(context: Context, domainSuffix: String): Extraction {
        if (!rootUsable(context)) {
            return Extraction(error = Error.SU_UNAVAILABLE, detail = "su id -u 未返回 0")
        }

        val packages = findBrowserPackages(context)
        if (packages.isEmpty()) {
            return Extraction(error = Error.NO_BROWSER, detail = "pm list 与已知包名都没命中")
        }

        var lastError = Error.NO_DATABASE
        var lastDetail = ""

        for (pkg in packages) {
            val databases = findCookieDatabases(context, pkg)
            if (databases.isEmpty()) {
                lastDetail = pkg + ": find 没找到 cookies.sqlite"
                continue
            }

            for (src in databases) {
                val tag = pkg + "/" + src.substringBeforeLast("/cookies.sqlite").substringAfterLast("/")
                val tmp = File(
                    context.cacheDir,
                    "cookies-" + pkg.hashCode() + "-" + src.hashCode() + ".sqlite"
                )
                tmp.delete()

                // 连同 -wal / -shm 一起拷：WAL 模式下少这两个文件会读到旧快照甚至空表
                val copy = runSu(
                    "cp -f '" + src + "' '" + tmp.absolutePath + "' 2>/dev/null; " +
                        "[ -f '" + src + "-wal' ] && cp -f '" + src + "-wal' '" +
                        tmp.absolutePath + "-wal' 2>/dev/null; " +
                        "[ -f '" + src + "-shm' ] && cp -f '" + src + "-shm" +
                        "' '" + tmp.absolutePath + "-shm' 2>/dev/null; " +
                        "chmod 644 '" + tmp.absolutePath + "'* && echo ok"
                )
                if (copy?.trim() != "ok") {
                    lastError = Error.COPY_FAILED
                    lastDetail = tag + ": 拷贝失败 (" + (copy ?: "null") + ")"
                    FileDebugLog.w(
                        context, "COOKIE_ROOT",
                        "step" to "copy", "tag" to tag, "out" to (copy ?: "null")
                    )
                    continue
                }

                val cookies = runCatching { readCookies(tmp, domainSuffix) }.getOrElse { t ->
                    FileDebugLog.e(
                        context, "COOKIE_ROOT_DB", t,
                        "tag" to tag, "err" to (t.javaClass.simpleName + ": " + t.message)
                    )
                    emptyList()
                }
                listOf(
                    tmp,
                    File(tmp.absolutePath + "-wal"),
                    File(tmp.absolutePath + "-shm")
                ).forEach { it.delete() }

                if (cookies.isNotEmpty()) {
                    FileDebugLog.i(
                        context, "COOKIE_ROOT_OK",
                        "pkg" to pkg, "profile" to tag, "count" to cookies.size
                    )
                    return Extraction(cookies, pkg, tag)
                }
                lastError = Error.NO_COOKIE
                lastDetail = tag + ": 库可读，但没有 " + domainSuffix + " 的 cookie"
            }
        }

        FileDebugLog.w(context, "COOKIE_ROOT_FAIL", "error" to lastError, "detail" to lastDetail)
        return Extraction(error = lastError, detail = lastDetail)
    }

    /** root 是否真的可用（su -c id -u 返回 0） */
    fun rootUsable(context: Context): Boolean {
        val out = runSu("id -u")?.trim()
        FileDebugLog.i(context, "COOKIE_ROOT_PROBE", "uid" to (out ?: "null"))
        return out == "0"
    }

    private fun findBrowserPackages(context: Context): List<String> {
        val hits = LinkedHashSet<String>()

        // 1) pm list packages：由 PackageManager 回答"装了谁"，不依赖 /data/data 能否 ls
        val pmOut = runSu("pm list packages 2>/dev/null").orEmpty()
        val allPackages = pmOut.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toList()
        allPackages
            .filter { pkg -> BROWSER_HINTS.any { pkg.contains(it, ignoreCase = true) } }
            .forEach { hits.add(it) }

        // 2) 已知包名兜底
        for (pkg in KNOWN_PACKAGES) {
            if (hits.contains(pkg)) continue
            val exists = runSu("test -d '/data/data/" + pkg + "' && echo yes")?.trim() == "yes"
            if (exists) hits.add(pkg)
        }

        FileDebugLog.i(
            context, "COOKIE_ROOT_PKGS",
            "pmCount" to allPackages.size,
            "found" to hits.joinToString(",")
        )
        return hits.toList()
    }

    /** 用 root 的 find 找出该包所有 cookies.sqlite（不猜目录层级） */
    private fun findCookieDatabases(context: Context, pkg: String): List<String> {
        val dirs = dataDirs(pkg).joinToString(" ")
        val out = runSu("find " + dirs + " -name cookies.sqlite -print 2>/dev/null").orEmpty()
        val files = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith("cookies.sqlite") }
            .toList()
        FileDebugLog.i(
            context, "COOKIE_ROOT_FIND",
            "pkg" to pkg, "count" to files.size,
            "first" to (files.firstOrNull() ?: "-")
        )
        return files
    }

    /** 同一包可能落在三个位置：符号链接 /data/data、/data/user/0、DE 存储 */
    private fun dataDirs(pkg: String): List<String> = listOf(
        "/data/data/" + pkg,
        "/data/user/0/" + pkg
    )

    private fun readCookies(dbFile: File, domainSuffix: String): List<CookieFileImport.ImportedCookie> {
        val cookies = mutableListOf<CookieFileImport.ImportedCookie>()
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use { database ->
            database.rawQuery(
                "SELECT host, name, value FROM moz_cookies WHERE host LIKE ?",
                arrayOf("%" + domainSuffix + "%")
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    cookies += CookieFileImport.ImportedCookie(
                        domain = cursor.getString(0).orEmpty(),
                        name = name,
                        value = cursor.getString(2).orEmpty()
                    )
                }
            }
        }
        return cookies
    }

    /** 依次尝试多种 su 形式（Magisk/KernelSU 的 su -c、SuperSU 的 su 0 sh -c、绝对路径） */
    private fun runSu(command: String): String? {
        val attempts = listOf(
            arrayOf("su", "-c", command),
            arrayOf("su", "0", "sh", "-c", command),
            arrayOf("/system/bin/su", "-c", command),
            arrayOf("/debug_ramdisk/su", "-c", command)
        )
        for (args in attempts) {
            try {
                val process = ProcessBuilder(*args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(20, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    continue
                }
                if (process.exitValue() == 0) return output
            } catch (_: Throwable) {
                // 换下一种形式
            }
        }
        return null
    }
}
