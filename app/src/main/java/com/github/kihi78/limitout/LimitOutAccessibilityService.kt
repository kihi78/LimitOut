package com.github.kihi78.limitout

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class LimitOutAccessibilityService : AccessibilityService() {

    private var lastPackageName: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var debounceJob: Job? = null

    private var snoozeWakeUpJob: Job? = null

    private lateinit var sharedPrefs: SharedPreferences
    private var snoozeEndTimeMillis: Long = 0

    /** 有効な制限対象アプリ（パッケージ名 → 制限時間[分]）。オフにしたアプリは含まない。 */
    private var activeTargets: Map<String, Int> = emptyMap()

    /** 通知表示用に、無効化されたアプリも含めた対象アプリ数を保持する。 */
    private var targetAppCount: Int = 0

    /** 現在計測中のアプリ。別の対象アプリへ移ったらタイマーを引き継がず計測し直す。 */
    private var timerPackage: String? = null

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_LIMITOUT_SNOOZE" -> {
                    val snoozeMinutes = sharedPrefs.getString("snooze_time", "10")?.toLongOrNull() ?: 10L
                    snoozeEndTimeMillis = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)
                    cancelLimitTimer()

                    Log.d("LimitOutService", "一定時間停止を開始: ${snoozeMinutes}分間監視を停止します")
                    Toast.makeText(context, "${snoozeMinutes}分間、監視を一時停止します", Toast.LENGTH_SHORT).show()

                    snoozeWakeUpJob?.cancel()
                    snoozeWakeUpJob = serviceScope.launch {
                        delay((snoozeMinutes * 60 * 1000L) + 200L)
                        snoozeEndTimeMillis = 0L
                        Log.d("LimitOutService", "一定時間停止が終了しました。現在の画面を再確認します。")
                        checkCurrentScreenAndHandleTimer()
                    }
                }
                "ACTION_LIMITOUT_NOTIF_DISMISSED" -> {
                    val isEnabled = sharedPrefs.getBoolean("is_enabled", false)
                    val showNotif = sharedPrefs.getBoolean("show_notification", true)
                    if (isEnabled && showNotif) {
                        Log.d("LimitOutService", "通知が消されましたが、設定がONのため復活させます")
                        showNotification()
                    }
                }
            }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "is_enabled", "show_notification", "snooze_time" -> {
                val isEnabled = prefs.getBoolean("is_enabled", false)
                val showNotif = prefs.getBoolean("show_notification", true)

                if (isEnabled && showNotif) {
                    showNotification()
                } else {
                    hideNotification()
                }

                if (!isEnabled) cancelLimitTimer()
            }
            TargetAppsStore.KEY_TARGET_APPS -> {
                reloadTargetApps(prefs)

                // 対象アプリ・制限時間・有効/無効の変更を即座に反映させるため、計測をやり直す
                cancelLimitTimer()
                if (isServiceEnabled()) {
                    if (prefs.getBoolean("show_notification", true)) showNotification()
                    checkCurrentScreenAndHandleTimer()
                }
            }
        }
    }

    private val baseIgnoredPackages = listOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "android"
    )

    /** 制限対象に選ばれていない限り、画面遷移のノイズとして無視するブラウザ。 */
    private val conditionallyIgnoredBrowsers = listOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser"
    )

    private fun isIgnoredPackage(pkg: String, targets: Set<String>): Boolean {
        if (baseIgnoredPackages.contains(pkg)) return true
        if (conditionallyIgnoredBrowsers.contains(pkg) && !targets.contains(pkg)) return true
        return false
    }

    private fun reloadTargetApps(prefs: SharedPreferences) {
        val allTargets = TargetAppsStore.load(prefs)
        targetAppCount = allTargets.size
        activeTargets = allTargets.filterValues { it.enabled }.mapValues { it.value.limitMinutes }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("LimitOutService", "LimitOutの監視サービスが接続されました！")

        sharedPrefs = getSharedPreferences("limitout_prefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        reloadTargetApps(sharedPrefs)

        val filter = IntentFilter().apply {
            addAction("ACTION_LIMITOUT_SNOOZE")
            addAction("ACTION_LIMITOUT_NOTIF_DISMISSED")
        }
        ContextCompat.registerReceiver(this, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (sharedPrefs.getBoolean("is_enabled", false) && sharedPrefs.getBoolean("show_notification", true)) {
            showNotification()
        }
    }

    private fun isServiceEnabled(): Boolean = sharedPrefs.getBoolean("is_enabled", false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceEnabled()) return

        val targets = activeTargets
        if (targets.isEmpty()) return

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            if (isIgnoredPackage(packageName, targets.keys)) return
            if (packageName == lastPackageName) return

            lastPackageName = packageName

            debounceJob?.cancel()
            debounceJob = serviceScope.launch {
                delay(500L)
                checkCurrentScreenAndHandleTimer()
            }
        }
    }

    private fun checkCurrentScreenAndHandleTimer() {
        val targets = activeTargets
        if (targets.isEmpty()) {
            cancelLimitTimer()
            return
        }

        val activePackage = resolveActivePackage(targets.keys)
        val limitMinutes = targets[activePackage]

        if (limitMinutes != null && System.currentTimeMillis() >= snoozeEndTimeMillis) {
            startLimitTimer(activePackage, limitMinutes)
        } else {
            cancelLimitTimer()
        }
    }

    /** 現在表示中のアプリを返す。無視対象の画面が挟まった場合は直前のアプリとみなす。 */
    private fun resolveActivePackage(targets: Set<String>): String {
        val activePackage = rootInActiveWindow?.packageName?.toString() ?: lastPackageName
        return if (isIgnoredPackage(activePackage, targets)) lastPackageName else activePackage
    }

    private fun startLimitTimer(targetPackage: String, limitMinutes: Int) {
        // 同じアプリを計測中ならそのまま継続する
        if (timerJob?.isActive == true && timerPackage == targetPackage) return

        cancelLimitTimer()
        timerPackage = targetPackage

        timerJob = serviceScope.launch {
            Log.d("LimitOutService", "タイマースタート: ターゲット[$targetPackage] ${limitMinutes}分")
            delay(limitMinutes * 60 * 1000L)

            if (System.currentTimeMillis() < snoozeEndTimeMillis) return@launch

            if (resolveActivePackage(activeTargets.keys) == targetPackage) {
                Log.d("LimitOutService", "制限時間に到達したため、ホーム画面へ強制遷移させます")
                forceGoHome()
            }
        }
    }

    private fun cancelLimitTimer() {
        if (timerJob?.isActive == true) {
            timerJob?.cancel()
            Log.d("LimitOutService", "タイマーをリセットしました")
        }
        timerJob = null
        timerPackage = null
    }

    private fun forceGoHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        lastPackageName = ""
        timerJob = null
        timerPackage = null
    }

    private fun showNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "limitout_status_channel"

        val channel = NotificationChannel(
            channelId,
            "LimitOut 動作状態",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val snoozeMinutes = sharedPrefs.getString("snooze_time", "10") ?: "10"
        val snoozeIntent = Intent("ACTION_LIMITOUT_SNOOZE").apply { setPackage(packageName) }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            this, 0, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent("ACTION_LIMITOUT_NOTIF_DISMISSED").apply { setPackage(packageName) }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this, 1, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (targetAppCount > 0) {
            "${activeTargets.size}/${targetAppCount}個のアプリの連続使用を監視しています"
        } else {
            "制限対象のアプリが選択されていません"
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LimitOut 監視中")
            .setContentText(contentText)
            .setSmallIcon(com.github.kihi78.limitout.R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "${snoozeMinutes}分間停止", snoozePendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .build()

        notificationManager.notify(1, notification)
    }

    private fun hideNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterReceiver(actionReceiver)
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        hideNotification()
    }

    override fun onInterrupt() {}
}
