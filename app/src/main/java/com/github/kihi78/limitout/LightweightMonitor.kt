package com.github.kihi78.limitout

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * AccessibilityService を使わない軽量版の監視エンジン。
 *
 * 常駐せず、[ForegroundAppTracker] で現在の状態を取得 → [LimitCheckScheduler] で
 * 次に確認すべき最短のタイミングを逆算 → AlarmManager でその時刻に自分を起こす、
 * というサイクルを繰り返す。毎秒ポーリングしないぶん消費リソースが小さい代わりに、
 * 検知はチェック時点までしか遡れない（＝リアルタイム性は従来方式に劣る）。
 */
object LightweightMonitor {

    private const val TAG = "LimitOutLight"

    const val ACTION_CHECK = "com.github.kihi78.limitout.action.LW_CHECK"
    const val ACTION_SNOOZE = "com.github.kihi78.limitout.action.LW_SNOOZE"
    const val ACTION_RESET = "com.github.kihi78.limitout.action.LW_RESET"
    const val ACTION_NOTIF_DISMISSED = "com.github.kihi78.limitout.action.LW_NOTIF_DISMISSED"

    private const val REQUEST_CHECK = 100

    /** 前面化イベントを取りこぼさないよう、制限時間にこれだけ余裕を足して履歴を遡る。 */
    private const val LOOK_BACK_MARGIN_MILLIS = 60L * 60L * 1000L

    /** スヌーズ明けの再チェックが早すぎて空振りしないための猶予。 */
    private const val SNOOZE_WAKE_MARGIN_MILLIS = 200L

    // ---------------------------------------------------------------- 外部API

    /**
     * 設定変更後・アプリ起動時・端末起動後に呼ぶ。
     * 軽量版を動かすべき状態なら監視を開始し、そうでなければ後片付けをする。
     */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        val prefs = MonitorSettings.prefs(appContext)

        if (MonitorSettings.useAccessibility(prefs)) {
            cancelAlarm(appContext)
            // 従来方式が実際に動いていれば常駐通知はそちらが管理するので触らない。
            // まだ許可されていない場合は、軽量モードが出した通知が残るので消す。
            if (!MonitorPermissions.isAccessibilityServiceEnabled(appContext)) {
                MonitorNotifications.hideStatus(appContext)
            }
            return
        }

        if (!MonitorSettings.isEnabled(prefs) || !MonitorPermissions.hasUsageStats(appContext)) {
            stop(appContext)
            return
        }

