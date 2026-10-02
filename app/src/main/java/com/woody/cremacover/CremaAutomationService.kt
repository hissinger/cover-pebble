package com.woody.cremacover

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * 크레마 설정 화면을 대신 눌러 슬립·종료 화면 이미지를 고른다.
 *
 * 크레마는 슬립·종료 이미지를 시스템 앱(com.wetao.usersettings)이 고를 때만
 * /data/misc/eink/{standby,poweroff}.png 로 복사하고, 이 위치는 일반 앱이 쓸 수 없다(SELinux).
 * 그래서 설정 앱 첫 화면 → 슬립/종료화면 이미지 → 사용자 지정 → 이미지 목록에서 우리 파일을 누르는 과정을 자동화한다.
 * 이미지 목록은 [기본 이미지] + /sdcard/Wallpaper 의 파일 순서이고, 한 페이지에 6개씩 보인다.
 * 목록 터치는 GestureDetector 로 처리하므로 노드 클릭이 아니라 실제 탭 제스처를 보낸다.
 * 진행하는 동안 설정 화면이 보이지 않도록, 터치를 통과시키는 가리개 화면을 그 위에 띄운다.
 */
class CremaAutomationService : AccessibilityService() {
    enum class Target(val fileName: String) {
        SLEEP("bookcover_sleep.png"),
        POWER_OFF("bookcover_poweroff.png"),
    }

    private enum class Step { MAIN, CONFIG, PICKER, WAIT_SAVE }

    /** [reset] 이면 [target] 화면을 기본 이미지로 되돌린다. 아니면 [Target.fileName] 을 고른다. */
    private class Job(val target: Target, val reset: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var step = Step.MAIN
    private var startedAt = 0L
    private var lastActionAt = 0L
    private var customToggledOff = false
    /** 목록을 탭한 뒤 크레마 설정의 저장 중 표시가 보였는지 */
    private var sawLoading = false

    private val tick = Runnable { advance() }
    private var overlay: View? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (job == null) return
        // 크레마 설정은 이미지를 저장하고 나면 완료 토스트를 띄운다. 이것을 성공 신호로 쓴다.
        if (step == Step.WAIT_SAVE && event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            event.packageName == SETTINGS_PKG
        ) {
            return finish(null)
        }
        schedule(300)
    }

