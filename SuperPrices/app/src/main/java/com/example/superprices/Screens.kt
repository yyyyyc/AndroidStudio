package com.example.superprices

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFEF6C00)

/** "2026-12-11T23:59" → "11/12/26". */
fun shortDate(s: String?): String {
    if (s == null || s.length < 10) return ""
    return "${s.substring(8, 10)}/${s.substring(5, 7)}/${s.substring(2, 4)}"
}

/** A file stamp "20261106043100" → "06/11 04:31". */
fun stampText(s: String?): String =
    if (s == null || s.length < 12) "טרם עודכן" else "${s.substring(6, 8)}/${s.substring(4, 6)} ${s.substring(8, 10)}:${s.substring(10, 12)}"

fun qtyText(q: Double) = if (q == Math.floor(q)) q.toLong().toString() else String.format(java.util.Locale.US, "%.2f", q)

@Composable
private fun Hint(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** Opens the scanner, asking for the camera first if needed. */
@Composable
private fun rememberScan(vm: AppViewModel, target: ScanFor): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.scanning = target else vm.snack = "צריך הרשאת מצלמה כדי לסרוק ברקוד"
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            vm.scanning = target
        } else ask.launch(Manifest.permission.CAMERA)
    }
}

@Composable
private fun noStoresHint(vm: AppViewModel): Boolean {
    if (vm.myStores.isEmpty()) {
        Hint("כדי להתחיל, בחר את החנויות שבהן אתה קונה", "לבחירת חנויות") { vm.tab = Tab.STORES }
        return true
    }
    if (vm.myStores.all { it.itemCount == 0 }) {
        if (vm.refreshing) Hint("מוריד מחירים… זה לוקח כחצי דקה לכל חנות")
        else Hint("עדיין אין מחירים לחנויות שבחרת", "הורד מחירים עכשיו") { vm.refreshPrices() }
        return true
    }
    return false
}

// ---------------- Search ----------------

