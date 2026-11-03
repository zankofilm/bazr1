package ir.bazr.inspector

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val executor = ContextCompat.getMainExecutor(this)
        val vault = BiometricVault(this, executor, SecureStore(this))
        setContent {
            val vm: InspectorViewModel = viewModel()
            BazrTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    InspectorApp(
                        vm = vm,
                        biometricAvailable = vault.isAvailable(),
                        onBiometricUnlock = {
                            vault.unlock(
                                onToken = { token -> vm.resumeWithDeviceToken(token) },
                                onError = vm::onBiometricFailure
                            )
                        },
                        onEnableBiometric = {
                            val token = vm.standardDeviceToken()
                            if (token == null) vm.onBiometricFailure("برای فعال‌سازی اثر انگشت یک‌بار با رمز وارد شوید")
                            else vault.enable(token, vm::onBiometricEnabled, vm::onBiometricFailure)
                        }
                    )
                }
            }
        }
    }
}

private val Green = Color(0xFF087F5B)
private val GreenLight = Color(0xFFE7F6EF)
private val Orange = Color(0xFFF08C24)
private val OrangeLight = Color(0xFFFFF1E3)
private val Red = Color(0xFFC92A2A)
private val Ink = Color(0xFF17211E)
private val Muted = Color(0xFF687773)
private val Surface = Color(0xFFF7FAF8)

@Composable
fun BazrTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.lightColorScheme(
        primary = Green,
        onPrimary = Color.White,
        primaryContainer = GreenLight,
        secondary = Orange,
        secondaryContainer = OrangeLight,
        error = Red,
        background = Surface,
        surface = Color.White,
        onSurface = Ink
    )
    MaterialTheme(colorScheme = scheme, typography = androidx.compose.material3.Typography(), content = content)
}

@Composable
private fun InspectorApp(
    vm: InspectorViewModel,
    biometricAvailable: Boolean,
    onBiometricUnlock: () -> Unit,
    onEnableBiometric: () -> Unit
) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.error, vm.info) {
        val msg = vm.error ?: vm.info
        if (!msg.isNullOrBlank()) {
            snack.showSnackbar(msg)
            vm.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            "login" -> LoginScreen(vm, biometricAvailable, onBiometricUnlock, snack)
            "home" -> MainShell(vm, "home", biometricAvailable, onEnableBiometric, snack)
            "missions" -> MainShell(vm, "missions", biometricAvailable, onEnableBiometric, snack)
            "reports" -> MainShell(vm, "reports", biometricAvailable, onEnableBiometric, snack)
            "checklist" -> ChecklistScreen(vm, snack)
            else -> LoginScreen(vm, biometricAvailable, onBiometricUnlock, snack)
        }
        if (vm.busy) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("در حال پردازش…")
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    vm: InspectorViewModel,
    biometricAvailable: Boolean,
    onBiometricUnlock: () -> Unit,
    snack: SnackbarHostState
) {
    var server by remember { mutableStateOf(vm.store.apiBaseUrl.removeSuffix("/api/")) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            item {
                Spacer(Modifier.height(36.dp))
                Box(
                    Modifier.size(72.dp).background(GreenLight, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Assignment, null, tint = Green, modifier = Modifier.size(40.dp)) }
                Spacer(Modifier.height(18.dp))
                Text("بازرسی", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink)
                Text("اپلیکیشن میدانی بازرسان", color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(28.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("آدرس سرور") },
                    supportingText = { Text("مثال: https://inspection.example.ir") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("نام کاربری بازرس") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("رمز عبور") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { vm.login(server, username, password) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("ورود به سامانه", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                if (biometricAvailable && vm.biometricEnabled && vm.store.apiBaseUrl.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onBiometricUnlock,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, null)
                        Spacer(Modifier.width(8.dp))
                        Text("ورود با اثر انگشت")
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    "رمز عبور فقط برای ورود اولیه به سرور ارسال می‌شود و برای ورود بیومتریک روی گوشی ذخیره نمی‌شود.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    vm: InspectorViewModel,
    tab: String,
    biometricAvailable: Boolean,
    onEnableBiometric: () -> Unit,
    snack: SnackbarHostState
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(if (tab == "home") "بازرسی" else if (tab == "missions") "ماموریت‌های من" else "گزارش‌ها") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
                actions = {
                    IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, "همگام‌سازی") }
                    IconButton(onClick = { vm.logout() }) { Icon(Icons.Default.Logout, "خروج") }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = tab == "home", onClick = { vm.navigate("home") }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("خانه") })
                NavigationBarItem(selected = tab == "missions", onClick = { vm.navigate("missions") }, icon = { Icon(Icons.Default.Assignment, null) }, label = { Text("ماموریت‌ها") })
                NavigationBarItem(selected = tab == "reports", onClick = { vm.navigate("reports") }, icon = { Icon(Icons.Default.Description, null) }, label = { Text("گزارش‌ها") })
            }
        }
    ) { pad ->
        when (tab) {
            "home" -> HomeScreen(vm, biometricAvailable, onEnableBiometric, Modifier.padding(pad))
            "missions" -> MissionsScreen(vm, Modifier.padding(pad))
            else -> ReportsScreen(vm, Modifier.padding(pad))
        }
    }
}

