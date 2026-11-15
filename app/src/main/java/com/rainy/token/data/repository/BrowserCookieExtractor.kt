package com.rainy.token.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rainy.token.data.debug.FileDebugLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从本机浏览器的 Cookie 数据库直接取 Cookie —— **需要 root**。
 *
 * Android 没有跨 App 读 Cookie 的 API：Firefox / IceRaven 的 Cookie 在
 * `/data/data/<pkg>/files/mozilla/<profile>/cookies.sqlite`，普通权限读不到。
 * 已 root 时用 `su` 把库（含 -wal / -shm）拷到应用缓存，只读查询 `moz_cookies`，
 * 查完立即删除，不修改浏览器数据。
 *
 * 失败原因细分成「su 不可用 / 没装浏览器 / 没找到库 / 库里没有目标 cookie」，
 * 每一步的 su 输出都写进 FileDebugLog，方便直接定位。
 */
object BrowserCookieExtractor {

    /** 已知 Firefox 系（含 fork）包名；命中即用，避免遍历整个 /data/data */
    private val KNOWN_PACKAGES = listOf(
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
        "iceraven", "firefox", "fennec", "mull", "ironfox", "torbrowser"
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
            return Extraction(error = Error.NO_BROWSER, detail = "候选包与 /data/data 都没命中")
        }

        var lastError = Error.NO_DATABASE
        var lastDetail = ""
        for (pkg in packages) {
            val mozillaDir = "/data/data/" + pkg + "/files/mozilla"
            val profiles = runSu("ls -1 '" + mozillaDir + "' 2>/dev/null")
                ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
                .orEmpty()
            if (profiles.isEmpty()) {
                lastDetail = pkg + ": 没有 " + mozillaDir
                continue
            }

            for (profile in profiles) {
                val src = mozillaDir + "/" + profile + "/cookies.sqlite"
                if (runSu("test -f '" + src + "' && echo yes")?.trim() != "yes") continue

                val tag = pkg + "/" + profile
                val tmp = File(
                    context.cacheDir,
                    "cookies-" + pkg.hashCode() + "-" + profile.hashCode() + ".sqlite"
                )
                tmp.delete()

                // 连同 -wal / -shm 一起拷：浏览器可能还没 checkpoint，少了 WAL 会缺最新记录
                val copy = runSu(
                    "cp -f '" + src + "' '" + tmp.absolutePath + "' 2>/dev/null; " +
                        "[ -f '" + src + "-wal' ] && cp -f '" + src + "-wal' '" + tmp.absolutePath + "-wal' 2>/dev/null; " +
                        "[ -f '" + src + "-shm' ] && cp -f '" + src + "-shm' '" + tmp.absolutePath + "-shm' 2>/dev/null; " +
                        "chmod 644 '" + tmp.absolutePath + "'* && echo ok"
                )?.trim()
                if (copy != "ok") {
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
                        "err" to (t.javaClass.simpleName + ": " + t.message)
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
                        "pkg" to pkg, "profile" to profile, "count" to cookies.size
                    )
                    return Extraction(cookies, pkg, profile)
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
        for (pkg in KNOWN_PACKAGES) {
            if (runSu("test -d '/data/data/" + pkg + "' && echo yes")?.trim() == "yes") hits.add(pkg)
        }
        if (hits.isEmpty()) {
            val listing = runSu("ls /data/data 2>/dev/null").orEmpty()
            listing.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { pkg -> BROWSER_HINTS.any { pkg.contains(it, ignoreCase = true) } }
                .forEach { hits.add(it) }
        }
        FileDebugLog.i(context, "COOKIE_ROOT_PKGS", "found" to hits.joinToString(","))
        return hits.toList()
    }

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
                val finished = process.waitFor(15, TimeUnit.SECONDS)
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
