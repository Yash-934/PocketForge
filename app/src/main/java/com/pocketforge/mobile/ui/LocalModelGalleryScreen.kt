package com.pocketforge.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.mobile.localmodel.LocalModelMetadata
import com.pocketforge.mobile.localmodel.gallery.DownloadState
import com.pocketforge.mobile.localmodel.gallery.GalleryCategory
import com.pocketforge.mobile.localmodel.gallery.GalleryModelItem
import com.pocketforge.mobile.localmodel.gallery.HuggingFaceCatalog

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalModelGalleryScreen(
    state: AppUiState,
    onBack: (() -> Unit)? = null,
    onStartDownload: (GalleryModelItem) -> Unit,
    onPauseDownload: (String) -> Unit,
    onCancelDownload: (String, String) -> Unit,
    onLoadModel: (LocalModelMetadata) -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onVerifySha256: (String) -> Unit,
    onImportLocalModel: (Uri) -> Unit,
    onAddCustomModel: (String) -> Result<GalleryModelItem>,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(GalleryCategory.ALL) }
    var pendingLargeDownloadItem by remember { mutableStateOf<GalleryModelItem?>(null) }
    var pendingDeleteModel by remember { mutableStateOf<LocalModelMetadata?>(null) }
    var showCustomUrlDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImportLocalModel(it) }
    }

    // Combine curated catalog with any custom models added by the user
    val allCatalogModels = remember(state.customGalleryModels) {
        HuggingFaceCatalog.curatedModels + state.customGalleryModels
    }

    // Filtered models
    val filteredModels = remember(allCatalogModels, state.localModels, searchQuery, selectedCategory) {
        val query = searchQuery.trim().lowercase()
        val baseList = when (selectedCategory) {
            GalleryCategory.ALL -> allCatalogModels
            GalleryCategory.DOWNLOADED -> {
                // Return catalog items matching downloaded models or virtual items for local imports
                val downloadedFileNames = state.localModels.map { it.fileName.lowercase() }.toSet()
                allCatalogModels.filter { it.fileName.lowercase() in downloadedFileNames }
            }
            else -> allCatalogModels.filter { it.category == selectedCategory }
        }

        if (query.isBlank()) {
            baseList
        } else {
            baseList.filter { item ->
                item.name.lowercase().contains(query) ||
                    item.repoId.lowercase().contains(query) ||
                    item.tags.any { it.lowercase().contains(query) } ||
                    item.description.lowercase().contains(query) ||
                    item.quantization.lowercase().contains(query)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // === HEADER BAR ===
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("gallery_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Model Gallery",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Hugging Face GGUF",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text(
                                text = "High-efficiency local models for 100% offline edge execution",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row {
                        // Import file button
                        IconButton(
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.testTag("gallery_import_file_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Import local GGUF file",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        // Custom Hugging Face URL button
                        IconButton(
                            onClick = { showCustomUrlDialog = true },
                            modifier = Modifier.testTag("gallery_add_hf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add custom Hugging Face model",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search coding, reasoning, fast models, tags...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gallery_search_input")
                )

                Spacer(Modifier.height(10.dp))

                // Category Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(GalleryCategory.entries) { category ->
                        val isSelected = selectedCategory == category
                        val count = when (category) {
                            GalleryCategory.ALL -> allCatalogModels.size
                            GalleryCategory.DOWNLOADED -> state.localModels.size
                            else -> allCatalogModels.count { it.category == category }
                        }

                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = category },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = category.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("${category.label} ($count)")
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("filter_chip_${category.name.lowercase()}")
                        )
                    }
                }
            }
        }

        // === DEVICE SYSTEM STATUS BANNER ===
        DeviceResourceBanner(state = state)

        // === MODEL LIST ===
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
            }

            if (filteredModels.isEmpty()) {
                item {
                    EmptyGalleryState(category = selectedCategory, query = searchQuery)
                }
            } else {
                items(filteredModels, key = { it.id }) { item ->
                    val matchingInstalled = state.localModels.firstOrNull {
                        it.fileName.equals(item.fileName, ignoreCase = true) ||
                            it.name.equals(item.name, ignoreCase = true)
                    }
                    val isLoaded = matchingInstalled != null && state.activeLocalModel?.id == matchingInstalled.id && state.isLocalModelLoaded
                    val downloadState = state.galleryDownloadStates[item.id] ?: DownloadState.Idle

                    GalleryModelCard(
                        item = item,
                        installedModel = matchingInstalled,
                        isLoaded = isLoaded,
                        downloadState = downloadState,
                        onDownloadClick = {
                            if (item.isLargeModel || item.sizeBytes >= 1_500_000_000L) {
                                pendingLargeDownloadItem = item
                            } else {
                                onStartDownload(item)
                            }
                        },
                        onPauseClick = { onPauseDownload(item.id) },
                        onResumeClick = { onStartDownload(item) },
                        onCancelClick = { onCancelDownload(item.id, item.fileName) },
                        onLoadClick = { matchingInstalled?.let { onLoadModel(it) } },
                        onUnloadClick = onUnloadModel,
                        onDeleteClick = { matchingInstalled?.let { pendingDeleteModel = it } },
                        onVerifySha256 = { matchingInstalled?.let { onVerifySha256(it.id) } },
                    )
                }
            }

            // Local imported models that aren't part of standard catalog
            if (selectedCategory == GalleryCategory.ALL || selectedCategory == GalleryCategory.DOWNLOADED) {
                val catalogFileNames = allCatalogModels.map { it.fileName.lowercase() }.toSet()
                val extraLocalModels = state.localModels.filter { it.fileName.lowercase() !in catalogFileNames }

                if (extraLocalModels.isNotEmpty()) {
                    item {
                        Text(
                            text = "Other Local Imports",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(extraLocalModels, key = { it.id }) { localModel ->
                        val isLoaded = state.activeLocalModel?.id == localModel.id && state.isLocalModelLoaded
                        LocalImportedModelCard(
                            metadata = localModel,
                            isLoaded = isLoaded,
                            onLoadClick = { onLoadModel(localModel) },
                            onUnloadClick = onUnloadModel,
                            onDeleteClick = { pendingDeleteModel = localModel },
                            onVerifySha256 = { onVerifySha256(localModel.id) },
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // === LARGE MODEL DOWNLOAD CONFIRMATION DIALOG ===
    pendingLargeDownloadItem?.let { item ->
        LargeModelWarningDialog(
            item = item,
            state = state,
            onConfirm = {
                val toDownload = pendingLargeDownloadItem
                pendingLargeDownloadItem = null
                toDownload?.let { onStartDownload(it) }
            },
            onDismiss = { pendingLargeDownloadItem = null }
        )
    }

    // === DELETE CONFIRMATION DIALOG ===
    pendingDeleteModel?.let { model ->
        DeleteConfirmationDialog(
            model = model,
            onConfirm = {
                val id = model.id
                pendingDeleteModel = null
                onDeleteModel(id)
            },
            onDismiss = { pendingDeleteModel = null }
        )
    }

    // === ADD CUSTOM HUGGING FACE URL DIALOG ===
    if (showCustomUrlDialog) {
        CustomHuggingFaceDialog(
            onDismiss = { showCustomUrlDialog = false },
            onAdd = { url ->
                val res = onAddCustomModel(url)
                if (res.isSuccess) {
                    showCustomUrlDialog = false
                }
                res
            }
        )
    }
}

@Composable
private fun DeviceResourceBanner(state: AppUiState) {
    val report = state.localModelResourceReport
    if (report == null) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "RAM: ${report.formattedAvailableRam} / ${report.formattedTotalRam}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = if (report.isStorageSufficient) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Free Disk: ${report.formattedAvailableStorage}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (report.isStorageSufficient) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryModelCard(
    item: GalleryModelItem,
    installedModel: LocalModelMetadata?,
    isLoaded: Boolean,
    downloadState: DownloadState,
    onDownloadClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit,
    onLoadClick: () -> Unit,
    onUnloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onVerifySha256: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gallery_model_card_${item.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Row 1: Name and Category badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.repoId,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Category pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (item.category) {
                                GalleryCategory.CODING -> Color(0xFF1B5E20).copy(alpha = 0.15f)
                                GalleryCategory.REASONING -> Color(0xFF0D47A1).copy(alpha = 0.15f)
                                GalleryCategory.FAST -> Color(0xFFE65100).copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item.category.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (item.category) {
                            GalleryCategory.CODING -> Color(0xFF2E7D32)
                            GalleryCategory.REASONING -> Color(0xFF1565C0)
                            GalleryCategory.FAST -> Color(0xFFEF6C00)
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Row 2: Specifications Chips (Size, Quantization, Context, RAM recommendation)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SpecBadge(label = "Size", value = item.formattedSize, highlight = item.isLargeModel)
                SpecBadge(label = "Quant", value = item.quantization)
                SpecBadge(label = "Context", value = "${item.contextLength / 1024}k tokens")
                SpecBadge(label = "Rec RAM", value = item.formattedRecommendedRam)
                SpecBadge(label = "Min RAM", value = item.formattedMinimumRam)
            }

            Spacer(Modifier.height(10.dp))

            // Description
            Text(
                text = item.description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            // Tags
            if (item.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.take(4).forEach { tag ->
                        Text(
                            text = "#$tag",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Status and Actions
            when {
                // Currently downloading or paused
                downloadState is DownloadState.Downloading -> {
                    DownloadingSection(
                        state = downloadState,
                        onPause = onPauseClick,
                        onResume = onResumeClick,
                        onCancel = onCancelClick
                    )
                }
                // Verifying SHA-256 and GGUF
                downloadState is DownloadState.Verifying -> {
                    VerifyingSection(state = downloadState)
                }
                // Download failed
                downloadState is DownloadState.Failed -> {
                    FailedSection(
                        error = downloadState.error,
                        onRetry = onDownloadClick,
                        onCancel = onCancelClick
                    )
                }
                // Installed locally on device
                installedModel != null -> {
                    InstalledSection(
                        model = installedModel,
                        isLoaded = isLoaded,
                        onLoad = onLoadClick,
                        onUnload = onUnloadClick,
                        onDelete = onDeleteClick,
                        onVerifySha = onVerifySha256
                    )
                }
                // Not downloaded yet
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Ready to download (${item.formattedSize})",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = onDownloadClick,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("download_button_${item.id}")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecBadge(label: String, value: String, highlight: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (highlight) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$label: ",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DownloadingSection(
    state: DownloadState.Downloading,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isPaused) {
                    Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFFEF6C00), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paused (${state.formattedProgress})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                } else {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Downloading: ${state.formattedSpeed}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            Text(
                text = "${(state.fraction * 100).toInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (state.isPaused) Color(0xFFEF6C00) else MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.formattedProgress,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row {
                if (state.isPaused) {
                    TextButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resume")
                    }
                } else {
                    TextButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pause")
                    }
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun VerifyingSection(state: DownloadState.Verifying) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Integrity & Safety Verification",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = state.message,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun FailedSection(
    error: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Verification or Download Failed", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(4.dp))
        Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) {
                Text("Dismiss", color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun InstalledSection(
    model: LocalModelMetadata,
    isLoaded: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onVerifySha: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isLoaded) Color(0xFF1B5E20).copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isLoaded) Icons.Default.CheckCircle else Icons.Default.Download,
                    contentDescription = null,
                    tint = if (isLoaded) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isLoaded) "Active in RAM (Offline Ready)" else "Downloaded on Device",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLoaded) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                )
            }

            Row {
                IconButton(onClick = onVerifySha, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Verify SHA-256",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Model",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SHA-256: ${model.formattedShortSha}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isLoaded) {
                OutlinedButton(
                    onClick = onUnload,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Unload RAM", fontSize = 11.sp)
                }
            } else {
                Button(
                    onClick = onLoad,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Load Model", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun LocalImportedModelCard(
    metadata: LocalModelMetadata,
    isLoaded: Boolean,
    onLoadClick: () -> Unit,
    onUnloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onVerifySha256: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(metadata.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(metadata.fileName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Custom GGUF", fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }

            Spacer(Modifier.height(8.dp))

            InstalledSection(
                model = metadata,
                isLoaded = isLoaded,
                onLoad = onLoadClick,
                onUnload = onUnloadClick,
                onDelete = onDeleteClick,
                onVerifySha = onVerifySha256
            )
        }
    }
}

@Composable
private fun EmptyGalleryState(category: GalleryCategory, query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (query.isNotBlank()) "No models found matching '$query'" else "No models in ${category.label}",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (category == GalleryCategory.DOWNLOADED) "Download a model from the gallery or import a .gguf file from your storage." else "Try searching with different terms or add a custom Hugging Face model URL.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun LargeModelWarningDialog(
    item: GalleryModelItem,
    state: AppUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val report = state.localModelResourceReport

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(32.dp))
        },
        title = {
            Text("Large Model Download Warning", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "You are about to download ${item.name} (${item.formattedSize}).",
                    fontSize = 14.sp
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Download Size:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.formattedSize, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Recommended RAM:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.formattedRecommendedRam, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (report != null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Your Free Disk:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(report.formattedAvailableStorage, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Your Total RAM:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(report.formattedTotalRam, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "We strongly recommend downloading over an unmetered Wi-Fi connection and keeping your device charging.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Confirm & Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DeleteConfirmationDialog(
    model: LocalModelMetadata,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
        },
        title = {
            Text("Delete Model", fontWeight = FontWeight.Bold)
        },
        text = {
            Text("Are you sure you want to delete '${model.name}' (${model.formattedSize})? The local .gguf file will be permanently removed from device storage.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CustomHuggingFaceDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Result<GalleryModelItem>,
) {
    var urlInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text("Add from Hugging Face", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter a direct Hugging Face resolve URL or model path pointing to a .gguf file:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        errorMessage = null
                    },
                    placeholder = { Text("https://huggingface.co/user/repo/resolve/main/model.gguf") },
                    singleLine = false,
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(errorMessage!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                Text(
                    text = "Example format:\nQwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val res = onAdd(urlInput)
                    if (res.isFailure) {
                        errorMessage = res.exceptionOrNull()?.message ?: "Invalid Hugging Face model URL"
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Add to Gallery")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
