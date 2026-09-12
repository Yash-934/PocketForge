package com.pocketforge.mobile.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketforge.mobile.ui.theme.PocketOrange
import com.pocketforge.mobile.webchat.DetectedFileChange
import com.pocketforge.mobile.webchat.PromptContextMode
import com.pocketforge.mobile.webchat.WebChatAutomationEngine
import com.pocketforge.mobile.webchat.WebChatPromptBuilder
import com.pocketforge.mobile.webchat.WebChatProvider
import com.pocketforge.mobile.webchat.WebChatResponseParser
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class CompanionTab(val title: String) {
    BROWSER("Web Chat"),
    PROMPT("Prepared Prompt"),
    IMPORT("Import Response"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebChatCompanionSheet(
    state: AppUiState,
    onClose: () -> Unit,
    onSetProvider: (WebChatProvider) -> Unit,
    onSetCustomUrl: (String) -> Unit,
    onSetPromptMode: (PromptContextMode) -> Unit,
    onUpdateResponseText: (String) -> Unit,
    onToggleFileSelection: (String) -> Unit,
    onApplyChanges: (selectedOnly: Boolean, addToChat: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(CompanionTab.BROWSER) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var pageLoading by rememberSaveable { mutableStateOf(false) }
    var pageProgress by rememberSaveable { mutableIntStateOf(0) }
    var canGoBack by rememberSaveable { mutableStateOf(false) }
    var canGoForward by rememberSaveable { mutableStateOf(false) }
    var isDesktopMode by rememberSaveable { mutableStateOf(false) }
    var autoSending by remember { mutableStateOf(false) }
    var autoExtracting by remember { mutableStateOf(false) }
    var autoActionStatus by remember { mutableStateOf<String?>(null) }
    var copiedRecently by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        dragHandle = null,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header Bar
            CompanionHeader(
                selectedProvider = state.webCompanionProvider,
                onSelectProvider = { provider ->
                    onSetProvider(provider)
                    webViewInstance?.loadUrl(provider.resolveUrl(state.webCompanionCustomUrl))
                },
                customUrl = state.webCompanionCustomUrl,
                onCustomUrlChange = onSetCustomUrl,
                onOpenExternal = {
                    val url = state.webCompanionProvider.resolveUrl(state.webCompanionCustomUrl)
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }.onFailure {
                        Toast.makeText(context, "Could not open browser: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onClose = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onClose() }
                },
            )

            // Policy & Safety Disclosure Banner (strictly informative)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "100% Free • No API Key Needed • One-Tap Auto Code Injection & Extract",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Tab Selector
            SecondaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CompanionTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.title, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal)
                                if (tab == CompanionTab.IMPORT && state.webCompanionDetectedFiles.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                    ) {
                                        Text(
                                            text = "${state.webCompanionDetectedFiles.size}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Tab Content
            when (selectedTab) {
                CompanionTab.BROWSER -> {
                    BrowserTabContent(
                        provider = state.webCompanionProvider,
                        customUrl = state.webCompanionCustomUrl,
                        preparedPrompt = state.webCompanionPreparedPrompt,
                        pageLoading = pageLoading,
                        pageProgress = pageProgress,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        isDesktopMode = isDesktopMode,
                        autoSending = autoSending,
                        autoExtracting = autoExtracting,
                        autoActionStatus = autoActionStatus,
                        detectedFilesCount = state.webCompanionDetectedFiles.size,
                        onToggleDesktopMode = {
                            isDesktopMode = !isDesktopMode
                            webViewInstance?.let { wv ->
                                WebChatAutomationEngine.configureWebView(wv, isDesktopMode)
                                wv.reload()
                            }
                        },
                        onClearWebData = {
                            webViewInstance?.let { wv ->
                                wv.clearCache(true)
                                wv.clearHistory()
                                CookieManager.getInstance().removeAllCookies(null)
                                wv.reload()
                                Toast.makeText(context, "Web cache and cookies cleared", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onWebViewReady = { webViewInstance = it },
                        onPageProgressChange = { loading, progress ->
                            pageLoading = loading
                            pageProgress = progress
                        },
                        onNavigationStateChange = { back, forward, title ->
                            canGoBack = back
                            canGoForward = forward
                            pageTitle = title
                        },
                        onAutoSendPrompt = {
                            val wv = webViewInstance
                            if (wv == null) {
                                Toast.makeText(context, "Web page is initializing…", Toast.LENGTH_SHORT).show()
                                return@BrowserTabContent
                            }
                            autoSending = true
                            autoActionStatus = "⚡ Auto-injecting prompt & sending…"
                            val script = WebChatAutomationEngine.buildAutoInjectAndSendScript(state.webCompanionPreparedPrompt)
                            wv.evaluateJavascript(script) { resultJson ->
                                autoSending = false
                                runCatching {
                                    val cleaned = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                                        JSONObject("{ \"wrapped\": $resultJson }").getString("wrapped")
                                    } else resultJson
                                    val obj = JSONObject(cleaned)
                                    if (obj.optBoolean("success", false)) {
                                        autoActionStatus = "✓ Prompt sent! AI is generating code…"
                                        Toast.makeText(context, "✓ Prompt sent to ${state.webCompanionProvider.displayName}!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val reason = obj.optString("reason", "Could not locate chat input on page.")
                                        autoActionStatus = "Notice: $reason"
                                        Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                                    }
                                }.onFailure {
                                    autoActionStatus = "Injected prompt into page."
                                    Toast.makeText(context, "Injected prompt into web chat!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onAutoExtractCode = {
                            val wv = webViewInstance
                            if (wv == null) {
                                Toast.makeText(context, "Web page is not ready yet", Toast.LENGTH_SHORT).show()
                                return@BrowserTabContent
                            }
                            autoExtracting = true
                            autoActionStatus = "📥 Extracting AI code response from web page…"
                            val script = WebChatAutomationEngine.buildExtractResponseScript()
                            wv.evaluateJavascript(script) { resultJson ->
                                autoExtracting = false
                                runCatching {
                                    val cleaned = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                                        JSONObject("{ \"wrapped\": $resultJson }").getString("wrapped")
                                    } else resultJson
                                    val obj = JSONObject(cleaned)
                                    val fullText = obj.optString("fullText", "")
                                    if (fullText.isNotBlank()) {
                                        onUpdateResponseText(fullText)
                                        val parsed = WebChatResponseParser.parse(fullText)
                                        if (parsed.detectedFiles.isNotEmpty()) {
                                            autoActionStatus = "✓ Extracted ${parsed.detectedFiles.size} file change(s)!"
                                            Toast.makeText(context, "✓ Extracted ${parsed.detectedFiles.size} file(s)! Reviewing Import tab…", Toast.LENGTH_SHORT).show()
                                            selectedTab = CompanionTab.IMPORT
                                        } else {
                                            autoActionStatus = "Extracted reply (No files parsed). Open Import tab."
                                            selectedTab = CompanionTab.IMPORT
                                        }
                                    } else {
                                        autoActionStatus = "No text found on page yet."
                                        Toast.makeText(context, "Could not extract response yet. Please wait for AI to finish typing.", Toast.LENGTH_SHORT).show()
                                    }
                                }.onFailure { err ->
                                    autoActionStatus = "Extraction error: ${err.message}"
                                    Toast.makeText(context, "Extraction error: ${err.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onCopyPrompt = {
                            copyToClipboard(context, state.webCompanionPreparedPrompt)
                            copiedRecently = true
                            Toast.makeText(context, "Prompt copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        onSwitchToImport = {
                            val clipboardText = getClipboardText(context)
                            if (!clipboardText.isNullOrBlank() && state.webCompanionResponseText.isBlank()) {
                                onUpdateResponseText(clipboardText)
                            }
                            selectedTab = CompanionTab.IMPORT
                        },
                        onSwitchToPrompt = {
                            selectedTab = CompanionTab.PROMPT
                        },
                    )
                }

                CompanionTab.PROMPT -> {
                    PromptInspectorTabContent(
                        preparedPrompt = state.webCompanionPreparedPrompt,
                        promptMode = state.webCompanionPromptMode,
                        onSetPromptMode = onSetPromptMode,
                        onCopyPrompt = {
                            copyToClipboard(context, state.webCompanionPreparedPrompt)
                            Toast.makeText(context, "Prompt copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        onOpenWebTab = { selectedTab = CompanionTab.BROWSER }
                    )
                }

                CompanionTab.IMPORT -> {
                    ImportResponseTabContent(
                        responseText = state.webCompanionResponseText,
                        detectedFiles = state.webCompanionDetectedFiles,
                        onUpdateResponseText = onUpdateResponseText,
                        onPasteFromClipboard = {
                            val clip = getClipboardText(context)
                            if (!clip.isNullOrBlank()) {
                                onUpdateResponseText(clip)
                                Toast.makeText(context, "Pasted from clipboard!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onToggleFile = onToggleFileSelection,
                        onApplyChanges = onApplyChanges,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompanionHeader(
    selectedProvider: WebChatProvider,
    onSelectProvider: (WebChatProvider) -> Unit,
    customUrl: String,
    onCustomUrlChange: (String) -> Unit,
    onOpenExternal: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = PocketOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Web Chat Companion",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenExternal,
                        modifier = Modifier.size(36.dp).testTag("companion_open_external_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = "Open in External Browser",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp).testTag("companion_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Provider Selector Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WebChatProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = selectedProvider == provider,
                        onClick = { onSelectProvider(provider) },
                        label = { Text(provider.displayName, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }

            if (selectedProvider == WebChatProvider.CUSTOM) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = onCustomUrlChange,
                    placeholder = { Text("Enter Web AI URL (e.g. http://192.168.1.5:3000)") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserTabContent(
    provider: WebChatProvider,
    customUrl: String,
    preparedPrompt: String,
    pageLoading: Boolean,
    pageProgress: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isDesktopMode: Boolean,
    autoSending: Boolean,
    autoExtracting: Boolean,
    autoActionStatus: String?,
    detectedFilesCount: Int,
    onToggleDesktopMode: () -> Unit,
    onClearWebData: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onPageProgressChange: (Boolean, Int) -> Unit,
    onNavigationStateChange: (Boolean, Boolean, String) -> Unit,
    onAutoSendPrompt: () -> Unit,
    onAutoExtractCode: () -> Unit,
    onCopyPrompt: () -> Unit,
    onSwitchToImport: () -> Unit,
    onSwitchToPrompt: () -> Unit,
) {
    val targetUrl = remember(provider, customUrl) { provider.resolveUrl(customUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Navigation & Quick-Action mini-bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(15.dp),
                            tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            modifier = Modifier.size(15.dp),
                            tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onToggleDesktopMode,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (isDesktopMode) Icons.Default.DesktopMac else Icons.Default.PhoneAndroid,
                            contentDescription = if (isDesktopMode) "Desktop View Active" else "Mobile View Active",
                            modifier = Modifier.size(15.dp),
                            tint = if (isDesktopMode) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onClearWebData,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = "Clear Cache & Reset",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = provider.domain,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                TextButton(
                    onClick = onSwitchToPrompt,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(13.dp), tint = PocketOrange)
                    Spacer(Modifier.width(3.dp))
                    Text("Context", fontSize = 11.sp)
                }
            }
        }

        if (pageLoading && pageProgress < 100) {
            LinearProgressIndicator(
                progress = { pageProgress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = PocketOrange,
            )
        }

        // Live automation status banner
        if (!autoActionStatus.isNullOrBlank() || autoSending || autoExtracting) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (autoSending || autoExtracting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = PocketOrange,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = autoActionStatus.orEmpty(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Android WebView container with full nested scroll support
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        onWebViewReady(this)
                        WebChatAutomationEngine.configureWebView(this, isDesktopMode)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                onPageProgressChange(newProgress < 100, newProgress)
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                onNavigationStateChange(canGoBack(), canGoForward(), title.orEmpty())
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                onPageProgressChange(true, 15)
                                onNavigationStateChange(canGoBack(), canGoForward(), view?.title.orEmpty())
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                onPageProgressChange(false, 100)
                                onNavigationStateChange(canGoBack(), canGoForward(), view?.title.orEmpty())
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.proceed()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    return false
                                }
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                                    view?.context?.startActivity(intent)
                                }
                                return true
                            }
                        }

                        loadUrl(targetUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != targetUrl && targetUrl.isNotBlank()) {
                        webView.loadUrl(targetUrl)
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("web_chat_companion_webview")
            )
        }

        // Bottom Fast-Action Automation Tray (One-Tap Automatic Coding)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Primary Automated Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAutoSendPrompt,
                        enabled = !autoSending,
                        modifier = Modifier.weight(1.1f).testTag("companion_auto_send_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = PocketOrange)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (autoSending) "Sending…" else "⚡ Auto-Send Prompt",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onAutoExtractCode,
                        enabled = !autoExtracting,
                        modifier = Modifier.weight(1.2f).testTag("companion_auto_extract_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (autoExtracting) "Extracting…" else "📥 Extract & Apply",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Secondary Manual Fallback Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onCopyPrompt,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy Raw Prompt", fontSize = 11.sp)
                    }

                    TextButton(
                        onClick = onSwitchToImport,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (detectedFilesCount > 0) "Review $detectedFilesCount Files →" else "Manual Import →",
                            fontSize = 11.sp,
                            fontWeight = if (detectedFilesCount > 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (detectedFilesCount > 0) PocketOrange else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptInspectorTabContent(
    preparedPrompt: String,
    promptMode: PromptContextMode,
    onSetPromptMode: (PromptContextMode) -> Unit,
    onCopyPrompt: () -> Unit,
    onOpenWebTab: () -> Unit,
) {
    val charCount = preparedPrompt.length
    val tokenEstimate = (charCount / 4).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Context & Formatting Mode",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PromptContextMode.entries.forEach { mode ->
                FilterChip(
                    selected = promptMode == mode,
                    onClick = { onSetPromptMode(mode) },
                    label = { Text(mode.title, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            }
        }

        Text(
            text = promptMode.description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Length: $charCount chars (~$tokenEstimate tokens)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onCopyPrompt,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy Prompt", fontSize = 12.sp)
            }
        }

        // Monospace prompt preview
        OutlinedTextField(
            value = preparedPrompt,
            onValueChange = {},
            readOnly = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onOpenWebTab,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PocketOrange)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Web Chat & Auto-Send")
            }
        }
    }
}

@Composable
private fun ImportResponseTabContent(
    responseText: String,
    detectedFiles: List<DetectedFileChange>,
    onUpdateResponseText: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onToggleFile: (String) -> Unit,
    onApplyChanges: (selectedOnly: Boolean, addToChat: Boolean) -> Unit,
) {
    val selectedCount = detectedFiles.count { it.selected }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Generated Response",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedButton(
                    onClick = onPasteFromClipboard,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("paste_from_clipboard_button")
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste Clipboard", fontSize = 12.sp)
                }
            }
        }

        item {
            OutlinedTextField(
                value = responseText,
                onValueChange = onUpdateResponseText,
                placeholder = {
                    Text(
                        "Extracted response from Claude, DeepSeek, ChatGPT, or Gemini appears here automatically when you tap '📥 Extract & Apply'.",
                        fontSize = 12.sp,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 200.dp)
                    .testTag("companion_response_input"),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                shape = RoundedCornerShape(12.dp),
            )
        }

        if (detectedFiles.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "✓ Detected ${detectedFiles.size} File Changes",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "$selectedCount of ${detectedFiles.size} file(s) selected to apply to project",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(detectedFiles, key = { it.relativePath }) { change ->
                DetectedFileCard(
                    change = change,
                    onToggle = { onToggleFile(change.relativePath) }
                )
            }
        } else if (responseText.isNotBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "No explicit file headers detected. You can insert this reply into your PocketForge chat.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detectedFiles.isNotEmpty()) {
                    Button(
                        onClick = { onApplyChanges(true, true) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.fillMaxWidth().testTag("apply_and_chat_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = PocketOrange)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Apply $selectedCount File(s) & Add to Chat", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { onApplyChanges(true, false) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.fillMaxWidth().testTag("apply_files_only_button"),
                    ) {
                        Text("Apply File(s) Only (Without Chat Entry)")
                    }
                }

                Button(
                    onClick = { onApplyChanges(false, true) },
                    enabled = responseText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("add_to_chat_only_button"),
                    colors = if (detectedFiles.isEmpty()) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text(if (detectedFiles.isEmpty()) "Insert into Chat Conversation" else "Insert into Chat Only")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetectedFileCard(
    change: DetectedFileChange,
    onToggle: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (change.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).clickable(onClick = onToggle)
                ) {
                    Checkbox(
                        checked = change.selected,
                        onCheckedChange = { onToggle() }
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = change.relativePath,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${change.language.uppercase()} • ${change.lineCount} lines",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse preview" else "Expand preview",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(change.content.lines().take(60)) { line ->
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("PocketForge Prompt", text)
    clipboard?.setPrimaryClip(clip)
}

private fun getClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val item = clipboard?.primaryClip?.getItemAt(0)
    return item?.text?.toString()
}
