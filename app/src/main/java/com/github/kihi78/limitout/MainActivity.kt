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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    var targetPackageName by remember { mutableStateOf(sharedPrefs.getString("target_package", "") ?: "") }
    var targetAppName by remember { mutableStateOf("未選択") }
    var targetAppIcon by remember { mutableStateOf<Drawable?>(null) }

    var limitTime by remember { mutableStateOf(sharedPrefs.getInt("limit_time", 5)) }
    var snoozeTime by remember { mutableStateOf(sharedPrefs.getString("snooze_time", "10") ?: "10") }

    var showAppSelector by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

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
            }.sortedBy { it.appName.lowercase() }
            installedApps = apps
        }
    }

    LaunchedEffect(targetPackageName) {
        if (targetPackageName.isNotEmpty()) {
            try {
                val appInfo = packageManager.getApplicationInfo(targetPackageName, 0)
                targetAppName = packageManager.getApplicationLabel(appInfo).toString()
                targetAppIcon = packageManager.getApplicationIcon(appInfo)
            } catch (e: PackageManager.NameNotFoundException) {
                targetAppName = "アプリが見つかりません"
                targetAppIcon = null
            }
        }
    }

    if (showAppSelector) {
        AppSelectionScreen(
            apps = installedApps,
            onAppSelected = { selected ->
                targetPackageName = selected.packageName
                targetAppName = selected.appName
                targetAppIcon = selected.icon
                sharedPrefs.edit().putString("target_package", selected.packageName).apply()
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
            targetAppName = targetAppName,
            targetAppIcon = targetAppIcon,
            onSelectAppClick = { showAppSelector = true },
            limitTime = limitTime,
            onLimitTimeChange = {
                limitTime = it
                sharedPrefs.edit().putInt("limit_time", it).apply()
            },
            snoozeTime = snoozeTime,
            onSnoozeTimeChange = {
                snoozeTime = it
                sharedPrefs.edit().putString("snooze_time", it).apply()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isShowNotification: Boolean,
    onNotificationChange: (Boolean) -> Unit,
    targetAppName: String,
    targetAppIcon: Drawable?,
    onSelectAppClick: () -> Unit,
    limitTime: Int,
    onLimitTimeChange: (Int) -> Unit,
    snoozeTime: String,
    onSnoozeTimeChange: (String) -> Unit
) {
    val limitOptions = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 20, 25, 30, 40, 50, 60)

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

            // カード2: ターゲットアプリ
            item {
                SettingCard(title = "制限対象", icon = Icons.Default.Smartphone) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSelectAppClick,
                        colors = CardDefaults.cardColors(containerColor = LimitOutBackground),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (targetAppIcon != null) {
                                Image(
                                    painter = rememberDrawablePainter(drawable = targetAppIcon),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            Text(text = targetAppName, style = MaterialTheme.typography.titleMedium, color = LimitOutPrimary)
                        }
                    }
                }
            }

            // カード3: タイマー設定
            item {
                SettingCard(title = "時間設定", icon = Icons.Default.Timer) {
                    Text("連続使用の制限時間", style = MaterialTheme.typography.labelLarge, color = LimitOutPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    AndroidView(
                        factory = { ctx ->
                            android.widget.NumberPicker(ctx).apply {
                                minValue = 0
                                maxValue = limitOptions.size - 1
                                displayedValues = limitOptions.map { "$it 分" }.toTypedArray()
                                wrapSelectorWheel = true
                                value = limitOptions.indexOf(limitTime).takeIf { it >= 0 } ?: 0
                                setOnValueChangedListener { _, _, newVal ->
                                    onLimitTimeChange(limitOptions[newVal])
                                }
                            }
                        },
                        update = { picker ->
                            picker.value = limitOptions.indexOf(limitTime).takeIf { it >= 0 } ?: 0
                        },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

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
    onAppSelected: (AppInfo) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アプリを選択") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
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
                items(apps) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAppSelected(app) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
