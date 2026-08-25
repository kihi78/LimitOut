package com.github.kihi78.limitout

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
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
                ACTION_SNOOZE -> {
                    val snoozeMinutes = MonitorSettings.snoozeMinutes(sharedPrefs)
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
                ACTION_NOTIF_DISMISSED -> {
                    if (isServiceEnabled() && MonitorSettings.showNotification(sharedPrefs)) {
                        Log.d("LimitOutService", "通知が消されましたが、設定がONのため復活させます")
                        showNotification()
                    }
                }
            }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            MonitorSettings.KEY_IS_ENABLED,
            MonitorSettings.KEY_SHOW_NOTIFICATION,
            MonitorSettings.KEY_SNOOZE_TIME,
            MonitorSettings.KEY_USE_ACCESSIBILITY -> {
                val isActive = isServiceEnabled()

                if (isActive && MonitorSettings.showNotification(prefs)) {
                    showNotification()
                } else {
                    hideNotification()
                }

                if (!isActive) cancelLimitTimer()
            }
            TargetAppsStore.KEY_TARGET_APPS -> {
                reloadTargetApps(prefs)

                // 対象アプリ・制限時間・有効/無効の変更を即座に反映させるため、計測をやり直す
                cancelLimitTimer()
                if (isServiceEnabled()) {
                    if (MonitorSettings.showNotification(prefs)) showNotification()
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

        sharedPrefs = MonitorSettings.prefs(this)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        reloadTargetApps(sharedPrefs)

        val filter = IntentFilter().apply {
            addAction(ACTION_SNOOZE)
            addAction(ACTION_NOTIF_DISMISSED)
        }
        ContextCompat.registerReceiver(this, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (isServiceEnabled() && MonitorSettings.showNotification(sharedPrefs)) {
            showNotification()
        }
    }

    /**
     * 制限機能がONで、かつ監視方式としてAccessibilityServiceが選ばれているか。
     * 軽量モードが選ばれている間、このサービスは何もしない。
     */
    private fun isServiceEnabled(): Boolean =
        MonitorSettings.isEnabled(sharedPrefs) && MonitorSettings.useAccessibility(sharedPrefs)

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
        MonitorNotifications.showStatus(
            context = this,
            activeCount = activeTargets.size,
            totalCount = targetAppCount,
            snoozeMinutes = MonitorSettings.snoozeMinutesText(sharedPrefs),
            modeLabel = MODE_LABEL,
            snoozeIntent = Intent(ACTION_SNOOZE).apply { setPackage(packageName) },
            dismissIntent = Intent(ACTION_NOTIF_DISMISSED).apply { setPackage(packageName) }
        )
    }

    private fun hideNotification() {
        MonitorNotifications.hideStatus(this)
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

    private companion object {
        /** 軽量モード側と取り違えないよう、このサービス専用のアクション名を使う。 */
        const val ACTION_SNOOZE = "ACTION_LIMITOUT_SNOOZE"
        const val ACTION_NOTIF_DISMISSED = "ACTION_LIMITOUT_NOTIF_DISMISSED"

        const val MODE_LABEL = "リアルタイム"
    }
}
