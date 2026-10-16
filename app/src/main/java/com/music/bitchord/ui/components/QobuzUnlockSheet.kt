package com.music.bitchord.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.R
import com.music.bitchord.data.sources.SourceConfig
import org.json.JSONObject

/**
 * One-tap unlock for the Qobuz conversion queue.
 *
 * flacdownloader.com mints queue tokens only for sessions that passed its
 * Turnstile widget, and Turnstile only runs in a real browser — Dio/OkHttp
 * get `challenge_failed` forever. So this sheet loads the real site, runs
 * the site's own ritual inside page context (resume `fd_sp`, else
 * Turnstile → `POST /prepare`), and bridges `{t, sp}` out. The session
 * then resumes natively with no further browser visits until the server
 * rotates it.
 *
 * The page may show an invisible check that passes on its own, or a visible
 * checkbox on a stricter network — either way the listener just waits or
 * taps once. Nothing is downloaded here; this mints the session, nothing
 * else.
 */
@Composable
fun QobuzUnlockSheet(
    config: SourceConfig,
    onUnlocked: (sp: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var status by remember { mutableStateOf<UnlockStatus>(UnlockStatus.Auto) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val currentOnUnlocked = rememberUpdatedState(onUnlocked)
    // Silent first, click-driver second, hands last: most unlocks need no
    // browser at all; when headless cannot pass, the sheet drives the
    // charts page itself (first Descargar, then a quality button) before
    // asking the listener to tap anything. One attempt each per opening.
    LaunchedEffect(config.id) {
        when (val outcome = com.music.bitchord.data.qobuz.QobuzSilentUnlock.unlock(context)) {
            is com.music.bitchord.data.qobuz.QobuzSilentUnlock.Outcome.Unlocked ->
                currentOnUnlocked.value(outcome.session.sp, outcome.session.token)
            else -> {
                status = UnlockStatus.AutoClicks
                webView?.evaluateJavascript(CLICK_DRIVER, null)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                }
                Text(
                    text = stringResource(R.string.qobuz_unlock_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = when (status) {
                    UnlockStatus.Waiting -> stringResource(R.string.qobuz_unlock_waiting)
                    UnlockStatus.Auto -> stringResource(R.string.qobuz_unlock_auto)
                    UnlockStatus.AutoClicks -> stringResource(R.string.qobuz_unlock_auto_clicks)
                    UnlockStatus.Checking -> stringResource(R.string.qobuz_unlock_checking)
                    is UnlockStatus.Error -> (status as UnlockStatus.Error).message
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (status is UnlockStatus.Error) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Button(onClick = {
                        status = UnlockStatus.Checking
                        webView?.evaluateJavascript(ritual(), null)
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.qobuz_unlock_retry))
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            // The session lives in localStorage (`fd_sp`) —
                            // without DOM storage there is nothing to resume.
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            addJavascriptInterface(
                                UnlockBridge { raw ->
                                    Handler(Looper.getMainLooper()).post {
                                        handleUnlockResult(raw, { status = it }, currentOnUnlocked.value)
                                    }
                                },
                                BRIDGE,
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // The charts page is where a download (and
                                    // its session) starts; the silent attempt
                                    // runs headless in parallel, so landing
                                    // here first costs nothing.
                                    if (status == UnlockStatus.AutoClicks) {
                                        postDelayed({ view?.evaluateJavascript(CLICK_DRIVER, null) }, 4000)
                                    } else if (status != UnlockStatus.Auto) {
                                        status = UnlockStatus.Checking
                                        postDelayed({ view?.evaluateJavascript(ritual(), null) }, 4000)
                                    }
                                }
                            }
                            webView = this
                            loadUrl("https://flacdownloader.com/top")
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private sealed interface UnlockStatus {
    data object Waiting : UnlockStatus
    data object Auto : UnlockStatus
    data object AutoClicks : UnlockStatus
    data object Checking : UnlockStatus
    data class Error(val message: String) : UnlockStatus
}

private class UnlockBridge(private val onRaw: (String) -> Unit) {
    @JavascriptInterface
    fun onResult(raw: String) = onRaw(raw)
}

private fun handleUnlockResult(
    raw: String,
    setStatus: (UnlockStatus) -> Unit,
    onUnlocked: (sp: String, token: String) -> Unit,
) {
    val obj = runCatching { JSONObject(raw) }.getOrNull()
    val token = obj?.optString("t").orEmpty()
    val sp = obj?.optString("sp").orEmpty()
    if (token.isNotBlank() && sp.isNotBlank()) {
        onUnlocked(sp, token)
        return
    }
    val error = obj?.optString("error").orEmpty()
    setStatus(
        UnlockStatus.Error(
            when {
                error.startsWith("challenge") ->
                    "The browser check didn't pass. Tap the checkbox if you see one, then retry."
                error == "token" -> "The site issued no token. Retry in a moment."
                error.startsWith("challenge-timeout") ->
                    "The check timed out. Retry — it usually passes on the second attempt."
                error == "no-descargar" ->
                    "No download button found on the charts page — the site may have changed. Tap through yourself."
                error == "no-quality" ->
                    "No quality button appeared after opening the track. Tap a quality yourself, then retry."
                error == "manual" ->
                    "Automatic taps didn't mint a session. Tap a download yourself, then retry."
                else -> "Unlock failed ($error). Retry."
            },
        ),
    )
}


/**
 * The site's own `Ao()` ritual, minus the React state: resume `fd_sp`,
 * else Turnstile response → `POST /prepare`, bridge `{t, sp}` out.
 * Runs in page context so cookies, storage and origin all match a real visit.
 *
 * Single copy lives in [QobuzSilentUnlock][com.music.bitchord.data.qobuz.QobuzSilentUnlock];
 * the headless unlocker runs this same text with its own bridge name.
 */
private const val BRIDGE = "QobuzUnlock"

private fun ritual(): String =
    com.music.bitchord.data.qobuz.QobuzSilentUnlock.ritualJs(BRIDGE)

/**
 * Click-through fallback for when the silent ritual cannot pass: drives
 * the charts page the way a listener would — first Descargar, then a
 * quality button — then runs the session ritual and bridges the result.
 * Text-matched in Spanish and English, bounded (~2 min), and always
 * bridges something: a hang here would strand the sheet with no way out.
 */
private const val CLICK_DRIVER = """
(function(){
  function done(o){ QobuzUnlock.onResult(JSON.stringify(o)); }
  function txt(el){ return ((el.innerText||el.textContent)||'').trim(); }
  function findClick(rx){
    var els = document.querySelectorAll('a,button,[role="button"]');
    for (var i=0;i<els.length;i++){
      var t = txt(els[i]);
      if (t && rx.test(t) && els[i].offsetParent !== null) return els[i];
    }
    return null;
  }
  function waitFor(rx, ms){
    return new Promise(function(res){
      var t0 = Date.now();
      var iv = setInterval(function(){
        var el = null;
        try { el = findClick(rx); } catch(e){}
        if (el){ clearInterval(iv); res(el); }
        else if (Date.now()-t0 > ms){ clearInterval(iv); res(null); }
      }, 1000);
    });
  }
  (async function(){
    try {
      var first = await waitFor(/descargar|download/i, 30000);
      if (!first){ done({error:'no-descargar'}); return; }
      first.click();
      await new Promise(function(r){ setTimeout(r, 8000); });
      var q = await waitFor(/flac|lossless|24-bit|16-bit|mp3|320|calidad|quality/i, 45000);
      if (!q){ done({error:'no-quality'}); return; }
      q.click();
      await new Promise(function(r){ setTimeout(r, 10000); });
      var sp=''; try{sp=localStorage.getItem('fd_sp')||'';}catch(e){}
      if(sp){
        var o=await fetch('/prepare',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sp:sp})});
        if(o.ok){var l=await o.json(); if(l&&l.t){ done({t:l.t,sp:(l.sp||sp)}); return; } }
      }
      done({error:'manual'});
    }catch(e){ done({error:String((e&&e.message)||e)}); }
  })();
})();
"""