@Composable
fun SearchScreen(vm: AppViewModel) {
    val scan = rememberScan(vm, ScanFor.SEARCH)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQuery,
                label = { Text("שם מוצר או ברקוד") },
                singleLine = true,
                trailingIcon = {
                    if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Filled.Clear, "נקה") }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = scan) { Text("סרוק") }
        }
        if (noStoresHint(vm)) return
        if (vm.query.isBlank()) {
            Hint("חפש מוצר כדי להשוות את מחירו ב-${vm.myStores.size} החנויות שלך")
            return
        }
        if (vm.hits.isEmpty()) {
            Hint("לא נמצאו מוצרים")
            return
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(vm.hits, key = { it.code }) { h ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openProduct(h.code) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(h.manufacturer, "ב-${h.stores} חנויות").filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 12.sp, color = Color.Gray,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("מ-${money(h.minPrice)}", color = Green, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { vm.addToList(h.code, h.name) }) { Icon(Icons.Filled.Add, "הוסף לרשימה") }
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------- Product ----------------

@Composable
fun ProductScreen(vm: AppViewModel, p: ProductDetail) {
    val context = LocalContext.current
    var askTarget by remember { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) vm.snack = "בלי הרשאת התראות לא אוכל להודיע על ירידת מחיר"
        askTarget = true
    }
    val min = p.prices.minOfOrNull { it.price }
    val max = p.prices.maxOfOrNull { it.price }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(p.name, style = MaterialTheme.typography.headlineSmall)
            Text("ברקוד ${p.code}", color = Color.Gray, fontSize = 13.sp)
            if (min != null && max != null && max > min + 0.001) {
                Text(
                    "הפרש בין הזול ליקר: ${money(max - min)} (${((max - min) / max * 100).toInt()}%)",
                    color = Orange, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addToList(p.code, p.name) }) {
                    Text(if (p.qtyInList > 0) "ברשימה (${qtyText(p.qtyInList)}) · עוד אחד" else "הוסף לרשימה")
                }
                if (p.watch != null) {
                    OutlinedButton(onClick = { vm.removeWatch(p.code) }) { Text("הפסק מעקב") }
                } else {
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(context)) {
                            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else askTarget = true
                    }) { Text("עקוב אחרי המחיר") }
                }
            }
            p.watch?.let { w ->
                Text(
                    if (w.target != null) "במעקב: אודיע כשהמחיר יגיע ל-${money(w.target)}" else "במעקב: אודיע על כל ירידת מחיר",
                    fontSize = 13.sp, color = Green,
                )
            }
            Section("מחירים בחנויות שלך")
            if (p.prices.isEmpty()) Text("המוצר לא נמכר כרגע באף אחת מהחנויות שלך", color = Color.Gray)
        }
        items(p.prices, key = { it.store.key }) { sp ->
            val cheapest = min != null && sp.price <= min + 0.001
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sp.store.title, fontWeight = if (cheapest) FontWeight.Bold else FontWeight.Normal)
                    val unit = sp.unitPrice?.let { "${money(it)} ל-${sp.unitMeasure}" }
                    Text(
                        listOfNotNull(sp.store.city.ifBlank { null }, unit, sp.updated.takeIf { it.isNotEmpty() }?.let { "עודכן ${shortDate(it)}" })
                            .joinToString(" · "),
                        fontSize = 12.sp, color = Color.Gray,
                    )
                }
                Text(money(sp.price), color = if (cheapest) Green else Color.Unspecified, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            HorizontalDivider()
        }
        if (p.promos.isNotEmpty()) {
            item { Section("מבצעים על המוצר") }
            items(p.promos, key = { it.store.key + it.id }) { pr -> PromoRow(pr) { vm.openPromo(pr) } }
        }
        item {
            Section("היסטוריית מחירים")
            if (p.history.isEmpty()) {
                Text("עוד לא נרשמו שינויי מחיר. כל שינוי יירשם בעדכון היומי.", color = Color.Gray, fontSize = 13.sp)
            } else {
                HistoryChart(p.history, p.storeTitles)
            }
        }
    }

    if (askTarget) WatchDialog(min, onDismiss = { askTarget = false }) { target ->
        askTarget = false
        vm.setWatch(p.code, p.name, target)
    }
    vm.openPromo?.let { PromoDialog(vm, it.first, it.second) }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleMedium, color = Green,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun WatchDialog(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("מעקב מחיר") },
        text = {
            Column {
                Text("אודיע לך כשהמחיר בחנויות שלך יגיע למחיר היעד. השאר ריק כדי לקבל התראה על כל ירידת מחיר.")
                current?.let { Text("המחיר הזול כרגע: ${money(it)}", color = Color.Gray, modifier = Modifier.padding(top = 6.dp)) }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("מחיר יעד (₪)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim().toDoubleOrNull()) }) { Text("שמור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

private val lineColors = listOf(Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF6A1B9A), Color(0xFFC62828), Color(0xFF00838F))

/** Price over time, one line per store; prices hold until the next change. */
@Composable
private fun HistoryChart(points: List<HistoryPoint>, titles: Map<String, String>) {
    val byStore = points.groupBy { it.storeKey }
    val times = points.map { it.at }.distinct().sorted()
    val lo = points.minOf { it.price }
    val hi = points.maxOf { it.price }
    Column {
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp)) {
            val span = (hi - lo).takeIf { it > 0.001 } ?: 1.0
            fun x(t: String) = if (times.size < 2) size.width / 2 else size.width * times.indexOf(t) / (times.size - 1)
            fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat().coerceIn(4f, size.height - 4f)
            byStore.entries.forEachIndexed { i, (_, pts) ->
                val color = lineColors[i % lineColors.size]
                var prev: Offset? = null
                for (pt in pts) {
                    val o = Offset(x(pt.at), y(pt.price))
                    prev?.let {
                        drawLine(color, it, Offset(o.x, it.y), strokeWidth = 4f)
                        drawLine(color, Offset(o.x, it.y), o, strokeWidth = 4f)
                    }
                    drawCircle(color, 6f, o, style = Stroke(3f))
                    prev = o
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortDate(times.first()), fontSize = 11.sp, color = Color.Gray)
            Text("${money(lo)} – ${money(hi)}", fontSize = 11.sp, color = Color.Gray)
            Text(shortDate(times.last()), fontSize = 11.sp, color = Color.Gray)
        }
        byStore.keys.forEachIndexed { i, key ->
            val last = byStore.getValue(key).last()
            Text(
                "● ${titles[key] ?: key}: ${money(last.price)} מ-${shortDate(last.at)}",
                color = lineColors[i % lineColors.size], fontSize = 12.sp,
            )
        }
    }
}

// ---------------- Shopping list ----------------

@Composable
fun ListScreen(vm: AppViewModel) {
    val scan = rememberScan(vm, ScanFor.LIST)
    val b = vm.basket
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = scan) { Text("סרוק והוסף") }
            OutlinedButton(onClick = { vm.tab = Tab.SEARCH }) { Text("חפש והוסף") }
            Spacer(Modifier.weight(1f))
            if (b != null && b.entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("נקה") }
        }
        if (b == null || b.entries.isEmpty()) {
            Hint("הרשימה ריקה. הוסף מוצרים מהחיפוש או בסריקת ברקוד, ותראה איפה הסל הכי זול.")
            return
        }
        val names = b.entries.associate { it.code to it.name }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            item {
                Text("איפה הסל הכי זול", style = MaterialTheme.typography.titleMedium, color = Green)
            }
            items(b.result.totals, key = { "t" + it.storeKey }) { t ->
                val best = t == b.result.totals.first()
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (best) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.titles[t.storeKey] ?: t.storeKey, fontWeight = if (best) FontWeight.Bold else FontWeight.Normal)
                            val promoCount = b.promos[t.storeKey]?.size ?: 0
                            Text(
                                listOfNotNull(
                                    if (t.missing.isEmpty()) "כל המוצרים" else "חסרים ${t.missing.size}: " +
                                        t.missing.take(3).joinToString(", ") { names[it] ?: it } + if (t.missing.size > 3) "…" else "",
                                    if (promoCount > 0) "★ $promoCount במבצע" else null,
                                ).joinToString(" · "),
                                fontSize = 12.sp, color = Color.Gray,
                            )
                        }
                        Text(money(t.total), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (best) Green else Color.Unspecified)
                    }
                }
            }
            if (b.titles.size > 1) item {
                val stores = b.result.split.map { it.storeKey }.distinct().size
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("קנייה מפוצלת: כל מוצר בחנות הכי זולה", fontWeight = FontWeight.Medium)
                        Text("${money(b.result.splitTotal)} ב-$stores חנויות", color = Green, fontWeight = FontWeight.Bold)
                        Text("מחירים לפני מבצעים. ★ = יש מבצע על המוצר בחנות.", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
            item {
                Text("המוצרים", style = MaterialTheme.typography.titleMedium, color = Green, modifier = Modifier.padding(top = 12.dp))
            }
            items(b.entries, key = { it.code }) { e ->
                val line = b.result.split.firstOrNull { it.code == e.code }
                Row(Modifier.fillMaxWidth().clickable { vm.openProduct(e.code) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (line == null) "לא נמכר בחנויות שלך"
                            else "הכי זול: ${money(line.cost / e.qty)} ב${b.titles[line.storeKey] ?: ""}" +
                                if (b.promos[line.storeKey]?.contains(e.code) == true) " ★" else "",
                            fontSize = 12.sp, color = if (line == null) Orange else Color.Gray,
                        )
                    }
                    TextButton(onClick = { vm.setQty(e.code, e.qty - 1) }) { Text(if (e.qty <= 1) "✕" else "−", fontSize = 18.sp) }
                    Text(qtyText(e.qty), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { vm.setQty(e.code, e.qty + 1) }) { Text("+", fontSize = 18.sp) }
                }
                HorizontalDivider()
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("לנקות את הרשימה?") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearList() }) { Text("נקה") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("ביטול") } },
    )
}

