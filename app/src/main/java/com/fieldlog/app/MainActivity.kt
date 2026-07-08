package com.fieldlog.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private const val WINDOW_DAYS = 21
private val Context.dataStore by preferencesDataStore(name = "field_log")
private val HABITS_KEY = stringPreferencesKey("habits")
private val ENTRIES_KEY = stringPreferencesKey("entries")

private val DEFAULT_HABITS = listOf(
    "Sleep 7+ hrs",
    "Workout",
    "Drink 2L water",
    "Read 15 min",
    "No phone in bed",
    "Job search / applications"
)

// ---------- palette ----------
private val Bg = Color(0xFF0B1220)
private val Surface = Color(0xFF111B2C)
private val BorderC = Color(0xFF1D2A40)
private val TextPrimary = Color(0xFFE8EDF4)
private val TextMuted = Color(0xFF8996AC)
private val TextFaint = Color(0xFF5C6B85)
private val Ember = Color(0xFFFF7A45)
private val Ember2 = Color(0xFFFF5F6D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surface)) {
                Surface(color = Bg) {
                    FieldLogApp()
                }
            }
        }
    }
}

private fun loadJsonHabits(raw: String?): MutableList<String> {
    if (raw == null) return DEFAULT_HABITS.toMutableList()
    val arr = JSONArray(raw)
    return MutableList(arr.length()) { arr.getString(it) }
}

private fun loadJsonEntries(raw: String?): MutableMap<String, Boolean> {
    val map = mutableMapOf<String, Boolean>()
    if (raw == null) return map
    val obj = JSONObject(raw)
    obj.keys().forEach { k -> map[k] = obj.getBoolean(k) }
    return map
}

@Composable
fun FieldLogApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var habits by remember { mutableStateOf(DEFAULT_HABITS.toMutableList()) }
    var entries by remember { mutableStateOf(mutableMapOf<String, Boolean>()) }
    var loaded by remember { mutableStateOf(false) }
    var addingHabit by remember { mutableStateOf(false) }
    var newHabitText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        habits = loadJsonHabits(prefs[HABITS_KEY])
        entries = loadJsonEntries(prefs[ENTRIES_KEY])
        loaded = true
    }

    fun persist(h: List<String>, e: Map<String, Boolean>) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[HABITS_KEY] = JSONArray(h).toString()
                val obj = JSONObject()
                e.forEach { (k, v) -> obj.put(k, v) }
                prefs[ENTRIES_KEY] = obj.toString()
            }
        }
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("loading log…", color = TextFaint)
        }
        return
    }

    val today = LocalDate.now()
    val days = remember(today) { (WINDOW_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) } }

    fun toggle(habit: String, day: LocalDate) {
        val key = "$habit|$day"
        val next = entries.toMutableMap()
        if (next[key] == true) next.remove(key) else next[key] = true
        entries = next
        persist(habits, next)
    }

    fun addHabit() {
        val name = newHabitText.trim()
        if (name.isEmpty() || habits.contains(name)) return
        val next = (habits + name).toMutableList()
        habits = next
        persist(next, entries)
        newHabitText = ""
        addingHabit = false
    }

    fun removeHabit(h: String) {
        val next = habits.filter { it != h }.toMutableList()
        val nextEntries = entries.filterKeys { !it.startsWith("$h|") }.toMutableMap()
        habits = next
        entries = nextEntries
        persist(next, nextEntries)
    }

    // ---- derived stats ----
    val dailyCounts = days.map { d ->
        habits.count { h -> entries["$h|$d"] == true }
    }
    val totalPossible = habits.size * WINDOW_DAYS
    val totalDone = dailyCounts.sum()
    val overallRate = if (totalPossible > 0) totalDone.toFloat() / totalPossible else 0f

    var currentStreak = 0
    for (i in dailyCounts.indices.reversed()) {
        if (dailyCounts[i] > 0) currentStreak++ else break
    }
    var longestStreak = 0
    var run = 0
    dailyCounts.forEach { c ->
        if (c > 0) { run++; longestStreak = maxOf(longestStreak, run) } else run = 0
    }

    val habitRates = habits.map { h ->
        val done = days.count { d -> entries["$h|$d"] == true }
        Triple(h, if (WINDOW_DAYS > 0) done.toFloat() / WINDOW_DAYS else 0f, done)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeroSection(overallRate, currentStreak, longestStreak, totalDone, totalPossible)
        Spacer(Modifier.height(22.dp))
        HabitGrid(
            habits = habits, days = days, entries = entries, today = today,
            onToggle = ::toggle, onRemove = ::removeHabit
        )
        Spacer(Modifier.height(10.dp))
        AddHabitRow(
            adding = addingHabit,
            text = newHabitText,
            onTextChange = { newHabitText = it },
            onStart = { addingHabit = true },
            onConfirm = ::addHabit,
            onCancel = { addingHabit = false; newHabitText = "" }
        )
        Spacer(Modifier.height(26.dp))
        SectionTitle("Daily Ascent", "habits completed per day")
        Spacer(Modifier.height(10.dp))
        AscentChart(dailyCounts)
        Spacer(Modifier.height(26.dp))
        SectionTitle("Habit Summits", "% of last $WINDOW_DAYS days completed")
        Spacer(Modifier.height(14.dp))
        RingsRow(habitRates)
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun HeroSection(overallRate: Float, streak: Int, longest: Int, done: Int, possible: Int) {
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawMountainGlow(size.width, size.height, overallRate)
        }
        Column(Modifier.padding(20.dp)) {
            Text(
                "FIELD LOG — ASCENT TRACKER",
                color = Ember.copy(alpha = 0.9f),
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Day $streak", color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(if (streak > 0) "\uD83D\uDD25" else "", fontSize = 22.sp)
            }
            Text(
                if (streak > 0) "$streak day streak — keep the fire lit" else "No streak yet — log a habit today to start climbing",
                color = TextMuted, fontSize = 12.5.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatBlock("LONGEST STREAK", "${longest}d")
                StatBlock("COMPLETION", "${(overallRate * 100).toInt()}%")
                StatBlock("HABITS DONE", "$done/$possible")
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextFaint, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMountainGlow(w: Float, h: Float, progress: Float) {
    val path = Path().apply {
        moveTo(0f, h)
        lineTo(w * 0.15f, h * 0.43f)
        lineTo(w * 0.27f, h * 0.68f)
        lineTo(w * 0.42f, h * 0.14f)
        lineTo(w * 0.57f, h * 0.57f)
        lineTo(w * 0.72f, h * 0.29f)
        lineTo(w * 0.85f, h * 0.64f)
        lineTo(w, h * 0.39f)
        lineTo(w, h)
        close()
    }
    drawPath(path, color = Surface)
    drawPath(path, color = BorderC, style = Stroke(width = 2f))

    val glowHeight = (0.30f + progress * 0.7f) * h
    clipPath(path) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Ember.copy(alpha = 0.85f), Ember.copy(alpha = 0.1f), Ember.copy(alpha = 0f)),
                startY = h - glowHeight,
                endY = h
            ),
            topLeft = Offset(0f, h - glowHeight),
            size = androidx.compose.ui.geometry.Size(w, glowHeight)
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = TextFaint, fontSize = 11.5.sp)
    }
}

