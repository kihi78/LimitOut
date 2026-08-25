package com.github.kihi78.limitout

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * UsageStatsManager のイベント履歴から「いま前面にあるアプリ」と「その連続起動時間」を割り出す。
 *
 * AccessibilityService と違い常駐して画面遷移を受け取るわけではないので、
 * 呼ばれた時点までのイベントを遡って現在の状態を組み立てる。
 *
 * 制約: [currentState] は `lookBackMillis` の範囲に前面化イベントが1件も無い場合、
 * 「どのアプリも前面にない」と判断する。制限に達したアプリはホームへ戻されるため
 * 連続起動時間が制限時間を大きく超えることは通常ないが、
 * 呼び出し側は制限時間に十分な余裕を足した `lookBackMillis` を渡すこと。
 */
class ForegroundAppTracker(private val context: Context) {

    fun currentState(
        lookBackMillis: Long,
        now: Long = System.currentTimeMillis()
    ): ForegroundState {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return NO_FOREGROUND

        val events = try {
            manager.queryEvents(now - lookBackMillis, now)
        } catch (e: SecurityException) {
            // 使用状況へのアクセスが未許可
            return NO_FOREGROUND
        } ?: return NO_FOREGROUND

        val event = UsageEvents.Event()
        var currentPackage: String? = null
        var foregroundSince = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue

            @Suppress("DEPRECATION")
            when (event.eventType) {
                // ACTIVITY_RESUMED(API29) と同値。minSdk 26 のため旧定数を使う。
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // 同じアプリ内の画面遷移では連続起動時間をリセットしない
                    if (packageName != currentPackage) {
                        currentPackage = packageName
                        foregroundSince = event.timeStamp
                    }
                }
                // ACTIVITY_PAUSED(API29) と同値。
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (packageName == currentPackage) {
                        currentPackage = null
                        foregroundSince = 0L
                    }
                }
                // 画面消灯・ロックはアプリを閉じたものとして扱う
                EVENT_SCREEN_NON_INTERACTIVE, EVENT_KEYGUARD_SHOWN -> {
                    currentPackage = null
                    foregroundSince = 0L
                }
            }
        }

        val packageName = currentPackage ?: return NO_FOREGROUND
        val continuousSeconds = ((now - foregroundSince) / 1000L).coerceAtLeast(0L)
        return ForegroundState(packageName, continuousSeconds)
    }

    private companion object {
        val NO_FOREGROUND = ForegroundState(null, 0L)

        /** UsageEvents.Event.SCREEN_NON_INTERACTIVE (API 28) の値。minSdk 26 のため直接定義する。 */
        const val EVENT_SCREEN_NON_INTERACTIVE = 16

        /** UsageEvents.Event.KEYGUARD_SHOWN (API 28) の値。 */
        const val EVENT_KEYGUARD_SHOWN = 17
    }
}
