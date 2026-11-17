package cz.majkl.karaoke

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Ink = Color(0xFF11131D)
private val Panel = Color(0xFF202437)
private val Purple = Color(0xFFB69CFF)
private val Muted = Color(0xFFADB3C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KaraokeScreen() }
    }
}

private data class LyricLine(val timeMs: Long, val text: String)

@Composable
private fun KaraokeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("Zatím není vybraná skladba") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var activeLine by remember { mutableIntStateOf(-1) }
    var listening by remember { mutableStateOf(false) }
    var detectedHz by remember { mutableDoubleStateOf(0.0) }
    var detectedNote by remember { mutableStateOf("—") }
    var targetNote by remember { mutableStateOf("C4") }
    var score by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Vyber hudbu a případně soubor .lrc s textem.") }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            audioName = uri.lastPathSegment?.substringAfterLast('/') ?: "Vybraná skladba"
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener { duration = it.duration.toLong(); it.start(); playing = true; status = "Přehrávání připraveno." }
                setOnCompletionListener { playing = false; position = 0L }
                prepareAsync()
            }
            status = "Načítám skladbu…"
        }
    }
    val lyricPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                lyrics = parseLrc(text)
                activeLine = -1
                status = "Načteno ${lyrics.size} řádků textu."
            } catch (e: Exception) { status = "Text se nepodařilo načíst: ${e.message}" }
        }
    }

    LaunchedEffect(playing, player) {
        while (playing && player != null) {
            try {
                position = player?.currentPosition?.toLong() ?: 0L
                activeLine = lyrics.indexOfLast { it.timeMs <= position }
            } catch (_: Exception) { }
            kotlinx.coroutines.delay(100)
        }
    }

    DisposableEffect(Unit) { onDispose { player?.release() } }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Ink, surface = Panel)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("ČESKÉ AI KARAOKE", color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Zpívej po svém.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Offline prototyp · verze 0.1", color = Muted, fontSize = 13.sp)
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("MOJE SKLADBA", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(audioName, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) { Text("Vybrat hudbu") }
                                OutlinedButton(onClick = { lyricPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Načíst .LRC") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = player != null, onClick = {
                                    player?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } }
                                }) { Text(if (playing) "Pozastavit" else "Přehrát") }
                                Text("${formatTime(position)} / ${formatTime(duration)}", color = Muted)
                            }
                            if (duration > 0) Slider(value = position.toFloat().coerceIn(0f, duration.toFloat()), onValueChange = { position = it.toLong(); player?.seekTo(position.toInt()) }, valueRange = 0f..duration.toFloat())
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("TEXT PÍSNĚ", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            if (lyrics.isEmpty()) {
                                Text("Zatím bez textu. Načti soubor .lrc – každý řádek obsahuje čas a text.", color = Muted)
                            } else {
                                lyrics.forEachIndexed { index, line ->
                                    Text(line.text, color = if (index == activeLine) Purple else Color.White.copy(alpha = 0.58f), fontSize = if (index == activeLine) 22.sp else 16.sp, fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("TVOJ HLAS", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(detectedNote, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            Text(if (detectedHz > 0) "${detectedHz.roundToInt()} Hz" else "Čekám na zvuk z mikrofonu", color = Muted)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (listening) { listening = false; status = "Mikrofon vypnut." }
                                    else {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listening = true
                                        else status = "Povol mikrofon v nastavení aplikace a zkus to znovu."
                                    }
                                }) { Text(if (listening) "Zastavit mikrofon" else "Zapnout mikrofon") }
                                Text("Referenční tón: $targetNote", color = Muted, fontSize = 12.sp)
                            }
                            Text("Zkušební bodování je zatím proti zvolenému tónu, nikoli proti skutečnému zpěvákovi.", color = Muted, fontSize = 12.sp)
                            Text("Přesnost tónu: $score %", color = Color.White, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(progress = { score / 100f }, modifier = Modifier.fillMaxWidth(), color = Purple)
                        }
                    }
                }
                item { Text(status, color = Muted, fontSize = 12.sp) }
                item { Text("Tip: .LRC je obyčejný textový soubor s časovými značkami, například [00:12.50]První řádek textu.", color = Muted, fontSize = 11.sp) }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listening = true else status = "Přístup k mikrofonu nebyl povolen."
    }
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        scope.launch(Dispatchers.IO) {
            val sampleRate = 44100
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { listening = false; return@launch }
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
            val buffer = ShortArray(4096)
            try {
                recorder.startRecording()
                while (isActive && listening) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 1024) {
                        val hz = estimatePitch(buffer, count, sampleRate)
                        if (hz in 65.0..1000.0) {
                            val note = frequencyToNote(hz)
                            val targetHz = noteToFrequency(targetNote)
                            val cents = abs(1200.0 * (ln(hz / targetHz) / ln(2.0)))
                            val points = (100.0 - cents / 2.0).roundToInt().coerceIn(0, 100)
                            detectedHz = hz; detectedNote = note; score = points
                        }
                    }
                }
            } catch (_: SecurityException) { }
            finally { try { recorder.stop() } catch (_: Exception) {}; recorder.release() }
        }
    }
}

private fun parseLrc(raw: String): List<LyricLine> {
    val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s*(.*)")
    return raw.lineSequence().mapNotNull { line ->
        val m = regex.matchEntire(line.trim()) ?: return@mapNotNull null
        val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
        val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        LyricLine(min * 60_000 + sec * 1000 + fraction, m.groupValues[4])
    }.sortedBy { it.timeMs }.toList()
}

private fun formatTime(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

private fun estimatePitch(input: ShortArray, size: Int, sampleRate: Int): Double {
    val n = minOf(size, 4096)
    var mean = 0.0
    for (i in 0 until n) mean += input[i].toDouble()
    mean /= n
    val minLag = sampleRate / 1000
    val maxLag = sampleRate / 65
    var bestLag = -1
    var best = 0.0
    for (lag in minLag..minOf(maxLag, n / 2)) {
        var corr = 0.0; var energy = 0.0
        for (i in 0 until n - lag) {
            val a = input[i].toDouble() - mean
            val b = input[i + lag].toDouble() - mean
            corr += a * b; energy += a * a
        }
        val normalized = if (energy > 0) corr / energy else 0.0
        if (normalized > best) { best = normalized; bestLag = lag }
    }
    return if (bestLag > 0 && best > 0.55) sampleRate.toDouble() / bestLag else 0.0
}

private fun frequencyToNote(freq: Double): String {
    val midi = (69 + 12 * (ln(freq / 440.0) / ln(2.0))).roundToInt()
    val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return names[(midi % 12 + 12) % 12] + (midi / 12 - 1)
}

private fun noteToFrequency(note: String): Double {
    val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val match = Regex("([A-G](?:♯)?)(-?\\d+)").matchEntire(note) ?: return 261.63
    val index = names.indexOf(match.groupValues[1]).coerceAtLeast(0)
    val midi = (match.groupValues[2].toInt() + 1) * 12 + index
    return 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
}
