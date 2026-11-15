package com.rainy.token.data.repository

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从本机浏览器的 Cookie 数据库直接取 Cookie —— **需要 root**。
 *
 * Android 没有跨 App 读 Cookie 的 API：Firefox / IceRaven 的 Cookie 存在
 * `/data/data/<pkg>/files/mozilla/<profile>/cookies.sqlite`，普通权限读不到。
 * 设备已 root 时，可以通过 `su` 把这个库拷到应用缓存目录再查 `moz_cookies` 表。
 *
 * 兼容：Magisk / KernelSU 的 `su -c`，以及老式 SuperSU 的 `su 0 sh -c`。
 * 只读、只拷一份临时文件、查完立刻删除；不修改浏览器数据。
 */
object BrowserCookieExtractor {

    data class Result(
        val cookies: List<CookieFileImport.ImportedCookie>,
        val browserPackage: String,
        val profile: String
    )

    /** 已知 Firefox 系（含 fork）包名特征 */
    private val BROWSER_HINTS = listOf("iceraven", "firefox", "fennec", "mull", "ironfox", "torbrowser")

    /**
     * @param domainSuffix 只取 host 含该串的 cookie，如 "open-design.ai"
     * @param cacheDir 应用缓存目录（用来放临时拷贝）
     * @return 找到的第一个有目标 cookie 的浏览器/配置；没有 root 或找不到返回 null
     */
    fun extract(domainSuffix: String, cacheDir: File): Result? {
        val packages = runSu("ls /data/data") ?: return null
        val candidates = packages.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { pkg -> BROWSER_HINTS.any { hint -> pkg.contains(hint, ignoreCase = true) } }
            .toList()

        for (pkg in candidates) {
            val mozillaDir = "/data/data/$pkg/files/mozilla"
            val profiles = runSu("ls -1 '$mozillaDir' 2>/dev/null")?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()

            for (profile in profiles) {
                val src = "$mozillaDir/$profile/cookies.sqlite"
                if (runSu("test -f '$src' && echo yes")?.trim() != "yes") continue

                val tmp = File(cacheDir, "cookies-${pkg.hashCode()}-${profile.hashCode()}.sqlite")
                tmp.delete()
                val copied = runSu(
                    "cp -f '$src' '${tmp.absolutePath}' && chmod 644 '${tmp.absolutePath}' && echo ok"
                )?.trim() == "ok"
                if (!copied) continue

                val cookies = runCatching { readCookies(tmp, domainSuffix) }.getOrDefault(emptyList())
                tmp.delete()
                if (cookies.isNotEmpty()) return Result(cookies, pkg, profile)
            }
        }
        return null
    }

    /** root 是否可用（用于 UI 给提示，不做实际读取） */
    fun hasRoot(): Boolean = runSu("id -u")?.trim() == "0"

    private fun readCookies(dbFile: File, domainSuffix: String): List<CookieFileImport.ImportedCookie> {
        val cookies = mutableListOf<CookieFileImport.ImportedCookie>()
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        db.use { database ->
            database.rawQuery(
                "SELECT host, name, value FROM moz_cookies WHERE host LIKE ?",
                arrayOf("%$domainSuffix%")
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

    /** 依次尝试 Magisk/KernelSU 与 SuperSU 两种 su 调用形式 */
    private fun runSu(command: String): String? {
        val attempts = listOf(
            arrayOf("su", "-c", command),
            arrayOf("su", "0", "sh", "-c", command)
        )
        for (args in attempts) {
            try {
                val process = ProcessBuilder(*args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return output
                }
                process.destroy()
            } catch (_: Throwable) {
                // 换下一种形式
            }
        }
        return null
    }
}
