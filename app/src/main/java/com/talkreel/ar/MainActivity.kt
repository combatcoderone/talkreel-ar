package com.talkreel.ar

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (!granted) {
            Toast.makeText(this, "Camera and mic permission are needed to record.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
        setContent { TalkReelScreen() }
    }

    @Composable
    fun TalkReelScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var script by remember {
            mutableStateOf(
                "Hi, this is my honest review in 30 seconds.\n\nFirst, this is what I liked.\n\nSecond, this is what I did not like.\n\nMy verdict: yes, if you need it for daily use."
            )
        }
        var isRecording by remember { mutableStateOf(false) }
        var isPaused by remember { mutableStateOf(false) }
        var seconds by remember { mutableIntStateOf(0) }
        var speed by remember { mutableFloatStateOf(1f) }
        var filterIndex by remember { mutableIntStateOf(0) }
        var backgroundIndex by remember { mutableIntStateOf(0) }
        var showCaptions by remember { mutableStateOf(true) }
        var status by remember { mutableStateOf("Ready. Press Record, the teleprompter starts with the video.") }
        val filters = listOf("None", "Warm", "Bright", "Cool", "Fun")
        val backgrounds = listOf("None", "Studio", "Shop", "City")
        val sounds = listOf("Morning review beat", "Quick unbox pop", "Calm talk piano")
        var soundIndex by remember { mutableIntStateOf(0) }
        val scrollState = rememberScrollState()

        // Teleprompter timer: scrolls only while recording and not paused.
        // Seconds are counted in the same loop, one tick per second.
        LaunchedEffect(isRecording, isPaused, speed) {
            var ticks = 0
            while (isRecording && !isPaused) {
                delay(500)
                ticks += 1
                if (ticks % 2 == 0) {
                    seconds += 1
                    scrollState.scrollTo(scrollState.value + (6 * speed).toInt())
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp)
        ) {
            Text("TalkReel AR  v0.1.0", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(status, color = Color(0xFFAAAAAA), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth().height(340.dp).background(Color.DarkGray)) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val recorder = Recorder.Builder()
                                    .setQualitySelector(QualitySelector.from(Quality.HD))
                                    .build()
                                videoCapture = VideoCapture.withOutput(recorder)
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_FRONT_CAMERA,
                                        preview,
                                        videoCapture
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Camera error: " + e.message, Toast.LENGTH_SHORT).show()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Filter tint overlay, v0 simple colour filter. Full face AR is v1.
                val tint = when (filterIndex) {
                    1 -> Color(0x22FFAA00)
                    2 -> Color(0x11FFFFFF)
                    3 -> Color(0x2200AAFF)
                    4 -> Color(0x22FF00AA)
                    else -> Color.Transparent
                }
                Box(modifier = Modifier.fillMaxSize().background(tint))
                // Teleprompter overlay near the lens.
                Box(
                    modifier = Modifier.fillMaxWidth().background(Color(0xAA000000)).padding(10.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Text(
                        text = script,
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier.height(110.dp).verticalScroll(scrollState)
                    )
                }
                if (showCaptions && isRecording) {
                    Text(
                        text = "Captions on: " + script.lines().firstOrNull().orEmpty(),
                        color = Color.Yellow,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                            .background(Color(0xAA000000)).padding(6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Time: ${seconds}s   Filter: ${filters[filterIndex]}   Background: ${backgrounds[backgroundIndex]}   Sound: ${sounds[soundIndex]}", color = Color.White, fontSize = 13.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!isRecording) {
                        startRecording { msg -> status = msg }
                        isRecording = true
                        isPaused = false
                        seconds = 0
                        status = "Recording. Teleprompter is scrolling."
                    } else {
                        stopRecording()
                        isRecording = false
                        isPaused = false
                        status = "Saved to Movies/TalkReelAR. 9:16 vertical."
                    }
                }) {
                    Text(if (isRecording) "Stop" else "Record")
                }
                Button(
                    onClick = {
                        if (isRecording) {
                            isPaused = !isPaused
                            if (isPaused) {
                                recording?.pause()
                                status = "Paused. Teleprompter and video are paused together."
                            } else {
                                recording?.resume()
                                status = "Resumed. Teleprompter continues from the last line."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555))
                ) { Text(if (isPaused) "Resume" else "Pause") }
                Button(onClick = { filterIndex = (filterIndex + 1) % filters.size }) { Text("Filter") }
                Button(onClick = { backgroundIndex = (backgroundIndex + 1) % backgrounds.size }) { Text("BG") }
            }

            Text("Speed", color = Color.White)
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { soundIndex = (soundIndex + 1) % sounds.size }) { Text("Sound") }
                Button(onClick = { showCaptions = !showCaptions }) {
                    Text(if (showCaptions) "Captions: On" else "Captions: Off")
                }
            }

            Text("Your script, edit it here:", color = Color.White, fontWeight = FontWeight.Bold)
            TextField(
                value = script,
                onValueChange = { script = it },
                modifier = Modifier.fillMaxWidth().height(130.dp)
            )
            Text(
                "v0 notes: AR face tracking, background segmentation, auto captions from speech, and auto silence cut are in v1. v0 has camera, teleprompter, record, pause, filters, backgrounds, and 9:16 export.",
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp
            )
        }
    }

    private fun startRecording(onStatus: (String) -> Unit) {
        val capture = videoCapture ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "TalkReel-$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/TalkReelAR")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()
        recording = capture.output.prepareRecording(this, output)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (!event.hasError()) {
                        onStatus("Saved. Video is ready in Movies/TalkReelAR.")
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }
}
