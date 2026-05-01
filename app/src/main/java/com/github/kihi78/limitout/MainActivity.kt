package com.github.kihi78.limitout

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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

    // 通知権限を要求するためのランチャー
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* 許可されたかどうかの結果（今回は特に処理しなくてOK） */ }
    )

    // 状態管理
    var isEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("is_enabled", false)) }
    var isShowNotification by remember { mutableStateOf(sharedPrefs.getBoolean("show_notification", true)) }
    var targetPackageName by remember { mutableStateOf(sharedPrefs.getString("target_package", "") ?: "") }
    var targetAppName by remember { mutableStateOf("未選択") }

    var limitTime by remember { mutableStateOf(sharedPrefs.getInt("limit_time", 5).toString()) }
    var snoozeTime by remember { mutableStateOf(sharedPrefs.getString("snooze_time", "10") ?: "10") }

    var showAppSelector by remember { mutableStateOf(false) } // アプリ選択画面の表示フラグ
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    // 初期化時に、保存されているパッケージ名からアプリ名を復元する
    LaunchedEffect(targetPackageName) {
        if (targetPackageName.isNotEmpty()) {
            try {
                val appInfo = packageManager.getApplicationInfo(targetPackageName, 0)
                targetAppName = packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                targetAppName = "アプリが見つかりません"
            }
        }
    }

    // アプリ一覧を取得する非同期処理
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        withContext(Dispatchers.IO) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)

            // アプリ名でアルファベット順（あいうえお順）にソートしてリスト化
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

    if (showAppSelector) {
        // --- アプリ選択画面 ---
        AppSelectionScreen(
            apps = installedApps,
            onAppSelected = { selected ->
                targetPackageName = selected.packageName
                targetAppName = selected.appName
                sharedPrefs.edit().putString("target_package", selected.packageName).apply()
                showAppSelector = false
            },
            onBack = { showAppSelector = false }
        )
    } else {
        // --- メイン設定画面 ---
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
            onSelectAppClick = { showAppSelector = true },
            limitTime = limitTime,
            onLimitTimeChange = {
                limitTime = it
                sharedPrefs.edit().putInt("limit_time", it.toInt()).apply()
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
    onSelectAppClick: () -> Unit,
    limitTime: String,
    onLimitTimeChange: (String) -> Unit,
    snoozeTime: String,
    onSnoozeTimeChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val limitOptions = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "20", "25", "30", "40", "50", "60")

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(text = "LimitOut", style = MaterialTheme.typography.headlineLarge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "制限機能の有効化", style = MaterialTheme.typography.titleMedium)
            Switch(checked = isEnabled, onCheckedChange = onEnabledChange)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "通知を表示", style = MaterialTheme.typography.titleMedium)
            Switch(checked = isShowNotification, onCheckedChange = onNotificationChange)
        }

        HorizontalDivider()

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "対象アプリ", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onSelectAppClick, modifier = Modifier.fillMaxWidth()) {
                Text(text = targetAppName)
            }
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = "$limitTime 分",
                onValueChange = {},
                readOnly = true,
                label = { Text("制限時間") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                limitOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("$option 分") },
                        onClick = {
                            onLimitTimeChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = snoozeTime,
            onValueChange = {
                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                    onSnoozeTimeChange(it)
                }
            },
            label = { Text("一定時間停止 (分)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

// アプリ選択画面のレイアウト
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
                    Button(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                        Text("戻る")
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