@Composable
private fun HomeScreen(vm: InspectorViewModel, biometricAvailable: Boolean, onEnableBiometric: () -> Unit, modifier: Modifier = Modifier) {
    val snap = vm.snapshot
    val missions = snap?.missions.orEmpty()
    val reports = snap?.reports.orEmpty()
    val completed = reports.map { it.missionKey }.toSet().size
    val active = missions.count { it.canExecute }
    val total = missions.size.coerceAtLeast(1)
    val progress = (completed.toFloat() / total).coerceIn(0f, 1f)
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("سلام، ${snap?.profile?.name ?: "بازرس"}", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("بازرس میدانی • ${snap?.profile?.inspectorRef.orEmpty()}", color = Muted)
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Green), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("پیشرفت مأموریت‌ها", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(9.dp), color = Color.White, trackColor = Color.White.copy(alpha = .25f))
                    Spacer(Modifier.height(10.dp))
                    Text("${(progress * 100).roundToInt()}٪ • $completed گزارش ثبت‌شده", color = Color.White.copy(alpha = .95f))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("ماموریت", missions.size.toString(), GreenLight, Green, Modifier.weight(1f))
                StatCard("فعال امروز", active.toString(), OrangeLight, Orange, Modifier.weight(1f))
                StatCard("گزارش", reports.size.toString(), Color(0xFFE9F0FF), Color(0xFF315CA8), Modifier.weight(1f))
            }
            if (biometricAvailable && !vm.biometricEnabled && vm.standardDeviceToken() != null) {
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = GreenLight), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Fingerprint, null, tint = Green, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ورود با اثر انگشت", fontWeight = FontWeight.Bold)
                            Text("بعد از فعال‌سازی، نیازی به واردکردن مجدد رمز نیست.", color = Muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = onEnableBiometric) { Text("فعال‌سازی") }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("ماموریت‌های امروز", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (missions.isEmpty()) item { EmptyCard("ماموریتی برای نمایش وجود ندارد. برای دریافت آخرین اطلاعات همگام‌سازی کنید.") }
        items(missions.take(4), key = { it.key }) { MissionCard(it) { vm.openMission(it) } }
        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.navigate("missions") }, modifier = Modifier.fillMaxWidth()) { Text("مشاهده همه ماموریت‌ها") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, bg: Color, fg: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = bg), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = fg, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text(title, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MissionsScreen(vm: InspectorViewModel, modifier: Modifier = Modifier) {
    val missions = vm.snapshot?.missions.orEmpty()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = OrangeLight), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudSync, null, tint = Orange)
                    Spacer(Modifier.width(10.dp))
                    Text("برنامه مأموریت‌ها از سرور دریافت می‌شود؛ اجرای مأموریت فقط در تاریخ مجاز انجام می‌شود.", fontSize = 12.sp, color = Ink)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (missions.isEmpty()) item { EmptyCard("ماموریتی یافت نشد") }
        items(missions, key = { it.key }) { MissionCard(it) { vm.openMission(it) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MissionCard(m: Mission, onClick: () -> Unit) {
    val statusColor = if (m.canExecute) Green else if (m.scheduleState == "upcoming") Orange else Muted
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(GreenLight, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Assignment, null, tint = Green) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.type, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(m.orgName, color = Muted, fontSize = 13.sp)
                }
                Text(m.scheduleLabel.ifBlank { m.status }, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text("تاریخ: ${m.date.ifBlank { "—" }} • ${m.questions.size} سؤال", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReportsScreen(vm: InspectorViewModel, modifier: Modifier = Modifier) {
    val reports = vm.snapshot?.reports.orEmpty()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        if (reports.isEmpty()) item { EmptyCard("هنوز گزارش نهایی از این حساب ثبت نشده است") }
        items(reports, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Green)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("گزارش ${r.missionKey}", fontWeight = FontWeight.Bold)
                            Text("کد رسید: ${r.receiptCode.ifBlank { "—" }}", color = Muted, fontSize = 12.sp)
                        }
                        Text(reviewLabel(r.reviewStatus), color = if (r.reviewStatus == "approved") Green else Orange, fontSize = 12.sp)
                    }
                    if (r.submittedAt.isNotBlank()) Text(r.submittedAt, color = Muted, fontSize = 11.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun reviewLabel(v: String) = when (v) {
    "approved" -> "تأیید شده"
    "rejected" -> "نیازمند اصلاح"
    else -> "در انتظار بررسی"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistScreen(vm: InspectorViewModel, snack: SnackbarHostState) {
    val mission = vm.selectedMission ?: return
    val draft = vm.currentDraft() ?: return
    @Suppress("UNUSED_VARIABLE") val tick = vm.draftTick
    var confirmSubmit by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Column { Text("چک‌لیست بازرسی"); Text(mission.orgName, fontSize = 11.sp, color = Muted) } },
                navigationIcon = { TextButton(onClick = { vm.navigate("missions") }) { Text("بازگشت") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        }
    ) { pad ->
        val answered = mission.questions.count { draft.answers.containsKey(it.id) }
        val progress = if (mission.questions.isEmpty()) 0f else answered.toFloat() / mission.questions.size
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = if (mission.canExecute) GreenLight else OrangeLight), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(mission.type, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("${mission.date} • ${mission.scheduleLabel}", color = if (mission.canExecute) Green else Orange)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("$answered از ${mission.questions.size} سؤال پاسخ داده شده", fontSize = 12.sp, color = Muted)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            mission.sections.forEach { section ->
                item(key = "section-${section.id}") {
                    Text(section.title, fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    Text(section.category, color = Muted, fontSize = 11.sp)
                }
                items(mission.questions.filter { it.sectionId == section.id }, key = { it.id }) { q ->
                    QuestionCard(vm, mission, q, draft)
                }
            }
            val orphan = mission.questions.filter { q -> mission.sections.none { it.id == q.sectionId } }
            if (orphan.isNotEmpty()) {
                item { Text("سایر سؤالات", fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp)) }
                items(orphan, key = { it.id }) { q -> QuestionCard(vm, mission, q, draft) }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { confirmSubmit = true },
                    enabled = mission.canExecute && answered == mission.questions.size,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("ثبت و ارسال نهایی", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                if (!mission.canExecute) Text("این مأموریت در حال حاضر مجاز به اجرا نیست.", color = Orange, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.height(28.dp))
            }
        }
    }
    if (confirmSubmit) {
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("ارسال نهایی گزارش") },
            text = { Text("پس از ارسال، گزارش روی سرور ثبت و قفل می‌شود. از کامل بودن پاسخ‌ها و مستندات مطمئن شوید.") },
            confirmButton = { Button(onClick = { confirmSubmit = false; vm.submitMission() }) { Text("ارسال نهایی") } },
            dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("انصراف") } }
        )
    }
}

