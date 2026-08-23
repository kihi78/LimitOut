package com.github.kihi78.limitout

import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

/** 制限対象アプリ1件分の設定。 */
data class TargetAppConfig(
    val limitMinutes: Int,
    val enabled: Boolean = true
)

/**
 * 制限対象アプリ（パッケージ名 → 制限時間[分]・有効/無効）の保存・読み込みを担当する。
 * 1つのアプリしか保持できなかった旧形式(target_package)や、有効/無効を持たなかった
 * 旧JSON形式(パッケージ名 → 分数のみ)からの移行もここで行う。
 */
object TargetAppsStore {

    const val KEY_TARGET_APPS = "target_apps"
    const val DEFAULT_LIMIT_MINUTES = 5

    private const val KEY_LEGACY_TARGET = "target_package"
    private const val KEY_DEFAULT_LIMIT = "limit_time"
    private const val FIELD_LIMIT = "limit"
    private const val FIELD_ENABLED = "enabled"

    fun load(prefs: SharedPreferences): Map<String, TargetAppConfig> {
        val json = prefs.getString(KEY_TARGET_APPS, null) ?: return migrateLegacy(prefs)
        return parse(json)
    }

    fun save(prefs: SharedPreferences, apps: Map<String, TargetAppConfig>) {
        val json = JSONObject()
        apps.forEach { (packageName, config) ->
            val entry = JSONObject()
            entry.put(FIELD_LIMIT, config.limitMinutes)
            entry.put(FIELD_ENABLED, config.enabled)
            json.put(packageName, entry)
        }
        prefs.edit().putString(KEY_TARGET_APPS, json.toString()).apply()
    }

    private fun parse(json: String): Map<String, TargetAppConfig> = try {
        val obj = JSONObject(json)
        buildMap {
            obj.keys().forEach { packageName ->
                val value = obj.opt(packageName)
                val config = when (value) {
                    is JSONObject -> TargetAppConfig(
                        limitMinutes = value.optInt(FIELD_LIMIT, DEFAULT_LIMIT_MINUTES),
                        enabled = value.optBoolean(FIELD_ENABLED, true)
                    )
                    // 旧JSON形式（パッケージ名 → 分数のみ）との互換用
                    is Number -> TargetAppConfig(limitMinutes = value.toInt(), enabled = true)
                    else -> TargetAppConfig(limitMinutes = DEFAULT_LIMIT_MINUTES, enabled = true)
                }
                put(packageName, config)
            }
        }
    } catch (e: JSONException) {
        emptyMap()
    }

    /** 旧バージョンで単一指定されていたターゲットを新形式へ引き継ぐ。 */
    private fun migrateLegacy(prefs: SharedPreferences): Map<String, TargetAppConfig> {
        val legacyPackage = prefs.getString(KEY_LEGACY_TARGET, "") ?: ""
        if (legacyPackage.isEmpty()) return emptyMap()

        val legacyLimit = prefs.getInt(KEY_DEFAULT_LIMIT, DEFAULT_LIMIT_MINUTES)
        val migrated = mapOf(legacyPackage to TargetAppConfig(legacyLimit, enabled = true))
        save(prefs, migrated)
        return migrated
    }
}
