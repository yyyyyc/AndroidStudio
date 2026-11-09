package com.example.voicemimic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Muted = Color(0xFFB9B4D0)

/** Seconds since the composable appeared, updated every frame (drives the bird animations). */
@Composable
private fun animTime(): Float {
    val t by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }
    return t
}

@Composable
fun RaceScreen(vm: MimicViewModel) {
    val r = vm.race
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    Box(Modifier.fillMaxSize()) {
        when (r.phase) {
            RacePhase.LOBBY -> RaceLobby(vm)
            RacePhase.HOSTING, RacePhase.JOINING, RacePhase.WAITING -> RaceConnect(vm)
            else -> RaceTrack(vm)
        }
        if (r.busy) BusyOverlay(vm.s.preparing)
    }
}

// ================================================================ lobby

@Composable
private fun RaceLobby(vm: MimicViewModel) {
    val s = vm.s
    val r = vm.race
    val mic = rememberMicGate(vm)
    val time = animTime()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(s.raceTitle, vm, onBack = vm::back)
        // a little preview: both birds walking towards the flag
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(130.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3B2F7A), Color(0xFF6A4FC9))))) {
            val w = size.width; val h = size.height
            drawRect(Color(0xFF2E7D4F), Offset(0f, h * 0.78f), Size(w, h * 0.22f))
            drawFinishFlag(w - h * 0.55f * 0.55f - 20f, h * 0.8f, h * 0.55f, time)
            val p1 = (time * 0.12f) % 1f
            val p2 = (time * 0.09f + 0.3f) % 1f
            drawParrot(w * 0.1f + p2 * w * 0.7f, h * 0.84f, h * 0.55f, BirdPalette.BLUE, time * 7f, false, time)
            drawParrot(w * 0.1f + p1 * w * 0.7f, h * 0.9f, h * 0.62f, BirdPalette.GREEN, time * 8f, true, time)
        }
        Text(s.raceRules, Modifier.padding(16.dp), color = Color(0xFFE2DEF5))

        Text(s.raceVoice, Modifier.padding(horizontal = 20.dp), color = Muted, fontSize = 13.sp)
        if (vm.voices.isEmpty()) {
            Text(s.raceNoVoices, Modifier.padding(20.dp), color = Color(0xFFFFC145))
        } else {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                for (v in vm.voices.take(8)) {
                    Row(Modifier.fillMaxWidth().clickable { r.voiceId = v.id }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(r.voiceId == v.id, { r.voiceId = v.id })
                        Text(v.name, Modifier.weight(1f), maxLines = 1)
                        Text(s.sec(v.durationSec), color = Color(0xFF9C97B5), fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }

        Text(s.raceTime, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), color = Muted, fontSize = 13.sp)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            RaceLevel.entries.forEachIndexed { i, lv ->
                SegmentedButton(selected = r.level == lv, onClick = { r.level = lv },
                    shape = SegmentedButtonDefaults.itemShape(i, RaceLevel.entries.size), icon = {}) {
                    Text(s.raceLevels[i], maxLines = 1, fontSize = 13.sp)
                }
            }
        }

        val canPlay = r.voiceId != null && vm.voices.any { it.id == r.voiceId }
        Spacer(Modifier.height(12.dp))
        Button({ mic { r.startSolo() } }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), enabled = canPlay) {
            Icon(Icons.Default.Person, null); Spacer(Modifier.width(8.dp)); Text(s.raceSolo, fontSize = 17.sp)
        }
        Text(s.raceWifiHint, Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), textAlign = TextAlign.Center, color = Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton({ mic { r.host() } }, Modifier.weight(1f).height(52.dp), enabled = canPlay) {
                Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(6.dp)); Text(s.raceHost, maxLines = 1)
            }
            FilledTonalButton({ mic { r.join() } }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.WifiFind, null); Spacer(Modifier.width(6.dp)); Text(s.raceJoin, maxLines = 1)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ================================================================ connecting

