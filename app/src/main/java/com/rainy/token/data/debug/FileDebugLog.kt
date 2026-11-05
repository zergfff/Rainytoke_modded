package com.rainy.token.data.debug

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小组件 / 后台任务共用的文件日志（跨进程）。
 *
 * 为什么不复用 [DebugLog]：那是进程内的内存 ring buffer，而小米要求「小部件原则上
 * 只能运行在 Widget 进程」，HyperOS 会把 provider 调度到独立进程里跑，APP 进程
 * 读不到那份内存。这里统一追加到 filesDir/widget_debug.log，两个进程都能写；
 * APP 内「调试日志」页面直接读文件展示，出问题一键复制即可。
 *
 * 行格式：`MM-dd HH:mm:ss.SSS <LEVEL> <EVENT> k=v k=v ...`
 * 单文件超过 [MAX_BYTES] 轮转一次（.log → .log.1），磁盘占用上限约 2×MAX_BYTES。
 *
 * 纪律：日志本身任何异常都必须被吞掉 —— 小组件渲染失败可以忍，被日志拖崩不行。
 */
object FileDebugLog {

    private const val FILE_NAME = "widget_debug.log"
    private const val BACKUP_NAME = "widget_debug.log.1"
    private const val MAX_BYTES = 256 * 1024

    /** 调试页默认读取的行数（尾部） */
    const val DEFAULT_TAIL_LINES = 400

    private val lock = Any()

    @Volatile
    private var envLogged = false

    fun i(context: Context, event: String, vararg fields: Pair<String, Any?>) =
        write(context, "I", event, fields, null)

    fun w(context: Context, event: String, vararg fields: Pair<String, Any?>) =
        write(context, "W", event, fields, null)

    fun e(
        context: Context,
        event: String,
        error: Throwable? = null,
        vararg fields: Pair<String, Any?>
    ) = write(context, "E", event, fields, error)

    /** 每个进程只记一次：版本 / 机型 / ROM / 密度 / 字体缩放 / 进程名 */
    fun logEnvOnce(context: Context) {
        if (envLogged) return
        envLogged = true
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) {
            "?"
        }
        val dm = context.resources.displayMetrics
        i(
            context, "ENV",
            "app" to version,
            "sdk" to Build.VERSION.SDK_INT,
            "rel" to Build.VERSION.RELEASE,
            "dev" to (Build.MANUFACTURER + "/" + Build.MODEL),
            "rom" to Build.DISPLAY,
            "incr" to Build.VERSION.INCREMENTAL,
            "dens" to dm.densityDpi,
            "px" to (dm.widthPixels.toString() + "x" + dm.heightPixels),
            "font" to context.resources.configuration.fontScale,
            "proc" to processName()
        )
    }

    private fun processName(): String = try {
        Application.getProcessName() ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    /** 读取最近 [limit] 行（含轮转备份），按时间正序（旧 → 新）返回 */
    fun tail(context: Context, limit: Int = DEFAULT_TAIL_LINES): List<String> {
        val all = ArrayList<String>()
        try {
            val backup = File(context.filesDir, BACKUP_NAME)
            if (backup.exists()) all.addAll(backup.readLines(Charsets.UTF_8))
            val current = File(context.filesDir, FILE_NAME)
            if (current.exists()) all.addAll(current.readLines(Charsets.UTF_8))
        } catch (_: Throwable) {
            // 读不到就当没有
        }
        return if (all.size <= limit) all else all.subList(all.size - limit, all.size)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, FILE_NAME).delete()
                File(context.filesDir, BACKUP_NAME).delete()
            } catch (_: Throwable) {
                // 忽略
            }
        }
    }

    private fun write(
        context: Context,
        level: String,
        event: String,
        fields: Array<out Pair<String, Any?>>,
        error: Throwable?
    ) {
        val line = try {
            buildString {
                append(SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
                append(' ').append(level).append(' ').append(event)
                for ((key, value) in fields) {
                    val text = value?.toString() ?: continue
                    if (text.isEmpty()) continue
                    append(' ').append(key).append('=').append(text.replace('\n', ' '))
                }
                if (error != null) {
                    append(" err=").append(error.javaClass.simpleName)
                    append(':').append((error.message ?: "").replace('\n', ' '))
                    error.stackTrace.firstOrNull()?.let {
                        append(" at=").append(it.toString().replace('\n', ' '))
                    }
                }
            }
        } catch (_: Throwable) {
            return
        }
        append(context, line)
    }

    private fun append(context: Context, line: String) {
        synchronized(lock) {
            try {
                val dir = context.filesDir
                val current = File(dir, FILE_NAME)
                if (current.length() > MAX_BYTES) {
                    val backup = File(dir, BACKUP_NAME)
                    if (backup.exists()) backup.delete()
                    current.renameTo(backup)
                }
                File(dir, FILE_NAME).appendText(line + "\n", Charsets.UTF_8)
            } catch (_: Throwable) {
                // 日志失败绝不能影响小组件本身
            }
        }
    }
}