// ---------------- Promotions ----------------

@Composable
private fun PromoRow(p: Promo, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(p.description.ifBlank { "מבצע ${p.id}" }, fontWeight = FontWeight.Medium)
        val price = p.discountedPrice?.takeIf { it > 0 }?.let {
            if ((p.minQty ?: 1.0) > 1.0) "${qtyText(p.minQty!!)} ב-${money(it)}" else money(it)
        }
        val club = p.club.takeIf { it.isNotBlank() && !it.startsWith("0") }?.let { "מועדון" }
        Text(
            listOfNotNull(p.store.title, price, club, "עד ${shortDate(p.end)}", "${p.itemCount} מוצרים").joinToString(" · "),
            fontSize = 12.sp, color = Color.Gray,
        )
    }
    HorizontalDivider()
}

@Composable
private fun PromoDialog(vm: AppViewModel, p: Promo, items: List<Triple<String, String?, Double?>>) {
    AlertDialog(
        onDismissRequest = { vm.openPromo = null },
        title = { Text(p.description.ifBlank { "מבצע" }) },
        text = {
            Column {
                Text("${p.store.title}\n${shortDate(p.start)} – ${shortDate(p.end)}", fontSize = 13.sp, color = Color.Gray)
                LazyColumn(Modifier.padding(top = 8.dp).height(320.dp)) {
                    items(items) { (code, name, price) ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = name != null) {
                                vm.openPromo = null; vm.openProduct(code)
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name ?: code, Modifier.weight(1f), fontSize = 14.sp)
                            price?.let { Text(money(it), fontSize = 13.sp, color = Color.Gray) }
                            if (name != null) IconButton(onClick = { vm.addToList(code, name) }) { Icon(Icons.Filled.Add, "הוסף") }
                        }
                    }
                }
                if (p.itemCount > items.size) Text("ועוד ${p.itemCount - items.size}…", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.openPromo = null }) { Text("סגור") } },
    )
}

