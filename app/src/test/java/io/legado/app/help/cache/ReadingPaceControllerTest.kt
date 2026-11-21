package io.legado.app.help.cache

import android.app.Application
import io.legado.app.exception.ConcurrentException
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ReadingPaceController A 层单测（Chief 批准的七组用例）。
 * 基建对齐 CacheBookRequestSerializationTest：Robolectric + manifest NONE + sdk 35。
 * 时间敏感用例统一走「手填间隔」把区间钳成确定值（randomBetween 在 min>=max 时返回 min）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = ReadingPaceControllerTestApplication::class)
class ReadingPaceControllerTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T> ctrlField(name: String): T =
        ReadingPaceController.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(ReadingPaceController) as T
        }

    private fun nextInterval(sourceKey: String): Long =
        ReadingPaceController.javaClass
            .getDeclaredMethod("nextIntervalMillis", String::class.java)
            .apply { isAccessible = true }
            .invoke(ReadingPaceController, sourceKey) as Long

    private fun quietWait(): Long =
        ReadingPaceController.javaClass
            .getDeclaredMethod("quietHoursWaitMillis")
            .apply { isAccessible = true }
            .invoke(ReadingPaceController) as Long

    /** 控制器是常驻 object，四个 per-source 表跨用例必须清空，否则计数/上次时间串味。 */
    private fun resetControllerState() {
        listOf("sourceGates", "lastFinishTime", "chapterCounter", "lastContentLength")
            .forEach { ctrlField<MutableMap<String, *>>(it).clear() }
    }

    @Before
    fun setUp() {
        resetControllerState()
        AppConfig.readingPaceEnabled = true
        AppConfig.readingPaceLongRest = true
        AppConfig.readingPaceQuietHours = false
        AppConfig.cachePaceManualEnabled = false
        AppConfig.readingPaceMinInterval = 1200
        AppConfig.readingPaceMaxInterval = 4500
    }

    @After
    fun tearDown() {
        resetControllerState()
        AppConfig.cachePaceManualEnabled = false
        AppConfig.readingPaceEnabled = true
    }

    // ---- 1. 串行 / 直通 ----

    @Test
    fun sameSourceIsSerialized() = runBlocking {
        AppConfig.cachePaceManualEnabled = true
        AppConfig.readingPaceMinInterval = 0
        AppConfig.readingPaceMaxInterval = 0
        val entered = CountDownLatch(2)
        val gate = CompletableDeferred<Unit>()
        val a = async(Dispatchers.Default) {
            ReadingPaceController.withPacedTurn("s1") { entered.countDown(); gate.await(); 1 }
        }
        val b = async(Dispatchers.Default) {
            ReadingPaceController.withPacedTurn("s1") { entered.countDown(); gate.await(); 2 }
        }
        // 串行：第一个未退出前，第二个进不了 block（latch 到不了 0）
        assertFalse(entered.await(300, TimeUnit.MILLISECONDS))
        gate.complete(Unit)
        withTimeout(3000) {
            assertEquals(1, a.await())
            assertEquals(2, b.await())
        }
    }

    @Test
    fun disabledFlagBypassesGate() = runBlocking {
        AppConfig.readingPaceEnabled = false
        val entered = CountDownLatch(2)
        val gate = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) {
            ReadingPaceController.withPacedTurn("s2") { entered.countDown(); gate.await(); 1 }
        }
        val b = launch(Dispatchers.Default) {
            ReadingPaceController.withPacedTurn("s2") { entered.countDown(); gate.await(); 2 }
        }
        // 关闭后同 key 也并发：两个都进得去
        assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
        gate.complete(Unit)
        withTimeout(3000) { a.join(); b.join() }
    }

    @Test
    fun blankSourceKeyBypassesGate() = runBlocking {
        val entered = CountDownLatch(2)
        val gate = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) {
            ReadingPaceController.withPacedTurn("") { entered.countDown(); gate.await(); 1 }
        }
        val b = launch(Dispatchers.Default) {
            ReadingPaceController.withPacedTurn("") { entered.countDown(); gate.await(); 2 }
        }
        assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
        gate.complete(Unit)
        withTimeout(3000) { a.join(); b.join() }
    }

    // ---- 2. 指数退避 ----

    @Test
    fun backoffIsExponentialCappedAndClamped() {
        // attempt=1: 1000±20% -> [800,1200]
        repeat(20) {
            val v = ReadingPaceController.nextBackoffMillis(1)
            assertTrue("attempt1=$v", v in 800..1200)
        }
        // attempt=3: 4000±800
        repeat(20) {
            val v = ReadingPaceController.nextBackoffMillis(3)
            assertTrue("attempt3=$v", v in 3200..4800)
        }
        // attempt=8 与越界钳位后一致: 60000±12000
        repeat(20) {
            val v8 = ReadingPaceController.nextBackoffMillis(8)
            val vBig = ReadingPaceController.nextBackoffMillis(99)
            assertTrue("attempt8=$v8", v8 in 48000..72000)
            assertTrue("attempt99=$vBig", vBig in 48000..72000)
        }
        // attempt=0 钳到 1
        repeat(20) {
            val v = ReadingPaceController.nextBackoffMillis(0)
            assertTrue("attempt0=$v", v in 800..1200)
        }
    }

    @Test
    fun backoffRespectsRateLimitWait() {
        val e = ConcurrentException("limited", 90_000L)
        // 源站要求等 90s：任何 attempt 都不得低于它
        repeat(10) {
            assertEquals(90_000L, ReadingPaceController.nextBackoffMillis(1, e))
        }
        // 关闭时退化为 max(1000, waitTime)，无指数无抖动
        AppConfig.readingPaceEnabled = false
        assertEquals(1_000L, ReadingPaceController.nextBackoffMillis(5))
        assertEquals(1_000L, ReadingPaceController.nextBackoffMillis(5, ConcurrentException("x", 500)))
        assertEquals(5_000L, ReadingPaceController.nextBackoffMillis(5, ConcurrentException("x", 5_000)))
    }

    // ---- 3. 限流抖动只放大 ----

    @Test
    fun rateLimitJitterOnlyWidens() {
        repeat(30) {
            val v = ReadingPaceController.withRateLimitJitter(800)
            // jitter = max(1, 800/8)=100 -> [800, 900]
            assertTrue("jitter=$v", v in 800..900)
        }
        assertEquals(0L, ReadingPaceController.withRateLimitJitter(0))
        assertEquals(-5L, ReadingPaceController.withRateLimitJitter(-5))
        AppConfig.readingPaceEnabled = false
        assertEquals(800L, ReadingPaceController.withRateLimitJitter(800))
    }

    // ---- 4. 长休息 ----

    @Test
    fun longRestEveryThirtiethChapter() {
        AppConfig.cachePaceManualEnabled = true
        AppConfig.readingPaceMinInterval = 1000
        AppConfig.readingPaceMaxInterval = 1000
        val counters = ctrlField<MutableMap<String, Int>>("chapterCounter")
        counters["s"] = 29
        assertEquals(1000L, nextInterval("s"))
        counters["s"] = 30
        // 1000 + [45000,150000]
        val v = nextInterval("s")
        assertTrue("longRest=$v", v in 46000..151000)
        // 开关关闭不叠加
        AppConfig.readingPaceLongRest = false
        assertEquals(1000L, nextInterval("s"))
    }

    // ---- 5. 长度自适应 ----

    @Test
    fun intervalScalesWithContentLength() {
        AppConfig.cachePaceManualEnabled = true
        AppConfig.readingPaceMinInterval = 1000
        AppConfig.readingPaceMaxInterval = 1000
        val counters = ctrlField<MutableMap<String, Int>>("chapterCounter")
        val lengths = ctrlField<MutableMap<String, Int>>("lastContentLength")
        counters["s"] = 1 // 避开 30 章长休息
        lengths["s"] = 6000 // scale = 0.8+0.6*1.0 = 1.4
        assertEquals(1400L, nextInterval("s"))
        lengths["s"] = 3000 // scale = 1.1
        assertEquals(1100L, nextInterval("s"))
        lengths["s"] = 60_000 // 超长封顶 1.4
        assertEquals(1400L, nextInterval("s"))
        lengths["s"] = 1 // scale≈0.8 -> 800，被下限钳回 1000
        assertEquals(1000L, nextInterval("s"))
        lengths.remove("s") // 无长度不缩放
        assertEquals(1000L, nextInterval("s"))
    }

    @Test
    fun onContentFetchedIgnoresBlankKeyAndCountsPerSource() {
        val counters = ctrlField<MutableMap<String, Int>>("chapterCounter")
        ReadingPaceController.onContentFetched("", 100)
        assertTrue(counters.isEmpty())
        ReadingPaceController.onContentFetched("s", 100)
        ReadingPaceController.onContentFetched("s", -5) // coerceAtLeast(0)
        assertEquals(2, counters["s"])
        assertEquals(0, ctrlField<MutableMap<String, Int>>("lastContentLength")["s"])
    }

    // ---- 6. 夜间静默（依赖挂钟，只断言不变量）----

    @Test
    fun quietHoursDisabledZeroAndEnabledNonNegative() {
        AppConfig.readingPaceQuietHours = false
        assertEquals(0L, quietWait())
        AppConfig.readingPaceQuietHours = true
        val w = quietWait()
        assertTrue("quietWait=$w", w >= 0L)
        // 处于静默时段时，等待必为「等到 07:00」，即不超过 8 小时
        assertTrue("quietWait=$w", w == 0L || w <= 8 * 3600_000L)
    }

    // ---- 7. 手填开关 effective* ----

    @Test
    fun effectiveIntervalsFollowManualSwitch() {
        // 关：忽略存量偏好，恒为内置 1200~4500
        AppConfig.cachePaceManualEnabled = false
        AppConfig.readingPaceMinInterval = 999
        AppConfig.readingPaceMaxInterval = 999
        assertEquals(1200, AppConfig.effectiveReadingPaceMinInterval)
        assertEquals(4500, AppConfig.effectiveReadingPaceMaxInterval)
        // 开：取手填值，且写入时按 [0,60000] 钳位
        AppConfig.cachePaceManualEnabled = true
        assertEquals(999, AppConfig.effectiveReadingPaceMinInterval)
        AppConfig.readingPaceMaxInterval = 70_000
        assertEquals(60_000, AppConfig.readingPaceMaxInterval)
        assertEquals(60_000, AppConfig.effectiveReadingPaceMaxInterval)
        AppConfig.readingPaceMinInterval = -5
        assertEquals(0, AppConfig.effectiveReadingPaceMinInterval)
    }
}

class ReadingPaceControllerTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        injectAsAppCtx()
    }
}
