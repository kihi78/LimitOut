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

    /** 通知の残り時間をリアルタイム更新するためのループ。 */
    private var countdownJob: Job? = null

    /** 直近で通知に出した残り時間の文言。変化した時だけ通知を再発行するための比較用。 */
    private var lastCountdownText: String? = null

    private lateinit var sharedPrefs: SharedPreferences
    private var snoozeEndTimeMillis: Long = 0

    /** 稼働中の制限時間タイマーが満了する予定時刻（ミリ秒）。残り時間表示の計算に使う。 */
    private var timerEndTimeMillis: Long = 0

    /** 有効な制限対象アプリ（パッケージ名 → 制限時間[分]）。オフにしたアプリは含まない。 */
    private var activeTargets: Map<String, Int> = emptyMap()

    /** 通知表示用に、無効化されたアプリも含めた対象アプリ数を保持する。 */
    private var targetAppCount: Int = 0

    /** 現在計測中のアプリ。一瞬でも別のアプリへ移ったらタイマーを引き継がず計測し直す。 */
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
                ACTION_RESET -> {
                    Log.d("LimitOutService", "リセットが要求されました。スヌーズとタイマーを破棄して計測をやり直します")
                    snoozeWakeUpJob?.cancel()
                    snoozeEndTimeMillis = 0L
                    cancelLimitTimer()
                    Toast.makeText(context, "監視をリセットしました", Toast.LENGTH_SHORT).show()
                    checkCurrentScreenAndHandleTimer()
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

    /** 制限対象に選ばれていない限り、画面遷移のノイズとして無視するブラウザ。 */
    private val conditionallyIgnoredBrowsers = listOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser"
    )

    /** [isLaunchableApp] の判定結果。画面遷移のたびに PackageManager へ問い合わせないよう覚えておく。 */
    private val launchableCache = mutableMapOf<String, Boolean>()

    /** キャッシュされたデフォルトホームアプリのパッケージ名。 */
    private var cachedHomePackage: String? = null

    /**
     * ホーム画面のアプリ一覧に並ぶ「普通のアプリ」かどうか。
     *
     * 通知パネル・ステータスバー・IME・システムダイアログなどはランチャーから起動できないため、
     * ここで false になる。これらのパッケージ名は端末（メーカー・OSバージョン）ごとに異なり
     * 列挙しきれないので、名前を並べる代わりにこの性質で判定する。
     */
    private fun isLaunchableApp(pkg: String): Boolean = launchableCache.getOrPut(pkg) {
        runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
    }

    /**
     * デフォルトホーム画面のパッケージ名を返す。端末やユーザー設定によって異なり、
     * 列挙不可のため `ACTION_MAIN` + `CATEGORY_HOME` で動的に特定する。
     */
    private fun getDefaultHomePackage(): String? {
        if (cachedHomePackage != null) return cachedHomePackage

        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val homePackage = runCatching {
            packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        }.getOrNull()

        cachedHomePackage = homePackage
        return homePackage
    }

    /**
     * 「ユーザーが別のアプリへ移った」とは見なさない画面かどうか。
     *
     * 通知パネル・ステータスバー・IMEなどランチャーから起動できない画面だけを例外とする。
     * ホーム画面や他の実アプリへ移動したら、対象アプリからは離れたものとしてタイマーをリセットする。
     */
    private fun isIgnoredPackage(pkg: String, targets: Set<String>): Boolean {
        // 制限対象そのものは常に評価する
        if (targets.contains(pkg)) return false

        if (conditionallyIgnoredBrowsers.contains(pkg)) return true

        // ホーム画面は実アプリなので、移動時にカウントをリセットする
        if (pkg == getDefaultHomePackage()) return false

        // 通知パネルを引き下げただけでタイマーがやり直しになるのを防ぐ
        return !isLaunchableApp(pkg)
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
            addAction(ACTION_RESET)
        }
        ContextCompat.registerReceiver(this, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (isServiceEnabled() && MonitorSettings.showNotification(sharedPrefs)) {
            showNotification()
        }

        startCountdownLoop()
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
        timerEndTimeMillis = System.currentTimeMillis() + limitMinutes * 60 * 1000L

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
        timerEndTimeMillis = 0
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
        timerEndTimeMillis = 0
    }

    /**
     * スヌーズ中は残りのスヌーズ時間、タイマー作動中は残りの制限時間をミリ秒で返す。
     * どちらでもない場合はnull（＝残り時間表示なし）。
     */
    private fun remainingMillisOrNull(): Long? {
        val now = System.currentTimeMillis()
        return when {
            now < snoozeEndTimeMillis -> snoozeEndTimeMillis - now
            timerJob?.isActive == true -> (timerEndTimeMillis - now).coerceAtLeast(0)
            else -> null
        }
    }

    /** 1分以上なら「M分S秒」、1分未満なら「S秒」で残り時間を表示する。 */
    private fun formatRemaining(remainingMillis: Long): String {
        val totalSeconds = remainingMillis / 1000
        return if (totalSeconds >= 60) {
            "${totalSeconds / 60}分${totalSeconds % 60}秒"
        } else {
            "${totalSeconds}秒"
        }
    }

    /**
     * 残り時間を秒単位で更新し続ける。表示が変わった時だけ通知を出し直すので、
     * 実際に notify() を呼ぶのは毎秒1回まで。低優先度チャンネル＋setOnlyAlertOnce のため
     * 音やバイブは鳴らず、消費は毎分更新と比べて実用上の差はない。
     *
     * AccessibilityServiceが接続されている間だけ動くループなので、このサービスが
     * 動いていない（＝AccessibilityServiceがオフ）間はリアルタイム表示は行われない。
     */
    private fun startCountdownLoop() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (isActive) {
                val remainingMillis = remainingMillisOrNull()
                val text = remainingMillis?.let { formatRemaining(it) }

                if (text != lastCountdownText) {
                    lastCountdownText = text
                    if (isServiceEnabled() && MonitorSettings.showNotification(sharedPrefs)) {
                        showNotification()
                    }
                }

                delay(nextTickDelayMillis(remainingMillis))
            }
        }
    }

    /**
     * 次に表示が変わる「秒の変わり目」までの待ち時間。
     * 一定間隔で回すとズレが溜まって1秒飛ばしたり二重更新したりするため、毎回計算し直す。
     */
    private fun nextTickDelayMillis(remainingMillis: Long?): Long {
        if (remainingMillis == null) return 1000L
        val untilNextSecond = remainingMillis % 1000L
        return if (untilNextSecond > 0L) untilNextSecond else 1000L
    }

    private fun showNotification() {
        MonitorNotifications.showStatus(
            context = this,
            totalCount = targetAppCount,
            snoozeMinutes = MonitorSettings.snoozeMinutesText(sharedPrefs),
            snoozeIntent = Intent(ACTION_SNOOZE).apply { setPackage(packageName) },
            dismissIntent = Intent(ACTION_NOTIF_DISMISSED).apply { setPackage(packageName) },
            resetIntent = Intent(ACTION_RESET).apply { setPackage(packageName) },
            remainingText = remainingMillisOrNull()?.let { formatRemaining(it) }
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
        const val ACTION_RESET = "ACTION_LIMITOUT_RESET"
    }
}
