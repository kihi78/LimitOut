package com.github.kihi78.limitout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仕様書「アプリの連続起動時間検知ロジック」の判定とスケジュール逆算を検証する。
 * 説明は分単位だが、実装・テストとも秒で計算する。
 */
class LimitCheckSchedulerTest {

    private val target = "com.example.sns"
    private val limits = mapOf(target to FIVE_MINUTES)

    // ------------------------------------------------------------ 基本の分岐

    @Test
    fun `対象アプリが前面にないときは制限時間ぶんだけ待つ`() {
        val result = LimitCheckScheduler.decide(ForegroundState(null, 0), limits)

        assertEquals(CheckResult.Reschedule(FIVE_MINUTES), result)
    }

    @Test
    fun `対象外のアプリが前面にあるときも閉じている扱いになる`() {
        val result = LimitCheckScheduler.decide(ForegroundState("com.example.other", 600), limits)

        assertEquals(CheckResult.Reschedule(FIVE_MINUTES), result)
    }

    @Test
    fun `連続起動時間が制限に達したら発火する`() {
        val result = LimitCheckScheduler.decide(ForegroundState(target, FIVE_MINUTES), limits)

        assertEquals(CheckResult.Fire(target, FIVE_MINUTES), result)
    }

    @Test
    fun `制限を超えていても発火する`() {
        val result = LimitCheckScheduler.decide(ForegroundState(target, 420), limits)

        assertEquals(CheckResult.Fire(target, FIVE_MINUTES), result)
    }

    @Test
    fun `未達なら残り時間だけ待つ`() {
        val result = LimitCheckScheduler.decide(ForegroundState(target, 120), limits)

        assertEquals(CheckResult.Reschedule(180), result)
    }

    @Test
    fun `制限対象が1つもなければ何もしない`() {
        val result = LimitCheckScheduler.decide(ForegroundState(target, 120), emptyMap())

        assertEquals(CheckResult.Idle, result)
    }

    @Test
    fun `残り時間が0秒以下にならないよう下限を設ける`() {
        val oneSecondLimit = mapOf(target to 1L)

        val result = LimitCheckScheduler.decide(ForegroundState(target, 0), oneSecondLimit)

        assertEquals(CheckResult.Reschedule(LimitCheckScheduler.MIN_DELAY_SECONDS), result)
    }

    // -------------------------------------------------- 複数アプリを登録した場合

    @Test
    fun `前面にないときの待ち時間は最も短い制限時間になる`() {
        val multiple = mapOf(
            "com.example.sns" to FIVE_MINUTES,
            "com.example.game" to 120L
        )

        val result = LimitCheckScheduler.decide(ForegroundState(null, 0), multiple)

        assertEquals(CheckResult.Reschedule(120), result)
    }

    @Test
    fun `前面のアプリ自身の制限時間で判定する`() {
        val multiple = mapOf(
            "com.example.sns" to FIVE_MINUTES,
            "com.example.game" to 120L
        )

        val result = LimitCheckScheduler.decide(ForegroundState("com.example.game", 150), multiple)

        assertEquals(CheckResult.Fire("com.example.game", 120), result)
    }

    // -------------------------------------------------------- 仕様書の動作例

    @Test
    fun `パターン1 途中で1回再起動した場合`() {
        // 1. 初回検知: 連続2分 → 残り3分
        assertEquals(
            CheckResult.Reschedule(180),
            LimitCheckScheduler.decide(ForegroundState(target, 120), limits)
        )

        // 3. 第2回検知（3分後）: 途中で開き直したので連続2分 → 残り3分
        assertEquals(
            CheckResult.Reschedule(180),
            LimitCheckScheduler.decide(ForegroundState(target, 120), limits)
        )

        // 4. 第3回検知（さらに3分後）: 連続5分 → 発火
        assertEquals(
            CheckResult.Fire(target, FIVE_MINUTES),
            LimitCheckScheduler.decide(ForegroundState(target, FIVE_MINUTES), limits)
        )
    }

    @Test
    fun `パターン2 途中で複数回再起動した場合`() {
        // 1. 初回検知: 連続1分 → 残り4分
        assertEquals(
            CheckResult.Reschedule(240),
            LimitCheckScheduler.decide(ForegroundState(target, 60), limits)
        )

        // 3. 第2回検知（4分後）: 連続1分 → 残り4分
        assertEquals(
            CheckResult.Reschedule(240),
            LimitCheckScheduler.decide(ForegroundState(target, 60), limits)
        )

        // 5. 第3回検知（4分後）: 連続2分 → 残り3分
        assertEquals(
            CheckResult.Reschedule(180),
            LimitCheckScheduler.decide(ForegroundState(target, 120), limits)
        )

        // 6. 第4回検知（3分後）: 連続5分 → 発火
        assertEquals(
            CheckResult.Fire(target, FIVE_MINUTES),
            LimitCheckScheduler.decide(ForegroundState(target, FIVE_MINUTES), limits)
        )
    }

    /**
     * 仕様どおりに逆算すると、5分間開き続けたときのチェック回数が
     * 毎秒ポーリング（300回）よりはるかに少なくて済むことを確認する。
     */
    @Test
    fun `開きっぱなしなら2回のチェックで発火に到達する`() {
        var elapsedSeconds = 0L
        var checks = 0
        var fired = false

        while (checks < 100) {
            checks++
            val result = LimitCheckScheduler.decide(ForegroundState(target, elapsedSeconds), limits)
            if (result is CheckResult.Fire) {
                fired = true
                break
            }
            elapsedSeconds += (result as CheckResult.Reschedule).delaySeconds
        }

        assert(fired)
        assertEquals(2, checks)
        assertEquals(FIVE_MINUTES, elapsedSeconds)
    }

    private companion object {
        const val FIVE_MINUTES = 300L
    }
}
