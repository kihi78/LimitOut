package com.github.kihi78.limitout

/**
 * ある時点で観測した「前面アプリの状態」。
 *
 * @param packageName 前面にあるアプリ。判定できない／どのアプリも前面にない場合は null。
 * @param continuousSeconds そのアプリが連続して前面にあり続けている秒数。
 */
data class ForegroundState(
    val packageName: String?,
    val continuousSeconds: Long
)

/** [LimitCheckScheduler.decide] の判定結果。 */
sealed interface CheckResult {

    /** 連続起動時間が制限に到達した。制限アクションを発火させる。 */
    data class Fire(val packageName: String, val limitSeconds: Long) : CheckResult

    /** まだ到達していない。[delaySeconds] 秒後に再チェックする。 */
    data class Reschedule(val delaySeconds: Long) : CheckResult

    /** 制限対象アプリが1つもない。チェックをスケジュールする意味がない状態。 */
    data object Idle : CheckResult
}

/**
 * 「アプリの連続起動時間が制限に達したか」を判定し、次に確認すべき最短のタイミングを逆算する。
 *
 * 毎秒ポーリングする代わりに、
 * - 対象アプリが前面にない → 最短でも「制限時間」後にしか成立しないので、その分だけ待つ
 * - 前面にあるが未達        → 残り時間（制限時間 - 連続起動時間）だけ待つ
 * とすることで、チェック回数を最小限に抑える。
 *
 * 制限対象が複数ある場合、前面にアプリがないときの待ち時間は「最も短い制限時間」を使う。
 * どのアプリが次に開かれても、発火し得る最短のタイミングがそこだからである。
 *
 * 副作用を持たない純粋な関数として切り出してあるため、そのまま単体テストできる。
 */
object LimitCheckScheduler {

    /** 0秒や負の遅延でアラームが暴走しないための下限。 */
    const val MIN_DELAY_SECONDS = 1L

    /**
     * @param state 現在の前面アプリの状態
     * @param limitSecondsByPackage 有効な制限対象（パッケージ名 → 制限時間[秒]）
     */
    fun decide(
        state: ForegroundState,
        limitSecondsByPackage: Map<String, Long>
    ): CheckResult {
        if (limitSecondsByPackage.isEmpty()) return CheckResult.Idle

        val packageName = state.packageName
        val limitSeconds = packageName?.let { limitSecondsByPackage[it] }

        // 【状態が「閉じている」場合】= 制限対象アプリが前面にない
        if (packageName == null || limitSeconds == null) {
            return CheckResult.Reschedule(limitSecondsByPackage.values.min())
        }

        // 【状態が「開いている」場合】
        return if (state.continuousSeconds >= limitSeconds) {
            // 分岐A: 到達したので発火
            CheckResult.Fire(packageName, limitSeconds)
        } else {
            // 分岐B: 残り時間だけ待つ
            val remaining = limitSeconds - state.continuousSeconds
            CheckResult.Reschedule(maxOf(remaining, MIN_DELAY_SECONDS))
        }
    }
}
