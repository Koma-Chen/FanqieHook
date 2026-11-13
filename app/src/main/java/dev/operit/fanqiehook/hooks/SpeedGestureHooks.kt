package dev.operit.fanqiehook.hooks

import android.content.res.Resources
import android.view.MotionEvent
import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import java.lang.reflect.Method

/**
 * 全屏短剧播放页：长按倍速 + 滑动调档。
 *
 * ## 宿主原本怎么做（红果 7.4.5.32，DEX 静态核验）
 *
 * 全屏播放页（`ShortSeriesLandActivity`）的触摸事件由
 * `shortserieslayer.speed.VideoGestureDetectLayout` 统一接收，再转给手势回调列表；回调实现在
 * `fullscreen.i$d`（类名混淆，但重写的是框架回调 `onLongPress`/`onDoubleTap`，方法名不可混淆）。
 * 长按时宿主把倍速写成固定值（实测 200%），松手恢复。
 *
 * 也就是说：**长按加速是宿主既有能力，缺的只是「滑动能改档位」**。
 *
 * ## 只在全屏生效
 *
 * 竖屏信息流里长按只是弹操作面板，没有倍速这回事，所以竖屏那条手势链（`vb5.o0`）完全不碰。
 *
 * ## 档位
 *
 * 沿用红果设置页真实展示的六档（0.75 / 1 / 1.25 / 1.5 / 2 / 3）。宿主自带的横拖只有
 * 1.5/2/3 三档，不够细。
 *
 * ## 换档模型：增量式 + 原点吸附
 *
 * 每滑过一个步长换一档，锚点跟着手指走；到顶/到底后再往同方向滑，锚点贴到手指位置，
 * 这样反向滑一个步长就能立刻换档，不用先把「超出上限」的行程滑回来。
 * 手指回到按下点 ±[SNAP_PX] 内则强制回基准倍速，保证「回到原点 = 按下去时的倍速」。
 *
 * ## 写倍速走宿主自己的入口
 *
 * 直接调播放器接口虽然也能改速度，但会绕过宿主的缓存与埋点，界面文案会和实际不一致。
 * 这里用 adapter 上与 `PlaybackSpeedChangeSource` 配对的那个入口（`X2`），它同时更新
 * 播放器、缓存和埋点。方法名混淆，所以按**签名**定位。
 */
class SpeedGestureHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver
) {

    private companion object {
        /** 短剧播放器 adapter，类名未混淆且近几个版本稳定。 */
        const val ADAPTER_CLASS = "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a"

        /** 倍速来源枚举，类名未混淆。 */
        const val SOURCE_CLASS =
            "com.dragon.read.component.shortvideo.api.controller.PlaybackSpeedChangeSource"

        /** 全屏短剧播放页的手势回调所在的包（类名混淆，但包路径稳定）。 */
        const val FULLSCREEN_PREFIX = "com.dragon.read.component.shortvideo.impl.fullscreen."

        /** 横屏手势容器，类名未混淆。 */
        const val VIDEO_GESTURE_LAYOUT =
            "com.dragon.read.component.shortvideo.impl.shortserieslayer.speed.VideoGestureDetectLayout"

        /** 左下角「N倍速 快进中」那行 TextView 的 id（7.4.5.32 核验）。 */
        const val SPEED_HINT_ID = 0x7f11325f

        /** 红果设置页实际展示的倍速档位（百分比）：0.75 / 1 / 1.25 / 1.5 / 2 / 3。 */
        val SPEED_PERCENTS = intArrayOf(75, 100, 125, 150, 200, 300)

        /** 每滑动多少 dp 换一档。60dp 在 3x 屏上约 180px。 */
        const val STEP_DP = 60f

        /** 按住多久算长按。比系统 GestureDetector 的 500ms 略早。 */
        const val LONG_PRESS_MS = 450L

        /** 按住期间位移超过这么多 px 就不算长按。 */
        const val CANCEL_SLOP_PX = 100f

        /** 手指回到按下点这么多像素以内，强制回到基准倍速。8dp @3x = 24px。 */
        const val SNAP_PX = 24f
    }

    private var adapterClass: Class<*>? = null
    private var getCurrentSpeed: Method? = null
    private var setSpeedPercent: Method? = null
    private var longPressSource: Any? = null
    private var stepPx: Float = 180f

    /** 长按是否已进入「滑动调档」模式。 */
    @Volatile
    private var armed = false
    private var originY = 0f

    /** 增量式换档的锚点：每换一档就向前移动一个 stepPx，到边界时贴到手指位置。 */
    private var anchorY = 0f

    /** 当前档位在 [SPEED_PERCENTS] 里的下标。 */
    private var currentIndex = 0

    /** 长按加速后的基准档位（手指回到原点时回到这个值）。 */
    private var basePercent = 200

    /** 长按之前的倍速，松手时恢复成它。 */
    private var restorePercent = 100
    private var currentPercent = 100

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingArm: Runnable? = null
    private var downY = 0f
    private var downEventTime = 0L

    /** 最近一次设倍速的 adapter 实例。全屏手势回调不持有它，只能从 X2 调用时捕获。 */
    @Volatile
    private var lastAdapter: Any? = null

    /** 左下角「N倍速 快进中」的 TextView，首次调档时定位并缓存。 */
    private var hintView: android.widget.TextView? = null

    fun installAll() {
        val adapter = resolver.findClass(ADAPTER_CLASS) ?: return
        adapterClass = adapter

        getCurrentSpeed = runCatching {
            adapter.getDeclaredMethod("getCurrentPlaySpeed").apply { isAccessible = true }
        }.getOrNull()

        val source = resolver.findClass(SOURCE_CLASS) ?: return
        setSpeedPercent = resolver.findUniqueMethodByParams(
            adapter,
            arrayOf(Integer.TYPE, source),
            java.lang.Void.TYPE
        )
        longPressSource = runCatching {
            source.getDeclaredMethod("valueOf", String::class.java)
                .invoke(null, "LONG_PRESS")
        }.getOrNull()

        if (getCurrentSpeed == null || setSpeedPercent == null || longPressSource == null) return

        stepPx = STEP_DP * Resources.getSystem().displayMetrics.density
        installAdapterCache()
        installGestureHooks()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 落点
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 宿主所有倍速修改都汇聚到 [setSpeedPercent]，顺便把 adapter 实例缓存下来。
     * 全屏的手势回调不持有 adapter 字段（持有它的是竖屏那个），所以只能这样拿。
     */
    private fun installAdapterCache() {
        hooks.install("speedGesture:adapterCache", setSpeedPercent) { chain ->
            lastAdapter = chain.thisObject
            chain.proceed()
        }
    }

    private fun installGestureHooks() {
        // onLongPress 是框架回调、方法名不可混淆；再用「全屏包」把候选收窄到全屏播放页那一个。
        val fullscreen = resolver.findClassesHavingMethod("onLongPress")
            .filter { it.name.startsWith(FULLSCREEN_PREFIX) }
        if (fullscreen.isEmpty()) return
        fullscreen.forEach { installTouchHook(it) }
        installLandGestureSuppressor()
    }

    private fun installTouchHook(gestureClass: Class<*>) {
        val touchDispatch = gestureClass.declaredMethods.firstOrNull { m ->
            m.parameterCount == 2 &&
                m.parameterTypes[0] == MotionEvent::class.java &&
                m.parameterTypes[1] == android.view.View::class.java
        } ?: return
        hooks.install("speedGesture:touch", touchDispatch, deoptimize = true) { chain ->
            runCatching { onTouch(chain.args.getOrNull(0) as? MotionEvent) }
            chain.proceed()
        }
    }

    /**
     * 长按期间屏蔽横屏手势（亮度 / 音量 / 进度）。
     *
     * `VideoGestureDetectLayout` 一旦识别到垂直滑动就会切到亮度/音量手势并把后续事件吐掉，
     * 表现为「长按后一滑就直接 CANCEL」。在长按期间让它直接返回 false，滑动才会继续流到
     * 手势回调列表里。类名未混淆，方法名混淆，按签名 `(MotionEvent, GestureDetector)Z` 定位。
     */
    private fun installLandGestureSuppressor() {
        val layout = resolver.findClass(VIDEO_GESTURE_LAYOUT) ?: return
        val method = layout.declaredMethods.firstOrNull { m ->
            m.parameterCount == 2 &&
                m.parameterTypes[0] == MotionEvent::class.java &&
                m.parameterTypes[1] == android.view.GestureDetector::class.java &&
                m.returnType == java.lang.Boolean.TYPE
        } ?: return
        hooks.install("speedGesture:suppressLandGesture", method, deoptimize = true) { chain ->
            val ev = chain.args.getOrNull(0) as? MotionEvent
            val heldLongEnough = ev != null && ev.eventTime - ev.downTime >= LONG_PRESS_MS
            if (armed || heldLongEnough) false else chain.proceed()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 手势状态机
    // ─────────────────────────────────────────────────────────────────────────

    private fun onTouch(event: MotionEvent?) {
        if (event == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingArm()
                downY = event.rawY
                anchorY = event.rawY
                downEventTime = event.eventTime
                val runnable = Runnable { armFromTimer() }
                pendingArm = runnable
                handler.postDelayed(runnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed) {
                    applyForPosition(event.rawY)
                } else if (kotlin.math.abs(event.rawY - downY) > CANCEL_SLOP_PX) {
                    // 手指在按住期间就开始移动 -> 这是滑动，不是长按。
                    cancelPendingArm()
                } else if (event.eventTime - downEventTime >= LONG_PRESS_MS) {
                    // 按事件自己的时间算长按：按住不动时触摸屏也会持续上报 MOVE，
                    // 而用 Handler 的真实时间会和注入式事件对不上。
                    armFromTimer()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingArm()
                if (armed) {
                    armed = false
                    applySpeed(restorePercent)
                }
            }
        }
    }

    private fun cancelPendingArm() {
        pendingArm?.let { handler.removeCallbacks(it) }
        pendingArm = null
    }

    /** 定时器到点：确认仍在按住，就把倍速提上去。 */
    private fun armFromTimer() {
        pendingArm = null
        if (armed) return
        val adapterInstance = lastAdapter ?: return
        val before = readSpeed(adapterInstance)
        // 与红果原本的取法一致：低于 1x 时提到 2x，已经更快则保持。
        val base = if (before <= 100) 200 else before
        restorePercent = before
        basePercent = base
        currentPercent = base
        currentIndex = nearestIndex(base)
        originY = downY
        anchorY = downY
        armed = true
        if (base != before) applySpeed(base)
        updateSpeedHint(base)
    }

    /** @return true 表示这次真的换了档位。 */
    private fun applyForPosition(rawY: Float): Boolean {
        // 1) 原点吸附：手指回到按下点附近，直接回基准。
        //    这条是「回到原点 = 按下去时的倍速」的唯一保证——增量式本身不保证。
        if (kotlin.math.abs(rawY - originY) <= SNAP_PX) {
            anchorY = originY
            if (currentPercent == basePercent) return false
            applySpeed(basePercent)
            currentPercent = basePercent
            updateSpeedHint(basePercent)
            return true
        }

        // 2) 增量式换档：每跨一个步长换一档，锚点跟着走。
        //    到顶/到底后把锚点贴到手指位置，反向滑一个步长就能立刻换档。
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
            val target = SPEED_PERCENTS[next]
            applySpeed(target)
            currentPercent = target
            updateSpeedHint(target)
            anchorY -= delta * stepPx
            changed = true
        }
        return changed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 倍速读写
    // ─────────────────────────────────────────────────────────────────────────

    private fun applySpeed(percent: Int) {
        val method = setSpeedPercent ?: return
        val adapterInstance = lastAdapter ?: return
        runCatching { method.invoke(adapterInstance, percent, longPressSource) }
    }

    private fun readSpeed(adapterInstance: Any): Int = runCatching {
        (getCurrentSpeed?.invoke(adapterInstance) as? Int) ?: 100
    }.getOrDefault(100)

    private fun nearestIndex(percent: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        SPEED_PERCENTS.forEachIndexed { i, v ->
            val d = kotlin.math.abs(v - percent)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 左下角提示文案
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 把左下角那行提示改成当前档位。
     *
     * 宿主在长按时把文案写成固定字符串（如「2倍速 快进中」），之后无论怎么改倍速都不会
     * 再动它。这里只替掉开头的倍速数字，保留宿主自己的后半句——不同版本文案模板不一样。
     */
    private fun updateSpeedHint(percent: Int) {
        val cached = hintView?.takeIf { it.isAttachedToWindow }
        val tv = cached ?: findHintView()?.also { hintView = it } ?: return
        val label = formatPercent(percent)
        val current = tv.text?.toString().orEmpty()
        val updated = if (current.contains("倍速")) {
            current.replace(Regex("""\d+(?:\.\d+)?\s*倍速"""), "${label}倍速")
        } else {
            "${label}倍速 快进中"
        }
        runCatching { tv.text = updated }
    }

    /**
     * 找左下角那行提示的 TextView。
     *
     * 两层策略：
     *  1. 先按 id 找——快，但 id 是版本相关的（[SPEED_HINT_ID] 只在 7.4.5.32 核验过）；
     *  2. id 找不到就按**文本特征**在视图树里搜（文本含「倍速」且短），不依赖任何 id/类名。
     *     宿主每个版本都会重排资源 id，实测 7.4.3.32 上按 id 完全找不到。
     */
    private fun findHintView(): android.widget.TextView? {
        val adapter = lastAdapter ?: return null
        return searchHintView(adapter, 0)
    }

    private fun searchHintView(root: Any, depth: Int): android.widget.TextView? {
        if (depth > 2) return null
        val fields = runCatching { root.javaClass.declaredFields }.getOrNull() ?: return null
        for (f in fields) {
            runCatching { f.isAccessible = true }
            val value = runCatching { f.get(root) }.getOrNull() ?: continue
            if (value is android.widget.TextView && looksLikeSpeedHint(value)) return value
            if (value is android.view.View) {
                runCatching {
                    value.rootView.findViewById<android.widget.TextView>(SPEED_HINT_ID)
                }.getOrNull()?.takeIf { looksLikeSpeedHint(it) }?.let { return it }
                searchViewTree(value)?.let { return it }
            }
            searchHintView(value, depth + 1)?.let { return it }
        }
        return null
    }

    /** 在给定 View 子树里按文本特征找那行提示。限制深度，避免在播放器根布局里做全量扫描。 */
    private fun searchViewTree(root: android.view.View, depth: Int = 0): android.widget.TextView? {
        if (depth > 8) return null
        if (root is android.widget.TextView && looksLikeSpeedHint(root)) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                searchViewTree(root.getChildAt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 「2倍速 快进中」这类短文本：长度短且含「倍速」，避免误中正文或弹幕。 */
    private fun looksLikeSpeedHint(tv: android.widget.TextView): Boolean {
        val text = tv.text?.toString().orEmpty()
        return text.length in 2..24 && text.contains("倍速")
    }

    /** 300 -> "3"，150 -> "1.5"，125 -> "1.25"，75 -> "0.75" */
    private fun formatPercent(p: Int): String = when {
        p % 100 == 0 -> (p / 100).toString()
        p % 10 == 0 -> "${p / 100}.${(p % 100) / 10}"
        else -> "${p / 100}.${p % 100}"
    }
}
