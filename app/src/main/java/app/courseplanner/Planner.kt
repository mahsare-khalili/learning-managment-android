package app.courseplanner

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** برنامه‌ریزی روزانه؛ ترجمه‌ی مستقیم توابع مربوط در course_timer.py و app.py */
object Planner {

    class Vid(val path: String, val name: String, val ms: Long)

    fun flatten(node: JSONObject): List<Vid> {
        val out = ArrayList<Vid>()
        val vids = node.getJSONArray("videos")
        for (i in 0 until vids.length()) {
            val v = vids.getJSONObject(i)
            out.add(Vid(v.getString("path"), v.getString("name"), Util.msOf(v.optJSONObject("duration"))))
        }
        val subs = node.getJSONArray("subdirectories")
        for (i in 0 until subs.length()) out.addAll(flatten(subs.getJSONObject(i)))
        return out
    }

    /** ویدیو بین دو روز نصف نمی‌شود؛ هر روز می‌تواند تا grace دقیقه بیشتر شود. */
    fun buildSchedule(videos: List<Vid>, daily: Int, grace: Int): List<List<Vid>> {
        val limit = (daily + maxOf(grace, 0)) * 60_000L
        val days = ArrayList<List<Vid>>()
        var cur = ArrayList<Vid>()
        var used = 0L
        for (v in videos) {
            if (cur.isNotEmpty() && used + v.ms > limit) {
                days.add(cur)
                cur = ArrayList()
                used = 0L
            }
            cur.add(v)
            used += v.ms
        }
        if (cur.isNotEmpty()) days.add(cur)
        return days
    }

    fun stats(schedule: List<List<Vid>>, daily: Int): JSONObject {
        val dailyMs = daily * 60_000L
        val dayMs = schedule.map { day -> day.sumOf { it.ms } }
        return JSONObject()
            .put("planned_days", schedule.size)
            .put("longest_day", Util.timeInfo(dayMs.maxOrNull() ?: 0L))
            .put("overrun_days", dayMs.count { it > dailyMs })
    }

    fun applyProgress(plan: JSONObject, completed: Collection<String>) {
        val set = completed.toHashSet()
        val ordered = JSONArray()
        val days = plan.getJSONArray("days")
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            val vids = day.getJSONArray("videos")
            var n = 0
            for (j in 0 until vids.length()) {
                val v = vids.getJSONObject(j)
                val isDone = set.contains(v.getString("path"))
                v.put("done", isDone)
                if (isDone) {
                    n++
                    ordered.put(v.getString("path"))
                }
            }
            day.put("video_count", vids.length())
            day.put("done_count", n)
            day.put("done", n == vids.length())
        }
        plan.put("completed", ordered)
        plan.put("updated_at", Util.now())
    }

    fun makePlan(report: JSONObject, daily: Int, grace: Int): JSONObject {
        val videos = flatten(report.getJSONObject("tree"))
        val totalMs = videos.sumOf { it.ms }
        val dailyMs = daily * 60_000L
        val schedule = buildSchedule(videos, daily, grace)
        val st = stats(schedule, daily)

        val days = JSONArray()
        schedule.forEachIndexed { i, day ->
            val vs = JSONArray()
            for (v in day) {
                vs.put(
                    JSONObject().put("path", v.path).put("name", v.name)
                        .put("duration", Util.timeInfo(v.ms)).put("done", false)
                )
            }
            days.put(
                JSONObject()
                    .put("day", i + 1)
                    .put("done", false)
                    .put("done_count", 0)
                    .put("video_count", day.size)
                    .put("total_duration", Util.timeInfo(day.sumOf { it.ms }))
                    .put("videos", vs)
            )
        }

        val plan = JSONObject()
            .put("course_name", report.getString("course_name"))
            .put("created_at", Util.now())
            .put("daily_minutes", daily)
            .put("daily_time", String.format(Locale.ROOT, "%02d:%02d", daily / 60, daily % 60))
            .put("grace_minutes", grace)
            .put("total_duration", Util.timeInfo(totalMs))
            .put("min_days_if_split_anywhere", (totalMs + dailyMs - 1) / dailyMs)
            .put("planned_days", st.getInt("planned_days"))
            .put("longest_day", st.getJSONObject("longest_day"))
            .put("overrun_days", st.getInt("overrun_days"))
            .put("days", days)
            .put("completed", JSONArray())
        applyProgress(plan, emptyList())
        return plan
    }

    fun tailOf(path: String): String {
        val i = path.indexOf('/')
        return if (i < 0) path else path.substring(i + 1)
    }

    /** تیک‌های قبلی را روی پلن جدید می‌گذارد (حتی اگر نام پوشه‌ی ریشه عوض شده باشد). */
    fun remapCompleted(old: List<String>, plan: JSONObject): List<String> {
        val exact = old.toHashSet()
        val tails = old.map { tailOf(it) }.toHashSet()
        val out = ArrayList<String>()
        val days = plan.getJSONArray("days")
        for (i in 0 until days.length()) {
            val vids = days.getJSONObject(i).getJSONArray("videos")
            for (j in 0 until vids.length()) {
                val p = vids.getJSONObject(j).getString("path")
                if (exact.contains(p) || tails.contains(tailOf(p))) out.add(p)
            }
        }
        return out
    }

    fun planVideoPaths(plan: JSONObject): List<String> {
        val out = ArrayList<String>()
        val days = plan.getJSONArray("days")
        for (i in 0 until days.length()) {
            val vids = days.getJSONObject(i).getJSONArray("videos")
            for (j in 0 until vids.length()) out.add(vids.getJSONObject(j).getString("path"))
        }
        return out
    }

    fun matches(report: JSONObject?, plan: JSONObject?): Boolean? {
        if (report == null || plan == null) return null
        return try {
            flatten(report.getJSONObject("tree")).map { it.path } == planVideoPaths(plan)
        } catch (e: Exception) {
            false
        }
    }

    fun progress(plan: JSONObject): JSONObject {
        val done = plan.optJSONArray("completed").toStrings().toHashSet()
        var totalMs = 0L
        var watchedMs = 0L
        var nVideos = 0
        var nDone = 0
        var daysDone = 0
        var current: Int? = null
        val days = plan.getJSONArray("days")
        for (i in 0 until days.length()) {
            val d = days.getJSONObject(i)
            val vids = d.getJSONArray("videos")
            var allDone = true
            for (j in 0 until vids.length()) {
                val v = vids.getJSONObject(j)
                val ms = Util.msOf(v.optJSONObject("duration"))
                totalMs += ms
                nVideos++
                if (done.contains(v.getString("path"))) {
                    watchedMs += ms
                    nDone++
                } else {
                    allDone = false
                }
            }
            if (allDone) daysDone++ else if (current == null) current = d.getInt("day")
        }
        val pct = if (totalMs > 0) 100.0 * watchedMs / totalMs else if (nVideos > 0) 100.0 * nDone / nVideos else 0.0
        val out = JSONObject()
            .put("done_videos", nDone)
            .put("total_videos", nVideos)
            .put("done_days", daysDone)
            .put("total_days", days.length())
            .put("progress_pct", Math.round(pct * 10.0) / 10.0)
        if (current != null) out.put("current_day", current)
        return out
    }
}