@Composable
private fun QuestionCard(vm: InspectorViewModel, mission: Mission, q: ChecklistQuestion, draft: MissionDraft) {
    val context = LocalContext.current
    val answer = draft.answers[q.id]
    val note = draft.notes[q.id].orEmpty()
    val evidence = draft.uploads.filter { it.questionId == q.id }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadEvidence(context.contentResolver, q.id, uri)
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(q.id, color = Green, fontWeight = FontWeight.Black, modifier = Modifier.background(GreenLight, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.width(10.dp))
                Text(q.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), lineHeight = 23.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnswerButton("بله", 100.0, answer, Color(0xFFE4F5ED), Green, Modifier.weight(1f)) { vm.setAnswer(q.id, 100.0) }
                AnswerButton("نسبی", 50.0, answer, OrangeLight, Orange, Modifier.weight(1f)) { vm.setAnswer(q.id, 50.0) }
                AnswerButton("خیر", 0.0, answer, Color(0xFFFFE8E8), Red, Modifier.weight(1f)) { vm.setAnswer(q.id, 0.0) }
            }
            if (answer != null && answer < 60) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { vm.setNote(q.id, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("توضیح الزامی برای امتیاز پایین") },
                    minLines = 2
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { launcher.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")) }) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (evidence.isEmpty()) "افزودن مستند" else "مستند (${evidence.size})")
                }
                if ((answer != null && answer < 30) || q.critical) {
                    Spacer(Modifier.width(8.dp))
                    Text("مستند الزامی", color = Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            if (evidence.isNotEmpty()) {
                Spacer(Modifier.height(8.dp)); HorizontalDivider()
                evidence.forEach { Text("✓ ${it.name}", color = Green, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) }
            }
        }
    }
}

@Composable
private fun AnswerButton(label: String, value: Double, current: Double?, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    val selected = current == value
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) fg else bg, contentColor = if (selected) Color.White else fg),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp)
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
private fun EmptyCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Text(text, Modifier.padding(18.dp), color = Muted)
    }
}