@Composable
fun PromosScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.promoQuery, onValueChange = vm::onPromoQuery,
            label = { Text("חפש במבצעים") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (noStoresHint(vm)) return
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = vm.promoStore == null, onClick = { vm.onPromoStore(null) }, label = { Text("הכול") }) }
            items(vm.myStores, key = { it.store.key }) { m ->
                FilterChip(
                    selected = vm.promoStore == m.store.key,
                    onClick = { vm.onPromoStore(m.store.key) },
                    label = { Text(m.store.title, maxLines = 1) },
                )
            }
        }
        if (vm.promos.isEmpty()) {
            Hint("אין מבצעים פעילים")
            return
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(vm.promos, key = { it.store.key + it.id }) { p -> PromoRow(p) { vm.openPromo(p) } }
        }
    }
    vm.openPromo?.let {
        BackHandler { vm.openPromo = null }
        PromoDialog(vm, it.first, it.second)
    }
}

// ---------------- Price watch ----------------

@Composable
fun WatchScreen(vm: AppViewModel) {
    if (vm.watches.isEmpty()) {
        Hint("אין מוצרים במעקב. פתח מוצר ולחץ \"עקוב אחרי המחיר\" כדי לקבל התראה כשהוא מוזל.", "לחיפוש מוצר") { vm.tab = Tab.SEARCH }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("המחירים נבדקים בכל עדכון יומי, ותקבל התראה כשמחיר יורד.", fontSize = 13.sp, color = Color.Gray)
        }
        items(vm.watches, key = { it.watch.code }) { r ->
            Row(Modifier.fillMaxWidth().clickable { vm.openProduct(r.watch.code) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.watch.name, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(
                            r.min?.let { "עכשיו ${money(it)}" + (r.storeTitle?.let { t -> " ב$t" } ?: "") } ?: "לא נמכר בחנויות שלך",
                            r.watch.target?.let { "יעד ${money(it)}" },
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                        color = if (r.min != null && r.watch.target != null && r.min <= r.watch.target) Green else Color.Gray,
                    )
                }
                IconButton(onClick = { vm.removeWatch(r.watch.code) }) { Icon(Icons.Filled.Delete, "הסר") }
            }
            HorizontalDivider()
        }
    }
}

// ---------------- Stores ----------------

