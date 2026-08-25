package com.github.kihi78.limitout

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.text.TextUtils

/** 2つの監視方式それぞれが必要とする権限の確認と、設定画面を開くためのIntentをまとめたもの。 */
object MonitorPermissions {

    /** 軽量版の必須権限: 使用状況へのアクセス。 */
    fun hasUsageStats(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 軽量版の推奨権限: 他のアプリの上に重ねて表示。
     *
     * Android 10 以降はバックグラウンドからのActivity起動が制限されるため、
     * この権限が無いとホーム画面への強制遷移ができず、通知での警告に留まる。
     */
    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 従来方式の必須権限: ユーザーがシステム設定でサービスを有効にしているか。 */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, LimitOutAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == expected) return true
        }
        return false
    }

    fun usageStatsSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
