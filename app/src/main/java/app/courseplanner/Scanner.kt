package app.courseplanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class NoVideosException : Exception()

/** کارهای مربوط به پوشه‌هایی که کاربر با انتخاب‌گر سیستم داده (Storage Access Framework) */
object Saf {
    /** نمایش خوانا برای آدرس پوشه، مثلاً /storage/emulated/0/Courses/Python */
    fun label(treeUriStr: String): String {
        val id = try {
            DocumentsContract.getTreeDocumentId(Uri.parse(treeUriStr))
        } catch (e: Exception) {
            return treeUriStr
        }
        if (!id.contains(':')) return id
        val vol = id.substringBefore(':')
        val rest = id.substringAfter(':')
        val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        return if (rest.isEmpty()) base else "$base/$rest"
    }

    private fun exists(ctx: Context, docUri: Uri): Boolean = try {
        val c = ctx.contentResolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )
        c?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    fun treeAccessible(ctx: Context, treeUriStr: String): Boolean = try {
        val tree = Uri.parse(treeUriStr)
        exists(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
    } catch (e: Exception) {
        false
    }

    fun documentExists(ctx: Context, docUri: Uri): Boolean = exists(ctx, docUri)
}

/** معادل CourseScanner در course_timer.py، ولی با MediaMetadataRetriever به‌جای ffprobe */
class Scanner(private val ctx: Context, private val tree: Uri) {

    private class Child(val id: String, val name: String, val mime: String)
    private class V(val name: String, val path: String, val uri: Uri) {
        var ms: Long? = null
    }
    private class N(val name: String, val path: String) {
        val videos = ArrayList<V>()
        val subs = ArrayList<N>()
    }

    private val failed = JSONArray()
    private val all = ArrayList<V>()

    suspend fun scan(): JSONObject = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = displayName(rootId)
            ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "course" }

        val root = collect(rootId, rootName, rootName) ?: throw NoVideosException()

        // مدت ویدیوها: چهارتا چهارتا هم‌زمان
        val gate = Semaphore(4)
        coroutineScope {
            all.map { v ->
                async(Dispatchers.IO) {
                    gate.withPermit { v.ms = durationMs(v.uri) }
                }
            }.awaitAll()
        }
        for (v in all) {
            if (v.ms == null) {
                failed.put(JSONObject().put("path", v.path).put("error", "could not read duration"))
            }
        }

        val tn = toJson(root)
        val node = tn.first
        JSONObject()
            .put("course_name", node.getString("name"))
            .put("root_directory", tree.toString())
            .put("scanned_at", Util.now())
            .put("video_count", node.getInt("video_count"))
            .put("total_duration", node.getJSONObject("total_duration"))
            .put("failed_files", failed)
            .put("tree", node)
    }

    private fun displayName(docId: String): String? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val c = ctx.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )
        c?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Child>()
        val c = ctx.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )
        c?.use {
            while (it.moveToNext()) {
                out.add(Child(it.getString(0), it.getString(1) ?: "", it.getString(2) ?: ""))
            }
        }
        return out
    }

    private fun collect(docId: String, name: String, rel: String): N? {
        val list = try {
            children(docId)
        } catch (e: Exception) {
            failed.put(JSONObject().put("path", rel).put("error", e.message ?: "error"))
            return null
        }
        val node = N(name, rel)
        for (c in list.sortedWith(Comparator<Child> { a, b -> Util.naturalComparator.compare(a.name, b.name) })) {
            if (c.name.startsWith(".")) continue          // فایل‌های مخفی
            val childRel = rel + "/" + c.name
            if (c.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val sub = collect(c.id, c.name, childRel)
                if (sub != null) node.subs.add(sub)
            } else if (Util.isVideoName(c.name)) {
                val v = V(c.name, childRel, DocumentsContract.buildDocumentUriUsingTree(tree, c.id))
                node.videos.add(v)
                all.add(v)
            }
        }
        return if (node.videos.isEmpty() && node.subs.isEmpty()) null else node
    }

    private fun durationMs(uri: Uri): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** گره را به همان ساختار JSON پایتون تبدیل می‌کند؛ مقدار دوم مجموع میلی‌ثانیه است. */
    private fun toJson(n: N): Pair<JSONObject, Long> {
        var total = 0L
        var count = 0
        val vids = JSONArray()
        for (v in n.videos) {
            val o = JSONObject().put("name", v.name).put("path", v.path).put("uri", v.uri.toString())
            val ms = v.ms
            if (ms != null) {
                o.put("duration", Util.timeInfo(ms))
                total += ms
            } else {
                o.put("duration", JSONObject.NULL)
            }
            vids.put(o)
            count++
        }
        val subs = JSONArray()
        for (s in n.subs) {
            val r = toJson(s)
            subs.put(r.first)
            total += r.second
            count += r.first.getInt("video_count")
        }
        val node = JSONObject()
            .put("name", n.name)
            .put("path", n.path)
            .put("video_count", count)
            .put("total_duration", Util.timeInfo(total))
            .put("videos", vids)
            .put("subdirectories", subs)
        return Pair(node, total)
    }
}
