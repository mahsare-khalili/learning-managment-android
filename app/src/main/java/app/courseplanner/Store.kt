package app.courseplanner

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * ذخیره‌ی فایل‌های JSON، دقیقاً مثل پوشه‌ی data/ در نسخه‌ی دسکتاپ:
 *   <id>_meta.json   <id>_report.json   <id>_plan.json   <id>_positions.json
 *   settings.json    trash/<id>/...
 */
object Store {
    lateinit var dataDir: File
    lateinit var trashDir: File

    val lock = Any()
    val KINDS = listOf("meta", "report", "plan", "positions")

    /** پخش‌های در حال انجام با پلیر داخلی: "<cid>|<tail>" */
    val active: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    private val io = Executors.newSingleThreadExecutor()
    private val ID_RE = Regex("[A-Za-z0-9_-]{1,64}")

    fun init(ctx: Context) {
        dataDir = File(ctx.filesDir, "data")
        dataDir.mkdirs()
        trashDir = File(dataDir, "trash")
    }

    fun checkId(cid: String): String {
        if (!ID_RE.matches(cid)) throw ApiError(404, "برنامه پیدا نشد.")
        return cid
    }

    fun file(cid: String, kind: String): File = File(dataDir, checkId(cid) + "_" + kind + ".json")

    val settingsFile: File
        get() = File(dataDir, "settings.json")

    // ---------------- خواندن / نوشتن ----------------
    fun readOpt(f: File): JSONObject? = try {
        JSONObject(f.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun read(f: File): JSONObject {
        if (!f.exists()) throw ApiError(404, "فایل پیدا نشد: " + f.name)
        return try {
            JSONObject(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ApiError(500, "فایل JSON خراب است: " + f.name)
        }
    }

    fun save(obj: JSONObject, f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(obj.toString(2), Charsets.UTF_8)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    // ---------------- برنامه‌ها ----------------
    fun existingIds(): List<String> {
        val ids = HashSet<String>()
        val names = dataDir.list() ?: return emptyList()
        for (n in names) {
            for (k in KINDS) {
                val suffix = "_" + k + ".json"
                if (n.endsWith(suffix)) ids.add(n.removeSuffix(suffix))
            }
        }
        return ids.filter { ID_RE.matches(it) }.sortedWith(Util.naturalComparator)
    }

    fun newId(): String {
        while (true) {
            val cid = "p" + UUID.randomUUID().toString().replace("-", "").take(8)
            val taken = KINDS.any { file(cid, it).exists() } || File(trashDir, cid).exists()
            if (!taken) return cid
        }
    }

    fun loadProgram(cid: String): Triple<JSONObject, JSONObject?, JSONObject?> {
        val meta = readOpt(file(cid, "meta")) ?: JSONObject()
        val report = readOpt(file(cid, "report"))
        val plan = readOpt(file(cid, "plan"))
        if (meta.length() == 0 && report == null && plan == null) throw ApiError(404, "برنامه پیدا نشد.")
        return Triple(meta, report, plan)
    }

    fun nameOf(cid: String): String {
        val meta = readOpt(file(cid, "meta"))
        val n = meta?.optString("name", "") ?: ""
        if (n.isNotEmpty()) return n
        val src = readOpt(file(cid, "report")) ?: readOpt(file(cid, "plan"))
        return src?.optString("course_name", cid) ?: cid
    }

    // ---------------- تنظیمات ----------------
    fun loadSettings(): JSONObject {
        val d = readOpt(settingsFile) ?: JSONObject()
        return JSONObject()
            .put("resume", d.optBoolean("resume", true))
            .put("player_id", d.optString("player_id", "builtin"))
    }

    // ---------------- زمان توقف ----------------
    /** از پلیر داخلی صدا زده می‌شود. اگر برنامه حذف شده باشد چیزی نمی‌نویسد. */
    fun savePosition(cid: String, tail: String, seconds: Double, duration: Double?) {
        io.execute {
            try {
                synchronized(lock) {
                    if (!file(cid, "report").exists() && !file(cid, "plan").exists()) return@synchronized
                    val f = file(cid, "positions")
                    val data = readOpt(f) ?: JSONObject()
                    val old = data.optJSONObject(tail)
                    val dur: Double? = duration ?: old?.optDouble("duration")?.takeIf { !it.isNaN() }
                    val entry = JSONObject()
                        .put("seconds", Math.round(seconds * 10.0) / 10.0)
                        .put("updated_at", Util.now())
                        .put("manual", false)
                    if (dur != null) entry.put("duration", Math.round(dur * 10.0) / 10.0)
                    data.put(tail, entry)
                    save(data, f)
                }
            } catch (e: Exception) {
                // ذخیره‌ی زمان توقف نباید پخش را خراب کند
            }
        }
    }
}
