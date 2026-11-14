package dev.operit.fanqiehook.hooks

import android.content.res.Resources
import android.view.MotionEvent
import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog
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
    private val resolver: ClassResolver,
    private val log: ModuleLog
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

    /** 上一次的原始 Y，用来判断手指是在往上还是往下滑。 */
    private var lastRawY = 0f

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
    /** 自绘的倍速提示浮层。 */
    private var overlayView: android.widget.TextView? = null

    /** 标记“这次 X2 是我们自己写的”，用于区分宿主发起的倍速变更（换集重置等）。 */
    private val selfApply = ThreadLocal.withInitial<Boolean> { false }

    fun installAll() {
        // 类名是混淆产物（adapter.a），红果重新混淆就会变字母。先按已知名找，
        // 找不到再按 getCurrentPlaySpeed 这个方法名反查——方法名没被混淆，跨版本更稳。
        val adapter = resolver.findClass(ADAPTER_CLASS)
            ?: run {
                log.info("speed gesture: adapter name miss, falling back to method search")
                resolver.findClassesHavingMethod("getCurrentPlaySpeed").firstOrNull()
            }
            ?: run {
                log.warn("speed gesture: adapter not found (neither name nor method)")
                return
            }
        adapterClass = adapter

        getCurrentSpeed = runCatching {
            adapter.getDeclaredMethod("getCurrentPlaySpeed").apply { isAccessible = true }
        }.getOrNull()

        val source = resolver.findClass(SOURCE_CLASS) ?: run {
            log.warn("speed gesture: $SOURCE_CLASS not found")
            return
        }
        setSpeedPercent = resolver.findUniqueMethodByParams(
            adapter,
            arrayOf(Integer.TYPE, source),
            java.lang.Void.TYPE
        )
        longPressSource = runCatching {
            source.getDeclaredMethod("valueOf", String::class.java)
                .invoke(null, "LONG_PRESS")
        }.getOrNull()

        if (getCurrentSpeed == null || setSpeedPercent == null || longPressSource == null) {
            log.warn("speed gesture: read/write path incomplete, feature skipped")
            return
        }

        stepPx = STEP_DP * Resources.getSystem().displayMetrics.density
        installAdapterCache()
        installPlayerParamsHook()
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
            val incoming = chain.thisObject
            val percent = chain.args.getOrNull(0) as? Int
            val fromSelf = selfApply.get() ?: false
            lastAdapter = incoming

            if (!fromSelf && armed && percent != null && percent != currentPercent) {
                // 宿主自己改了倍速：换集 / 播放器重建 / 内部恢复都会走到这里。
                // 先让它设完，再把当前档位写回去——否则长按中的倍速会在换集后“消失”，
                // 必须松手重按才恢复（就是这个 bug）。
                log.info("speed gesture: external speed=${percent}% != ${currentPercent}%, re-apply")
                val result = chain.proceed()
                applySpeed(currentPercent)
                result
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * 播放器层的倍速兑子。
     *
     * 实测（7.4.5.32）：换集时宿主**不会**走 adapter 的 `X2`，而是直接换一个播放器实例，
     * 新实例默认 1x——所以长按中的倍速会在换集后“消失”，必须松手重按。
     * 唯一能把持的地方是真正写进引擎的那个方法：`TTVideoEngine.setPlaybackParams`
     * （参数类型是字节自己的 `com.ss.ttm.player.PlaybackParams`，不是 android.media 那个）。
     * 长按期间把它的 speed 拉回当前档位，换集、播放器重建都能盖住。
     */
    private fun installPlayerParamsHook() {
        val engineClass = resolver.findClass("com.ss.ttvideoengine.TTVideoEngine") ?: run {
            log.warn("speed gesture: TTVideoEngine not found")
            return
        }
        val paramsClass = resolver.findClass("com.ss.ttm.player.PlaybackParams") ?: run {
            log.warn("speed gesture: TTM PlaybackParams not found")
            return
        }
        val method = runCatching {
            engineClass.getDeclaredMethod("setPlaybackParams", paramsClass)
        }.getOrNull() ?: run {
            log.warn("speed gesture: setPlaybackParams not found")
            return
        }
        val getSpeed = runCatching { paramsClass.getMethod("getSpeed") }.getOrNull() ?: return
        val setSpeed = runCatching {
            paramsClass.getMethod("setSpeed", java.lang.Float.TYPE)
        }.getOrNull() ?: return
        val setPitch = runCatching {
            paramsClass.getMethod("setPitch", java.lang.Float.TYPE)
        }.getOrNull()

        hooks.install("speedGesture:playerParams", method, deoptimize = true) { chain ->
            if (armed && !(selfApply.get() ?: false)) {
                val params = chain.args.getOrNull(0)
                if (params != null && paramsClass.isInstance(params)) {
                    val actual = runCatching { getSpeed.invoke(params) as? Float }.getOrNull() ?: 1f
                    val expected = currentPercent / 100f
                    if (kotlin.math.abs(actual - expected) > 0.01f) {
                        log.info("speed gesture: player params ${actual}x -> ${expected}x (episode switch)")
                        runCatching { setSpeed.invoke(params, expected) }
                        runCatching { setPitch?.invoke(params, 1.0f) }
                    }
                }
            }
            chain.proceed()
        }
    }

    private fun installGestureHooks() {
        // onLongPress 是框架回调、方法名不可混淆；再用「全屏包」把候选收窄到全屏播放页那一个。
        val fullscreen = resolver.findClassesHavingMethod("onLongPress")
            .filter { it.name.startsWith(FULLSCREEN_PREFIX) }
        log.info("speed gesture: fullscreen gesture handlers=${fullscreen.size}")
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
                lastRawY = event.rawY
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
                    hideSpeedOverlay()
                    log.info("speed gesture released: restore=${restorePercent}% last=${currentPercent}%")
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
        lastRawY = downY
        armed = true
        if (base != before) applySpeed(base)
        showSpeedOverlay(base)
        log.info("speed gesture armed: from=${before}% base=${base}% originY=$originY stepPx=$stepPx")
    }

    /** @return true 表示这次真的换了档位。 */
    private fun applyForPosition(rawY: Float): Boolean {
        val movingUp = rawY < lastRawY
        lastRawY = rawY
        val baseIdx = nearestIndex(basePercent)

        // 1) 原点吸附：只在「往下滑回原点」时触发。
        //    之前两个 bug 叠在一起：(a) 没同步 currentIndex，索引停在旧档位，
        //    后续升降档全乱；(b) anchorY 跳到 originY 而不是跟随手指。
        //    再加上上滑穿过原点也会吸附，表现就是「明明在往上滑，倍速却在降」。
        if (!movingUp && kotlin.math.abs(rawY - originY) <= SNAP_PX) {
            anchorY = rawY
            if (currentIndex == baseIdx && currentPercent == basePercent) return false
            currentIndex = baseIdx
            currentPercent = basePercent
            applySpeed(basePercent)
            showSpeedOverlay(basePercent)
            log.info("speed gesture: snap to origin -> ${basePercent}%")
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
            showSpeedOverlay(target)
            anchorY -= delta * stepPx
            log.info("speed gesture: index=$next -> ${target}%")
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
        selfApply.set(true)
        try {
            method.invoke(adapterInstance, percent, longPressSource)
        } catch (t: Throwable) {
            log.warn("speed gesture: apply ${percent}% failed (${t.javaClass.simpleName})")
        } finally {
            selfApply.set(false)
        }
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
    private fun showSpeedOverlay(percent: Int) {
        val tv = ensureOverlay() ?: return
        tv.text = "${formatPercent(percent)}倍速 快进中"
        tv.visibility = android.view.View.VISIBLE
        suppressHostHint()
    }

    /**
     * 把宿主自己的那个倍速浮层藏掉，避免两行字重叠。
     *
     * 时序：我们 450ms 就显示，宿主 500ms 才显示——所以先立刻藏一次（此时它可能还没出来），
     * 再补一次 800ms 的延迟藏，覆盖它真正出现的时刻。
     */
    private fun suppressHostHint() {
        val activity = findActivity() ?: return
        val root = activity.window?.decorView as? android.view.ViewGroup ?: return
        val hide = Runnable { hideHostHintIn(root) }
        root.post(hide)
        root.postDelayed(hide, 800)
    }

    private fun hideHostHintIn(v: android.view.View) {
        if (v === overlayView) return
        if (v is android.widget.TextView && v.isShown) {
            val text = v.text?.toString().orEmpty()
            if (text.length in 2..24 && text.contains("倍速")) hideWholeHintLayer(v)
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) hideHostHintIn(v.getChildAt(i))
        }
    }

    /**
     * 隐藏整个浮层，而不只是文字。
     *
     * 宿主的浮层是「带背景的容器 > 文字」，只把文字设成 INVISIBLE 会留下背景块。
     * 这里从文字向上找第一层自带背景的容器（最多 4 层），把那层整个藏掉。
     * 到根节点就停，避免误伤整个界面。
     */
    private fun hideWholeHintLayer(tv: android.view.View) {
        var target: android.view.View = tv
        var parent: android.view.ViewParent? = tv.parent
        var depth = 0
        while (parent is android.view.ViewGroup && depth < 4) {
            if (parent.parent == null) break
            target = parent
            if (parent.background != null) break
            parent = parent.parent
            depth++
        }
        val ok = runCatching { target.visibility = android.view.View.INVISIBLE }.isSuccess
        if (ok) log.info("speed gesture: host hint layer hidden (${target.javaClass.simpleName})")
    }

    private fun hideSpeedOverlay() {
        overlayView?.let { runCatching { it.visibility = android.view.View.GONE } }
    }

    /**
     * 自绘浮层。
     *
     * 不再指望宿主那个浮层：它只在宿主的「长按处理」里创建/显示，换集时会被销毁，
     * 而换集并不会重新触发那段逻辑——所以任何「找它新实例」的路子都拿不到。
     * 这里直接往 Activity 的 decorView 上加一个自己的 TextView，换集不会动它。
     */
    private fun ensureOverlay(): android.widget.TextView? {
        overlayView?.let { if (it.isAttachedToWindow) return it }
        val activity = findActivity() ?: run {
            log.warn("speed gesture: activity not found, overlay skipped")
            return null
        }
        val root = activity.window?.decorView as? android.view.ViewGroup ?: return null
        val tv = android.widget.TextView(activity).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(android.graphics.Color.parseColor("#B3000000"))
            }
        }
        val lp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            leftMargin = dp(16)
            bottomMargin = dp(40)
        }
        val added = runCatching { root.addView(tv, lp) }
            .onFailure { log.warn("speed gesture: overlay add failed (${it.javaClass.simpleName})") }
            .isSuccess
        if (!added) return null
        overlayView = tv
        log.info("speed gesture: overlay created")
        return tv
    }

    /** 拿当前 Activity。先用 adapter 里的 View 反查，失败则读 ActivityThread 的注册表。 */
    private fun findActivity(): android.app.Activity? {
        lastAdapter?.let { adapter ->
            val fields = runCatching { adapter.javaClass.declaredFields }.getOrNull()
            if (fields != null) {
                for (f in fields) {
                    runCatching { f.isAccessible = true }
                    val value = runCatching { f.get(adapter) }.getOrNull() ?: continue
                    if (value !is android.view.View) continue
                    var ctx: android.content.Context? = value.context
                    while (ctx is android.content.ContextWrapper) {
                        if (ctx is android.app.Activity) return ctx
                        ctx = ctx.baseContext
                    }
                }
            }
        }

        // 全屏的 adapter 不持有 View 字段（实测），只能从 ActivityThread.mActivities 里取。
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getMethod("currentActivityThread").invoke(null)
            val records = atClass.getDeclaredField("mActivities")
                .apply { isAccessible = true }
                .get(thread) as? Map<*, *> ?: return@runCatching null
            for (record in records.values) {
                if (record == null) continue
                val activity = runCatching {
                    record.javaClass.getDeclaredField("activity")
                        .apply { isAccessible = true }
                        .get(record) as? android.app.Activity
                }.getOrNull() ?: continue
                if (!activity.isFinishing && activity.window?.decorView?.isShown == true) {
                    return@runCatching activity
                }
            }
            null
        }.getOrNull()
    }

    private fun dp(v: Int): Int = (v * Resources.getSystem().displayMetrics.density).toInt()

    /** 300 -> "3"，150 -> "1.5"，125 -> "1.25"，75 -> "0.75" */
    private fun formatPercent(p: Int): String = when {
        p % 100 == 0 -> (p / 100).toString()
        p % 10 == 0 -> "${p / 100}.${(p % 100) / 10}"
        else -> "${p / 100}.${p % 100}"
    }
}
