package com.rtorgil.txlv_downloader

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.PlayArrow
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtorgil.txlv_downloader.ui.theme.TXLVDownloaderTheme
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Environment
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var audioActionHandler: AudioActionHandler
    private val viewModel: PodcastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioActionHandler = AudioActionHandler(this)
        
        enableEdgeToEdge()
        setContent {
            TXLVDownloaderTheme {
                var showSplash by remember { mutableStateOf(true) }
                
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showSplash = false
                }
                
                if (showSplash) {
                    SplashScreen()
                } else {
                    AppContent(viewModel = viewModel, audioActionHandler = audioActionHandler)
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
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
    }
}

fun getDownloadFolder(context: Context): String {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getString("download_folder", "Tenerife por la Verdad") ?: "Tenerife por la Verdad"
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
    val podcastTitle by viewModel.podcastTitle.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isSharing by remember { mutableStateOf(false) }
    var selectedEpisodeForPlayback by remember { mutableStateOf<Episode?>(null) }

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
        if (episodes.isEmpty()) {
            viewModel.fetchEpisodes()
        }
        
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (podcastTitle.isNotEmpty()) podcastTitle else "TXLV Downloader", 
                        fontSize = 22.sp, 
                        fontWeight = FontWeight.Bold
                    ) 
                },
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                if (isLoading && episodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (error != null && episodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Error: $error", color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
                    }
                } else if (episodes.isNotEmpty()) {
                    val latestEpisode = episodes.first()
                    val previousEpisodes = episodes.drop(1)

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        item {
                            Text(
                                text = "TU ÚLTIMO PROGRAMA",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "ÚLTIMO PROGRAMA PUBLICADO",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = latestEpisode.title,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = 28.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (latestEpisode.duration != null) "${latestEpisode.date} - ${latestEpisode.duration}" else latestEpisode.date,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    Button(
                                        onClick = { selectedEpisodeForPlayback = latestEpisode },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("ESCUCHAR PROGRAMA", fontSize = 18.sp, textAlign = TextAlign.Center)
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                isSharing = true
                                                Toast.makeText(context, "Preparando audio...", Toast.LENGTH_SHORT).show()
                                                audioActionHandler.shareEpisode(latestEpisode)
                                                isSharing = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(16.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("ENVIAR AUDIO POR WHATSAPP / REDES", fontSize = 18.sp, textAlign = TextAlign.Center)
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    OutlinedButton(
                                        onClick = {
                                            val folderName = getDownloadFolder(context)
                                            audioActionHandler.downloadEpisode(latestEpisode, folderName)
                                            Toast.makeText(context, "Iniciando descarga...", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(16.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("GUARDAR MP3 EN EL TELÉFONO", fontSize = 18.sp, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }

                        if (previousEpisodes.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "PROGRAMAS ANTERIORES",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "Programas anteriores (toca uno para descargarlo o enviarlo)",
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            items(previousEpisodes) { episode ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = episode.title,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            lineHeight = 26.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (episode.duration != null) "${episode.date} - ${episode.duration}" else episode.date,
                                            fontSize = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        Button(
                                            onClick = { selectedEpisodeForPlayback = episode },
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("ESCUCHAR PROGRAMA", fontSize = 18.sp, textAlign = TextAlign.Center)
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    isSharing = true
                                                    Toast.makeText(context, "Preparando audio...", Toast.LENGTH_SHORT).show()
                                                    audioActionHandler.shareEpisode(episode)
                                                    isSharing = false
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(12.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("ENVIAR POR WHATSAPP", fontSize = 18.sp, textAlign = TextAlign.Center)
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        OutlinedButton(
                                            onClick = {
                                                val folderName = getDownloadFolder(context)
                                                audioActionHandler.downloadEpisode(episode, folderName)
                                                Toast.makeText(context, "Iniciando descarga...", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(12.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("GUARDAR MP3", fontSize = 18.sp, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
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
                AlertDialog(
                    onDismissRequest = { showCreditsDialog = false },
                    title = { Text("Créditos") },
                    text = {
                        Column {
                            Text(
                                "software open source , totalmente libre y sin judiadas ",
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Si te resulta útil, ¡invítame a un café! ☕",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/rtorgil")))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Text("Apoyar en Ko-fi", fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/rtorgil")))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Text("Apoyar en PayPal", fontSize = 18.sp)
                            }
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
            
            selectedEpisodeForPlayback?.let { episode ->
                PodcastPlayerBottomSheet(
                    episode = episode,
                    onDismiss = { selectedEpisodeForPlayback = null }
                )
            }
        }
    }
}
