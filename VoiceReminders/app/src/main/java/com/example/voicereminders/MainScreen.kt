package com.example.voicereminders

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val EXAMPLES = listOf(
    "תזכיר לי מחר ב-5 להתקשר לאמא",
    "תזכיר לי בעוד 20 דקות להוציא את העוגה",
    "תזכורת ביום שלישי ב-10 בבוקר פגישה עם דני",
    "תזכיר לי ב-15 בנובמבר ב-16:30 רופא שיניים",
    "תזכיר לי הערב לסגור את הגז",
)

@Composable
fun MainScreen(refreshKey: Int, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val calendars = remember { CalendarRepository(context) }
    var missing by remember { mutableStateOf(emptyList<String>()) }
    var calendarList by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var handsFree by remember { mutableStateOf(settings.handsFree) }
    var request by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        missing = Permissions.missing(context)
        calendarList = calendars.list()
        selectedId = settings.calendarId?.takeIf { id -> calendarList.any { it.id == id } }
            ?: calendarList.firstOrNull()?.id
        handsFree = settings.handsFree
    }

    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("תזכורות קוליות", style = MaterialTheme.typography.headlineMedium)
        Text("אמרו תזכורת בעברית והיא תיכנס ליומן Google שלכם, עם התראה בזמן.")

        if (missing.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("חסרות הרשאות: ${missing.joinToString(", ")}")
                    Button(onClick = onRequestPermissions) { Text("אישור הרשאות") }
                }
            }
        }

        Button(
            onClick = { context.startActivity(Intent(context, VoiceActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🎤  אמרו תזכורת") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("האזנה ברקע", style = MaterialTheme.typography.titleMedium)
                Text(
                    "האפליקציה מקשיבה כל הזמן, וכשאומרים \"תזכיר לי...\" היא קובעת תזכורת. " +
                        "צורכת סוללה. אחרי הפעלה מחדש של הטלפון צריך לפתוח את האפליקציה פעם אחת.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = handsFree,
                onCheckedChange = { on ->
                    settings.handsFree = on
                    handsFree = on
                    when {
                        !on -> ListenService.stop(context)
                        Permissions.granted(context, Manifest.permission.RECORD_AUDIO) -> ListenService.start(context)
                        else -> onRequestPermissions()
                    }
                },
            )
        }
        if (handsFree) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("פטור מחיסכון בסוללה (מומלץ)") }
        }

        HorizontalDivider()
        Text("או הקלידו תזכורת", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = request,
            onValueChange = { request = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("מחר ב-9 לשלם חשבון חשמל") },
        )
        Button(
            enabled = request.isNotBlank(),
            onClick = {
                val outcome = ReminderCreator.create(context, request, requireTrigger = false)
                status = ReminderCreator.message(outcome, request)
                if (outcome is Outcome.Created) {
                    Notifications.showOutcome(context, outcome, request)
                    request = ""
                }
            },
        ) { Text("קביעת תזכורת") }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.primary)

        HorizontalDivider()
        Text("יומן", style = MaterialTheme.typography.titleMedium)
        if (calendarList.isEmpty()) {
            Text(
                if (missing.isEmpty()) "לא נמצא יומן. ודאו שחשבון Google מסונכרן בטלפון."
                else "אשרו גישה ליומן כדי לבחור יומן.",
            )
        }
        calendarList.forEach { calendar ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = calendar.id == selectedId) {
                        selectedId = calendar.id
                        settings.calendarId = calendar.id
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = calendar.id == selectedId, onClick = null)
                Spacer(Modifier.padding(4.dp))
                Column {
                    Text(calendar.name)
                    Text(calendar.account, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()
        Text("דוגמאות", style = MaterialTheme.typography.titleMedium)
        EXAMPLES.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text(
            "אפשר גם: אריח \"תזכורת חדשה\" בהגדרות המהירות, \"Hey Google, פתח תזכורת חדשה\", " +
                "או שיתוף טקסט לאפליקציה.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
