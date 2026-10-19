package com.rtorgil.txlv_downloader

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import com.google.common.util.concurrent.ListenableFuture
import com.rtorgil.txlv_downloader.ui.theme.LatestCardBg
import com.rtorgil.txlv_downloader.ui.theme.TXLVDownloaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

class MainActivity : ComponentActivity() {
    private lateinit var audioActionHandler: AudioActionHandler
    private val viewModel: PodcastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioActionHandler = AudioActionHandler(this)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        
        val workRequest = PeriodicWorkRequestBuilder<PodcastWorker>(24, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PodcastWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        
        enableEdgeToEdge()
        setContent {
            TXLVDownloaderTheme {
                if (!viewModel.isSplashFinished) {
                    SplashScreen(viewModel = viewModel) {
                        viewModel.isSplashFinished = true
                    }
                } else {
                    AppContent(viewModel = viewModel, audioActionHandler = audioActionHandler)
                }
            }
        }
    }
}

@Composable
fun SplashScreen(viewModel: PodcastViewModel, onSplashFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 5000),
        label = "progress"
    )

    LaunchedEffect(Unit) {
        viewModel.fetchEpisodes()
        startAnimation = true
        delay(5000L)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.White))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0050A4)))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFFFD100)))
        }
        Image(
            painter = painterResource(id = R.drawable.portada),
            contentDescription = "Splash Screen",
            modifier = Modifier.align(Alignment.Center),
            contentScale = ContentScale.Fit
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .fillMaxWidth(0.5f),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

fun getDownloadFolder(context: Context): String {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getString("download_folder", "Tenerife por la Verdad") ?: "Tenerife por la Verdad"
}

fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

fun saveDownloadFolder(context: Context, folderName: String) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("download_folder", folderName).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    viewModel: PodcastViewModel,
    audioActionHandler: AudioActionHandler
) {
    val episodes by viewModel.episodes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val downloadedIds by viewModel.downloadedIds.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isSharing by remember { mutableStateOf(false) }
    var exoPlayer by remember { mutableStateOf<Player?>(null) }
    var controllerFuture by remember { mutableStateOf<ListenableFuture<MediaController>?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var showExpandedPlayer by rememberSaveable { mutableStateOf(false) }

    var showFolderDialog by remember { mutableStateOf(false) }
    var showSocialDialog by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf("") }
    var newVersionName by remember { mutableStateOf("") }

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("download_tree_uri", uri.toString()).apply()
            Toast.makeText(context, "Carpeta de descargas actualizada", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val response = OkHttpClient().newCall(
                    Request.Builder().url("https://rtorgil.ynh.fr/api/v1/repos/rtorgil/TXLV-Ivoox/releases/latest").build()
                ).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val tagName = json.getString("tag_name").replace("v", "")
                    val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    if (tagName != currentVersion) {
                        val assets = json.getJSONArray("assets")
                        if (assets.length() > 0) {
                            updateUrl = assets.getJSONObject(0).getString("browser_download_url")
                            newVersionName = tagName
                            showUpdateDialog = true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(context) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future

        var playerRef: MediaController? = null
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                isPlaying = isPlayingState
            }
        }

        future.addListener({
            try {
                val controller = future.get()
                playerRef = controller
                controller.addListener(listener)
                exoPlayer = controller
                
                // Restore activeEpisode based on MediaItem so bottom player reappears!
                if (controller.currentMediaItem != null) {
                    val mediaId = controller.currentMediaItem?.mediaId
                    val currentEp = viewModel.episodes.value.find { it.id == mediaId }
                    if (currentEp != null) {
                        viewModel.activeEpisode = currentEp
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            playerRef?.removeListener(listener)
            MediaController.releaseFuture(future)
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            exoPlayer?.let { player ->
                val pos = player.currentPosition
                val duration = player.duration
                if (pos > 0L) {
                    viewModel.activeEpisode?.let { ep ->
                        viewModel.savePosition(ep.id, pos)
                        if (duration > 0) {
                            val progress = pos.toFloat() / duration
                            if (progress > 0.95f) {
                                if (downloadedIds.contains(ep.id) && viewModel.isCleanupAllowed(ep.id)) {
                                    val folderName = getDownloadFolder(context)
                                    val deleted = audioActionHandler.deleteEpisode(ep, folderName)
                                    if (deleted) {
                                        viewModel.removeDownload(ep.id)
                                        viewModel.markCleanupDone(ep.id)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            delay(5000L)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sleepTimerEvent.collect {
            exoPlayer?.pause()
        }
    }

    LaunchedEffect(viewModel.activeEpisode) {
        viewModel.activeEpisode?.let { episode ->
            val folderName = getDownloadFolder(context)
            val localUri = audioActionHandler.getDownloadedEpisodeUri(episode, folderName)
            val uriToPlay = localUri ?: Uri.parse(episode.audioUrl)

            exoPlayer?.setMediaItem(MediaItem.Builder().setUri(uriToPlay).setMediaId(episode.id).build())
            
            val savedPos = viewModel.getSavedPosition(episode.id)
            if (savedPos > 0L) {
                exoPlayer?.seekTo(savedPos)
            }
            
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
    }

    var selectedFilter by remember { mutableStateOf("Todos") }
    var currentScreen by remember { mutableStateOf("Inicio") }

    val filteredEpisodes = remember(episodes, searchQuery, selectedFilter, currentScreen, favoriteIds, downloadedIds) {
        var baseList = episodes.filter {
            (searchQuery.isEmpty() || it.title.contains(searchQuery, ignoreCase = true) || (it.description?.contains(searchQuery, ignoreCase = true) == true))
        }
        
        baseList = when (currentScreen) {
            "Favoritos" -> baseList.filter { favoriteIds.contains(it.id) }
            "Descargas" -> baseList.filter { downloadedIds.contains(it.id) }
            else -> baseList
        }
        
        if (currentScreen == "Inicio") {
            if (selectedFilter == "Este mes") {
                val latestMonthYear = episodes.firstOrNull()?.date?.let { extractMonthYear(it) }
                baseList = if (latestMonthYear != null) {
                    baseList.filter { extractMonthYear(it.date) == latestMonthYear }
                } else {
                    baseList
                }
            }
        }
        baseList
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)),
                actions = {
                    TextButton(onClick = { viewModel.fetchEpisodes() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Actualizar", fontSize = 18.sp)
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Carpeta de descargas") },
                            onClick = { 
                                menuExpanded = false
                                documentTreeLauncher.launch(null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Web iVoox") },
                            onClick = { 
                                menuExpanded = false
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ivoox.com/podcast-tenerife-despierta_sq_f12614052_1.html")))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Redes Sociales") },
                            onClick = { 
                                menuExpanded = false
                                showSocialDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Créditos") },
                            onClick = { 
                                menuExpanded = false
                                showCreditsDialog = true
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (viewModel.activeEpisode != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { showExpandedPlayer = true }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(viewModel.activeEpisode!!.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            IconButton(onClick = {
                                val currentPos = exoPlayer?.currentPosition ?: 0
                                exoPlayer?.seekTo((currentPos - 5000).coerceAtLeast(0))
                            }) {
                                Icon(Icons.Default.Replay5, contentDescription = "Retroceder")
                            }
                            IconButton(onClick = {
                                if (isPlaying) exoPlayer?.pause() else exoPlayer?.play()
                            }) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause")
                            }
                            IconButton(onClick = {
                                val currentPos = exoPlayer?.currentPosition ?: 0
                                val duration = exoPlayer?.duration ?: 0
                                exoPlayer?.seekTo((currentPos + 5000).coerceAtMost(duration))
                            }) {
                                Icon(Icons.Default.Forward5, contentDescription = "Avanzar")
                            }
                            IconButton(onClick = {
                                exoPlayer?.stop()
                                viewModel.activeEpisode = null
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar")
                            }
                        }
                    }
                }
                
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Inicio") },
                        label = { Text("Inicio") },
                        selected = currentScreen == "Inicio",
                        onClick = { currentScreen = "Inicio" }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Download, contentDescription = "Descargas") },
                        label = { Text("Descargas") },
                        selected = currentScreen == "Descargas",
                        onClick = { currentScreen = "Descargas" }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "Favoritos") },
                        label = { Text("Favoritos") },
                        selected = currentScreen == "Favoritos",
                        onClick = { currentScreen = "Favoritos" }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.portada),
                        contentDescription = "Portada",
                        modifier = Modifier.size(48.dp).clip(CircleShape).border(1.dp, Color.Gray, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Tenerife Despierta", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Text("Radio Geneto - Grabaciones", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("Buscar programas...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentScreen == "Inicio") {
                        listOf("Todos", "Este mes").forEach { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = { Text(filter) }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading && episodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (error != null && episodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Error: $error", color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
                    }
                } else if (filteredEpisodes.isNotEmpty()) {
                    val latestEpisode = filteredEpisodes.first()
                    val previousEpisodes = filteredEpisodes.drop(1)

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (searchQuery.isEmpty() && selectedFilter == "Todos") {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = LatestCardBg),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        val durationText = latestEpisode.duration ?: "120 MIN"
                                        Text(
                                            text = "${latestEpisode.date.uppercase()} • $durationText",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = latestEpisode.title,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (!latestEpisode.description.isNullOrEmpty()) {
                                            Text(
                                                text = latestEpisode.description,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                        if (!latestEpisode.tema.isNullOrEmpty()) {
                                            Text(
                                                text = "Tema: ${latestEpisode.tema}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                        if (!latestEpisode.invitados.isNullOrEmpty()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Person, contentDescription = "Invitados", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = latestEpisode.invitados,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                        if (latestEpisode.tags.isNotEmpty()) {
                                            @OptIn(ExperimentalLayoutApi::class)
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                latestEpisode.tags.forEach { tag ->
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(50))
                                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = tag,
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                        } else {
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Button(onClick = { viewModel.activeEpisode = latestEpisode }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Escuchar", fontSize = 16.sp)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            if (downloadedIds.contains(latestEpisode.id)) {
                                                IconButton(onClick = {
                                                    val folderName = getDownloadFolder(context)
                                                    val deleted = audioActionHandler.deleteEpisode(latestEpisode, folderName)
                                                    if (deleted) {
                                                        viewModel.removeDownload(latestEpisode.id)
                                                        Toast.makeText(context, "Episodio eliminado", Toast.LENGTH_SHORT).show()
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                                                }
                                            } else {
                                                IconButton(onClick = {
                                                    val folderName = getDownloadFolder(context)
                                                    audioActionHandler.downloadEpisode(latestEpisode, folderName)
                                                    viewModel.markAsDownloaded(latestEpisode.id)
                                                    Toast.makeText(context, "Iniciando descarga...", Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Icon(Icons.Default.Download, contentDescription = "Descargar")
                                                }
                                            }
                                            IconButton(onClick = {
                                                coroutineScope.launch {
                                                    isSharing = true
                                                    Toast.makeText(context, "Preparando audio...", Toast.LENGTH_SHORT).show()
                                                    audioActionHandler.shareEpisode(latestEpisode)
                                                    isSharing = false
                                                }
                                            }) {
                                                Icon(Icons.Default.Share, contentDescription = "Compartir")
                                            }
                                            IconButton(onClick = { viewModel.toggleFavorite(latestEpisode.id) }) {
                                                Icon(
                                                    if (favoriteIds.contains(latestEpisode.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                    contentDescription = "Favorito",
                                                    tint = if (favoriteIds.contains(latestEpisode.id)) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val listToRender = if (searchQuery.isNotEmpty() || selectedFilter != "Todos") filteredEpisodes else previousEpisodes
                        val groupedEpisodes = listToRender.groupBy { extractMonthYear(it.date) }
                        
                        groupedEpisodes.forEach { (monthYear, episodesForMonth) ->
                            item {
                                MonthSeparator(monthYear)
                            }
                            
                            items(episodesForMonth) { episode ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { viewModel.activeEpisode = episode }.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFE2E8F0)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            val dateParts = episode.date.split(" ")
                                            val day = dateParts.firstOrNull() ?: "00"
                                            val month = if (dateParts.size >= 3) dateParts[2].take(3).uppercase() else "MES"
                                            Text(day, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
                                            Text(month, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(16.dp))
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = episode.title,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!episode.description.isNullOrEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = episode.description,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (!episode.tema.isNullOrEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Tema: ${episode.tema}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (!episode.invitados.isNullOrEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Person, contentDescription = "Invitados", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = episode.invitados,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        if (episode.tags.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            @OptIn(ExperimentalLayoutApi::class)
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                episode.tags.forEach { tag ->
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(50))
                                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = tag,
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(episode.duration ?: "Desc. min", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Icon(Icons.Default.PlayCircleOutline, contentDescription = "Reproducir", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    
                                    Column {
                                        if (downloadedIds.contains(episode.id)) {
                                            IconButton(onClick = {
                                                val folderName = getDownloadFolder(context)
                                                val deleted = audioActionHandler.deleteEpisode(episode, folderName)
                                                if (deleted) {
                                                    viewModel.removeDownload(episode.id)
                                                    Toast.makeText(context, "Episodio eliminado", Toast.LENGTH_SHORT).show()
                                                }
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        } else {
                                            IconButton(onClick = {
                                                val folderName = getDownloadFolder(context)
                                                audioActionHandler.downloadEpisode(episode, folderName)
                                                viewModel.markAsDownloaded(episode.id)
                                                Toast.makeText(context, "Iniciando descarga...", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Icon(Icons.Default.Download, contentDescription = "Descargar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        IconButton(onClick = {
                                            coroutineScope.launch {
                                                isSharing = true
                                                Toast.makeText(context, "Preparando audio...", Toast.LENGTH_SHORT).show()
                                                audioActionHandler.shareEpisode(episode)
                                                isSharing = false
                                            }
                                        }) {
                                            Icon(Icons.Default.Share, contentDescription = "Compartir", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { viewModel.toggleFavorite(episode.id) }) {
                                            Icon(
                                                if (favoriteIds.contains(episode.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = "Favorito",
                                                tint = if (favoriteIds.contains(episode.id)) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        
        // Dialogs and Overlays
        if (showExpandedPlayer && viewModel.activeEpisode != null) {
            ModalBottomSheet(
                onDismissRequest = { showExpandedPlayer = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                var currentPosition by remember { mutableLongStateOf(0L) }
                var trackDuration by remember { mutableLongStateOf(0L) }

                LaunchedEffect(isPlaying, exoPlayer) {
                    while (true) {
                        currentPosition = exoPlayer?.currentPosition ?: 0L
                        trackDuration = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                        isPlaying = exoPlayer?.isPlaying == true
                        playbackState = exoPlayer?.playbackState ?: Player.STATE_IDLE
                        delay(500L)
                    }
                }

                val configuration = LocalConfiguration.current
                
                val playerContent = @Composable {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = viewModel.activeEpisode!!.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (!viewModel.activeEpisode!!.invitados.isNullOrBlank()) {
                                Text(
                                    text = "Invitados: ${viewModel.activeEpisode!!.invitados}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!viewModel.activeEpisode!!.tema.isNullOrBlank()) {
                                Text(
                                    text = "Tema: ${viewModel.activeEpisode!!.tema}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.toggleFavorite(viewModel.activeEpisode!!.id) }) {
                            Icon(
                                if (favoriteIds.contains(viewModel.activeEpisode!!.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorito",
                                tint = if (favoriteIds.contains(viewModel.activeEpisode!!.id)) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Slider(
                        value = if (trackDuration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = { currentPosition = it.toLong() },
                        onValueChangeFinished = {
                            exoPlayer?.seekTo(currentPosition)
                        },
                        valueRange = 0f..(if (trackDuration > 0) trackDuration.toFloat() else 100f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentPosition), style = MaterialTheme.typography.bodyMedium)
                        Text(formatTime(trackDuration), style = MaterialTheme.typography.bodyMedium)
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                val newPos = (currentPosition - 5000).coerceAtLeast(0)
                                exoPlayer?.seekTo(newPos)
                                currentPosition = newPos
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(Icons.Default.Replay5, contentDescription = "Retroceder 5s", modifier = Modifier.size(40.dp))
                        }
                        
                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer?.pause() else exoPlayer?.play()
                            },
                            modifier = Modifier
                                .size(80.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        
                        IconButton(
                            onClick = {
                                val newPos = (currentPosition + 5000).coerceAtMost(trackDuration)
                                exoPlayer?.seekTo(newPos)
                                currentPosition = newPos
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(Icons.Default.Forward5, contentDescription = "Avanzar 5s", modifier = Modifier.size(40.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(48.dp))
                    
                    var volume by remember { mutableFloatStateOf(exoPlayer?.volume ?: 1f) }
                    var showSleepTimerMenu by remember { mutableStateOf(false) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.VolumeDown, contentDescription = "Volume Down")
                        Slider(
                            value = volume,
                            onValueChange = { 
                                volume = it
                                exoPlayer?.volume = it 
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.VolumeUp, contentDescription = "Volume Up")
                        
                        Box {
                            IconButton(onClick = { showSleepTimerMenu = true }) {
                                Icon(
                                    if (viewModel.isSleepTimerActive) Icons.Default.Bedtime else Icons.Default.Timer, 
                                    contentDescription = "Sleep Timer",
                                    tint = if (viewModel.isSleepTimerActive) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                            DropdownMenu(
                                expanded = showSleepTimerMenu,
                                onDismissRequest = { showSleepTimerMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("15 min") },
                                    onClick = {
                                        viewModel.setSleepTimer(15)
                                        showSleepTimerMenu = false
                                        Toast.makeText(context, "Temporizador: 15 min", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("30 min") },
                                    onClick = {
                                        viewModel.setSleepTimer(30)
                                        showSleepTimerMenu = false
                                        Toast.makeText(context, "Temporizador: 30 min", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("60 min") },
                                    onClick = {
                                        viewModel.setSleepTimer(60)
                                        showSleepTimerMenu = false
                                        Toast.makeText(context, "Temporizador: 60 min", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Desactivar") },
                                    onClick = {
                                        viewModel.setSleepTimer(0)
                                        showSleepTimerMenu = false
                                        Toast.makeText(context, "Temporizador desactivado", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(48.dp))
                }

                if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.portada),
                            contentDescription = null,
                            modifier = Modifier
                                .weight(0.4f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Column(
                            modifier = Modifier
                                .weight(0.6f)
                                .padding(start = 16.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            playerContent()
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.portada),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        playerContent()
                    }
                }
            }
        }

        if (isSharing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Preparando audio para compartir...", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        
        if (showFolderDialog) {
            var folderName by remember { mutableStateOf(getDownloadFolder(context)) }
            AlertDialog(
                onDismissRequest = { showFolderDialog = false },
                title = { Text("Carpeta de descargas") },
                text = {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("Nombre de la carpeta") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        saveDownloadFolder(context, folderName)
                        showFolderDialog = false
                    }) {
                        Text("Guardar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFolderDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }

        if (showSocialDialog) {
            AlertDialog(
                onDismissRequest = { showSocialDialog = false },
                title = { Text("Redes Sociales") },
                text = {
                    Column {
                        val links = listOf(
                            "Tenerife por la Verdad" to "https://t.me/Tfeverdad",
                            "Tenerife por la verdad (Grupo)" to "https://t.me/Tenerifeporlaverdad",
                            "GeoIn Canarias" to "https://t.me/GeoinCanarias",
                            "Que tu alimento..." to "https://t.me/Quetualimetoseatumedicamento",
                            "Tenerife Despierta" to "https://t.me/TenerifeDespierta"
                        )
                        links.forEach { (name, url) ->
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }) {
                                Text(name)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSocialDialog = false }) {
                        Text("Cerrar")
                    }
                }
            )
        }

        if (showCreditsDialog) {
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Desconocida"
            } catch (e: Exception) {
                "Desconocida"
            }

            AlertDialog(
                onDismissRequest = { showCreditsDialog = false },
                title = { Text("Créditos") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Hecho con el ❤️, con Software Libre y Libre de Rastreadores, Peticiones de Datos y Políticas de Anti-Privacidad.",
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/rtorgil")))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Text("☕ Ko-fi", fontSize = 16.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/rtorgil")))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Text("💸 PayPal", fontSize = 16.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rtorgil.ynh.fr/gitea/rtorgil/TXLV-Ivoox")))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Text("Repositorio Gitea", fontSize = 16.sp)
                            }
                        }

                        Text(
                            text = "Versión $versionName",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCreditsDialog = false }) {
                        Text("Cerrar")
                    }
                }
            )
        }
        
        if (showUpdateDialog) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("Actualización disponible") },
                text = { Text("Hay una nueva versión ($newVersionName) disponible. ¿Deseas descargarla e instalarla?") },
                confirmButton = {
                    TextButton(onClick = {
                        showUpdateDialog = false
                        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val request = DownloadManager.Request(Uri.parse(updateUrl))
                            .setTitle("Actualizando TXLV Downloader")
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TXLV_update.apk")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        val downloadId = downloadManager.enqueue(request)
                        
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(c: Context, intent: Intent) {
                                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                                if (id == downloadId) {
                                    val uri = downloadManager.getUriForDownloadedFile(downloadId)
                                    if (uri != null) {
                                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "application/vnd.android.package-archive")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        }
                                        c.startActivity(installIntent)
                                    }
                                    c.unregisterReceiver(this)
                                }
                            }
                        }
                        ContextCompat.registerReceiver(
                            context,
                            receiver, 
                            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                            ContextCompat.RECEIVER_EXPORTED
                        )
                    }) {
                        Text("Actualizar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

fun extractMonthYear(dateString: String): String {
    val parts = dateString.split(" ")
    if (parts.size >= 5) {
        val month = parts[2].replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        val year = parts[4]
        return "$month $year"
    }
    return dateString
}

@Composable
fun MonthSeparator(monthYear: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
        Text(
            text = monthYear.uppercase(),
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
    }
}