    private fun start(target: Target, reset: Boolean) {
        job = Job(target, reset)
        step = Step.MAIN
        customToggledOff = false
        sawLoading = false
        startedAt = SystemClock.uptimeMillis()
        lastActionAt = 0L
        showOverlay(target, reset)
        val intent = Intent().setComponent(ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { startActivity(intent) }.onFailure {
            finish(getString(R.string.auto_failed_open))
            return
        }
        schedule(800)
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
    }

    /** 현재 화면을 보고 다음 동작을 한다. 화면이 바뀌길 기다리는 동안은 주기적으로 다시 확인한다. */
    private fun advance() {
        val job = job ?: return
        if (SystemClock.uptimeMillis() - startedAt > TIMEOUT_MS) {
            finish(getString(R.string.auto_failed_timeout))
            return
        }
        // 직전 동작의 화면 전환이 끝날 때까지 잠시 기다린다.
        if (SystemClock.uptimeMillis() - lastActionAt < 700) return schedule(400)
        val root = settingsRoot() ?: return schedule(400)

        when (step) {
            Step.MAIN -> {
                val id = if (job.target == Target.SLEEP) "sleep_wallpaper" else "shutdown_wallpaper"
                if (click(root.byId(id))) step = Step.CONFIG
            }
            Step.CONFIG -> when {
                job.target == Target.POWER_OFF -> if (click(root.byId("rl_custom"))) step = Step.PICKER
                // 슬립화면 초기화는 "기본 이미지 사용" 한 번이면 끝난다.
                job.reset -> if (click(root.byId("rl_default"))) return finish(null)
                else -> configSleep(root)
            }
            Step.PICKER -> pick(root, job)
            Step.WAIT_SAVE -> {
                // 완료 토스트를 못 받았을 때를 대비해, 저장 중 표시가 보였다가 사라지는 것도 완료로 본다.
                val loading = root.byId("loading")?.isVisibleToUser == true
                when {
                    loading -> sawLoading = true
                    sawLoading -> return finish(null)
                    SystemClock.uptimeMillis() - lastActionAt > SAVE_WAIT_MS -> return finish(getString(R.string.auto_unconfirmed))
                }
            }
        }
        schedule(400)
    }

    /**
     * 슬립화면의 "사용자 지정 이미지 사용"은 토글이라 이미 켜져 있으면 눌러도 목록이 열리지 않고 꺼진다.
     * 그래서 켜져 있으면 한 번 눌러 끄고, 꺼진 것을 확인한 뒤 다시 눌러 목록을 연다.
     */
    private fun configSleep(root: AccessibilityNodeInfo) {
        val custom = root.byId("rl_custom") ?: return
        val checked = root.byId("bt_custom")?.isChecked == true
        when {
            checked && !customToggledOff -> if (click(custom)) customToggledOff = true
            !checked -> if (click(custom)) step = Step.PICKER
        }
    }

    private fun pick(root: AccessibilityNodeInfo, job: Job) {
        val pageText = root.byId("page_text")?.text?.toString() ?: return
        // 0번은 시스템 기본 이미지다.
        val index = if (job.reset) 0 else CremaWallpaper.pickerIndex(job.target.fileName) ?: return finish(getString(R.string.auto_failed_missing))
        val targetPage = index / CremaWallpaper.PAGE_SIZE + 1
        val currentPage = pageText.substringBefore('/').trim().toIntOrNull() ?: return
        val list = root.byId("recycler_view") ?: return
        val bounds = Rect().also { list.getBoundsInScreen(it) }

        when {
            currentPage < targetPage -> swipe(bounds, next = true)
            currentPage > targetPage -> swipe(bounds, next = false)
            else -> {
                val images = root.allById("iv_image").map { node -> Rect().also { node.getBoundsInScreen(it) } }
                    .sortedWith(compareBy({ it.top }, { it.left }))
                val slot = index % CremaWallpaper.PAGE_SIZE
                val target = images.getOrNull(slot) ?: return
                tap(target.exactCenterX(), target.exactCenterY())
                step = Step.WAIT_SAVE
            }
        }
    }

    private fun finish(error: String?) {
        val finished = job ?: return
        job = null
        handler.removeCallbacks(tick)
        if (finished.reset && error == null) {
            // 기본 이미지로 되돌렸으면 이 앱이 만든 표지 파일도 지운다.
            File(CremaWallpaper.dir, finished.target.fileName).delete()
        }
        lastResult = Result(finished.target, finished.reset, error)
        // 앱으로 돌아가면 표지 화면이 결과를 보여준다. 앱 화면이 뜬 뒤에 가리개를 걷는다.
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        handler.postDelayed({ hideOverlay() }, 1200)
    }

    /** 가리개 아래에 있는 크레마 설정 창. 가리개는 포커스를 받지 않지만, 혹시 몰라 전체 창에서 찾는다. */
    private fun settingsRoot(): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName == SETTINGS_PKG }
            ?: windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == SETTINGS_PKG } }

    private fun showOverlay(target: Target, reset: Boolean) {
        hideOverlay()
        val wm = getSystemService(WindowManager::class.java)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 48, 48, 48)
            val cover = if (reset) null else runCatching {
                BitmapFactory.decodeFile(File(CremaWallpaper.dir, target.fileName).path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }.getOrNull()
            if (cover != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(cover)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f))
            }
            addView(TextView(context).apply {
                setText(
                    when {
                        reset && target == Target.SLEEP -> R.string.resetting_sleep_overlay
                        reset -> R.string.resetting_poweroff_overlay
                        target == Target.SLEEP -> R.string.applying_sleep_overlay
                        else -> R.string.applying_poweroff_overlay
                    }
                )
                setTextColor(Color.BLACK)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 12)
            })
            addView(TextView(context).apply {
                setText(R.string.applying_overlay_hint)
                setTextColor(Color.DKGRAY)
                textSize = 15f
                gravity = Gravity.CENTER
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 터치를 받지 않아야 자동 탭이 아래의 설정 화면으로 전달된다.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(view, params) }.onSuccess { overlay = view }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val done = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (done) lastActionAt = SystemClock.uptimeMillis()
        return done
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
        lastActionAt = SystemClock.uptimeMillis()
    }

    /** 목록 페이지 넘기기. 왼쪽으로 밀면 다음 페이지다. */
    private fun swipe(bounds: Rect, next: Boolean) {
        val y = bounds.exactCenterY()
        val (from, to) = if (next) bounds.right - 80f to bounds.left + 80f else bounds.left + 80f to bounds.right - 80f
        val path = Path().apply { moveTo(from, y); lineTo(to, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 120)).build(), null, null)
        lastActionAt = SystemClock.uptimeMillis()
    }

    private fun AccessibilityNodeInfo.byId(id: String): AccessibilityNodeInfo? = allById(id).firstOrNull()

    private fun AccessibilityNodeInfo.allById(id: String): List<AccessibilityNodeInfo> =
        findAccessibilityNodeInfosByViewId("$SETTINGS_PKG:id/$id").orEmpty()

    /** 자동화 결과. error 가 null 이면 성공. */
    data class Result(val target: Target, val reset: Boolean, val error: String?)

    companion object {
        private const val SETTINGS_PKG = CremaWallpaper.SETTINGS_PKG
        private const val TIMEOUT_MS = 30_000L
        /** 목록을 탭한 뒤 저장 완료 신호를 기다리는 최대 시간 */
        private const val SAVE_WAIT_MS = 8_000L

        private var instance: CremaAutomationService? = null

        @Volatile
        private var lastResult: Result? = null

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, CremaAutomationService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me } && instance != null
        }

        /**
         * 접근성 서비스가 켜져 있으면 자동화를 시작하고 true 를 돌려준다.
         * [reset] 이면 [target] 화면을 기본 이미지로 되돌리고, 아니면 [Target.fileName] 을 고른다.
         */
        fun run(target: Target, reset: Boolean = false): Boolean {
            val service = instance ?: return false
            if (service.job != null) return true // 이미 진행 중이면 그대로 둔다.
            lastResult = null
            service.start(target, reset)
            return true
        }

        /** 끝난 자동화 결과 중 등록([reset] = false) 또는 초기화([reset] = true) 결과를 한 번만 꺼내 준다. */
        fun takeResult(reset: Boolean): Result? =
            lastResult?.takeIf { it.reset == reset }?.also { lastResult = null }
    }
}

