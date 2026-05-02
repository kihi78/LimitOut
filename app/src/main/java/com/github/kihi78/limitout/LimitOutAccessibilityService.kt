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
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.github.kihi78.limitout.R

class LimitOutAccessibilityService : AccessibilityService() {

    private var lastPackageName: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var debounceJob: Job? = null

    private var snoozeWakeUpJob: Job? = null

    private lateinit var sharedPrefs: SharedPreferences
    private var snoozeEndTimeMillis: Long = 0

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
        }
    }

    private val baseIgnoredPackages = listOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "android"
    )

    private fun isIgnoredPackage(pkg: String, target: String): Boolean {
        if (baseIgnoredPackages.contains(pkg)) return true
        if (target != "com.android.chrome" && pkg == "com.android.chrome") return true
        if (target != "com.sec.android.app.sbrowser" && pkg == "com.sec.android.app.sbrowser") return true
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("LimitOutService", "LimitOutの監視サービスが接続されました！")

        sharedPrefs = getSharedPreferences("limitout_prefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)

        val filter = IntentFilter().apply {
            addAction("ACTION_LIMITOUT_SNOOZE")
            addAction("ACTION_LIMITOUT_NOTIF_DISMISSED")
        }
        ContextCompat.registerReceiver(this, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (sharedPrefs.getBoolean("is_enabled", false) && sharedPrefs.getBoolean("show_notification", true)) {
            showNotification()
        }
    }

    private fun getTargetAppPackage(): String = sharedPrefs.getString("target_package", "") ?: ""
    private fun isServiceEnabled(): Boolean = sharedPrefs.getBoolean("is_enabled", false)

    private fun getLimitTimeMillis(): Long {
        val minutes = sharedPrefs.getInt("limit_time", 5)
        return minutes * 60 * 1000L
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceEnabled()) return

        val currentTarget = getTargetAppPackage()
        if (currentTarget.isEmpty()) return

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            if (isIgnoredPackage(packageName, currentTarget)) return
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
        val currentTarget = getTargetAppPackage()
        if (currentTarget.isEmpty()) return

        var activePackage = rootInActiveWindow?.packageName?.toString() ?: lastPackageName
        if (isIgnoredPackage(activePackage, currentTarget)) {
            activePackage = lastPackageName
        }

        if (activePackage == currentTarget) {
            if (System.currentTimeMillis() >= snoozeEndTimeMillis) {
                startLimitTimer()
            } else {
                cancelLimitTimer()
            }
        } else {
            cancelLimitTimer()
        }
    }

    private fun startLimitTimer() {
        if (timerJob?.isActive == true) return

        timerJob = serviceScope.launch {
            val currentLimitMillis = getLimitTimeMillis()
            Log.d("LimitOutService", "タイマースタート: ターゲット[${getTargetAppPackage()}]")
            delay(currentLimitMillis)

            if (System.currentTimeMillis() < snoozeEndTimeMillis) return@launch

            val currentTarget = getTargetAppPackage()
            var activePackage = rootInActiveWindow?.packageName?.toString() ?: lastPackageName
            if (isIgnoredPackage(activePackage, currentTarget)) {
                activePackage = lastPackageName
            }

            if (activePackage == currentTarget) {
                Log.d("LimitOutService", "制限時間に到達したため、ホーム画面へ強制遷移させます")
                forceGoHome()
            }
        }
    }

    private fun cancelLimitTimer() {
        if (timerJob?.isActive == true) {
            timerJob?.cancel()
            timerJob = null
            Log.d("LimitOutService", "タイマーをリセットしました")
        }
    }

    private fun forceGoHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        lastPackageName = ""
        timerJob = null
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

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LimitOut 監視中")
            .setContentText("設定されたアプリの連続使用を監視しています")
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
