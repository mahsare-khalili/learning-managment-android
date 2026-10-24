package app.courseplanner

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * همان صفحه‌ی index.html را در یک WebView نشان می‌دهد.
 * هر «درخواست» صفحه (به‌جای fetch) از طریق Bridge به Api.kt می‌رسد.
 */
class MainActivity : AppCompatActivity(), Host {

    private lateinit var web: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pickCont: CancellableContinuation<Uri?>? = null

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val c = pickCont
        pickCont = null
        if (c != null && c.isActive) c.resume(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        Api.appCtx = applicationContext
        Api.host = this

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            // فقط خود برنامه؛ هیچ آدرس بیرونی باز نمی‌شود
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return true
                return !u.startsWith("file:///android_asset/")
            }
        }

        // confirm() صفحه (مثلاً «تیک‌ها پاک می‌شوند؟») با دیالوگ خود اندروید
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("ادامه") { _, _ -> result.confirm() }
                    .setNegativeButton("انصراف") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__back ? window.__back() : false)") { r ->
                    if (r != "true") finish()
                }
            }
        })

        web.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun request(id: Int, method: String, url: String, body: String) {
            scope.launch {
                val res = Api.handle(method, url, body)
                withContext(Dispatchers.Main) {
                    web.evaluateJavascript(
                        "window.__resolve(" + id + "," + res.first + "," + JSONObject.quote(res.second) + ")",
                        null
                    )
                }
            }
        }
    }

    // ---- Host ----
    override suspend fun pickFolder(): Uri? = suspendCancellableCoroutine { cont ->
        pickCont = cont
        runOnUiThread { pickLauncher.launch(null) }
    }

    override fun launch(intent: Intent) {
        runOnUiThread { startActivity(intent) }
    }

    // ---- چرخه‌ی عمر ----
    override fun onResume() {
        super.onResume()
        web.onResume()
        // بعد از برگشت از پلیر، «آخرین توقف» ویدیوها تازه شود
        web.evaluateJavascript("if (window.__onResume) window.__onResume();", null)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        Api.host = null
        scope.cancel()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