@Composable
private fun RaceConnect(vm: MimicViewModel) {
    val s = vm.s
    val r = vm.race
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar(s.raceTitle, vm, onBack = r::leave)
        Spacer(Modifier.height(24.dp))
        when (r.phase) {
            RacePhase.HOSTING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(s.raceWaitingFriend, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("${s.raceVoice}: ${r.voiceName}  ·  ${s.raceLevels[r.level.ordinal]}", color = Muted)
                r.localAddress?.let {
                    Spacer(Modifier.height(20.dp))
                    Text(s.raceYourAddress.format(""), color = Muted)
                    Text(if (r.port == NetLink.DEFAULT_PORT) it else "$it:${r.port}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
            RacePhase.JOINING -> {
                Text(s.raceSearching, color = Muted)
                Spacer(Modifier.height(12.dp))
                if (r.games.isEmpty()) CircularProgressIndicator()
                for (g in r.games) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { r.connectTo(g.host, g.port) },
                        colors = CardDefaults.cardColors(containerColor = CardBg)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, null, tint = Purple); Spacer(Modifier.width(12.dp))
                            Text(g.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                var addr by remember { mutableStateOf("") }
                Text(s.raceManual, color = Muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(addr, { addr = it.filter { c -> c.isDigit() || c == '.' || c == ':' } }, Modifier.weight(1f),
                        singleLine = true, placeholder = { Text("192.168.1.20") })
                    Spacer(Modifier.width(8.dp))
                    Button({
                        val parts = addr.trim().split(":")
                        r.connectTo(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: NetLink.DEFAULT_PORT)
                    }, enabled = addr.count { it == '.' } == 3) { Text(s.raceConnect) }
                }
            }
            else -> {   // WAITING: connected
                BirdsPair(r)
                Spacer(Modifier.height(12.dp))
                Text(s.raceConnectedTo.format(r.oppName.ifBlank { "…" }), style = MaterialTheme.typography.titleMedium)
                if (r.voiceName.isNotBlank()) Text("${s.raceVoice}: ${r.voiceName}  ·  ${s.raceLevels[r.level.ordinal]}", color = Muted)
                Spacer(Modifier.height(20.dp))
                if (r.isHost) {
                    if (!r.oppReady) { CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text(s.raceFriendPreparing, color = Muted) }
                    else Button(r::again, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                        Icon(Icons.Default.Flag, null); Spacer(Modifier.width(8.dp)); Text(s.raceStart, fontSize = 18.sp)
                    }
                } else if (!r.busy) Text(s.raceWaitHost, color = Muted)
                Spacer(Modifier.height(20.dp))
                Text(s.raceRoomTip, Modifier.padding(horizontal = 24.dp), color = Color(0xFF8E89A8), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Your bird and your friend's bird, side by side. */
@Composable
private fun BirdsPair(r: RaceController) {
    val time = animTime()
    Canvas(Modifier.size(220.dp, 110.dp)) {
        val h = size.height
        drawParrot(size.width * 0.28f, h * 0.95f, h * 0.85f, if (r.isHost) BirdPalette.GREEN else BirdPalette.BLUE, 0f, true, time)
        drawParrot(size.width * 0.72f, h * 0.95f, h * 0.85f, if (r.isHost) BirdPalette.BLUE else BirdPalette.GREEN, 0f, false, time + 1f)
    }
}

// ================================================================ the race

@Composable
private fun RaceTrack(vm: MimicViewModel) {
    val s = vm.s
    val r = vm.race
    val time = animTime()
    val mine = if (!r.network || r.isHost) BirdPalette.GREEN else BirdPalette.BLUE
    val theirs = if (mine === BirdPalette.GREEN) BirdPalette.BLUE else BirdPalette.GREEN

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(r.voiceName, vm, onBack = r::leave)

        // ---- clock + score
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val tl = r.timeLeft
            Column {
                Text(String.format(java.util.Locale.US, "%.1f", tl), fontSize = 40.sp, fontWeight = FontWeight.Black,
                    color = if (tl <= 5f && r.phase == RacePhase.RACE) Color(0xFFFF5C5C) else Color.White)
                Text(s.raceTimeLeft, fontSize = 12.sp, color = Muted)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                val sc = r.myScore
                Text(if (sc == null) "–" else "$sc", fontSize = 40.sp, fontWeight = FontWeight.Black,
                    color = if (sc == null) Color(0xFF6E6988) else scoreColor(sc))
                Text(vm.s.score, fontSize = 12.sp, color = Muted)
            }
        }

        // ---- the track (always left to right, also in Hebrew)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val lanes = if (r.network) 2 else 1
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(if (lanes == 2) 290.dp else 190.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2C2266), Color(0xFF5B45B8), Color(0xFF8F7BE8))))) {
                val w = size.width; val h = size.height
                // a few clouds drifting
                for (i in 0 until 3) {
                    val cx = ((i * 0.37f + time * 0.015f * (i + 1)) % 1.2f - 0.1f) * w
                    val cy = h * (0.08f + i * 0.07f)
                    drawCircle(Color(0x33FFFFFF), 16f + i * 4f, Offset(cx, cy))
                    drawCircle(Color(0x33FFFFFF), 12f + i * 3f, Offset(cx + 20f, cy + 4f))
                }
                val margin = w * 0.09f
                val laneH = h * (if (lanes == 2) 0.40f else 0.55f)
                val topOfLanes = h - laneH * lanes - 6f
                val finishX = w - laneH * 0.75f * 0.55f - 14f   // the flag waves to the right of its pole
                for (lane in 0 until lanes) {
                    val ground = topOfLanes + laneH * (lane + 1) - laneH * 0.12f
                    drawRect(Color(0xFF2E7D4F), Offset(0f, ground - 4f), Size(w, laneH * 0.12f + 8f))
                    drawRect(Color(0xFF8D6E4A), Offset(0f, ground), Size(w, 6f))
                    // start line
                    drawLine(Color(0x88FFFFFF), Offset(margin * 0.6f, ground - laneH * 0.55f), Offset(margin * 0.6f, ground + 6f), 3f)
                    drawFinishFlag(finishX, ground + 2f, laneH * 0.75f, time + lane)
                    val meLane = lanes == 1 || lane == 1
                    val prog = if (meLane) r.myProgress else r.oppProgress
                    val walk = if (meLane) r.myWalk else r.oppWalk
                    val singing = if (meLane) r.talking && r.phase == RacePhase.RACE
                        else r.phase == RacePhase.RACE && (r.oppScore ?: 0) > 0 && r.oppProgress < 1f
                    val birdH = laneH * 0.78f
                    val x = margin + prog * (finishX - birdH * 0.42f - margin)   // at the finish the beak touches the pole
                    drawParrot(x, ground + 2f, birdH, if (meLane) mine else theirs, walk, singing, time + lane)
                }
            }
        }
        if (r.network) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(theirs.body, RoundedCornerShape(6.dp)))
            Text(" ${r.oppName.ifBlank { "?" }}" + (r.oppScore?.let { "  ·  $it" } ?: ""), fontSize = 13.sp, color = Muted)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(12.dp).background(mine.body, RoundedCornerShape(6.dp)))
            Text(" ${s.raceYou}", fontSize = 13.sp, color = Muted)
        }

        // ---- what to do now
        Spacer(Modifier.height(8.dp))
        when (r.phase) {
            RacePhase.COUNTDOWN -> Text("${r.countdown}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 84.sp, fontWeight = FontWeight.Black, color = Pink)
            RacePhase.LISTEN -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Hearing, null, Modifier.size(56.dp), tint = Purple)
                Text(s.raceListen, style = MaterialTheme.typography.headlineSmall)
            }
            RacePhase.RACE -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (r.paused) s.raceStopped else s.raceGo, style = MaterialTheme.typography.titleLarge,
                    color = if (r.paused) Color(0xFFFFC145) else Color(0xFF4CD787), textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { PitchTuner(r.pitchOffset) }
                val off = r.pitchOffset
                Text(when {
                    off == null -> s.tunerSilent
                    off < -1f -> s.tunerHigher
                    off > 1f -> s.tunerLower
                    else -> s.tunerOk
                }, Modifier.padding(top = 4.dp), color = if (off != null && kotlin.math.abs(off) <= 1f) Color(0xFF4CD787) else Color(0xFFE2DEF5))
            }
            else -> RaceResult(vm)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RaceResult(vm: MimicViewModel) {
    val s = vm.s
    val r = vm.race
    val o = r.outcome ?: return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when (o) {
                RaceOutcome.WIN -> s.raceWin
                RaceOutcome.TIE -> s.raceTie
                RaceOutcome.LOSE -> if (r.network) s.raceLoseNet else s.raceLose
            }, fontSize = 34.sp, fontWeight = FontWeight.Black,
                color = when (o) { RaceOutcome.WIN -> Color(0xFF4CD787); RaceOutcome.TIE -> Color(0xFFFFC145); else -> Color(0xFFFF7A59) })
            val fin = r.myFinishMs
            Text(if (fin != null) s.raceFinishedIn.format(s.sec(fin / 1000f)) else s.raceGotTo.format((r.myProgress * 100).toInt()), color = Muted)
            if (r.network && r.myFinishMs == null && r.oppFinishMs == null) Text(s.raceTimeUp, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (r.myBest >= 0) Text("${s.raceBest}: ${r.myBest}", color = scoreColor(r.myBest), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(r::leave) { Text(s.raceLobby) }
                when {
                    !r.network -> Button(r::again, colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                        Icon(Icons.Default.Flag, null); Spacer(Modifier.width(6.dp)); Text(s.raceAgain)
                    }
                    r.isHost -> Button(r::again, enabled = r.oppConnected && r.oppReady, colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                        Icon(Icons.Default.Flag, null); Spacer(Modifier.width(6.dp)); Text(s.raceAgain)
                    }
                    else -> {}
                }
            }
            if (r.network && !r.isHost && r.oppConnected) Text(s.raceWaitHost, Modifier.padding(top = 8.dp), color = Muted, fontSize = 13.sp)
        }
    }
}
