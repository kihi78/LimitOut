package com.github.kihi78.limitout

import android.content.Context
import android.content.SharedPreferences

/**
 * 監視まわりの設定値（SharedPreferences）へのアクセスを1か所にまとめたもの。
 *
 * 監視方式は2つあり、[KEY_USE_ACCESSIBILITY] で切り替える。
 * - true : [LimitOutAccessibilityService] によるリアルタイム監視（従来方式）
 * - false: [LightweightMonitor] による UsageStats + AlarmManager のポーリング監視（軽量版）
 */
object MonitorSettings {

    const val PREFS_NAME = "limitout_prefs"

    const val KEY_IS_ENABLED = "is_enabled"
    const val KEY_SHOW_NOTIFICATION = "show_notification"
    const val KEY_SNOOZE_TIME = "snooze_time"
    const val KEY_USE_ACCESSIBILITY = "use_accessibility"

    /** 軽量版でスヌーズ終了時刻をプロセス再生成後も保つための保存先。 */
    const val KEY_SNOOZE_END_MILLIS = "snooze_end_millis"

    /**
     * 軽量版で「リセット」が押された時刻。常駐していない軽量版はタイマーを持たないため、
     * この時刻以前の連続使用は数えないことでリセットを表現する。
     */
    const val KEY_RESET_AT_MILLIS = "reset_at_millis"

    /** 既存ユーザーの挙動を変えないため、AccessibilityService方式を既定とする。 */
    const val DEFAULT_USE_ACCESSIBILITY = true

    const val DEFAULT_SNOOZE_MINUTES = "10"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_IS_ENABLED, false)

    fun showNotification(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SHOW_NOTIFICATION, true)

    fun useAccessibility(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_USE_ACCESSIBILITY, DEFAULT_USE_ACCESSIBILITY)

    fun snoozeMinutesText(prefs: SharedPreferences): String =
        prefs.getString(KEY_SNOOZE_TIME, DEFAULT_SNOOZE_MINUTES) ?: DEFAULT_SNOOZE_MINUTES

    fun snoozeMinutes(prefs: SharedPreferences): Long =
        snoozeMinutesText(prefs).toLongOrNull() ?: DEFAULT_SNOOZE_MINUTES.toLong()

    fun snoozeEndMillis(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_SNOOZE_END_MILLIS, 0L)

    fun setSnoozeEndMillis(prefs: SharedPreferences, endMillis: Long) {
        prefs.edit().putLong(KEY_SNOOZE_END_MILLIS, endMillis).apply()
    }

    fun resetAtMillis(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_RESET_AT_MILLIS, 0L)

    fun setResetAtMillis(prefs: SharedPreferences, atMillis: Long) {
        prefs.edit().putLong(KEY_RESET_AT_MILLIS, atMillis).apply()
    }
}
