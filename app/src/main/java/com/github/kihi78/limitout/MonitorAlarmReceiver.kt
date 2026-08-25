package com.github.kihi78.limitout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 軽量版監視の入口。AlarmManager からの起床、通知のボタン操作、端末起動をここで受ける。
 *
 * 端末起動時のブロードキャストを受け取るため exported="true" だが、
 * 実際の処理は既に保存済みの設定に従うだけなので、外部から叩かれても影響はない。
 */
class MonitorAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            LightweightMonitor.ACTION_CHECK -> LightweightMonitor.runCheck(context)

            LightweightMonitor.ACTION_SNOOZE -> LightweightMonitor.snooze(context)

            LightweightMonitor.ACTION_NOTIF_DISMISSED ->
                LightweightMonitor.restoreNotificationIfNeeded(context)

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> LightweightMonitor.sync(context)
        }
    }
}
