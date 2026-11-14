package dev.operit.fanqiehook.hooks

import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog
import java.lang.reflect.Method

/**
 * 竖屏播放页（首页推荐流）：长按倍速 + 上下滑动逐档调速。
 *
 * ## 与横屏的关系
 *
 * 这是一个完全独立的类，**[SpeedGestureHooks] 一行都不改**。两者只在两个只读点上重合：
 *  - 都用 adapter 的 `X2(int, PlaybackSpeedChangeSource)` 写倍速；
 *  - 都用 `getCurrentPlaySpeed()` 读当前倍速。
 * 运行时状态（armed / 当前档位 / 锚点 / 浮层）各持一份，互不干扰。
 *
 * ## 为什么竖屏必须走另一条路
 *
 * 横屏有一个 `VideoGestureDetectLayout` 统一接收触摸，hook 它就能精确拦截。
 * 竖屏没有这个类：滑动切集是 `ViewPager` + `RecyclerView`，长按是宿主另一套手势
 * （连 `setOnLongClickListener` 都没用）。静态代码里能对上的只有资源字符串
 * 「上滑锁定倍速」「下滑退出倍速」，实现在未反编译出来的部分。
 *
 * ## 宿主本来就有竖屏 2 倍速
 *
 * 长按会进入 2 倍速，底部显示，并提示「上滑锁定倍速」。本类不推翻它，只做两件事：
 *  1. 长按后接管滑动，把固定的 2 倍速扩成六档；
 *  2. 接管期间把触摸事件吃掉，宿主收不到滑动，就不会触发它自己的「上滑锁定」。
 *
 * ## 只在竖屏 + 页面里有视频时生效
 *
 * 用屏幕方向排除横屏；用视图树里有没有 `SurfaceView` / `TextureView` 排除设置页之类的
 * 非播放页面——否则在那些页面长按也会莫名其妙改倍速。
 */
class PortraitSpeedGestureHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog
) {

    private companion object {
        /** 短剧播放器 adapter。与 [SpeedGestureHooks] 同一个类。 */
        const val ADAPTER_CLASS = "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a"

        /** 倍速来源枚举。 */
        const val SOURCE_CLASS =
            "com.dragon.read.component.shortvideo.api.controller.PlaybackSpeedChangeSource"

        /** 红果设置页真实六档：0.75 / 1 / 1.25 / 1.5 / 2 / 3。 */
        val SPEED_PERCENTS = intArrayOf(75, 100, 125, 150, 200, 300)

        /** 竖屏每滑动多少 dp 换一档。手机竖屏长，大拇指行程短，步长比横屏小。 */
        const val STEP_DP = 36f

        /** 按住多久进入倍速。比宿主自己的 500ms 略早，抢在它前面接管。 */
        const val LONG_PRESS_MS = 420L

        /** 按住期间位移超过这么多 px 就不算长按。 */
        const val CANCEL_SLOP_PX = 100f

        /** 手指回到按下点附近强制回基准档。 */
        const val SNAP_PX = 24f

        /** 宿主长按给出的固定档位。 */
        const val HOST_LONG_PRESS_PERCENT = 200
    }

    private var adapterClass: Class<*>? = null
    private var getSpeed: Method? = null
    private var setSpeed: Method? = null
    private var longPressSource: Any? = null
    private var stepPx: Float = 180f

    @Volatile
    private var armed = false

    private var originY = 0f
    private var anchorY = 0f
    private var lastRawY = 0f
    private var downY = 0f
    private var downX = 0f
    private var downTime = 0L
    private var currentIndex = 0
    private var basePercent = HOST_LONG_PRESS_PERCENT
    private var restorePercent = 100
    private var currentPercent = 100

    /** 上一次观察到的宿主倍速，用来只记录变化、避免刷屏。 */
    private var lastHostPercent = -1

    private val handler = Handler(Looper.getMainLooper())
    private var pendingArm: Runnable? = null

    /** 最近一次写倍速的 adapter 实例。 */
    @Volatile
    private var lastAdapter: Any? = null

    /** 是否已确认当前页面有视频（避免每个 DOWN 都遍历视图树）。 */
    @Volatile
    private var lastVideoCheckAt = 0L
    @Volatile
    private var lastVideoCheckResult = false

    private var overlayView: TextView? = null

    /** 标记「这次 X2 是我们自己写的」，用于区分宿主发起的倍速变更。 */
    private val selfApply = ThreadLocal.withInitial<Boolean> { false }

    fun installAll() {
        val adapter = resolver.findClass(ADAPTER_CLASS)
            ?: run {
                log.info("portrait speed: adapter name miss, falling back to method search")
                resolver.findClassesHavingMethod("getCurrentPlaySpeed").firstOrNull()
            }
            ?: run {
                log.warn("portrait speed: adapter not found")
                return
            }
        adapterClass = adapter

        getSpeed = runCatching {
            adapter.getDeclaredMethod("getCurrentPlaySpeed").apply { isAccessible = true }
        }.getOrNull()

        val source = resolver.findClass(SOURCE_CLASS) ?: run {
            log.warn("portrait speed: $SOURCE_CLASS not found")
            return
        }
        setSpeed = resolver.findUniqueMethodByParams(
            adapter,
            arrayOf(Integer.TYPE, source),
            java.lang.Void.TYPE
        )
        longPressSource = runCatching {
            source.getDeclaredMethod("valueOf", String::class.java)
                .invoke(null, "LONG_PRESS")
        }.getOrNull()

        if (getSpeed == null || setSpeed == null || longPressSource == null) {
            log.warn("portrait speed: read/write path incomplete, feature skipped")
            return
        }

        stepPx = STEP_DP * Resources.getSystem().displayMetrics.density
        installSpeedObserver()
        installTouchHook()
    }

    /** 捕获 adapter 实例，并在宿主自己重置倍速时把当前档位写回去。 */
    private fun installSpeedObserver() {
        hooks.install("portraitSpeed:adapterCache", setSpeed) { chain ->
            val incoming = chain.thisObject
            val percent = chain.args.getOrNull(0) as? Int
            val fromSelf = selfApply.get() ?: false
            lastAdapter = incoming

            if (!fromSelf && percent != null) {
                if (armed && percent != currentPercent) {
                    log.info("portrait speed: external ${percent}% != ${currentPercent}%, re-apply")
                    val result = chain.proceed()
                    applySpeed(currentPercent)
                    return@install result
                }
                if (percent != lastHostPercent) {
                    lastHostPercent = percent
                    log.info("portrait speed: host set ${percent}% (armed=$armed)")
                }
            }
            chain.proceed()
        }
    }

    private fun installTouchHook() {
        val method = runCatching {
            Activity::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull() ?: run {
            log.warn("portrait speed: Activity.dispatchTouchEvent not found")
            return
        }
        hooks.install("portraitSpeed:touch", method) { chain ->
            val activity = chain.thisObject as? Activity
            val event = chain.args.getOrNull(0) as? MotionEvent
            if (activity == null || event == null) {
                return@install chain.proceed()
            }
            if (activity.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
                return@install chain.proceed()
            }
            if (handleTouch(activity, event)) {
                // 已接管，不传给宿主：这样它自己的「上滑锁定倍速」不会触发。
                true
            } else {
                chain.proceed()
            }
        }
    }

    /** @return true 表示这个事件已被消费，不再分发给宿主。 */
    private fun handleTouch(activity: Activity, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingArm()
                armed = false
                downY = event.rawY
                downX = event.rawX
                downTime = android.os.SystemClock.uptimeMillis()
                if (!hasVideoSurface(activity)) return false
                // 底部进度条区域不算长按：那里是拖进度用的。
                // 屏幕高的下 18%（本机约 420px）覆盖进度条和它上方的描述区。
                val screenH = activity.resources.displayMetrics.heightPixels
                if (event.rawY > screenH * 0.82f) return false
                scheduleArm(event.rawY)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!armed) {
                    if (kotlin.math.abs(event.rawY - downY) > CANCEL_SLOP_PX) cancelPendingArm()
                    return false
                }
                applyForPosition(event.rawY)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingArm()
                if (!armed) return false
                // 抬手必须放行。之前这里返回 true，宿主收不到 UP，它的长按状态一直停着，
                // 于是一直维持 2 倍速——表现就是「松手还是 2 倍速」。
                releaseSpeed()
                return false
            }
        }
        return false
    }

    private fun scheduleArm(rawY: Float) {
        cancelPendingArm()
        val runnable = Runnable {
            pendingArm = null
            val adapter = lastAdapter ?: run {
                log.warn("portrait speed: no adapter captured yet, long press ignored")
                return@Runnable
            }
            // 宿主自己的长按会设 200%；我们沿用它的值作为基准档，只扩滑动部分。
            val current = runCatching { getSpeed?.invoke(adapter) as? Int }.getOrNull() ?: 100
            restorePercent = current
            basePercent = HOST_LONG_PRESS_PERCENT
            originY = rawY
            anchorY = rawY
            lastRawY = rawY
            currentIndex = nearestIndex(basePercent)
            currentPercent = basePercent
            armed = true
            applySpeed(basePercent)
            showOverlay(basePercent)
            log.info("portrait speed: armed from=${current}% base=${basePercent}%")
        }
        pendingArm = runnable
        handler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelPendingArm() {
        pendingArm?.let { handler.removeCallbacks(it) }
        pendingArm = null
    }

    private fun releaseSpeed() {
        armed = false
        applySpeed(restorePercent)
        hideOverlay()
        log.info("portrait speed: released restore=${restorePercent}% last=${currentPercent}%")
    }

    /** 增量式换档 + 原点吸附，规则与横屏一致。 */
    private fun applyForPosition(rawY: Float): Boolean {
        val movingUp = rawY < lastRawY
        lastRawY = rawY
        val baseIdx = nearestIndex(basePercent)

        // 只在往下滑回原点时吸附。上滑穿过原点不吸附，否则会出现「在加速却被拽回基准」。
        if (!movingUp && kotlin.math.abs(rawY - originY) <= SNAP_PX) {
            anchorY = rawY
            if (currentIndex == baseIdx && currentPercent == basePercent) return false
            currentIndex = baseIdx
            currentPercent = basePercent
            applySpeed(basePercent)
            showOverlay(basePercent)
            log.info("portrait speed: snap to origin -> ${basePercent}%")
            return true
        }

        var changed = false
        while (true) {
            val dy = anchorY - rawY
            if (kotlin.math.abs(dy) < stepPx) break
            val delta = if (dy > 0) 1 else -1
            val next = currentIndex + delta
            if (next < 0 || next > SPEED_PERCENTS.lastIndex) {
                anchorY = rawY
                break
            }
            currentIndex = next
            anchorY -= delta * stepPx
            currentPercent = SPEED_PERCENTS[next]
            applySpeed(currentPercent)
            showOverlay(currentPercent)
            log.info("portrait speed: index=$next -> ${currentPercent}%")
            changed = true
        }
        return changed
    }

    private fun applySpeed(percent: Int) {
        val adapter = lastAdapter ?: return
        val method = setSpeed ?: return
        val source = longPressSource ?: return
        selfApply.set(true)
        try {
            method.invoke(adapter, percent, source)
            // 回读真实速度：证明 X2 写下去确实生效了，而不是只改了缓存。
            val actual = runCatching { getSpeed?.invoke(adapter) as? Int }.getOrNull()
            if (actual != null && actual != percent) {
                log.warn("portrait speed: wrote ${percent}% but adapter reports ${actual}%")
            }
        } catch (t: Throwable) {
            log.warn("portrait speed: apply $percent% failed (${t.javaClass.simpleName})")
        } finally {
            selfApply.set(false)
        }
        currentPercent = percent
    }

    private fun nearestIndex(percent: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (i in SPEED_PERCENTS.indices) {
            val d = kotlin.math.abs(SPEED_PERCENTS[i] - percent)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    // ─────────────────────────────────────────
    // 浮层：自己画，不碰宿主的提示
    // ─────────────────────────────────────────

    private fun showOverlay(percent: Int) {
        // 不用缓存：宿主可能随时销毁或隐藏我们的浮层，每次都重新创建/获取。
        val tv = ensureOverlay() ?: return
        tv.text = "${formatPercent(percent)}倍速 快进中"
        tv.visibility = View.VISIBLE
        tv.bringToFront()
        log.info("portrait speed: showOverlay ${percent}%")
    }

    private fun hideOverlay() {
        overlayView?.let {
            runCatching { it.visibility = View.GONE }
            runCatching { (it.parent as? ViewGroup)?.removeView(it) }
            overlayView = null
        }
    }

    private fun ensureOverlay(): TextView? {
        // 先检查缓存的浮层是否还真正可见（attached 且 shown 且透明度正常）。
        val existing = overlayView
        if (existing != null && existing.isAttachedToWindow && existing.isShown && existing.alpha > 0f) {
            return existing
        }
        // 缓存不可用，清理掉。
        if (existing != null) {
            runCatching { (existing.parent as? ViewGroup)?.removeView(existing) }
            overlayView = null
        }
        log.info("portrait speed: ensureOverlay begin")
        val activity = currentActivity()
        if (activity == null) {
            log.warn("portrait speed: overlay skipped, no activity")
            return null
        }
        val root = activity.window?.decorView as? ViewGroup
        if (root == null) {
            log.warn("portrait speed: overlay skipped, decorView not ViewGroup")
            return null
        }
        val tv = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#B3000000"))
            }
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(16)
            bottomMargin = dp(40)
        }
        val added = runCatching { root.addView(tv, lp) }
            .onFailure { log.warn("portrait speed: overlay add failed (${it.javaClass.simpleName})") }
            .isSuccess
        if (!added) return null
        overlayView = tv
        log.info("portrait speed: overlay created")
        return tv
    }

    private fun currentActivity(): Activity? {
        // 路径 1：从 adapter 持有的 View 反查。
        lastAdapter?.let { adapter ->
            val fields = runCatching { adapter.javaClass.declaredFields }.getOrNull()
            if (fields != null) {
                for (f in fields) {
                    runCatching { f.isAccessible = true }
                    val value = runCatching { f.get(adapter) }.getOrNull() ?: continue
                    if (value !is View) continue
                    var ctx: android.content.Context? = value.context
                    while (ctx is android.content.ContextWrapper) {
                        if (ctx is Activity) return ctx
                        ctx = ctx.baseContext
                    }
                }
            }
        }
        // 路径 2：ActivityThread 注册表。
        val fallback = findActivityByRegistry()
        if (fallback == null) log.warn("portrait speed: no activity found")
        return fallback
    }

    private fun findActivityByRegistry(): Activity? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getMethod("currentActivityThread").invoke(null)
            val records = atClass.getDeclaredField("mActivities")
                .apply { isAccessible = true }
                .get(thread) as? Map<*, *>
            if (records == null) {
                null
            } else {
                var found: Activity? = null
                for (record in records.values) {
                    if (record == null) continue
                    val activity = runCatching {
                        record.javaClass.getDeclaredField("activity")
                            .apply { isAccessible = true }
                            .get(record) as? Activity
                    }.getOrNull() ?: continue
                    if (!activity.isFinishing && activity.window?.decorView?.isShown == true) {
                        found = activity
                        break
                    }
                }
                found
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun dp(v: Int): Int = (v * Resources.getSystem().displayMetrics.density).toInt()

    /** 300 -> "3"，125 -> "1.25"。 */
    private fun formatPercent(p: Int): String = when {
        p % 100 == 0 -> "${p / 100}"
        p % 10 == 0 -> "${p / 100}.${(p % 100) / 10}"
        else -> "${p / 100}.${p % 100}"
    }

    /**
     * 当前页面有没有视频。用来排除设置页、个人中心这类竖屏页面——
     * 否则在那里长按也会把倍速改掉。
     */
    private fun hasVideoSurface(activity: Activity): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastVideoCheckAt < 1000L) return lastVideoCheckResult
        val root = activity.window?.decorView ?: return false
        val found = containsVideoView(root, 0)
        lastVideoCheckAt = now
        lastVideoCheckResult = found
        return found
    }

    private fun containsVideoView(view: View, depth: Int): Boolean {
        if (depth > 24) return false
        if (view is SurfaceView || view is TextureView) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsVideoView(view.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }
}