/** 크레마 설정의 사용자 이미지 목록과 같은 규칙으로 /sdcard/Wallpaper 를 다룬다. */
object CremaWallpaper {
    const val SETTINGS_PKG = "com.wetao.usersettings"
    private const val PEBBLE_DEVICE = "CREMA_PEBBLE"
    const val PAGE_SIZE = 6
    private val IMAGE_EXT = setOf("jpg", "gif", "png", "jpeg", "bmp")

    val dir: File get() = File(StorageDirs.root, "Wallpaper")

    /** 크레마 페블인지. 자동 탭은 페블의 크레마 설정 화면 구조에 맞춰져 있어 다른 기종은 막는다. */
    fun isAvailable(context: Context): Boolean =
        Build.DEVICE == PEBBLE_DEVICE &&
            runCatching { context.packageManager.getApplicationInfo(SETTINGS_PKG, 0).enabled }.getOrDefault(false)

    /**
     * 크레마 설정 이미지 목록에서 [fileName] 의 위치. 0번은 시스템 기본 이미지이고,
     * 그 뒤로 Wallpaper 폴더의 파일이 listFiles() 순서대로 온다(크레마 설정과 같은 규칙).
     */
    fun pickerIndex(fileName: String): Int? {
        val files = dir.listFiles()?.filter {
            !it.name.startsWith(".") && it.extension.lowercase() in IMAGE_EXT
        } ?: return null
        val i = files.indexOfFirst { it.name == fileName }
        return if (i < 0) null else i + 1
    }
}
