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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var status by remember { mutableStateOf<UnlockStatus>(UnlockStatus.Waiting) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentOnUnlocked = rememberUpdatedState(onUnlocked)

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
                        webView?.evaluateJavascript(UNLOCK_RITUAL, null)
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
                                    status = UnlockStatus.Checking
                                    // The widget renders on load; give it a
                                    // beat before reading the response.
                                    postDelayed({ view?.evaluateJavascript(UNLOCK_RITUAL, null) }, 4000)
                                }
                            }
                            webView = this
                            loadUrl("https://flacdownloader.com/")
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
                else -> "Unlock failed ($error). Retry."
            },
        ),
    )
}


/**
 * The site's own `Ao()` ritual, minus the React state: resume `fd_sp`,
 * else Turnstile response → `POST /prepare`, bridge `{t, sp}` out.
 * Runs in page context so cookies, storage and origin all match a real visit.
 */
private const val BRIDGE = "QobuzUnlock"

private const val UNLOCK_RITUAL = """
(function(){
  function done(o){ QobuzUnlock.onResult(JSON.stringify(o)); }
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