@Composable
fun StoresScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var chain by remember { mutableStateOf<String?>(null) }
    var editServer by remember { mutableStateOf(false) }
    if (editServer) ServerDialog(vm.serverUrl, onDismiss = { editServer = false }) { editServer = false; vm.saveServer(it) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.findNearby() else vm.snack = "צריך הרשאת מיקום כדי למצוא חנויות קרובות"
    }
    val near = vm.near
    val shown = remember(vm.stores, near, filter, chain) {
        val words = filter.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val base = near ?: vm.stores.map { NearStore(it, null) }
        base.filter { n ->
            val s = n.store
            (chain == null || s.chain == chain) &&
                words.all { w -> s.name.contains(w) || s.city.contains(w) || s.address.contains(w) || Chains.title(s.chain).contains(w) }
        }.take(300)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("החנויות שבחרת (${vm.myStores.size}/${Repository.MAX_STORES})", style = MaterialTheme.typography.titleMedium, color = Green)
                if (vm.myStores.isEmpty()) {
                    Text("עדיין לא בחרת. סמן למטה את הסניפים שבהם אתה קונה.", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
        items(vm.myStores, key = { "m" + it.store.key }) { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.store.title, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(m.store.city.ifBlank { null }, "${m.itemCount} מוצרים · מחירים ${stampText(m.priceStamp)}").joinToString(" · "),
                        fontSize = 12.sp, color = Color.Gray,
                    )
                }
                IconButton(onClick = { vm.toggleStore(m.store) }) { Icon(Icons.Filled.Delete, "הסר") }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (near == null) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            vm.findNearby()
                        } else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }, enabled = vm.storeBusy == null) { Text("חנויות קרובות אליי") }
                } else {
                    OutlinedButton(onClick = vm::clearNearby) { Text("הצג את כל הסניפים") }
                }
                OutlinedButton(onClick = vm::loadStoreDirectory, enabled = vm.storeBusy == null) { Text("רענן סניפים") }
            }
            TextButton(onClick = { editServer = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    if (vm.serverUrl.isBlank()) "מקור המחירים: אתרי הרשתות (אפשר להגדיר שרת)"
                    else "מקור המחירים: שרת ${vm.serverUrl}",
                    fontSize = 13.sp,
                )
            }
            vm.storeBusy?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Orange, fontSize = 13.sp) }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                label = { Text("סנן לפי עיר, שם או כתובת") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = chain == null, onClick = { chain = null }, label = { Text("כל הרשתות") }) }
                items(Chains.all, key = { it.key }) { c ->
                    FilterChip(selected = chain == c.key, onClick = { chain = c.key }, label = { Text(c.title) })
                }
            }
            if (vm.stores.isEmpty() && vm.storeBusy == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = vm::loadStoreDirectory) { Text("הורד רשימת סניפים") }
                }
            }
        }
        items(shown, key = { it.store.key }) { n ->
            val s = n.store
            Row(
                Modifier.fillMaxWidth().clickable { vm.toggleStore(s) }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = s.selected, onCheckedChange = { vm.toggleStore(s) })
                Column(Modifier.weight(1f)) {
                    Text(s.title)
                    Text(
                        listOfNotNull(s.address.ifBlank { null }, s.city.ifBlank { null }, s.subChain.takeIf { it.isNotBlank() && it != "1" })
                            .joinToString(" · "),
                        fontSize = 12.sp, color = Color.Gray,
                    )
                }
                n.km?.let { Text(String.format(java.util.Locale.US, "%.1f ק\"מ", it), fontSize = 13.sp, color = Green) }
            }
        }
    }
}

@Composable
internal fun ServerDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val initial = current.ifBlank { "http://" }
    // Cursor at the end, so typing continues after "http://".
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val text = field.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שרת מחירים") },
        text = {
            Column {
                Text(
                    "אם הפעלת שרת SuperPrices, האפליקציה תוריד ממנו את המחירים במקום מאתרי הרשתות. " +
                        "זה מהיר בהרבה, במיוחד בשופרסל. השאר ריק כדי להוריד ישירות מהרשתות.",
                    fontSize = 14.sp,
                )
                // Addresses read left to right, even in the Hebrew UI.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = field, onValueChange = { field = it },
                        label = { Text("כתובת, למשל http://192.168.1.10:8080") }, singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.takeUnless { it == "http://" }.orEmpty()) }) { Text("שמור") } },
        dismissButton = {
            Row {
                if (current.isNotBlank()) TextButton(onClick = { onSave("") }) { Text("ללא שרת") }
                TextButton(onClick = onDismiss) { Text("ביטול") }
            }
        },
    )
}
