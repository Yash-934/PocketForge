package com.pocketforge.mobile.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.DisposableEffect
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
import com.pocketforge.mobile.webchat.DetectedFileChange
import com.pocketforge.mobile.webchat.PromptContextMode
import com.pocketforge.mobile.webchat.WebChatPromptBuilder
import com.pocketforge.mobile.webchat.WebChatProvider
import kotlinx.coroutines.launch

private enum class CompanionTab(val title: String) {
    BROWSER("Web Chat"),
    PROMPT("Prepared Prompt"),
    IMPORT("Import Response"),
}

private const val MODERN_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.88 Mobile Safari/537.36"

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
    var copiedRecently by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        dragHandle = null,
        modifier = Modifier.fillMaxSize().imePadding(),
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
                        text = "Manual Login Safe • No API key • Zero credential extraction • Copy/paste workflow",
                        fontSize = 11.sp,
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
                        copiedRecently = copiedRecently,
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
                        onCopyPrompt = {
                            copyToClipboard(context, state.webCompanionPreparedPrompt)
                            copiedRecently = true
                            Toast.makeText(context, "Prompt copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        onSwitchToImport = {
                            // Check if clipboard contains text and offer to populate
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
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

            Spacer(Modifier.height(8.dp))

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
    copiedRecently: Boolean,
    onWebViewReady: (WebView) -> Unit,
    onPageProgressChange: (Boolean, Int) -> Unit,
    onNavigationStateChange: (Boolean, Boolean, String) -> Unit,
    onCopyPrompt: () -> Unit,
    onSwitchToImport: () -> Unit,
    onSwitchToPrompt: () -> Unit,
) {
    val targetUrl = remember(provider, customUrl) { provider.resolveUrl(customUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Navigation mini-bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(16.dp),
                            tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            modifier = Modifier.size(16.dp),
                            tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = provider.domain,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                TextButton(
                    onClick = onSwitchToPrompt,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Prompt", fontSize = 11.sp)
                }
            }
        }

        if (pageLoading && pageProgress < 100) {
            LinearProgressIndicator(
                progress = { pageProgress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        // Android WebView container
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        onWebViewReady(this)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            userAgentString = MODERN_MOBILE_USER_AGENT
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

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
                                onPageProgressChange(true, 10)
                                onNavigationStateChange(canGoBack(), canGoForward(), view?.title.orEmpty())
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                onPageProgressChange(false, 100)
                                onNavigationStateChange(canGoBack(), canGoForward(), view?.title.orEmpty())
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // Keep web AI browsing inside webview for safe manual login
                                return false
                            }
                        }

                        loadUrl(targetUrl)
                    }
                },
                update = { webView ->
                    // If targetUrl changed externally
                    if (webView.url != targetUrl && !targetUrl.isBlank()) {
                        webView.loadUrl(targetUrl)
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("web_chat_companion_webview")
            )
        }

        // Bottom Fast-Action Tray
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCopyPrompt,
                    modifier = Modifier.weight(1f).testTag("companion_quick_copy_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (copiedRecently) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (copiedRecently) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (copiedRecently) "Prompt Copied!" else "1. Copy Prompt",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onSwitchToImport,
                    modifier = Modifier.weight(1f).testTag("companion_quick_import_button"),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "2. Import Reply",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy Prompt")
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Web Chat & Paste")
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Paste AI Response",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedButton(
                    onClick = onPasteFromClipboard,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("paste_from_clipboard_button")
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste from Clipboard", fontSize = 12.sp)
                }
            }
        }

        item {
            OutlinedTextField(
                value = responseText,
                onValueChange = onUpdateResponseText,
                placeholder = {
                    Text(
                        "Paste the response you copied from Claude, ChatGPT, DeepSeek, or Gemini.\n\nCode blocks formatted with file paths (e.g. ```typescript:src/App.tsx) will be auto-detected.",
                        fontSize = 12.sp,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .testTag("companion_response_input"),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                shape = RoundedCornerShape(12.dp),
            )
        }

        if (detectedFiles.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Detected ${detectedFiles.size} File Changes",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "$selectedCount file(s) selected to apply to workspace",
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
                            text = "No explicit file patches detected. You can still insert this reply into your PocketForge chat.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detectedFiles.isNotEmpty()) {
                    Button(
                        onClick = { onApplyChanges(true, true) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.fillMaxWidth().testTag("apply_and_chat_button"),
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
