package com.music.bitchord.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One-tap guided unlock for the Hi-Res source.
 *
 * The downloader backend only issues conversion tokens to sessions that
 * passed its bot challenge, and a challenge needs a human in a real
 * browser — which this sheet is. The user downloads anything once; the
 * site stores its session (`fd_sp`) in this WebView's own storage, and
 * the poll below picks it up the moment it lands. Nothing is typed, no
 * account exists, no credential changes hands: the session is anonymous,
 * and only its token ever leaves the WebView, straight into the API
 * client that will present it.
 *
 * Returning users often unlock instantly: the WebView store persists in
 * app data, so a session captured last week is usually still sitting
 * there when the sheet opens.
 */
@Composable
fun FdlUnlockSheet(
    onUnlocked: (sp: String, cookies: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }

    // The session, if one is already stored from a previous unlock.
    LaunchedEffect(webView, pageReady) {
        val view = webView ?: return@LaunchedEffect
        if (!pageReady || unlocked) return@LaunchedEffect
        while (true) {
            delay(2_000)
            val sp = view.readLocalStorage("fd_sp")
            if (!sp.isNullOrBlank()) {
                unlocked = true
                // The browser context's cookies ride along: edge checks a
                // headless client trips have already been passed here, and
                // the clearance travels with the session — see
                // FlacDownloaderApi.cookiesReader.
                val cookies = runCatching {
                    android.webkit.CookieManager.getInstance()
                        .getCookie("https://flacdownloader.com")
                }.getOrDefault("").orEmpty()
                onUnlocked(sp, cookies)
                return@LaunchedEffect
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Unlock Hi-Res",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = onDismiss) { Text("Done") }
        }
        Text(
            text = if (unlocked) {
                "Unlocked — Hi-Res streams are live."
            } else {
                "Download anything once below. The session is captured " +
                    "automatically — nothing to copy, no account needed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!pageReady) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.width(24.dp))
            }
        }
        AndroidView(
            factory = { context ->
                @SuppressLint("SetJavaScriptEnabled")
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            pageReady = true
                        }
                    }
                    loadUrl("https://flacdownloader.com/en/qobuz")
                    webView = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            enabled = unlocked,
        ) {
            Text("Use Hi-Res")
        }
    }
}

/** localStorage, read across the JS bridge. JSON-quoted, `"null"`, or null. */
private suspend fun WebView.readLocalStorage(key: String): String? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(
            "(function(){try{return localStorage.getItem('$key')}catch(e){return null}})()",
        ) { value ->
            val clean = value
                ?.takeIf { it != "null" }
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.takeIf { it.isNotBlank() }
            cont.resume(clean)
        }
    }
