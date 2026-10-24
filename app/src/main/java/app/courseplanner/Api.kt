package app.courseplanner

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** کارهایی که فقط یک Activity می‌تواند انجام دهد (MainActivity این را پیاده می‌کند). */
interface Host {
    suspend fun pickFolder(): Uri?
    fun launch(intent: Intent)
}

/**
 * معادل app.py (FastAPI). صفحه‌ی وب به‌جای fetch، مستقیم این‌جا را صدا می‌زند.
 * خروجی: (کد وضعیت شبیه HTTP، متن JSON)
 */
object Api {
    lateinit var appCtx: Context
    var host: Host? = null

    private const val MAX_NAME = 80
    private const val MAX_GRACE = 120

    private val PLAYERS = listOf(
        Triple("builtin", "پلیر داخلی برنامه", true),
        Triple("external", "پلیر خارجی (VLC، MX Player و…)", false)
    )

    suspend fun handle(method: String, url: String, body: String): Pair<Int, String> {
        return try {
            val uri = Uri.parse(url)
            val parts = (uri.path ?: "").trim('/').split('/')
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            Pair(200, route(method, parts, uri, json).toString())
        } catch (e: ApiError) {
            Pair(e.status, JSONObject().put("detail", e.message ?: "خطا").toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Pair(500, JSONObject().put("detail", "خطای داخلی: " + (e.message ?: e.javaClass.simpleName)).toString())
        }
    }

    private suspend fun route(method: String, p: List<String>, uri: Uri, b: JSONObject): Any {
        if (p.size < 2 || p[0] != "api") throw ApiError(404, "پیدا نشد.")
        if (p[1] == "settings") return if (method == "PUT") putSettings(b) else settingsView()
        if (p[1] == "pick-folder") return pickFolderApi()
        if (p[1] != "programs") throw ApiError(404, "پیدا نشد.")
        if (p.size == 2) return if (method == "POST") createProgram(b) else listPrograms()

        val cid = p[2]
        if (p.size == 3) {
            return when (method) {
                "GET" -> getProgram(cid)
                "PATCH" -> updateProgram(cid, b)
                "DELETE" -> deleteProgram(cid)
                else -> throw ApiError(405, "روش نامعتبر.")
            }
        }
        return when (p[3] + ":" + method) {
            "restore:POST" -> restoreProgram(cid)
            "preview:GET" -> preview(cid, uri)
            "plan:POST" -> createPlan(cid, b)
            "progress:POST" -> setProgress(cid, b)
            "play:POST" -> play(cid, b)
            "positions:GET" -> getPositions(cid)
            "position:PUT" -> setPosition(cid, b)
            else -> throw ApiError(404, "پیدا نشد.")
        }
    }

    // ------------------------------------------------------------------
    // کمک‌ها
    // ------------------------------------------------------------------
    private fun cleanName(name: String): String {
        val n = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (n.length > MAX_NAME) throw ApiError(400, "نام برنامه حداکثر $MAX_NAME نویسه می‌تواند باشد.")
        return n
    }

    private fun nameTaken(name: String, exclude: String?): Boolean {
        val key = name.lowercase(Locale.ROOT)
        return Store.existingIds().any { it != exclude && Store.nameOf(it).lowercase(Locale.ROOT) == key }
    }

    private fun ensureNameFree(name: String, exclude: String?) {
        if (nameTaken(name, exclude)) {
            throw ApiError(409, "برنامه‌ای با نام «" + name + "» از قبل وجود دارد. نام دیگری انتخاب کنید.")
        }
    }

    private fun uniqueName(base: String): String {
        var name = base
        var n = 2
        while (nameTaken(name, null)) {
            name = "$base ($n)"
            n++
        }
        return name
    }

    private fun activeTails(cid: String): List<String> =
        Store.active.filter { it.startsWith(cid + "|") }.map { it.substringAfter('|') }

    private fun activeJson(cid: String): JSONArray {
        val a = JSONArray()
        for (t in activeTails(cid)) a.put(t)
        return a
    }

    private fun knownVideoPaths(report: JSONObject?, plan: JSONObject?): Set<String> {
        val s = HashSet<String>()
        try {
            if (report != null) for (v in Planner.flatten(report.getJSONObject("tree"))) s.add(v.path)
            if (plan != null) s.addAll(Planner.planVideoPaths(plan))
        } catch (e: Exception) {
            // فایل ناقص؛ همان چیزی که جمع شده کافی است
        }
        return s
    }

    private fun summarize(cid: String, meta: JSONObject, report: JSONObject?, plan: JSONObject?): JSONObject {
        val src = report ?: plan ?: JSONObject()
        var dirUri = meta.optString("directory", "")
        if (dirUri.isEmpty() && report != null) dirUri = report.optString("root_directory", "")
        val exists = dirUri.isNotEmpty() && Saf.treeAccessible(appCtx, dirUri)
        val brief: JSONObject? = try {
            if (plan != null) planBrief(plan) else null
        } catch (e: Exception) {
            null
        }
        val stamps = listOf(
            meta.optString("created_at", ""),
            meta.optString("updated_at", ""),
            plan?.optString("updated_at", "") ?: "",
            report?.optString("scanned_at", "") ?: ""
        ).filter { it.isNotEmpty() }
        val videoCount = when {
            report != null -> report.optInt("video_count", 0)
            plan != null -> Planner.planVideoPaths(plan).size
            else -> 0
        }
        val name = meta.optString("name", "").ifEmpty { src.optString("course_name", cid) }
        val created = meta.optString("created_at", "").ifEmpty { report?.optString("scanned_at", "") ?: "" }

        val out = JSONObject()
            .put("id", cid)
            .put("name", name)
            .put("directory", if (dirUri.isEmpty()) "" else Saf.label(dirUri))
            .put("directory_uri", dirUri)
            .put("directory_exists", exists)
            .put("created_at", created)
            .put("last_activity", stamps.maxOrNull() ?: "")
            .put("video_count", videoCount)
            .put("has_report", report != null)
            .put("has_plan", plan != null)
        val td = src.opt("total_duration")
        if (td != null) out.put("total_duration", td)
        val pm = Planner.matches(report, plan)
        if (pm != null) out.put("plan_matches_report", pm)
        if (brief != null) out.put("plan", brief)
        return out
    }

    private fun planBrief(plan: JSONObject): JSONObject {
        val out = Planner.progress(plan)
        out.put("daily_minutes", plan.optInt("daily_minutes", 0))
        out.put("daily_time", plan.optString("daily_time", ""))
        out.put("grace_minutes", plan.optInt("grace_minutes", 0))
        return out
    }

    private suspend fun scanDirectory(dir: String): JSONObject {
        if (dir.isEmpty()) throw ApiError(400, "پوشه‌ی دوره را انتخاب کنید.")
        if (!Saf.treeAccessible(appCtx, dir)) {
            throw ApiError(400, "دسترسی به این پوشه ممکن نیست. پوشه را دوباره انتخاب کنید.")
        }
        try {
            return Scanner(appCtx, Uri.parse(dir)).scan()
        } catch (e: NoVideosException) {
            throw ApiError(400, "در این پوشه (و زیرپوشه‌هایش) هیچ ویدیویی پیدا نشد.")
        } catch (e: SecurityException) {
            throw ApiError(400, "اجازه‌ی خواندن این پوشه را ندارید. آن را دوباره انتخاب کنید.")
        }
    }

    // ------------------------------------------------------------------
    // انتخاب پوشه
    // ------------------------------------------------------------------
    private suspend fun pickFolderApi(): JSONObject {
        val h = host ?: throw ApiError(500, "انتخاب پوشه در دسترس نیست.")
        val uri = h.pickFolder() ?: return JSONObject().put("status", "cancel")
        try {
            appCtx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // اگر نشد، همین نشست کار می‌کند ولی بعد از بستن برنامه باید دوباره انتخاب شود
        }
        return JSONObject()
            .put("status", "ok")
            .put("path", uri.toString())
            .put("label", Saf.label(uri.toString()))
    }

    // ------------------------------------------------------------------
    // برنامه‌ها
    // ------------------------------------------------------------------
    private fun listPrograms(): JSONArray {
        val items = ArrayList<JSONObject>()
        for (cid in Store.existingIds()) {
            val meta = Store.readOpt(Store.file(cid, "meta")) ?: JSONObject()
            val report = Store.readOpt(Store.file(cid, "report"))
            val plan = Store.readOpt(Store.file(cid, "plan"))
            if (meta.length() == 0 && report == null && plan == null) continue
            try {
                items.add(summarize(cid, meta, report, plan))
            } catch (e: Exception) {
                // برنامه‌ی خراب نباید کل فهرست را از کار بیندازد
            }
        }
        val sorted = items
            .sortedWith(Comparator<JSONObject> { a, b ->
                Util.naturalComparator.compare(a.optString("name", ""), b.optString("name", ""))
            })
            .sortedByDescending { it.optString("last_activity", "") }
        val out = JSONArray()
        for (o in sorted) out.put(o)
        return out
    }

    private suspend fun createProgram(b: JSONObject): JSONObject {
        val dir = b.optString("directory", "").trim()
        if (dir.isEmpty()) throw ApiError(400, "پوشه‌ی دوره را انتخاب کنید.")
        val wanted = cleanName(b.optString("name", ""))
        if (wanted.isNotEmpty()) ensureNameFree(wanted, null)
        val report = scanDirectory(dir)

        val result = synchronized(Store.lock) {
            val name: String
            if (wanted.isNotEmpty()) {
                ensureNameFree(wanted, null)
                name = wanted
            } else {
                name = uniqueName(report.getString("course_name").take(MAX_NAME))
            }
            val cid = Store.newId()
            val stamp = Util.now()
            Store.save(
                JSONObject().put("name", name).put("directory", report.getString("root_directory"))
                    .put("created_at", stamp).put("updated_at", stamp),
                Store.file(cid, "meta")
            )
            Store.save(report, Store.file(cid, "report"))
            Pair(cid, name)
        }
        return JSONObject()
            .put("id", result.first)
            .put("name", result.second)
            .put("video_count", report.getInt("video_count"))
            .put("failed", report.getJSONArray("failed_files").length())
    }

    private fun getProgram(cid: String): JSONObject {
        val (meta, report, plan) = Store.loadProgram(cid)
        val out = JSONObject()
            .put("program", summarize(cid, meta, report, plan))
            .put("positions", Store.readOpt(Store.file(cid, "positions")) ?: JSONObject())
            .put("active", activeJson(cid))
        if (report != null) out.put("report", report)
        if (plan != null) out.put("plan", plan)
        val pm = Planner.matches(report, plan)
        if (pm != null) out.put("plan_matches_report", pm)
        return out
    }

    private suspend fun updateProgram(cid: String, b: JSONObject): JSONObject {
        val loaded = Store.loadProgram(cid)
        val plan = loaded.third
        val current = summarize(cid, loaded.first, loaded.second, plan)

        val name: String? = if (b.has("name") && !b.isNull("name")) {
            val n = cleanName(b.getString("name"))
            if (n.isEmpty()) throw ApiError(400, "نام برنامه نمی‌تواند خالی باشد.")
            if (n != current.getString("name")) ensureNameFree(n, cid)
            n
        } else null

        val directory: String? = if (b.has("directory") && !b.isNull("directory")) {
            val d = b.getString("directory").trim()
            if (d.isEmpty()) throw ApiError(400, "پوشه‌ی دوره را انتخاب کنید.")
            d
        } else null

        val curDir = current.optString("directory_uri", "")
        val dirChanged = directory != null && (curDir.isEmpty() || directory != curDir)
        val rescan = b.optBoolean("rescan", false)

        val newReport: JSONObject? = if (dirChanged || rescan) {
            scanDirectory(if (dirChanged && directory != null) directory else curDir)
        } else null

        val saved = synchronized(Store.lock) {
            val m = Store.readOpt(Store.file(cid, "meta")) ?: JSONObject()
            if (!m.has("created_at")) {
                m.put("created_at", current.optString("created_at", "").ifEmpty { Util.now() })
            }
            m.put("name", name ?: m.optString("name", "").ifEmpty { current.getString("name") })
            var rep = loaded.second
            if (newReport != null) {
                m.put("directory", newReport.getString("root_directory"))
                Store.save(newReport, Store.file(cid, "report"))
                rep = newReport
            } else if (!m.has("directory")) {
                m.put("directory", curDir)
            }
            m.put("updated_at", Util.now())
            Store.save(m, Store.file(cid, "meta"))
            Pair(m, rep)
        }

        val summary = summarize(cid, saved.first, saved.second, plan)
        return JSONObject()
            .put("program", summary)
            .put("rescanned", newReport != null)
            .put("video_count", summary.getInt("video_count"))
            .put("failed", if (newReport != null) newReport.getJSONArray("failed_files").length() else 0)
    }

    private fun deleteProgram(cid: String): JSONObject {
        synchronized(Store.lock) {
            val files = Store.KINDS.map { Store.file(cid, it) }.filter { it.exists() }
            if (files.isEmpty()) throw ApiError(404, "برنامه پیدا نشد.")
            val dest = File(Store.trashDir, cid)
            dest.deleteRecursively()
            dest.mkdirs()
            for (f in files) {
                Files.move(f.toPath(), File(dest, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return JSONObject().put("deleted", cid)
    }

    private fun restoreProgram(cid: String): JSONObject {
        Store.checkId(cid)
        synchronized(Store.lock) {
            val src = File(Store.trashDir, cid)
            if (!src.isDirectory) throw ApiError(404, "چیزی برای بازگردانی پیدا نشد.")
            if (Store.KINDS.any { Store.file(cid, it).exists() }) throw ApiError(409, "این برنامه دوباره وجود دارد.")
            for (f in src.listFiles() ?: emptyArray()) {
                Files.move(f.toPath(), File(Store.dataDir, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            src.delete()
        }
        return JSONObject().put("restored", cid)
    }

    // ------------------------------------------------------------------
    // برنامه‌ی روزانه و پیشرفت
    // ------------------------------------------------------------------
    private fun intParam(uri: Uri, key: String): Int = uri.getQueryParameter(key)?.toIntOrNull() ?: 0

    private fun preview(cid: String, uri: Uri): JSONObject {
        val hours = intParam(uri, "hours")
        val minutes = intParam(uri, "minutes")
        val grace = intParam(uri, "grace")
        if (hours !in 0..23 || minutes !in 0..59 || grace !in 0..MAX_GRACE) throw ApiError(400, "ورودی نامعتبر است.")
        val daily = hours * 60 + minutes
        if (daily <= 0) throw ApiError(400, "زمان روزانه باید بیشتر از صفر باشد.")

        val report = Store.read(Store.file(cid, "report"))
        val videos = Planner.flatten(report.getJSONObject("tree"))
        val totalMs = videos.sumOf { it.ms }
        val stats = Planner.stats(Planner.buildSchedule(videos, daily, grace), daily)
        stats.put(
            "days_without_grace",
            if (grace > 0) Planner.buildSchedule(videos, daily, 0).size else stats.getInt("planned_days")
        )
        val dailyMs = daily * 60_000L
        stats.put("min_days", (totalMs + dailyMs - 1) / dailyMs)
        return stats
    }

    private fun createPlan(cid: String, b: JSONObject): JSONObject {
        val hours = b.optInt("hours", 0)
        val minutes = b.optInt("minutes", 0)
        val grace = b.optInt("grace_minutes", 0)
        val keep = b.optBoolean("keep_progress", false)
        if (hours !in 0..23 || minutes !in 0..59 || grace !in 0..MAX_GRACE) throw ApiError(400, "ورودی نامعتبر است.")
        val daily = hours * 60 + minutes
        if (daily <= 0) throw ApiError(400, "زمان روزانه باید بیشتر از صفر باشد.")

        return synchronized(Store.lock) {
            val report = Store.read(Store.file(cid, "report"))
            if (report.getJSONObject("total_duration").optDouble("total_seconds", 0.0) <= 0.0) {
                throw ApiError(400, "مدت کل ویدیوها صفر است؛ برنامه‌ریزی ممکن نیست.")
            }
            val plan = Planner.makePlan(report, daily, grace)
            val planFile = Store.file(cid, "plan")
            if (keep) {
                val old = Store.readOpt(planFile)
                if (old != null) {
                    Planner.applyProgress(plan, Planner.remapCompleted(old.optJSONArray("completed").toStrings(), plan))
                }
            }
            Store.save(plan, planFile)
            plan
        }
    }

    private fun setProgress(cid: String, b: JSONObject): JSONObject {
        val paths = b.optJSONArray("paths").toStrings()
        val done = b.optBoolean("done", false)
        val f = Store.file(cid, "plan")
        val result = synchronized(Store.lock) {
            val p = Store.read(f)
            val valid = Planner.planVideoPaths(p).toHashSet()
            val unknown = paths.firstOrNull { !valid.contains(it) }
            if (unknown != null) throw ApiError(400, "ویدیوی ناشناخته در این پلن: $unknown")
            val completed = p.optJSONArray("completed").toStrings().toHashSet()
            if (done) completed.addAll(paths) else completed.removeAll(paths.toSet())
            Planner.applyProgress(p, completed)
            Store.save(p, f)
            p
        }
        return JSONObject()
            .put("completed", result.getJSONArray("completed"))
            .put("updated_at", result.getString("updated_at"))
    }

    // ------------------------------------------------------------------
    // تنظیمات پلیر
    // ------------------------------------------------------------------
    private fun settingsView(): JSONObject {
        val s = Store.loadSettings()
        val wanted = s.getString("player_id")
        val pid = if (PLAYERS.any { it.first == wanted }) wanted else "builtin"
        val cur = PLAYERS.first { it.first == pid }
        val players = JSONArray()
        for (p in PLAYERS) {
            players.put(JSONObject().put("id", p.first).put("name", p.second).put("path", "").put("tracking", p.third))
        }
        return JSONObject()
            .put("configured", true)
            .put("resume", s.getBoolean("resume"))
            .put(
                "player",
                JSONObject().put("id", cur.first).put("name", cur.second)
                    .put("tracking", cur.third).put("available", true)
            )
            .put("players", players)
    }

    private fun putSettings(b: JSONObject): JSONObject {
        val pid = b.optString("player_id", "builtin")
        if (PLAYERS.none { it.first == pid }) throw ApiError(400, "پلیر نامعتبر است.")
        synchronized(Store.lock) {
            Store.save(
                JSONObject().put("player_id", pid).put("resume", b.optBoolean("resume", true)),
                Store.settingsFile
            )
        }
        return settingsView()
    }

    // ------------------------------------------------------------------
    // پخش ویدیو و زمان توقف
    // ------------------------------------------------------------------
    private fun findVideoUri(node: JSONObject, path: String): String? {
        val vids = node.getJSONArray("videos")
        for (i in 0 until vids.length()) {
            val v = vids.getJSONObject(i)
            if (v.getString("path") == path) {
                val u = v.optString("uri", "")
                return if (u.isEmpty()) null else u
            }
        }
        val subs = node.getJSONArray("subdirectories")
        for (i in 0 until subs.length()) {
            val r = findVideoUri(subs.getJSONObject(i), path)
            if (r != null) return r
        }
        return null
    }

    /** نزدیک ابتدا یا انتها یعنی از اول شروع کن. */
    private fun resumePoint(pos: JSONObject?): Double {
        if (pos == null) return 0.0
        val sec = pos.optDouble("seconds", 0.0)
        val dur = pos.optDouble("duration", Double.NaN)
        if (sec < 5 || (!dur.isNaN() && sec >= dur - 10)) return 0.0
        return sec
    }

    private fun play(cid: String, b: JSONObject): JSONObject {
        val (meta, report, plan) = Store.loadProgram(cid)
        val path = b.optString("path", "")
        if (!knownVideoPaths(report, plan).contains(path)) throw ApiError(400, "این ویدیو در این برنامه نیست.")

        var root = meta.optString("directory", "")
        if (root.isEmpty() && report != null) root = report.optString("root_directory", "")
        if (root.isEmpty() || !Saf.treeAccessible(appCtx, root)) {
            throw ApiError(404, "دسترسی به پوشه‌ی دوره ممکن نیست. در «ویرایش» پوشه را دوباره انتخاب کنید.")
        }

        val tail = Planner.tailOf(path)
        val uriStr = if (report != null) findVideoUri(report.getJSONObject("tree"), path) else null
        if (uriStr == null) throw ApiError(404, "آدرس این ویدیو ثبت نشده است. یک‌بار «اسکن مجدد» بزنید.")
        val videoUri = Uri.parse(uriStr)
        if (!Saf.documentExists(appCtx, videoUri)) throw ApiError(404, "فایل ویدیو پیدا نشد: $tail")

        val settings = Store.loadSettings()
        val builtin = settings.getString("player_id") != "external"
        val positions = Store.readOpt(Store.file(cid, "positions")) ?: JSONObject()
        val start = if (builtin && settings.getBoolean("resume")) resumePoint(positions.optJSONObject(tail)) else 0.0
        val playerName = if (builtin) PLAYERS[0].second else PLAYERS[1].second

        val intent: Intent
        if (builtin) {
            intent = Intent(appCtx, PlayerActivity::class.java)
                .putExtra("cid", cid)
                .putExtra("tail", tail)
                .putExtra("title", tail.substringAfterLast('/'))
                .putExtra("uri", uriStr)
                .putExtra("start", (start * 1000).toLong())
        } else {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(videoUri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = ClipData.newRawUri("", videoUri)
            intent = Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val h = host
        if (h != null) h.launch(intent) else appCtx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        return JSONObject()
            .put("already_running", false)
            .put("player", playerName)
            .put("tracking", builtin)
            .put("can_start", builtin)
            .put("start", start)
    }

    private fun getPositions(cid: String): JSONObject = JSONObject()
        .put("positions", Store.readOpt(Store.file(cid, "positions")) ?: JSONObject())
        .put("active", activeJson(cid))

    private fun setPosition(cid: String, b: JSONObject): JSONObject {
        val (_, report, plan) = Store.loadProgram(cid)
        val path = b.optString("path", "")
        if (!knownVideoPaths(report, plan).contains(path)) throw ApiError(400, "این ویدیو در این برنامه نیست.")
        val tail = Planner.tailOf(path)
        val clear = !b.has("seconds") || b.isNull("seconds")
        val seconds = if (clear) 0.0 else b.getDouble("seconds")
        if (!clear && (seconds < 0 || seconds > 24 * 3600)) {
            throw ApiError(400, "زمان توقف باید بین ۰ تا ۲۴ ساعت باشد.")
        }
        val data = synchronized(Store.lock) {
            val f = Store.file(cid, "positions")
            val d = Store.readOpt(f) ?: JSONObject()
            if (clear) {
                d.remove(tail)
            } else {
                val old = d.optJSONObject(tail)
                val entry = JSONObject()
                    .put("seconds", Math.round(seconds * 10.0) / 10.0)
                    .put("updated_at", Util.now())
                    .put("manual", true)
                if (old != null && old.has("duration")) entry.put("duration", old.get("duration"))
                d.put(tail, entry)
            }
            Store.save(d, f)
            d
        }
        return JSONObject().put("positions", data)
    }
}
