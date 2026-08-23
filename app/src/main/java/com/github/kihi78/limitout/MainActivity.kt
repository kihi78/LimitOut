package com.github.kihi78.limitout

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LimitOutPrimary = Color(0xFF5E80A4)
val LimitOutPrimaryVariant = Color(0xFF95B4D1)
val LimitOutBackground = Color(0xFFF5F7FA)
val LimitOutCardBg = Color(0xFFFFFFFF)

/** 制限時間として選べる分数。 */
val limitOptions = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 20, 25, 30, 40, 50, 60)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LimitOutApp()
                }
            }
        }
    }
}

private fun resolveAppInfo(packageManager: PackageManager, packageName: String): AppInfo = try {
    val appInfo = packageManager.getApplicationInfo(packageName, 0)
    AppInfo(
        appName = packageManager.getApplicationLabel(appInfo).toString(),
        packageName = packageName,
        icon = packageManager.getApplicationIcon(appInfo)
    )
} catch (e: PackageManager.NameNotFoundException) {
    AppInfo(appName = "アプリが見つかりません", packageName = packageName, icon = null)
}

@Composable
fun LimitOutApp() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("limitout_prefs", Context.MODE_PRIVATE) }
    val packageManager = context.packageManager

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    var isEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("is_enabled", false)) }
    var isShowNotification by remember { mutableStateOf(sharedPrefs.getBoolean("show_notification", true)) }

    // 制限対象アプリ（パッケージ名 → 制限時間[分]・有効/無効）
    var targetApps by remember { mutableStateOf(TargetAppsStore.load(sharedPrefs)) }
    var selectedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    var snoozeTime by remember { mutableStateOf(sharedPrefs.getString("snooze_time", "10") ?: "10") }

    var showAppSelector by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var editingPackage by remember { mutableStateOf<String?>(null) }

    val saveTargetApps: (Map<String, TargetAppConfig>) -> Unit = { updated ->
        targetApps = updated
        TargetAppsStore.save(sharedPrefs, updated)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        withContext(Dispatchers.IO) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            val apps = resolveInfos.map {
                AppInfo(
                    appName = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(packageManager)
                )
            }
                // 同じパッケージがランチャーActivityの数だけ重複するため取り除く
                .distinctBy { it.packageName }
                .sortedBy { it.appName.lowercase() }
            installedApps = apps
        }
    }

    // 選択中のアプリ名とアイコンを解決する（対象アプリが増減したときだけ実行）
    LaunchedEffect(targetApps.keys) {
        val packages = targetApps.keys.toList()
        selectedApps = withContext(Dispatchers.IO) {
            packages.map { resolveAppInfo(packageManager, it) }.sortedBy { it.appName.lowercase() }
        }
    }

    editingPackage?.let { packageName ->
        val appName = selectedApps.find { it.packageName == packageName }?.appName ?: packageName
        val currentConfig = targetApps[packageName]
            ?: TargetAppConfig(TargetAppsStore.DEFAULT_LIMIT_MINUTES)
        LimitTimeDialog(
            appName = appName,
            currentMinutes = currentConfig.limitMinutes,
            onConfirm = { minutes ->
                saveTargetApps(targetApps + (packageName to currentConfig.copy(limitMinutes = minutes)))
                editingPackage = null
            },
            onDismiss = { editingPackage = null }
        )
    }

    AnimatedContent(
        targetState = showAppSelector,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(250)) { width -> width } + fadeIn(tween(250))) togetherWith
                    (slideOutHorizontally(tween(250)) { width -> -width / 4 } + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(tween(250)) { width -> -width / 4 } + fadeIn(tween(250))) togetherWith
                    (slideOutHorizontally(tween(250)) { width -> width } + fadeOut(tween(150)))
            }
        },
        label = "screen_transition"
    ) { isSelectorVisible ->
        if (isSelectorVisible) {
            AppSelectionScreen(
                apps = installedApps,
                initiallySelectedPackages = targetApps.keys,
                onConfirm = { newSelection ->
                    val updated = newSelection.associateWith { pkg ->
                        targetApps[pkg] ?: TargetAppConfig(TargetAppsStore.DEFAULT_LIMIT_MINUTES)
                    }
                    saveTargetApps(updated)
                    showAppSelector = false
                },
                onBack = { showAppSelector = false }
            )
        } else {
            MainSettingsScreen(
                isEnabled = isEnabled,
                onEnabledChange = {
                    isEnabled = it
                    sharedPrefs.edit().putBoolean("is_enabled", it).apply()
                },
                isShowNotification = isShowNotification,
                onNotificationChange = {
                    isShowNotification = it
                    sharedPrefs.edit().putBoolean("show_notification", it).apply()
                },
                selectedApps = selectedApps,
                targetApps = targetApps,
                onSelectAppClick = { showAppSelector = true },
                onEditLimitClick = { editingPackage = it },
                onRemoveApp = { saveTargetApps(targetApps - it) },
                onToggleApp = { pkg, enabled ->
                    targetApps[pkg]?.let { config ->
                        saveTargetApps(targetApps + (pkg to config.copy(enabled = enabled)))
                    }
                },
                snoozeTime = snoozeTime,
                onSnoozeTimeChange = {
                    snoozeTime = it
                    sharedPrefs.edit().putString("snooze_time", it).apply()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isShowNotification: Boolean,
    onNotificationChange: (Boolean) -> Unit,
    selectedApps: List<AppInfo>,
    targetApps: Map<String, TargetAppConfig>,
    onSelectAppClick: () -> Unit,
    onEditLimitClick: (String) -> Unit,
    onRemoveApp: (String) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    snoozeTime: String,
    onSnoozeTimeChange: (String) -> Unit
) {
    Scaffold(
        containerColor = LimitOutBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("LimitOut", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = LimitOutPrimary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // カード1: システム設定
            item {
                SettingCard(title = "稼働設定", icon = Icons.Default.Security) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("制限機能の有効化", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = onEnabledChange,
                            colors = SwitchDefaults.colors(checkedTrackColor = LimitOutPrimary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("通知を表示（常駐）", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = isShowNotification,
                            onCheckedChange = onNotificationChange,
                            colors = SwitchDefaults.colors(checkedTrackColor = LimitOutPrimary)
                        )
                    }
                }
            }

            // カード2: ターゲットアプリ（複数選択・アプリごとの制限時間）
            item {
                SettingCard(title = "制限対象", icon = Icons.Default.Smartphone) {
                    Column(modifier = Modifier.animateContentSize()) {
                        if (selectedApps.isEmpty()) {
                            Text(
                                "制限するアプリが選択されていません",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            selectedApps.forEach { app ->
                                val config = targetApps[app.packageName]
                                    ?: TargetAppConfig(TargetAppsStore.DEFAULT_LIMIT_MINUTES)
                                TargetAppRow(
                                    app = app,
                                    config = config,
                                    onEditLimitClick = { onEditLimitClick(app.packageName) },
                                    onRemoveClick = { onRemoveApp(app.packageName) },
                                    onToggleEnabled = { onToggleApp(app.packageName, it) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onSelectAppClick) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = LimitOutPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("アプリを追加・変更", color = LimitOutPrimary)
                    }
                }
            }

            // カード3: タイマー設定
            item {
                SettingCard(title = "時間設定", icon = Icons.Default.Timer) {
                    Text("一定時間停止 (スヌーズ)", style = MaterialTheme.typography.labelLarge, color = LimitOutPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = snoozeTime,
                        onValueChange = {
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                onSnoozeTimeChange(it)
                            }
                        },
                        trailingIcon = { Text("分") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun TargetAppRow(
    app: AppInfo,
    config: TargetAppConfig,
    onEditLimitClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    val contentAlpha = if (config.enabled) 1f else 0.4f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditLimitClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = config.enabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = LimitOutPrimary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (app.icon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = app.icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp).alpha(contentAlpha)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = app.appName,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).alpha(contentAlpha)
        )
        Text(
            text = "${config.limitMinutes} 分",
            style = MaterialTheme.typography.labelLarge,
            color = LimitOutPrimary,
            modifier = Modifier.alpha(contentAlpha)
        )
        IconButton(onClick = onRemoveClick) {
            Icon(
                Icons.Default.Close,
                contentDescription = "${app.appName} を制限対象から外す",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 制限時間を分単位で選ぶピッカー。 */
@Composable
fun MinutePicker(
    selectedMinutes: Int,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            android.widget.NumberPicker(ctx).apply {
                minValue = 0
                maxValue = limitOptions.size - 1
                displayedValues = limitOptions.map { "$it 分" }.toTypedArray()
                wrapSelectorWheel = true
                value = limitOptions.indexOf(selectedMinutes).takeIf { it >= 0 } ?: 0
                setOnValueChangedListener { _, _, newVal ->
                    onMinutesChange(limitOptions[newVal])
                }
            }
        },
        update = { picker ->
            picker.value = limitOptions.indexOf(selectedMinutes).takeIf { it >= 0 } ?: 0
        },
        modifier = modifier.fillMaxWidth().height(120.dp)
    )
}

@Composable
fun LimitTimeDialog(
    appName: String,
    currentMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMinutes by remember(currentMinutes) { mutableStateOf(currentMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$appName の制限時間") },
        text = {
            MinutePicker(
                selectedMinutes = selectedMinutes,
                onMinutesChange = { selectedMinutes = it }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedMinutes) }) { Text("決定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

// 共通で使うデザインカード
@Composable
fun SettingCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LimitOutCardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = LimitOutPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LimitOutPrimary)
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(
    apps: List<AppInfo>,
    initiallySelectedPackages: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    // 「追加」を押すまでは確定させないためのステージング用の選択状態
    var stagedSelection by remember { mutableStateOf(initiallySelectedPackages) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アプリを選択 (${stagedSelection.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    TextButton(onClick = { onConfirm(stagedSelection) }) {
                        Text("追加", color = LimitOutPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        if (apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(apps, key = { it.packageName }) { app ->
                    val isSelected = stagedSelection.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                stagedSelection = if (isSelected) {
                                    stagedSelection - app.packageName
                                } else {
                                    stagedSelection + app.packageName
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                stagedSelection = if (it) {
                                    stagedSelection + app.packageName
                                } else {
                                    stagedSelection - app.packageName
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = LimitOutPrimary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Image(
                            painter = rememberDrawablePainter(drawable = app.icon),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = app.appName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
