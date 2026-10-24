package app.courseplanner

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** خطای قابل نمایش به کاربر؛ status مثل کد HTTP است (400، 404، 409 ...). */
class ApiError(val status: Int, message: String) : Exception(message)

object Util {
    /** همان پسوندهای course_timer.py */
    val VIDEO_EXTENSIONS = setOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm",
        ".m4v", ".mpg", ".mpeg", ".3gp", ".ogv"
    )

    fun isVideoName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot >= 0 && name.substring(dot).lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
    }

    fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    /** میلی‌ثانیه -> {hours, minutes, seconds, text, total_seconds} (همان time_info پایتون) */
    fun timeInfo(ms: Long): JSONObject {
        val total = (ms / 1000.0 + 0.5).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return JSONObject()
            .put("hours", h)
            .put("minutes", m)
            .put("seconds", s)
            .put("text", String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s))
            .put("total_seconds", Math.round(ms / 1000.0 * 1000.0) / 1000.0)
    }

    fun msOf(info: JSONObject?): Long =
        if (info == null) 0L else Math.round(info.optDouble("total_seconds", 0.0) * 1000.0)

    /** مرتب‌سازی طبیعی: «2. intro» قبل از «10. basics» */
    private val DIGITS = Regex("\\d+")

    private fun naturalKey(name: String): List<Any> {
        val s = name.lowercase(Locale.ROOT)
        val out = ArrayList<Any>()
        var last = 0
        for (m in DIGITS.findAll(s)) {
            out.add(s.substring(last, m.range.first))
            out.add(m.value.toLongOrNull() ?: Long.MAX_VALUE)
            last = m.range.last + 1
        }
        out.add(s.substring(last))
        return out
    }

    val naturalComparator = Comparator<String> { a, b ->
        val ka = naturalKey(a)
        val kb = naturalKey(b)
        var result = 0
        var i = 0
        while (result == 0 && i < ka.size && i < kb.size) {
            val x = ka[i]
            val y = kb[i]
            result = if (x is Long && y is Long) x.compareTo(y) else x.toString().compareTo(y.toString())
            i++
        }
        if (result != 0) result else ka.size.compareTo(kb.size)
    }
}

fun JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(getString(i))
    return out
}