        runCheck(appContext)
    }

    /** 監視を止めて通知も消す。 */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        cancelAlarm(appContext)
        MonitorNotifications.hideStatus(appContext)
    }

    /** アラームで起こされたときの1回分の検知処理。 */
    fun runCheck(context: Context) {
        val appContext = context.applicationContext
        val prefs = MonitorSettings.prefs(appContext)

        if (MonitorSettings.useAccessibility(prefs) ||
            !MonitorSettings.isEnabled(prefs) ||
            !MonitorPermissions.hasUsageStats(appContext)
        ) {
            stop(appContext)
            return
        }

        val allTargets = TargetAppsStore.load(prefs)
        val limitSecondsByPackage = allTargets
            .filterValues { it.enabled }
            .mapValues { (_, config) -> config.limitMinutes * 60L }

        updateStatusNotification(appContext, prefs, allTargets.size)

        if (limitSecondsByPackage.isEmpty()) {
            Log.d(TAG, "有効な制限対象がないためチェックを停止します")
            cancelAlarm(appContext)
            return
        }

        val now = System.currentTimeMillis()

        // スヌーズ中は判定せず、明けた時刻に再開する
        val snoozeEnd = MonitorSettings.snoozeEndMillis(prefs)
        if (now < snoozeEnd) {
            Log.d(TAG, "スヌーズ中のため ${(snoozeEnd - now) / 1000}秒後に再開します")
            scheduleAt(appContext, snoozeEnd + SNOOZE_WAKE_MARGIN_MILLIS)
            return
        }

        val lookBackMillis = limitSecondsByPackage.values.max() * 1000L + LOOK_BACK_MARGIN_MILLIS
        val state = applyResetBaseline(
            ForegroundAppTracker(appContext).currentState(lookBackMillis, now),
            MonitorSettings.resetAtMillis(prefs),
            now
        )

        when (val result = LimitCheckScheduler.decide(state, limitSecondsByPackage)) {
            is CheckResult.Idle -> {
                cancelAlarm(appContext)
            }
            is CheckResult.Reschedule -> {
                Log.d(
                    TAG,
                    "未達: 前面[${state.packageName}] 連続${state.continuousSeconds}秒 → " +
                        "${result.delaySeconds}秒後に再チェック"
                )
                scheduleAt(appContext, now + result.delaySeconds * 1000L)
            }
            is CheckResult.Fire -> {
                Log.d(
                    TAG,
                    "到達: [${result.packageName}] が ${state.continuousSeconds}秒連続で起動中"
                )
                fire(appContext, result)
                // 発火直後は最短でも「最も短い制限時間」後にしか次の到達はあり得ない
                scheduleAt(appContext, now + limitSecondsByPackage.values.min() * 1000L)
            }
        }
    }

    /** 通知の「N分間停止」ボタンから呼ばれる。 */
    fun snooze(context: Context) {
        val appContext = context.applicationContext
        val prefs = MonitorSettings.prefs(appContext)
        val snoozeMinutes = MonitorSettings.snoozeMinutes(prefs)
        val endMillis = System.currentTimeMillis() + snoozeMinutes * 60L * 1000L

        MonitorSettings.setSnoozeEndMillis(prefs, endMillis)
        Log.d(TAG, "一定時間停止を開始: ${snoozeMinutes}分間監視を停止します")

        scheduleAt(appContext, endMillis + SNOOZE_WAKE_MARGIN_MILLIS)
    }

    /**
     * 通知の「リセット」ボタンから呼ばれる。
     *
     * 常駐しない軽量版はタイマーを持たず、UsageStats から遡って連続使用時間を求めている。
     * そのため「リセット時刻より前の使用は数えない」という基準時刻を置くことで、
     * 押した瞬間から制限時間をまるごと数え直す（例: 制限5分なら次の検知は5分後）挙動にする。
     */
    fun reset(context: Context) {
        val appContext = context.applicationContext
        val prefs = MonitorSettings.prefs(appContext)

        MonitorSettings.setSnoozeEndMillis(prefs, 0L)
        MonitorSettings.setResetAtMillis(prefs, System.currentTimeMillis())
        Log.d(TAG, "リセット: スヌーズを解除し、ここから制限時間を数え直します")

        // 新しい基準で次のチェック時刻を引き直す
        runCheck(appContext)
    }

    /** 通知がスワイプで消されたとき、設定がONなら復活させる。 */
    fun restoreNotificationIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = MonitorSettings.prefs(appContext)
        if (MonitorSettings.useAccessibility(prefs) || !MonitorSettings.isEnabled(prefs)) return
        if (!MonitorSettings.showNotification(prefs)) return

        updateStatusNotification(appContext, prefs, TargetAppsStore.load(prefs).size)
    }

    // -------------------------------------------------------------- 内部処理

    /**
     * リセット時刻より前から続いている使用分を切り捨てる。
     * リセット直後は連続0秒として扱われるので、制限時間ぶんまるごと待ち直すことになる。
     */
    private fun applyResetBaseline(
        state: ForegroundState,
        resetAtMillis: Long,
        now: Long
    ): ForegroundState {
        if (resetAtMillis <= 0L || state.packageName == null) return state

        val secondsSinceReset = ((now - resetAtMillis) / 1000L).coerceAtLeast(0L)
        return if (state.continuousSeconds <= secondsSinceReset) {
            state
        } else {
            state.copy(continuousSeconds = secondsSinceReset)
        }
    }

    private fun fire(context: Context, result: CheckResult.Fire) {
        val label = resolveAppLabel(context, result.packageName)
        val limitMinutesText = (result.limitSeconds / 60L).toString()

        if (MonitorPermissions.hasOverlay(context)) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            Log.d(TAG, "ホーム画面へ強制遷移させました")
        } else {
            // Android 10 以降、重ねて表示の権限が無いとバックグラウンドから画面を切り替えられない
            MonitorNotifications.showLimitReached(context, label, limitMinutesText)
            Log.d(TAG, "重ねて表示の権限が無いため、通知で警告しました")
        }
    }

    private fun resolveAppLabel(context: Context, packageName: String): String = try {
        val packageManager = context.packageManager
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun updateStatusNotification(
        context: Context,
        prefs: SharedPreferences,
        totalCount: Int
    ) {
        if (!MonitorSettings.showNotification(prefs)) {
            MonitorNotifications.hideStatus(context)
            return
        }

        // 軽量版は常駐しないため残り時間のリアルタイム表示はできない（remainingText は渡さない）
        MonitorNotifications.showStatus(
            context = context,
            totalCount = totalCount,
            snoozeMinutes = MonitorSettings.snoozeMinutesText(prefs),
            snoozeIntent = receiverIntent(context, ACTION_SNOOZE),
            dismissIntent = receiverIntent(context, ACTION_NOTIF_DISMISSED),
            resetIntent = receiverIntent(context, ACTION_RESET)
        )
    }

    private fun receiverIntent(context: Context, action: String): Intent =
        Intent(context, MonitorAlarmReceiver::class.java).setAction(action)

    private fun checkPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CHECK,
            receiverIntent(context, ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun scheduleAt(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = checkPendingIntent(context)

        // 正確なアラームが許可されていない端末では、多少のずれを許容して継続する
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
            )
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(checkPendingIntent(context))
    }
}
