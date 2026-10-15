package com.rtorgil.txlv_downloader

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.PlayArrow
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
        Image(
            painter = painterResource(id = R.drawable.imagen),
            contentDescription = "Splash Screen",
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    }
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

    LaunchedEffect(Unit) {
        if (episodes.isEmpty()) {
            viewModel.fetchEpisodes()
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
                                            audioActionHandler.downloadEpisode(latestEpisode)
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
                                                audioActionHandler.downloadEpisode(episode)
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
            
            selectedEpisodeForPlayback?.let { episode ->
                PodcastPlayerBottomSheet(
                    episode = episode,
                    onDismiss = { selectedEpisodeForPlayback = null }
                )
            }
        }
    }
}