@Composable
private fun HabitGrid(
    habits: List<String>,
    days: List<LocalDate>,
    entries: Map<String, Boolean>,
    today: LocalDate,
    onToggle: (String, LocalDate) -> Unit,
    onRemove: (String) -> Unit
) {
    val rowHeight = 30.dp
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("BASECAMP LOG", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("last $WINDOW_DAYS days · tap a tile to log", color = TextFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(12.dp))
        Row {
            // fixed habit label column
            Column(Modifier.width(130.dp)) {
                Spacer(Modifier.height(26.dp))
                habits.forEach { h ->
                    Row(
                        Modifier.height(rowHeight).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            h, color = TextMuted, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "×", color = TextFaint, fontSize = 15.sp,
                            modifier = Modifier
                                .clickable { onRemove(h) }
                                .padding(horizontal = 6.dp)
                        )
                    }
                }
            }
            // scrollable day grid
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                days.forEach { d ->
                    Column(Modifier.width(26.dp)) {
                        Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) {
                            Text(
                                d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()).take(1),
                                color = TextFaint, fontSize = 9.sp
                            )
                        }
                        habits.forEach { h ->
                            val done = entries["$h|$d"] == true
                            Box(
                                Modifier
                                    .height(rowHeight)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (done) Brush.linearGradient(listOf(Color(0xFFFF9A5A), Color(0xFFFF5F6D)))
                                        else Brush.linearGradient(listOf(Surface, Surface))
                                    )
                                    .clickable { onToggle(h, d) }
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddHabitRow(
    adding: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (adding) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("New habit…", color = TextFaint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.width(200.dp).height(52.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Ember, unfocusedBorderColor = BorderC
                )
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onConfirm) { Text("Add", color = Ember) }
            TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
        } else {
            TextButton(onClick = onStart) { Text("+ Add habit", color = Ember) }
        }
    }
}

@Composable
private fun AscentChart(dailyCounts: List<Int>) {
    val maxVal = (dailyCounts.maxOrNull() ?: 1).coerceAtLeast(1)
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(150.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (dailyCounts.isEmpty()) return@Canvas
            val stepX = w / (dailyCounts.size - 1).coerceAtLeast(1)
            val points = dailyCounts.mapIndexed { i, v ->
                Offset(i * stepX, h - (v.toFloat() / maxVal) * (h - 10f) - 5f)
            }
            val fillPath = Path().apply {
                moveTo(points.first().x, h)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, h)
                close()
            }
            drawPath(
                fillPath,
                brush = Brush.verticalGradient(
                    listOf(Ember.copy(alpha = 0.5f), Ember.copy(alpha = 0.02f))
                )
            )
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(linePath, color = Ember, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun RingsRow(habitRates: List<Triple<String, Float, Int>>) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        habitRates.forEach { (label, rate, done) ->
            Column(
                Modifier.width(92.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Canvas(Modifier.size(74.dp)) {
                    val stroke = 6.dp.toPx()
                    val r = (size.minDimension - stroke) / 2
                    drawArc(
                        color = BorderC,
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                        topLeft = Offset((size.width - r * 2) / 2, (size.height - r * 2) / 2),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                    )
                    drawArc(
                        color = Ember,
                        startAngle = -90f, sweepAngle = 360f * rate, useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                        topLeft = Offset((size.width - r * 2) / 2, (size.height - r * 2) / 2),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                    )
                }
                Text("${(rate * 100).toInt()}%", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(label, color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${done}d", color = TextFaint, fontSize = 9.sp)
            }
        }
    }
}
