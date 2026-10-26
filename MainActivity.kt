package com.example.arabicink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.delay

private val InkColor = Color(0xFF1B1B1B)

data class Pt(val x: Float, val y: Float, val t: Long)

enum class ModelState { CHECKING, DOWNLOADING, READY, ERROR }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { ctx.getSharedPreferences("notes", Context.MODE_PRIVATE) }

    var text by remember { mutableStateOf(prefs.getString("text", "") ?: "") }
    var strokes by remember { mutableStateOf(listOf<List<Pt>>()) }
    var current by remember { mutableStateOf(listOf<Pt>()) }
    var state by remember { mutableStateOf(ModelState.CHECKING) }
    var busy by remember { mutableStateOf(false) }

    val model = remember {
        DigitalInkRecognitionModel.builder(
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("ar")!!
        ).build()
    }
    val recognizer = remember {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }
    DisposableEffect(Unit) { onDispose { recognizer.close() } }

    fun prepare() {
        val mgr = RemoteModelManager.getInstance()
        state = ModelState.CHECKING
        mgr.isModelDownloaded(model)
            .addOnSuccessListener { ok ->
                if (ok) {
                    state = ModelState.READY
                } else {
                    state = ModelState.DOWNLOADING
                    mgr.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { state = ModelState.READY }
                        .addOnFailureListener { state = ModelState.ERROR }
                }
            }
            .addOnFailureListener { state = ModelState.ERROR }
    }

    fun recognizeNow() {
        val snapshot = strokes
        if (snapshot.isEmpty() || state != ModelState.READY || busy) return
        busy = true
        val inkBuilder = Ink.builder()
        snapshot.forEach { s ->
            val sb = Ink.Stroke.builder()
            s.forEach { sb.addPoint(Ink.Point.create(it.x, it.y, it.t)) }
            inkBuilder.addStroke(sb.build())
        }
        recognizer.recognize(inkBuilder.build())
            .addOnSuccessListener { result ->
                val t = result.candidates.firstOrNull()?.text.orEmpty()
                if (t.isNotBlank()) {
                    text = if (text.isBlank() || text.endsWith("\n")) text + t else "$text $t"
                }
                strokes = strokes.drop(snapshot.size)
                busy = false
            }
            .addOnFailureListener { busy = false }
    }

    LaunchedEffect(Unit) { prepare() }
    LaunchedEffect(text) { prefs.edit().putString("text", text).apply() }
    // بعد ما توقف عن الكتابة لحوالي ثانية ونص، بيتحول الخط لنص تلقائيا
    LaunchedEffect(strokes, current.isEmpty(), state) {
        if (strokes.isNotEmpty() && current.isEmpty() && state == ModelState.READY) {
            delay(1300)
            recognizeNow()
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF6F3EC)) {
        Column(Modifier.padding(12.dp)) {
            Text("خطّي", fontSize = 24.sp, color = InkColor)

            when (state) {
                ModelState.CHECKING -> Text("جاري التجهيز...", fontSize = 13.sp, color = Color.Gray)
                ModelState.DOWNLOADING -> Text(
                    "جاري تنزيل نموذج الخط العربي (مرة واحدة، لازم إنترنت)...",
                    fontSize = 13.sp, color = Color(0xFF9A6B00)
                )
                ModelState.ERROR -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تعذر تنزيل النموذج.", fontSize = 13.sp, color = Color(0xFFB00020))
                    TextButton(onClick = { prepare() }) { Text("إعادة المحاولة") }
                }
                ModelState.READY -> Text(
                    if (busy) "جاري التحويل..." else "اكتب بإيدك بالمربع تحت وراح يتحول لنص",
                    fontSize = 13.sp, color = Color(0xFF2E7D32)
                )
            }

            Spacer(Modifier.height(8.dp))

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontSize = 22.sp, lineHeight = 36.sp, color = InkColor),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text("النص رح يظهر هون", fontSize = 18.sp, color = Color.LightGray)
                    }
                    inner()
                }
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool("تراجع") { strokes = strokes.dropLast(1) }
                Tool("مسح الخط") { strokes = emptyList() }
                Tool("سطر جديد") { text = if (text.endsWith("\n")) text else text + "\n" }
                Tool("نسخ") { clipboard.setText(AnnotatedString(text)) }
                Tool("مشاركة") {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    ctx.startActivity(Intent.createChooser(i, null))
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .border(1.5.dp, Color(0xFFCFC8B8), RoundedCornerShape(16.dp))
            ) {
                if (strokes.isEmpty() && current.isEmpty()) {
                    Text(
                        "اكتب هون ✍️",
                        fontSize = 20.sp,
                        color = Color.LightGray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val pts = mutableListOf(
                                    Pt(down.position.x, down.position.y, System.currentTimeMillis())
                                )
                                current = pts.toList()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    pts.add(Pt(c.position.x, c.position.y, System.currentTimeMillis()))
                                    current = pts.toList()
                                    c.consume()
                                }
                                strokes = strokes + listOf(pts.toList())
                                current = emptyList()
                            }
                        }
                ) {
                    strokes.forEach { drawInk(it) }
                    drawInk(current)
                }
            }
        }
    }
}

@Composable
private fun RowScope.Tool(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.weight(1f)
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

private fun DrawScope.drawInk(s: List<Pt>) {
    if (s.isEmpty()) return
    if (s.size == 1) {
        drawCircle(InkColor, radius = 4f, center = Offset(s[0].x, s[0].y))
        return
    }
    val p = Path()
    p.moveTo(s[0].x, s[0].y)
    for (i in 1 until s.size) p.lineTo(s[i].x, s[i].y)
    drawPath(p, InkColor, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
