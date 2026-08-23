package com.github.kihi78.limitout

import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

/**
 * 制限対象アプリ（パッケージ名 → 制限時間[分]）の保存・読み込みを担当する。
 * 1つのアプリしか保持できなかった旧形式(target_package)からの移行もここで行う。
 */
object TargetAppsStore {

    const val KEY_TARGET_APPS = "target_apps"
    const val DEFAULT_LIMIT_MINUTES = 5

    private const val KEY_LEGACY_TARGET = "target_package"
    private const val KEY_DEFAULT_LIMIT = "limit_time"

    fun load(prefs: SharedPreferences): Map<String, Int> {
        val json = prefs.getString(KEY_TARGET_APPS, null) ?: return migrateLegacy(prefs)
        return parse(json)
    }

    fun save(prefs: SharedPreferences, apps: Map<String, Int>) {
        val json = JSONObject()
        apps.forEach { (packageName, minutes) -> json.put(packageName, minutes) }
        prefs.edit().putString(KEY_TARGET_APPS, json.toString()).apply()
    }

    /** アプリを新しく追加したときに適用する制限時間。 */
    fun defaultLimitMinutes(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DEFAULT_LIMIT, DEFAULT_LIMIT_MINUTES)

    private fun parse(json: String): Map<String, Int> = try {
        val obj = JSONObject(json)
        buildMap {
            obj.keys().forEach { packageName ->
                put(packageName, obj.optInt(packageName, DEFAULT_LIMIT_MINUTES))
            }
        }
    } catch (e: JSONException) {
        emptyMap()
    }

    /** 旧バージョンで単一指定されていたターゲットを新形式へ引き継ぐ。 */
    private fun migrateLegacy(prefs: SharedPreferences): Map<String, Int> {
        val legacyPackage = prefs.getString(KEY_LEGACY_TARGET, "") ?: ""
        if (legacyPackage.isEmpty()) return emptyMap()

        val migrated = mapOf(legacyPackage to defaultLimitMinutes(prefs))
        save(prefs, migrated)
        return migrated
    }
}
