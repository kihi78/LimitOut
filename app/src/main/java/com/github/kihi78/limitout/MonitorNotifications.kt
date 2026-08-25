package com.github.kihi78.limitout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 2つの監視方式で共通に使う通知。どちらのモードでも常駐通知は1つだけなので、
 * チャンネルIDと通知IDを共有して取り違えを防ぐ。
 */
object MonitorNotifications {

    private const val CHANNEL_ID_STATUS = "limitout_status_channel"
    private const val CHANNEL_ID_ALERT = "limitout_alert_channel"

    const val ID_STATUS = 1
    private const val ID_ALERT = 2

    private const val REQUEST_SNOOZE = 0
    private const val REQUEST_DISMISS = 1

    /**
     * 常駐の「監視中」通知を表示する。
     *
     * @param modeLabel どちらの方式で動いているかを示す文言。動作確認しやすいよう通知に出す。
     * @param snoozeIntent 「N分間停止」ボタンで送るブロードキャスト
     * @param dismissIntent 通知がスワイプで消されたときに送るブロードキャスト
     */
    fun showStatus(
        context: Context,
        activeCount: Int,
        totalCount: Int,
        snoozeMinutes: String,
        modeLabel: String,
        snoozeIntent: Intent,
        dismissIntent: Intent
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_STATUS,
                "LimitOut 動作状態",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val snoozePendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_SNOOZE, snoozeIntent, flags)
        val dismissPendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_DISMISS, dismissIntent, flags)

        val contentText = if (totalCount > 0) {
            "${activeCount}/${totalCount}個のアプリの連続使用を監視しています"
        } else {
            "制限対象のアプリが選択されていません"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STATUS)
            .setContentTitle("LimitOut 監視中（$modeLabel）")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "${snoozeMinutes}分間停止",
                snoozePendingIntent
            )
            .setDeleteIntent(dismissPendingIntent)
            .build()

        notificationManager.notify(ID_STATUS, notification)
    }

    fun hideStatus(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ID_STATUS)
    }

    /**
     * 「他のアプリの上に重ねて表示」が無く、ホーム画面へ戻せなかったときの代替。
     * 強制はできないので、ユーザーに気づいてもらうためのヘッドアップ通知を出す。
     */
    fun showLimitReached(context: Context, appLabel: String, limitMinutesText: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ALERT,
                "LimitOut 制限の通知",
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setContentTitle("$appLabel を${limitMinutesText}分連続で使用しています")
            .setContentText("いったん閉じましょう。強制的にホームへ戻すには「他のアプリの上に重ねて表示」を許可してください。")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "いったん閉じましょう。強制的にホームへ戻すには、LimitOutに「他のアプリの上に重ねて表示」を許可してください。"
                )
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ID_ALERT, notification)
    }
}
