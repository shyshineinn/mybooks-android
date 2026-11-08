package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.ui.icons.ControlIcon


import com.huangder.lumibooks.ui.components.AppDialogAction
import com.huangder.lumibooks.ui.components.AppDialogActionRole

import com.huangder.lumibooks.domain.model.ActivityDuration
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.data.sync.BookDownloadState
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.RemoteBookFormats
import com.huangder.lumibooks.data.sync.toServerStatus

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.huangder.lumibooks.MainActivity
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookDeleteMode
import com.huangder.lumibooks.domain.model.DEFAULT_APP_ACCENT_HEX
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.components.ConfigurableActivityBack
import com.huangder.lumibooks.ui.components.CloudAwareBookDeleteDialog
import com.huangder.lumibooks.ui.components.EditInputDialog
import com.huangder.lumibooks.ui.components.LiquidGlassDialog
import com.huangder.lumibooks.ui.components.LiquidGlassDialogHost
import com.huangder.lumibooks.ui.components.LiquidGlassIconButton
import com.huangder.lumibooks.ui.components.ReplacingToast
import com.huangder.lumibooks.ui.components.LiquidGlassSurface
import com.huangder.lumibooks.ui.components.InformationCard
import com.huangder.lumibooks.ui.components.LiquidGlassAlertDialog
import com.huangder.lumibooks.ui.components.LiquidGlassButton
import com.huangder.lumibooks.ui.components.LiquidGlassMenuItem
import com.huangder.lumibooks.ui.components.LiquidGlassMenuSpec
import com.huangder.lumibooks.ui.components.LiquidGlassMenuHost
import com.huangder.lumibooks.ui.components.LiquidGlassMorphPopupHost
import com.huangder.lumibooks.ui.components.LiquidGlassTextButton
import com.huangder.lumibooks.ui.components.LocalLiquidGlassMenuHost
import com.huangder.lumibooks.ui.components.ProvideLiquidGlassBackdrop
import com.huangder.lumibooks.ui.components.liquidGlassMenuAnchor
import com.huangder.lumibooks.ui.home.HomeViewModel
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.KaiTi
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalEInkMode
import com.huangder.lumibooks.ui.theme.LocalIsDarkTheme
import com.huangder.lumibooks.ui.theme.effectiveAppTheme
import com.huangder.lumibooks.ui.theme.resolveAppFontFamily
import com.huangder.lumibooks.ui.theme.rememberLiquidGlassCapability
import com.huangder.lumibooks.util.FileUtils
import com.huangder.lumibooks.util.TimeUtils
import com.huangder.lumibooks.ui.icons.directionalIcon
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class BookDetailsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        fun start(context: Context, bookId: String) =
            context.startActivity(Intent(context, BookDetailsActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId))
    }

    @Inject lateinit var dataStoreManager: DataStoreManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.huangder.lumibooks.util.LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        val launchTheme = com.huangder.lumibooks.util.LaunchThemeController.themeSnapshot(this)
        setContent {
            val appTheme by dataStoreManager.appTheme.collectAsState(initial = launchTheme.appTheme)
            val accent by dataStoreManager.appAccentColor.collectAsState(initial = launchTheme.appAccentColor)
            val appFontSize by dataStoreManager.appFontSize.collectAsState(initial = launchTheme.appFontSize)
            val font by dataStoreManager.globalFontMode.collectAsState(initial = launchTheme.globalFontMode)
            val transparency by dataStoreManager.liquidGlassTransparency.collectAsState(initial = launchTheme.liquidGlassTransparency)
            val hdr by dataStoreManager.liquidGlassHdrHighlightEnabled.collectAsState(initial = launchTheme.liquidGlassHdrHighlightEnabled)
            val darkMode by dataStoreManager.darkMode.collectAsState(initial = launchTheme.darkMode)
            val eInk by dataStoreManager.eInkModeEnabled.collectAsState(initial = launchTheme.eInkModeEnabled)
            val cardOutlinesEnabled by dataStoreManager.cardOutlinesEnabled
                .collectAsState(initial = launchTheme.cardOutlinesEnabled)
            val predictiveBack by dataStoreManager.predictiveBackEnabled.collectAsState(initial = launchTheme.predictiveBackEnabled)
            val isDark = if (eInk) false else when (darkMode) { "dark" -> true; "light" -> false; else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
            val capability = rememberLiquidGlassCapability(eInk, LocalView.current)
            val resolvedTheme = effectiveAppTheme(appTheme, capability)
            val motion by dataStoreManager.motionPreference.collectAsState(initial = launchTheme.motionPreference)
            val menuAnimationStyleValue by dataStoreManager.menuAnimationStyle.collectAsState(initial = launchTheme.menuAnimationStyle)
            val liquidGlassStyle by dataStoreManager.liquidGlassStyle
                .collectAsState(initial = launchTheme.liquidGlassStyle)
            val liquidGlassSamplingQuality by dataStoreManager.liquidGlassSamplingQuality
                .collectAsState(initial = launchTheme.liquidGlassSamplingQuality)
            val reduceTransparencyEnabled by dataStoreManager.reduceTransparencyEnabled
                .collectAsState(initial = launchTheme.reduceTransparencyEnabled)
            EBookReaderTheme(
                liquidGlassStyle = liquidGlassStyle,
                liquidGlassSamplingQuality = liquidGlassSamplingQuality,
                reduceTransparencyEnabled = reduceTransparencyEnabled,
                menuAnimationStyle = com.huangder.lumibooks.domain.model.MenuAnimationStyle.fromStoredValue(menuAnimationStyleValue),
                motionPreference = com.huangder.lumibooks.ui.theme.MotionPreference.fromStoredValue(motion),
                darkTheme = isDark,
                dynamicColor = resolvedTheme == "material3",
                appTheme = appTheme,
                lumiBackgroundScene = com.huangder.lumibooks.ui.theme.LumiBackgroundScene.SECONDARY,
                appAccentColor = accent,
                liquidGlassTransparency = transparency,
                liquidGlassHdrHighlightEnabled = hdr && !eInk,
                eInkMode = eInk,
                cardOutlinesEnabled = cardOutlinesEnabled,
                appFontSize = appFontSize,
                globalFontMode = font
            ) {
                ConfigurableActivityBack(predictiveBackEnabled = predictiveBack, onBack = { finish() })
                val dialogBackdrop = rememberLayerBackdrop()
                val controlsBackdrop = rememberLayerBackdrop()
                val activeDialogBackdrop = dialogBackdrop.takeIf { resolvedTheme == "liquid_glass" }
                val activeControlsBackdrop = controlsBackdrop.takeIf { resolvedTheme == "liquid_glass" }
                LiquidGlassDialogHost(modifier = Modifier.fillMaxSize(), backdrop = activeDialogBackdrop) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(activeDialogBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(activeControlsBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                                .background(AppColors.PageBg)
                        )
                        ProvideLiquidGlassBackdrop(activeControlsBackdrop) {
                            LiquidGlassMenuHost(Modifier.fillMaxSize()) {
                                BookDetailsScreen(bookId = bookId, onBack = { finish() }, onOpenReader = { id ->
                                    startActivity(Intent(this@BookDetailsActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BOOK_ID, id))
                                    finish()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

data class BookDetailsUiState(
    val book: Book? = null,
    val totalReadingTime: Long = 0L,
    val activeDays: Int = 0,
    val bookmarkCount: Int = 0,
    val noteCount: Int = 0,
    val isLoading: Boolean = true,
    val review: String = "",
    val reviewRating: Float = 0f,
    val activityDuration: ActivityDuration = ActivityDuration()
)

@dagger.hilt.android.lifecycle.HiltViewModel
class BookDetailsViewModel @Inject constructor(
    private val bookRepository: com.huangder.lumibooks.domain.repository.BookRepository,
    private val readingRepository: com.huangder.lumibooks.domain.repository.ReadingRepository,
    private val dataStoreManager: DataStoreManager
) : androidx.lifecycle.ViewModel() {
    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(BookDetailsUiState())
    val uiState = _uiState.asStateFlow()
    fun load(bookId: String) {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                kotlinx.coroutines.flow.combine(
                    bookRepository.getAllBooks(),
                    readingRepository.getActivityDurationByBookId(bookId),
                    readingRepository.getActiveDaysByBookId(bookId),
                    readingRepository.getBookmarksByBookId(bookId),
                    readingRepository.getNotesByBookId(bookId)
                ) { books, duration, days, bookmarks, notes -> BookDetailsUiState(books.firstOrNull { it.id == bookId }, duration.totalDuration, days, bookmarks.size, notes.count { !it.isInkStroke }, false, activityDuration = duration) },
                dataStoreManager.bookReview(bookId),
                dataStoreManager.bookReviewRating(bookId)
            ) { state, review, rating -> state.copy(review = review, reviewRating = rating) }
                .collect { _uiState.value = it }
        }
    }
}

@Composable
fun BookDetailsScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit,
    detailsViewModel: BookDetailsViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    notesViewModel: MyBooksNotesViewModel = hiltViewModel(),
    editViewModel: MyBooksEditViewModel = hiltViewModel()
) {
    val details by detailsViewModel.uiState.collectAsState()
    val library by homeViewModel.uiState.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var editingMyBooks by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var coverOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toast = remember(context) { ReplacingToast(context) }
    LaunchedEffect(bookId) { detailsViewModel.load(bookId) }
    val book = details.book ?: return
    // Save outside the lazy content so item recycling cannot prune the expanded state.
    var summaryExpanded by rememberSaveable(book.id) { mutableStateOf(false) }
    val metaState by editViewModel.metaState.collectAsState()
    val syncBadge by editViewModel.syncBadge.collectAsState()
    val myBooksSyncing by homeViewModel.myBooksSyncing.collectAsState()
    LaunchedEffect(book.id) {
        editViewModel.loadMeta(book)
    }
    val timelineLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(timelineLifecycle, book.id) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                editViewModel.loadMeta(book, forceRefresh = true)
            }
        }
        timelineLifecycle.lifecycle.addObserver(observer)
        onDispose { timelineLifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(myBooksSyncing) {
        if (!myBooksSyncing && MyBooksConfig.isMyBooks(book)) {
            editViewModel.refreshSyncBadge(book.id)
        }
    }
    val notesState by notesViewModel.uiState.collectAsState()
    LaunchedEffect(book.id, metaState.meta?.hasNotes, metaState.meta?.notesUpdatedAt, metaState.meta?.notesSize) {
        if (metaState.meta != null) notesViewModel.refresh(book, metaState.meta)
    }
    val menuHost = LocalLiquidGlassMenuHost.current
    val selectorScope = rememberCoroutineScope()
    var statusMorphAnchor by remember(book.id) { mutableStateOf<ReadingStatusMorphAnchor?>(null) }
    var statusMorphCloseRequested by remember(book.id) { mutableStateOf(false) }
    var selectorSaving by remember(book.id) { mutableStateOf(false) }
    var statusUpdateToken by remember(book.id) { mutableIntStateOf(0) }
    val selectorStatus = remember(book.id) { mutableStateOf<ReadingStatus?>(null) }
    var locallyUpdatedStatus by remember(book.id) { mutableStateOf<ReadingStatus?>(null) }
    val statusSelectorOpen = statusMorphAnchor != null
    val effectiveReadingStatus = locallyUpdatedStatus
        ?: library.serverReadingStatusByBookId[book.id]
        ?: metaState.meta?.status?.let { ReadingStatus.fromServerStatus(it) }
    LaunchedEffect(library.serverReadingStatusByBookId[book.id]) {
        // 本地结果只做即时回显；同步状态一旦落地就以服务器 override 为准。
        if (locallyUpdatedStatus != null && library.serverReadingStatusByBookId[book.id] != null) {
            locallyUpdatedStatus = null
        }
    }

    fun openReadingStatusSelector(anchor: ReadingStatusMorphAnchor) {
        statusUpdateToken++
        selectorStatus.value = anchor.status
        selectorSaving = false
        statusMorphCloseRequested = false
        statusMorphAnchor = anchor
    }

    fun selectReadingStatus(anchor: ReadingStatusMorphAnchor, target: ReadingStatus, startNewReading: Boolean = false, abandonCurrentReading: Boolean = false) {
        val previous = selectorStatus.value ?: anchor.status
        if (selectorSaving) return
        if (target == effectiveReadingStatus && !startNewReading && !abandonCurrentReading) {
            statusMorphCloseRequested = true
            return
        }
        val token = ++statusUpdateToken
        selectorStatus.value = target
        selectorSaving = true
        homeViewModel.setReadingStatus(book, target, startNewReading, abandonCurrentReading) { success, message ->
            if (token != statusUpdateToken) return@setReadingStatus
            if (success) {
                // Keep the options locked through the feedback beat and the reverse morph.
                locallyUpdatedStatus = target
                editViewModel.loadMeta(book, forceRefresh = true)
                selectorScope.launch {
                    delay(160)
                    if (token == statusUpdateToken && statusMorphAnchor != null) {
                        statusMorphCloseRequested = true
                    }
                }
            } else {
                selectorSaving = false
                selectorStatus.value = previous
                toast.show(message ?: context.getString(R.string.mybooks_edit_save_failed))
            }
        }
    }

    var notesMenuExpanded by remember { mutableStateOf(false) }
    var notesMenuBounds by remember { mutableStateOf(Rect.Zero) }
    var pendingNoteUpload by remember { mutableStateOf<MyBooksPickedNote?>(null) }
    val notePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            notesViewModel.pickNote(uri) { picked -> if (picked != null) pendingNoteUpload = picked }
        }
    }
    val notesReadLabel = stringResource(R.string.mybooks_notes_read)
    val notesSyncReadLabel = stringResource(R.string.mybooks_notes_sync_and_read)
    val notesUploadLabel = stringResource(
        if (notesState.serverHas) R.string.mybooks_notes_update else R.string.mybooks_notes_upload
    )
    val hasNotesMenu = MyBooksConfig.isMyBooks(book) &&
        (notesState.localHas || notesState.serverHas || MyBooksConfig.hasWriteToken())
    val downloadState = library.downloadStates[book.id]
    val downloadInProgress = downloadState is BookDownloadState.Downloading
    // 服务器书若没有可读格式，下载必然失败，入口不展示。
    val serverHasReadableFile = if (MyBooksConfig.isMyBooks(book)) {
        metaState.meta?.files?.none { RemoteBookFormats.isSupported(it.format) } != true
    } else {
        true
    }
    val canDownload = book.isCloudOnly && serverHasReadableFile
    val showMoreMenu = hasNotesMenu || canDownload
    val downloadLabel = stringResource(R.string.download_book_file)
    val downloadProgressLabel = if (downloadInProgress) {
        stringResource(
            R.string.book_download_progress,
            ((downloadState as BookDownloadState.Downloading).progress * 100f).toInt().coerceIn(0, 100)
        )
    } else {
        null
    }
    // 只在本次进入页面后新出现的失败/完成才提示，避免重进时复述上一次结果。
    var notifiedDownloadState by remember(book.id) { mutableStateOf(downloadState) }
    LaunchedEffect(downloadState) {
        val state = downloadState ?: return@LaunchedEffect
        if (state === notifiedDownloadState) return@LaunchedEffect
        notifiedDownloadState = state
        when (state) {
            is BookDownloadState.Failed -> toast.show(state.message)
            is BookDownloadState.Completed -> toast.show(context.getString(R.string.book_download_completed))
            else -> Unit
        }
    }
    val canReadLocal = notesState.localHas && (!notesState.serverHas || notesState.upToDate)
    val noFileLabel = stringResource(R.string.server_meta_no_file)
    val heroFormatLabel = remember(book.format, book.isCloudOnly, metaState.meta, noFileLabel) {
        val hasNoReadableFile = MyBooksConfig.isMyBooks(book) &&
            book.isCloudOnly &&
            metaState.meta?.files?.none { RemoteBookFormats.isSupported(it.format) } == true
        if (hasNoReadableFile) noFileLabel else book.format.name
    }
    val currentFolderId = library.bookFolderLinks.firstOrNull { it.bookId == book.id }?.folderId
    val folderName = currentFolderId?.let { id -> folderPath(library.folders, id).joinToString(" / ") { it.name } }
        ?: stringResource(R.string.library_root)
    val tags = library.bookTagLinks.filter { it.bookId == book.id }.mapNotNull { link -> library.tags.firstOrNull { it.id == link.tagId } }
    var coverTarget by remember { mutableStateOf<Book?>(null) }
    var pendingCrossStorageMove by remember { mutableStateOf<PendingCrossStorageBookMove?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        coverTarget?.let { target -> if (uri != null) homeViewModel.updateCustomCover(target, uri) }
        coverTarget = null
    }

    LaunchedEffect(library.folderMessage) {
        val message = library.folderMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        homeViewModel.clearFolderMessage()
    }

    fun completeBookMove(request: PendingCrossStorageBookMove, allowCrossStorageMove: Boolean) {
        homeViewModel.moveBooksToFolder(
            bookIds = request.bookIds,
            targetFolderId = request.targetFolderId,
            allowCrossStorageMove = allowCrossStorageMove
        ) { success ->
            if (success) {
                moveOpen = false
                Toast.makeText(
                    context,
                    context.getString(R.string.folder_move_success, request.bookIds.size, request.targetName),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun requestBookMove(targetFolderId: String?) {
        val targetName = targetFolderId
            ?.let { id -> library.folders.firstOrNull { it.id == id }?.name }
            ?: context.getString(R.string.library_root)
        val request = PendingCrossStorageBookMove(setOf(book.id), targetFolderId, false, targetName)
        if (homeViewModel.requiresCrossStorageMoveConfirmation(setOf(book.id), targetFolderId)) {
            pendingCrossStorageMove = request
        } else {
            completeBookMove(request, allowCrossStorageMove = false)
        }
    }

    val timelineEvents = remember(metaState.meta) { metaState.meta?.let(::readingTimelineEvents).orEmpty() }
    val openReadingTimeline = { ReadingTimelineActivity.start(context, book.id, book.title) }
    LiquidGlassMorphPopupHost(
        active = statusSelectorOpen,
        content = {
            BookDetailsLayout(
                modifier = Modifier.fillMaxSize().background(AppColors.PageBg),
                topBar = { showHero ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = AppSpace.md, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidGlassIconButton(directionalIcon(AppIcons.ArrowLeft, AppIcons.ArrowRight), stringResource(R.string.back), onBack)
                        Spacer(Modifier.width(AppSpace.sm))
                        if (!showHero) {
                            BookDetailsCover(book, Modifier.size(30.dp, 42.dp), onCover = { coverOpen = true })
                            Spacer(Modifier.width(AppSpace.sm))
                        }
                        Text(
                            if (showHero) stringResource(R.string.book_details) else book.title,
                            fontSize = AppType.Title,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (showMoreMenu) {
                            LiquidGlassIconButton(
                                imageVector = AppIcons.DotsThreeVertical,
                                contentDescription = stringResource(R.string.more_options),
                                onClick = {
                                    val host = menuHost
                                    if (notesMenuExpanded) {
                                        host?.dismiss()
                                    } else if (host != null && notesMenuBounds != Rect.Zero) {
                                        notesMenuExpanded = true
                                        host.show(
                                            LiquidGlassMenuSpec(
                                                anchorBounds = notesMenuBounds,
                                                width = 220.dp,
                                                onDismiss = { notesMenuExpanded = false },
                                                items = buildList {
                                                    if (canDownload) {
                                                        add(
                                                            LiquidGlassMenuItem(
                                                                label = downloadLabel,
                                                                icon = AppIcons.CloudArrowDown,
                                                                subtitle = downloadProgressLabel
                                                            ) {
                                                                notesMenuExpanded = false
                                                                if (!downloadInProgress) {
                                                                    homeViewModel.downloadCloudBook(book)
                                                                }
                                                            }
                                                        )
                                                    }
                                                    if (canReadLocal) {
                                                        add(
                                                            LiquidGlassMenuItem(
                                                                label = notesReadLabel,
                                                                icon = AppIcons.BookOpenText,
                                                                dividerBefore = canDownload
                                                            ) {
                                                                notesMenuExpanded = false
                                                                notesViewModel.readLocal(book.id) {
                                                                    MyBooksNotesActivity.start(context, book.id, book.title)
                                                                }
                                                            }
                                                        )
                                                    } else if (notesState.serverHas) {
                                                        add(
                                                            LiquidGlassMenuItem(
                                                                label = notesSyncReadLabel,
                                                                icon = AppIcons.DownloadSimple,
                                                                dividerBefore = canDownload
                                                            ) {
                                                                notesMenuExpanded = false
                                                                notesViewModel.syncAndRead(book.id) { fallback ->
                                                                    if (fallback) {
                                                                        toast.show(
                                                                            context.getString(
                                                                                R.string.mybooks_notes_synced_fallback
                                                                            )
                                                                        )
                                                                    }
                                                                    MyBooksNotesActivity.start(context, book.id, book.title)
                                                                }
                                                            }
                                                        )
                                                    }
                                                    if (MyBooksConfig.hasWriteToken()) {
                                                        add(
                                                            LiquidGlassMenuItem(
                                                                label = notesUploadLabel,
                                                                icon = AppIcons.UploadSimple,
                                                                dividerBefore = canDownload || canReadLocal || notesState.serverHas
                                                            ) {
                                                                notesMenuExpanded = false
                                                                notePicker.launch(
                                                                    arrayOf(
                                                                        "text/markdown",
                                                                        "text/plain",
                                                                        "application/octet-stream"
                                                                    )
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .liquidGlassMenuAnchor()
                                    .onGloballyPositioned { notesMenuBounds = it.boundsInRoot() },
                                size = 44.dp,
                                iconSize = 20.dp
                            )
                        }
                    }
                },
                hero = { twoPane, compact ->
                    BookHero(
                        book, folderName, heroFormatLabel,
                        compact = compact, twoPane = twoPane,
                        onCover = { coverOpen = true }
                    )
                },
                actions = {
                    FloatingDetailTag(
                        modifier = Modifier.widthIn(max = 480.dp),
                        onDelete = { deleteOpen = true },
                        onEdit = { if (MyBooksConfig.isMyBooks(book)) editingMyBooks = true else editing = true },
                        onMove = { moveOpen = true },
                        onNotes = { context.startActivity(Intent(context, BookNotesActivity::class.java).putExtra(BookNotesActivity.EXTRA_BOOK_ID, book.id)) },
                        onRead = {
                            if (book.isArchived) {
                                Toast.makeText(context, R.string.archive_reader_message, Toast.LENGTH_SHORT).show()
                            } else onOpenReader(book.id)
                        }
                    )
                }
            ) { bottomOverlayHeight ->
                BookDetailsPager(
                    book.id, Modifier.fillMaxSize(),
                    bottomContentPadding = bottomOverlayHeight + AppSpace.md
                ) { page ->
                    when (page) {
                        BookDetailsPage.Information -> {
                            item {
                                ContentInfoCards(
                                    book = book,
                                    folder = folderName,
                                    tags = tags.joinToString { it.name },
                                    metaState = metaState,
                                    notesState = if (MyBooksConfig.isMyBooks(book)) notesState else null,
                                    syncBadge = if (MyBooksConfig.isMyBooks(book)) syncBadge else null,
                                    summaryExpanded = summaryExpanded,
                                    onSummaryExpandedChange = { summaryExpanded = it },
                                    onEdit = { if (MyBooksConfig.isMyBooks(book)) editingMyBooks = true else editing = true },
                                    onTags = { tagsOpen = true },
                                    onMove = { moveOpen = true },
                                    onReview = { BookReviewActivity.start(context, book.id, book.title, book.author, book.coverPath) },
                                    readingStatus = effectiveReadingStatus,
                                    statusMorphActive = statusSelectorOpen,
                                    onReadingStatusClick = { anchor -> openReadingStatusSelector(anchor) }
                                )
                            }
                            if (details.review.isNotBlank() || details.reviewRating > 0f) {
                                item {
                                    ReviewEntryCard(details.review, details.reviewRating) {
                                        BookReviewActivity.start(context, book.id, book.title, book.author, book.coverPath)
                                    }
                                }
                            }
                        }
                        BookDetailsPage.Reading -> {
                            item {
                                MetricGrid(book, details.totalReadingTime, details.activeDays, details.bookmarkCount, details.noteCount)
                                Text(
                                    stringResource(
                                        R.string.activity_duration_breakdown,
                                        com.huangder.lumibooks.ui.components.activityDurationText(details.activityDuration.readingDuration),
                                        com.huangder.lumibooks.ui.components.activityDurationText(details.activityDuration.listeningDuration)
                                    ),
                                    fontSize = AppType.Caption, color = AppColors.TextSecondary,
                                    modifier = Modifier.fillMaxWidth().padding(AppSpace.sm),
                                    textAlign = TextAlign.Center
                                )
                            }
                            metaState.meta?.let { meta ->
                                if (timelineEvents.isNotEmpty() || meta.readCount > 0) {
                                    item { ReadingRecordBlock(meta, timelineEvents, openReadingTimeline) }
                                }
                            }
                            item { com.huangder.lumibooks.ui.statistics.BookReadingArchive(book.id) }
                            item {
                                InformationCard(
                                    contentPadding = PaddingValues(0.dp),
                                    verticalArrangement = Arrangement.Top
                                ) {
                                    if (!book.isCloudOnly) {
                                        DetailRow(stringResource(R.string.book_detail_last_read), dateText(book.lastReadTime), false)
                                    }
                                    DetailRow(
                                        stringResource(R.string.bookmarks_notes),
                                        stringResource(R.string.book_detail_open_annotations),
                                        clickable = true,
                                        onClick = {
                                            context.startActivity(Intent(context, BookNotesActivity::class.java)
                                                .putExtra(BookNotesActivity.EXTRA_BOOK_ID, book.id))
                                        },
                                        showChevron = true
                                    )
                                }
                            }
                        }
                        BookDetailsPage.Files -> item {
                            FileInfoCard(book = book, metaState = metaState, downloadState = downloadState)
                        }
                    }
                }
            }
        },
        popup = {
            statusMorphAnchor?.let { anchor ->
                ReadingStatusMorphOverlay(
                    anchor = anchor,
                    selected = selectorStatus.value ?: anchor.status,
                    saving = selectorSaving,
                    closeRequested = statusMorphCloseRequested,
                    nextRound = remember(anchor) { if (anchor.status == ReadingStatus.DONE) nextReadingRound(metaState.meta) else null },
                    activeRound = remember(anchor) { pendingReadingRound(metaState.meta) },
                    onStartNextReading = { selectReadingStatus(anchor, ReadingStatus.READING, startNewReading = true) },
                    onAbandonReading = { selectReadingStatus(anchor, readingStatusAfterAbandon(metaState.meta), abandonCurrentReading = true) },
                    onSelect = { target -> selectReadingStatus(anchor, target) },
                    onDismiss = {
                        if (!statusMorphCloseRequested) statusMorphCloseRequested = true
                    },
                    onFinished = {
                        statusMorphAnchor = null
                        statusMorphCloseRequested = false
                        selectorSaving = false
                    }
                )
            }
        }
    )

    if (editing) EditBookDialog(book, homeViewModel) { editing = false }
    if (editingMyBooks) {
        MyBooksEditSheet(
            book = book,
            meta = metaState.meta,
            onDismiss = { editingMyBooks = false }
        )
    }
    pendingNoteUpload?.let { note ->
        val replacing = notesState.serverHas
        LiquidGlassAlertDialog(
            onDismissRequest = { if (!notesState.uploading) pendingNoteUpload = null },
            title = {
                Text(
                    stringResource(
                        if (replacing) R.string.mybooks_notes_replace_title
                        else R.string.mybooks_notes_upload_confirm_title
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            R.string.mybooks_notes_new_file,
                            note.displayName,
                            formatNoteFileSize(note.size)
                        )
                    )
                    val existing = notesState.serverUpdatedAt
                    if (replacing && !existing.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.mybooks_notes_existing_hint, formatNoteDate(existing)),
                            color = AppColors.TextSecondary,
                            fontSize = AppType.Caption
                        )
                    }
                }
            },
            confirmButton = {
                LiquidGlassTextButton(
                    text = if (replacing) {
                        stringResource(R.string.mybooks_notes_replace_confirm)
                    } else {
                        stringResource(R.string.mybooks_notes_upload_confirm)
                    },
                    enabled = !notesState.uploading,
                    tintedColor = AppColors.Accent,
                    onClick = {
                        notesViewModel.upload(
                            bookId = book.id,
                            note = note,
                            expectedUpdatedAt = notesState.serverUpdatedAt
                        ) { success, message ->
                            toast.show(message)
                            if (success) pendingNoteUpload = null
                        }
                    }
                )
            },
            dismissButton = {
                LiquidGlassTextButton(
                    text = stringResource(R.string.cancel),
                    enabled = !notesState.uploading,
                    onClick = { pendingNoteUpload = null }
                )
            }
        )
    }
    if (tagsOpen) BookTagBottomSheet(library.tags, library.bookTagLinks.filter { it.bookId == book.id }.map { it.tagId }.toSet(), { tagsOpen = false }, { tag, checked -> homeViewModel.setBookTag(book.id, tag.id, checked) }, { name, parent -> homeViewModel.createAndAssignTag(book.id, name, parent) }, { tag, children -> homeViewModel.deleteTag(tag.id, children) }, bookId = book.id)
    if (moveOpen) FolderMoveSheet(library.folders, 1, currentFolderId, { moveOpen = false }, { name, parent -> homeViewModel.createFolder(name, parent) }) { target -> requestBookMove(target) }
    pendingCrossStorageMove?.let { request ->
        LiquidGlassAlertDialog(
            onDismissRequest = { pendingCrossStorageMove = null },
            titleText = stringResource(R.string.folder_cross_storage_confirmation_title),
            text = { Text(stringResource(R.string.folder_cross_storage_confirmation_message)) },
            actions = listOf(
                AppDialogAction(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        pendingCrossStorageMove = null
                        completeBookMove(request, allowCrossStorageMove = true)
                    },
                    role = AppDialogActionRole.Primary
                ),
                AppDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { pendingCrossStorageMove = null },
                    role = AppDialogActionRole.Cancel
                )
            ))
    }
    if (coverOpen) CustomCoverSourceSheet(book, { coverOpen = false }, { coverOpen = false; coverTarget = book; coverPicker.launch("image/*") }, { coverOpen = false; CoverSearchActivity.start(context, book.id, book.title) })
    if (deleteOpen) CloudAwareBookDeleteDialog(
        bookCount = 1,
        hasRemoteBooks = book.remoteFileName != null,
        hasMyBooksBooks = MyBooksConfig.isMyBooks(book),
        onDismiss = { deleteOpen = false },
        onDelete = { mode: BookDeleteMode ->
            homeViewModel.deleteBook(book, mode)
            deleteOpen = false
            onBack()
        }
    )
}

@Composable
internal fun BookHero(
    book: Book,
    folder: String,
    formatLabel: String,
    compact: Boolean,
    twoPane: Boolean = false,
    onCover: () -> Unit
) {
    val scrollState = rememberScrollState()
    if (twoPane) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverWidth = (maxHeight * 0.26f).coerceIn(56.dp, 104.dp)
            Column(
                Modifier.fillMaxWidth().verticalScroll(scrollState).padding(bottom = AppSpace.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BookDetailsCover(book, Modifier.size(coverWidth, coverWidth * 1.4f), onCover)
                Text(
                    book.title, fontSize = 20.sp, lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    "$formatLabel · ${book.author}", fontSize = AppType.BodySmall,
                    color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    folder, fontSize = AppType.Caption, color = AppColors.TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { book.readingProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(AppRoundedCornerShape(3.dp)),
                    color = AppColors.Accent, trackColor = AppColors.Divider
                )
                Text(
                    "${(book.readingProgress * 100).toInt()}%",
                    fontSize = AppType.Caption, color = AppColors.Accent
                )
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BookDetailsCover(book, Modifier.size(if (compact) 48.dp else 72.dp, if (compact) 68.dp else 100.dp), onCover)
        Spacer(Modifier.width(AppSpace.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, fontSize = if (compact) 20.sp else 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            Text("$formatLabel · ${book.author}", fontSize = AppType.Body, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) Text(folder, fontSize = AppType.Caption, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) {
                LinearProgressIndicator(progress = { book.readingProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(AppRoundedCornerShape(3.dp)), color = AppColors.Accent, trackColor = AppColors.Divider)
                Text("${(book.readingProgress * 100).toInt()}%", fontSize = AppType.Caption, color = AppColors.Accent)
            }
        }
    }
}

@Composable
internal fun BookDetailsCover(book: Book, modifier: Modifier, onCover: () -> Unit) {
    Box(modifier.clip(AppRoundedCornerShape(AppRadius.md)).clickable(onClick = onCover)) {
        if (book.coverPath != null) {
            AsyncImage(
                ImageRequest.Builder(LocalContext.current).data(book.coverPath).build(),
                book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize().background(AppColors.BgGray), Alignment.Center) {
                Text(book.title.take(6), color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable internal fun MetricGrid(book: Book, duration: Long, days: Int, bookmarks: Int, notes: Int) {
    val coreMetrics = listOf(
        stringResource(R.string.book_detail_progress) to AnnotatedString("${(book.readingProgress * 100).toInt()}%"),
        stringResource(R.string.book_detail_reading_time) to compactReadingDuration(duration, AppColors.TextSecondary),
        stringResource(R.string.book_detail_active_days) to AnnotatedString(days.toString())
    )
    val valueStyle = LocalTextStyle.current.copy(
        fontSize = AppType.Section, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
    )
    val labelStyle = LocalTextStyle.current.copy(
        fontSize = AppType.Caption, lineHeight = 16.sp, textAlign = TextAlign.Center
    )
    val auxiliaryStyle = labelStyle.copy(fontSize = AppType.BodySmall, lineHeight = 20.sp)
    val auxiliaryMetrics = listOf(
        stringResource(R.string.book_detail_bookmarks) to bookmarks,
        stringResource(R.string.book_detail_notes) to notes
    ).map { (label, count) ->
        buildAnnotatedString {
            withStyle(SpanStyle(color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)) {
                append(count.toString())
            }
            append(' ')
            withStyle(SpanStyle(fontSize = AppType.Caption)) { append(label) }
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val coreWidths = coreMetrics.map { (label, value) ->
        maxOf(
            textMeasurer.measure(value, style = valueStyle, softWrap = false).size.width,
            textMeasurer.measure(label, style = labelStyle, softWrap = false).size.width
        )
    }
    val auxiliaryWidths = auxiliaryMetrics.map {
        textMeasurer.measure(it, style = auxiliaryStyle, softWrap = false).size.width
    }
    InformationCard(
        title = stringResource(R.string.book_detail_reading_statistics),
        contentPadding = PaddingValues(AppSpace.md),
        verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
    ) {
        ReadingMetricRow(coreWidths) { index, modifier ->
            val (label, value) = coreMetrics[index]
            Column(
                modifier.semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(value, modifier = Modifier.fillMaxWidth(), style = valueStyle, color = AppColors.TextPrimary)
                Text(label, modifier = Modifier.fillMaxWidth(), style = labelStyle, color = AppColors.TextSecondary)
            }
        }
        HorizontalDivider(color = AppColors.Divider)
        ReadingMetricRow(auxiliaryWidths) { index, modifier ->
            Text(
                auxiliaryMetrics[index], modifier = modifier,
                style = auxiliaryStyle, color = AppColors.TextSecondary
            )
        }
    }
}

/** Keep equal columns when every value and translated label fits at the current font scale. */
@Composable private fun ReadingMetricRow(
    requiredWidths: List<Int>,
    content: @Composable (Int, Modifier) -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AppSpace.sm
        val columnWidth = (constraints.maxWidth - with(density) { gap.roundToPx() } * (requiredWidths.size - 1)) / requiredWidths.size
        if (requiredWidths.any { it > columnWidth }) {
            // Stack the entire group rather than leaving an unbalanced final row.
            // Text remains free to wrap in exceptionally narrow windows.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                requiredWidths.indices.forEach { content(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                requiredWidths.indices.forEach { content(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable internal fun ContentInfoCards(
    book: Book,
    folder: String,
    tags: String,
    metaState: MyBooksMetaUiState,
    notesState: MyBooksNotesUiState?,
    syncBadge: MyBooksSyncBadge?,
    summaryExpanded: Boolean,
    onSummaryExpandedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTags: () -> Unit,
    onMove: () -> Unit,
    onReview: () -> Unit,
    readingStatus: ReadingStatus? = null,
    statusMorphActive: Boolean = false,
    onReadingStatusClick: ((ReadingStatusMorphAnchor) -> Unit)? = null
) {
    val serverBook = MyBooksConfig.isMyBooks(book)
    val meta = metaState.meta.takeIf { serverBook }
    val currentStatus = readingStatus ?: meta?.let { ReadingStatus.fromServerStatus(it.status) }
    val statusText = currentStatus?.let { statusDisplay(it.toServerStatus()) }
    val cardGeometry = remember(book.id) { ReadingStatusCardGeometry() }
    val openStatusSelectorAt: (Offset) -> Unit = { local ->
        val targetStatus = currentStatus
        val openSelector = onReadingStatusClick
        val statusBounds = cardGeometry.statusRowBounds
        if (targetStatus != null && openSelector != null && statusBounds != Rect.Zero) {
            openSelector(
                ReadingStatusMorphAnchor(
                    statusRowBounds = statusBounds,
                    originCenter = statusBounds.topLeft + local,
                    status = targetStatus
                )
            )
        }
    }
    val openStatusSelector = {
        val bounds = cardGeometry.statusRowBounds
        openStatusSelectorAt(Offset(bounds.width / 2f, bounds.height / 2f))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpace.md)) {
        if (meta != null && hasMeaningfulSummary(meta.summary)) {
            InformationCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
                SummaryRow(meta.summary.trim(), summaryExpanded, onSummaryExpandedChange)
            }
        }
        InformationCard(
            title = stringResource(R.string.book_detail_group_details),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.Top
        ) {
                DetailRow(stringResource(R.string.book_title_label), book.title, true, onEdit)
                DetailRow(stringResource(R.string.book_author_label), book.author, true, onEdit)
                if (!serverBook) {
                    metaState.meta?.translators?.takeIf { it.isNotEmpty() }?.let {
                        DetailRow(stringResource(R.string.mybooks_edit_field_translators), it.joinToString("、"), false)
                    }
                }
                if (serverBook && meta == null && statusText != null) {
                    // 缓存尚未到达时仍可用同步状态进入三态选择，离线也能改。
                    ReadingStatusDetailRow(
                        label = stringResource(R.string.server_meta_status),
                        value = statusText,
                        clickable = onReadingStatusClick != null,
                        onClick = openStatusSelector,
                        onBounds = { cardGeometry.statusRowBounds = it },
                        hidden = statusMorphActive,
                        onTapAt = openStatusSelectorAt
                    )
                }
                meta?.let {
                    MyBooksDetailRows(
                        meta = it,
                        statusText = statusText,
                        statusClickable = currentStatus != null && onReadingStatusClick != null,
                        statusHidden = statusMorphActive,
                        onStatusClick = openStatusSelector,
                        onStatusTap = openStatusSelectorAt,
                        onStatusBounds = { cardGeometry.statusRowBounds = it }
                    )
                }
        }
        InformationCard(
            title = stringResource(R.string.book_detail_group_library),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.Top
        ) {
            DetailRow(stringResource(R.string.book_detail_folder), folder, true, onMove)
            DetailRow(
                stringResource(R.string.tag_sheet_title),
                tags.ifBlank { stringResource(R.string.book_detail_none) },
                true,
                onTags
            )
            DetailRow(
                stringResource(R.string.book_review),
                stringResource(R.string.book_review_open),
                true,
                onReview
            )
        }
        val showRetry = serverBook && meta == null && !metaState.loading
        val showSync = serverBook && (notesState != null ||
            (syncBadge?.pendingCount ?: 0) > 0 || syncBadge?.lastError != null)
        if (showRetry || showSync) {
            InformationCard(
                title = stringResource(R.string.mybooks_sync_state),
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.Top
            ) {
                if (showRetry) MetaRetryRow(metaState.retry)
                MyBooksSyncRows(notesState, syncBadge)
            }
        }
    }
}

@Composable private fun FileInfoCard(
    book: Book,
    metaState: MyBooksMetaUiState,
    downloadState: BookDownloadState?
) {
    val context = LocalContext.current
    val fileNameText = remember(book.filePath, book.remoteFileName, book.sourceDisplayName) {
        fileName(context, book)
    }
    val hasSupportedServerFile = metaState.meta?.files?.any {
        RemoteBookFormats.isSupported(it.format)
    } == true
    val downloading = downloadState as? BookDownloadState.Downloading
    InformationCard(
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top
    ) {
        if (MyBooksConfig.isMyBooks(book)) {
            MyBooksFileRows(book = book, metaState = metaState)
        }
        if (fileNameText.isNotBlank() && !(MyBooksConfig.isMyBooks(book) && book.isCloudOnly)) {
            DetailRow(stringResource(R.string.book_detail_file_name), fileNameText, false)
        }
        if (!book.isCloudOnly || !MyBooksConfig.isMyBooks(book) || hasSupportedServerFile) {
            DetailRow(stringResource(R.string.book_detail_format), book.format.name, false)
        }
        DetailRow(
            stringResource(R.string.book_detail_storage),
            when {
                downloading != null -> stringResource(
                    R.string.book_download_progress,
                    (downloading.progress * 100f).toInt().coerceIn(0, 100)
                )
                book.isCloudOnly -> stringResource(R.string.book_detail_cloud_only)
                book.remoteFileName != null -> stringResource(R.string.book_detail_synced)
                else -> stringResource(R.string.book_detail_local)
            },
            false
        )
        DetailRow(
            stringResource(R.string.book_detail_file_size),
            remember(book.filePath, book.remoteFileSize) { fileSize(context, book) },
            false
        )
        if (!book.isCloudOnly) {
            DetailRow(stringResource(R.string.book_detail_imported), dateText(book.createdAt), false)
        }
        if (!book.isCloudOnly && book.filePath.isNotBlank()) {
            LocationDetailRow(book.filePath)
        }
    }
}

/**
 * A card of its own so the review stands out. A written review shows three lines and tapping the card
 * expands it in place; the edit button opens the editor. With nothing written, the whole card opens it.
 */
@Composable internal fun ReviewEntryCard(review: String, rating: Float, onEdit: () -> Unit) {
    val shape = AppRoundedCornerShape(AppRadius.lg)
    val (actionColor, onActionColor) = reviewActionColors()
    val hasReview = review.isNotBlank() || rating > 0f
    val fullText = remember(review) { review.trim() }
    val preview = remember(review) { reviewPreviewText(review) }
    var expanded by rememberSaveable(review) { mutableStateOf(false) }
    var truncated by remember(review) { mutableStateOf(false) }
    // The collapsed preview joins paragraphs, so a multi-paragraph review can expand even when it fits.
    val canExpand = truncated || expanded || preview != fullText
    val tint = when {
        hasReview || LocalEInkMode.current -> Color.Transparent
        LocalIsDarkTheme.current -> actionColor.copy(alpha = 0.12f)
        else -> actionColor.copy(alpha = 0.07f)
    }
    val onCardClick: (() -> Unit)? = when {
        preview.isEmpty() -> onEdit
        canExpand -> ({ expanded = !expanded })
        else -> null
    }
    LiquidGlassSurface(shape, AppColors.CardBg, Modifier.fillMaxWidth(), contentScrimColor = Color.Transparent,
        onClick = onCardClick, interactive = false, pressFeedbackEnabled = false) {
        Column(
            Modifier
                .fillMaxWidth()
                // Expanding long reviews in one layout pass avoids resizing the
                // glass render target and relaying out the page on every spring frame.
                .clip(shape)
                .background(tint)
                .padding(AppSpace.md),
            verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.book_review), Modifier.weight(1f), fontSize = AppType.Body, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                ReviewEditButton(hasReview, actionColor, onActionColor, onEdit)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(5) { index ->
                    ReviewStar((rating - index).coerceIn(0f, 1f), reviewStarEmptyColor, reviewStarFillColor, Modifier.size(18.dp))
                    if (index < 4) Spacer(Modifier.width(2.dp))
                }
                if (hasReview) {
                    Spacer(Modifier.width(AppSpace.sm))
                    val label = reviewRatingLabel(rating)
                    val primary = AppColors.TextPrimary
                    val secondary = AppColors.TextSecondary
                    val unrated = stringResource(R.string.book_review_unrated)
                    Text(
                        buildAnnotatedString {
                            if (label == null) {
                                withStyle(SpanStyle(color = secondary)) { append(unrated) }
                            } else {
                                withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) { append(formatReviewScore(rating)) }
                                withStyle(SpanStyle(color = secondary)) { append(" · $label") }
                            }
                        },
                        fontSize = AppType.BodySmall
                    )
                }
            }
            if (preview.isNotEmpty()) {
                ReviewExcerpt(if (expanded) fullText else preview, expanded, actionColor) { truncated = true }
            } else {
                Text(
                    stringResource(if (hasReview) R.string.book_review_hint else R.string.book_review_subtitle),
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary
                )
            }
        }
    }
}

@Composable private fun ReviewEditButton(hasReview: Boolean, actionColor: Color, onActionColor: Color, onClick: () -> Unit) {
    // Solid before the first review, quieter once there is one to edit. Glass keeps a neutral
    // pill for the quiet state: tinting glass at low alpha would wash out the accent text.
    val container = when {
        !hasReview -> actionColor
        LocalAppTheme.current == "liquid_glass" -> null
        else -> actionColor.copy(alpha = 0.12f)
    }
    val content = if (hasReview) actionColor else onActionColor
    LiquidGlassButton(onClick = onClick, tintedColor = container, contentColor = content) {
        ControlIcon(AppIcons.PencilSimple, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(AppSpace.xs))
        Text(
            stringResource(if (hasReview) R.string.book_review_edit else R.string.book_review_write),
            fontSize = AppType.BodySmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable private fun ReviewExcerpt(text: String, expanded: Boolean, accent: Color, onTruncated: () -> Unit) {
    val barColor = accent.copy(alpha = 0.6f)
    Text(
        text,
        onTextLayout = { if (!expanded && it.hasVisualOverflow) onTruncated() },
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val barWidth = 3.dp.toPx()
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - barWidth else 0f
                drawRoundRect(barColor, Offset(x, 0f), Size(barWidth, size.height), CornerRadius(barWidth / 2f))
            }
            .padding(start = 3.dp + AppSpace.sm),
        fontSize = AppType.BodySmall,
        lineHeight = AppType.BodySmall * 1.6f,
        fontFamily = resolveAppFontFamily(KaiTi),
        color = AppColors.TextPrimary,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable private fun LocationDetailRow(filePath: String) {
    val location = readableBookLocation(
        filePath = filePath,
        internalStorageLabel = stringResource(R.string.book_detail_internal_storage),
        unknownLabel = stringResource(R.string.book_detail_unknown)
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpace.md, vertical = 14.dp)
    ) {
        Text(
            stringResource(R.string.book_detail_location),
            fontSize = AppType.Body,
            color = AppColors.TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            location,
            modifier = Modifier.fillMaxWidth(),
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary
        )
    }
}

@Composable internal fun DetailRow(label: String, value: String, clickable: Boolean, onClick: () -> Unit = {}, showChevron: Boolean = false) {
    val textMeasurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labelStyle = TextStyle(fontSize = AppType.Body)
    val valueStyle = TextStyle(fontSize = AppType.BodySmall)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = AppSpace.md, vertical = 14.dp)
    ) {
        val gap = 16.dp
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val labelWidthPx = remember(label, labelStyle) {
            textMeasurer.measure(label, style = labelStyle, maxLines = 1).size.width.toFloat()
        }
        val valueWidthPx = remember(value, valueStyle, showChevron) {
            textMeasurer.measure(value, style = valueStyle, maxLines = 1).size.width.toFloat()
        }
        val chevronWidthPx = if (showChevron) with(density) { 20.dp.toPx() } else 0f
        val stack = shouldStackDetailRow(
            labelWidthPx = labelWidthPx,
            valueWidthPx = valueWidthPx + chevronWidthPx,
            availableWidthPx = availableWidthPx,
            gapWidthPx = with(density) { gap.toPx() }
        )
        if (stack) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = labelStyle, color = AppColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, style = valueStyle, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                    if (showChevron) {
                        ControlIcon(
                            AppIcons.CaretRight,
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = labelStyle, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(gap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, style = valueStyle, color = AppColors.TextSecondary, maxLines = 1)
                    if (showChevron) {
                        ControlIcon(
                            AppIcons.CaretRight,
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldStackDetailRow(
    labelWidthPx: Float,
    valueWidthPx: Float,
    availableWidthPx: Float,
    gapWidthPx: Float
): Boolean = labelWidthPx + valueWidthPx + gapWidthPx > availableWidthPx

@Composable internal fun FloatingDetailTag(modifier: Modifier, onDelete: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onNotes: () -> Unit, onRead: () -> Unit) {
    val isDark = LocalIsDarkTheme.current
    val shape = AppRoundedCornerShape(32.dp)
    LiquidGlassSurface(
        shape,
        AppColors.CardBg,
        modifier.clip(shape),
        controlEdge = true,
        contentScrimColor = if (isDark) Color.Black.copy(alpha = .22f) else Color.White.copy(alpha = .28f)
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TagIcon(AppIcons.Trash, stringResource(R.string.delete), onDelete)
            TagIcon(AppIcons.PencilSimple, stringResource(R.string.book_detail_edit), onEdit)
            TagIcon(AppIcons.FolderSimple, stringResource(R.string.move_to_folder), onMove)
            TagIcon(AppIcons.Bookmark.regular, stringResource(R.string.bookmarks_notes), onNotes)
            ReadTagAction(onRead)
        }
    }
}

@Composable private fun TagIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val isDark = LocalIsDarkTheme.current
    LiquidGlassIconButton(
        icon,
        description,
        onClick,
        size = 46.dp,
        iconSize = 21.dp,
        contentColor = if (isDark) Color.White else Color.Black,
        normalContainerColor = if (isDark) Color(0xFF202023) else Color(0xFFF6F6F8),
        liquidContainerColor = if (isDark) Color(0xFF171719) else Color(0xFFF7F7F9),
        liquidScrimColor = if (isDark) Color.Black.copy(alpha = .34f) else Color.White.copy(alpha = .62f)
    )
}

@Composable private fun ReadTagAction(onClick: () -> Unit) {
    val isDark = LocalIsDarkTheme.current
    LiquidGlassSurface(
        controlEdge = true,
        shape = AppRoundedCornerShape(23.dp),
        fallbackColor = if (isDark) Color(0xFF202023) else Color(0xFFF6F6F8),
        contentScrimColor = if (isDark) Color.Black.copy(alpha = .34f) else Color.White.copy(alpha = .62f),
        onClick = onClick,
        modifier = Modifier.width(92.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.8f)).height(46.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ControlIcon(
                AppIcons.BookOpen,
                contentDescription = null,
                tint = if (isDark) Color.White else Color.Black,
                modifier = Modifier.size(20.dp)
            )
            Text(
                stringResource(R.string.book_detail_read),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isDark) Color.White else Color.Black,
                fontSize = AppType.BodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable private fun EditBookDialog(book: Book, viewModel: HomeViewModel, onDismiss: () -> Unit) {
    LiquidGlassDialog(onDismissRequest = onDismiss, backgroundScrimColor = Color.Transparent) {
        EditInputDialog(stringResource(R.string.edit_book_info), listOf(Triple(stringResource(R.string.book_title_label), "", book.title), Triple(stringResource(R.string.book_author_label), "", book.author)), onDismiss, { values -> viewModel.updateBook(book.copy(title = values.getOrElse(0) { book.title }, author = values.getOrElse(1) { book.author })); onDismiss() })
    }
}

private fun fileSize(context: Context, book: Book): String = when {
    book.remoteFileSize > 0 -> FileUtils.formatFileSize(book.remoteFileSize)
    book.filePath.startsWith("content://") -> queryOpenable(context, book.filePath, OpenableColumns.SIZE)
        ?.toLongOrNull()?.let(FileUtils::formatFileSize) ?: "?"
    else -> runCatching { File(book.filePath).length() }.getOrDefault(0L).takeIf { it > 0 }?.let(FileUtils::formatFileSize) ?: "?"
}

private fun fileName(context: Context, book: Book): String {
    book.remoteFileName?.takeIf { book.isCloudOnly }?.let { return cleanDisplayName(it) }

    // Authorized-folder scans already read the provider's real display name. Prefer this
    // persisted value because some DocumentsProviders return an inconsistent/garbled name
    // when queried again from the detail page.
    book.sourceDisplayName
        ?.takeIf { it.isNotBlank() }
        ?.let { return cleanDisplayName(it) }

    if (!book.filePath.startsWith("content://")) {
        return File(book.filePath).name.ifBlank { book.title }
    }

    queryOpenable(context, book.filePath, OpenableColumns.DISPLAY_NAME)
        ?.takeIf { it.isNotBlank() }
        ?.let { return cleanDisplayName(it) }

    // Never expose the raw content URI as a user-facing file name. A readable title is
    // preferable when a provider temporarily refuses metadata access.
    return book.title.ifBlank { "book" }
}

private fun cleanDisplayName(value: String): String {
    val decoded = runCatching { Uri.decode(value) }.getOrDefault(value)
    return decoded.substringAfterLast('/').substringAfterLast('\\').ifBlank { value }
}

private fun queryOpenable(context: Context, uriString: String, column: String): String? = runCatching {
    context.contentResolver.query(Uri.parse(uriString), arrayOf(column), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
}.getOrNull()

private fun dateText(timestamp: Long): String = if (timestamp <= 0) "-" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun readableBookLocation(filePath: String, internalStorageLabel: String, unknownLabel: String): String {
    if (filePath.isBlank()) return unknownLabel

    val decodedPath = Uri.decode(filePath).replace('\\', '/')
    if (decodedPath.startsWith("content://com.android.externalstorage.documents/")) {
        val documentId = decodedPath.substringAfter("/document/", missingDelimiterValue = "")
        if (documentId.startsWith("primary:")) {
            return "$internalStorageLabel/${documentId.substringAfter(':').trimStart('/')}"
        }
    }

    val normalizedPath = decodedPath.removePrefix("file://")
    val storageRoot = listOf("/storage/emulated/0", "/mnt/sdcard", "/sdcard")
        .firstOrNull { normalizedPath == it || normalizedPath.startsWith("$it/") }
    return if (storageRoot != null) {
        internalStorageLabel + normalizedPath.removePrefix(storageRoot)
    } else {
        normalizedPath
    }
}

private fun compactReadingDuration(durationMillis: Long, unitColor: Color): AnnotatedString {
    val totalMinutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(durationMillis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val values = when {
        hours > 0 -> listOf(hours to "H", minutes to "M")
        minutes > 0 -> listOf(minutes to "M")
        else -> listOf(java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(durationMillis) to "S")
    }
    return buildAnnotatedString {
        values.forEachIndexed { index, (value, unit) ->
            if (index > 0) append(' ')
            append(value.toString())
            withStyle(SpanStyle(fontSize = 10.sp, color = unitColor, fontWeight = FontWeight.Medium)) {
                append(unit)
            }
        }
    }
}

private fun formatNoteFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    return if (bytes >= 1024L * 1024L) {
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    } else {
        String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    }
}

private fun formatNoteDate(raw: String): String {
    return runCatching {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.parse(raw))
    }.getOrElse { raw.take(16) }
}
