package com.swarm.ai.account

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.webkit.WebSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Shares the production UI and server routing; no JavaScript-to-native bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebWorkspace(onCompanion: () -> Unit, onNativeAccount: () -> Unit) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var failed by remember { mutableStateOf(false) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooser=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result -> fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode,result.data));fileCallback=null }
    fun external(uri: Uri) { if(uri.scheme in listOf("https", "mailto", "tel")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
    BackHandler { if(web?.canGoBack()==true)web?.goBack() else onNativeAccount() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={failed=false;web?.loadUrl("https://www.swarmgpt.online/app")}){Text("SWARM")};TextButton(onClick=onCompanion){Text("Connect PC")};TextButton(onClick=onNativeAccount){Text("Native workspace")}}
        if(failed){Column(Modifier.padding(24.dp)){Text("Connection unavailable. Your cloud history stays on your account.");Button(onClick={failed=false;web?.loadUrl("https://www.swarmgpt.online/app")}){Text("Retry")}}}
        AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={ctx -> WebView(ctx).apply {
            web=this
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            webChromeClient=object:WebChromeClient(){
                override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean {
                    fileCallback?.onReceiveValue(null);fileCallback=callback
                    return try {chooser.launch(params.createIntent());true}catch(_:Exception){fileCallback?.onReceiveValue(null);fileCallback=null;false}
                }
            }
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    val uri=request.url
                    if(uri.scheme=="https" && uri.host in listOf("www.swarmgpt.online","swarmgpt.online"))return false
                    if(request.isForMainFrame)external(uri)
                    return request.isForMainFrame
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:android.webkit.WebResourceError){if(request.isForMainFrame)failed=true}
            }
            setDownloadListener { url,_,_,_,_ -> external(Uri.parse(url)) }
            loadUrl("https://www.swarmgpt.online/app")
        }})
    }
    DisposableEffect(Unit){onDispose{fileCallback?.onReceiveValue(null);fileCallback=null;web?.stopLoading();web?.destroy();web=null}}
}
