package com.music.bitchord.data.qobuz

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Token renewal with no UI and no clicks.
 *
 * The tap-through journey (top → Descargar → quality button) is just what
 * a human does to end up with a solved Turnstile and an `fd_sp` session.
 * None of it needs simulating: a hidden WebView is a real browser context,
 * the invisible widget passes on its own there, and the same `Ao()` fetch
 * ritual the site runs mints `{t, sp}` straight into the bridge. No DOM is
 * touched, so a site redesign cannot break this the way click automation
 * would.
 *
 * Lazy by design — runs only when a session is actually needed
 * (unlock sheet, app resume with the addon enabled but no session), never
 * on a timer. Token expiry mid-session is already covered without any
 * browser: `sp` resumes natively on `TokenDead`. Only a dead `sp` comes
 * here, and only the visible sheet's checkbox case escapes headless mode.
 */
object QobuzSilentUnlock {

    private const val TAG = "BitChord"
    private const val BRIDGE = "QobuzSilentUnlock"
    private const val UNLOCK_TIMEOUT_MS = 130_000L
    private const val WIDGET_GRACE_MS = 4000L

    /** Minimum gap between silent attempts — a failing ritual must not spin. */
    const val ATTEMPT_THROTTLE_MS = 15 * 60 * 1000L

    private const val FILE = "bitchord_qobuz"
    private const val KEY_LAST_ATTEMPT = "silent_attempt_at"

    data class Session(val sp: String, val token: String)

    sealed interface Outcome {
        data class Unlocked(val session: Session) : Outcome
        /** Ran clean but the site refused — usually the visible checkbox. */
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lastAttemptAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_LAST_ATTEMPT, 0L) }.getOrDefault(0L)

    private fun markAttempt(context: Context) {
        runCatching {
            prefs(context).edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        }
    }

    /**
     * Headless unlock. Must be called holding no locks; returns on any
     * thread. Throttled — a recent attempt short-circuits to [Outcome.Failed]
     * rather than spinning up another renderer.
     */
    suspend fun unlock(context: Context): Outcome {
        val appContext = context.applicationContext
        if (System.currentTimeMillis() - lastAttemptAt(appContext) < ATTEMPT_THROTTLE_MS) {
            return Outcome.Failed("throttled")
        }
        markAttempt(appContext)
        return withTimeoutOrNull(UNLOCK_TIMEOUT_MS + 10_000L) {
            runHeadless(appContext)
        } ?: Outcome.Failed("timeout")
    }

    private suspend fun runHeadless(appContext: Context): Outcome =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = try {
                    WebView(appContext)
                } catch (e: Exception) {
                    cont.resume(Outcome.Failed("no webview: ${e.message}"))
                    return@suspendCancellableCoroutine
                }
                var settled = false
                fun finish(outcome: Outcome) {
                    if (settled) return
                    settled = true
                    runCatching {
                        webView.stopLoading()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
                cont.invokeOnCancellation { finish(Outcome.Failed("cancelled")) }
                try {
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.blockNetworkImage = true
                    webView.addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onResult(raw: String) {
                                Handler(Looper.getMainLooper()).post {
                                    finish(parseResult(raw))
                                }
                            }
                        },
                        BRIDGE,
                    )
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.postDelayed(
                                { view.evaluateJavascript(ritualJs(BRIDGE), null) },
                                WIDGET_GRACE_MS,
                            )
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            TrackLog.w(TAG, "qobuz silent unlock page error $errorCode")
                        }
                    }
                    webView.loadUrl(QobuzQueueApi.BASE + "/")
                } catch (e: Exception) {
                    finish(Outcome.Failed(e.message ?: "webview failed"))
                }
            }
        }

    private fun parseResult(raw: String): Outcome {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Outcome.Failed("bad bridge payload")
        val token = obj.optString("t").orEmpty()
        val sp = obj.optString("sp").orEmpty()
        if (token.isNotBlank() && sp.isNotBlank()) {
            return Outcome.Unlocked(Session(sp, token))
        }
        val error = obj.optString("error").orEmpty()
        // Anything the ritual reports is the site refusing this network, not
        // a broken WebView: challenge/timeout/token all mean the same thing
        // here — headless cannot pass, the visible sheet might.
        return Outcome.Refused(error.ifBlank { "unknown" })
    }

    /**
     * The site's own `Ao()` ritual, minus the React state: resume `fd_sp`,
     * else Turnstile response → `POST /prepare`, bridge `{t, sp}` out.
     * Shared with the visible unlock sheet — one ritual, two hosts.
     */
    fun ritualJs(bridge: String): String = UNLOCK_RITUAL.replace(BRIDGE, bridge)

    private const val UNLOCK_RITUAL: String = """
(function(){
  function done(o){ QobuzSilentUnlock.onResult(JSON.stringify(o)); }
  (async function(){
    try {
      var sp=''; try{sp=localStorage.getItem('fd_sp')||'';}catch(e){}
      if(sp){
        var o=await fetch('/prepare',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sp:sp})});
        if(o.ok){var l=await o.json(); if(l&&l.t){ if(l.sp){try{localStorage.setItem('fd_sp',l.sp);}catch(e){}} done({t:l.t,sp:(l.sp||sp)}); return; } }
        try{localStorage.removeItem('fd_sp');}catch(e){}
      }
      var t=await new Promise(function(res){
        var fin=false; function ok(v){if(!fin){fin=true;res(v);}}
        function attempt(){ try{ if(window.turnstile){ var r=null; try{r=window.turnstile.getResponse();}catch(e){} if(r){ok(r);return true;} } }catch(e){} return false; }
        if(attempt())return;
        var iv=setInterval(function(){ if(attempt()){clearInterval(iv);} },1500);
        setTimeout(function(){clearInterval(iv);ok('');},115000);
      });
      if(!t){done({error:'challenge-timeout'});return;}
      var n=await fetch('/prepare',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({cf:t})});
      if(!n.ok){done({error:'challenge:'+n.status});return;}
      var i=await n.json();
      if(!i||!i.t){done({error:'token'});return;}
      if(i.sp){try{localStorage.setItem('fd_sp',i.sp);}catch(e){}}
      done({t:i.t,sp:i.sp||''});
    }catch(e){ done({error:String((e&&e.message)||e)}); }
  })();
})();
"""
}
