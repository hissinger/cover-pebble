package com.woody.cremacover

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.woody.cremacover.CremaAutomationService.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CoverActivity : AppCompatActivity() {
    private lateinit var cover: BookCover
    private lateinit var prefs: Prefs
    private lateinit var saved: SavedCovers

    private lateinit var preview: ImageView
    private lateinit var statusText: TextView
    private lateinit var sleepButton: Button
    private lateinit var powerOffButton: Button
    private lateinit var saveButton: Button
    private lateinit var savedBadge: TextView

    private var originalBytes: ByteArray? = null
    private var rendered: Bitmap? = null
    private var busy = false
    private var crema = false
    private var pendingTarget: Target? = null

    private val writePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = pendingTarget
            pendingTarget = null
            if (granted && target != null) register(target) else if (!granted) statusText.setText(R.string.permission_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cover)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        cover = intent.getCover()
        prefs = Prefs(this)
        saved = SavedCovers(this)
        crema = CremaWallpaper.isAvailable(this)
        title = cover.title

        preview = findViewById(R.id.preview)
        statusText = findViewById(R.id.status)
        sleepButton = findViewById(R.id.apply_sleep)
        powerOffButton = findViewById(R.id.apply_poweroff)
        saveButton = findViewById(R.id.save)
        savedBadge = findViewById(R.id.saved_badge)
        findViewById<TextView>(R.id.title).text = cover.title
        findViewById<TextView>(R.id.meta).text =
            listOf(cover.author, cover.publisher).filter { it.isNotBlank() }.joinToString(" · ")

        sleepButton.setOnClickListener { requestRegister(Target.SLEEP) }
        powerOffButton.setOnClickListener { requestRegister(Target.POWER_OFF) }
        saveButton.setOnClickListener { onSaveButton() }

        updateSaveButton()
        load()
    }

    override fun onResume() {
        super.onResume()
        // 크레마 설정 자동화가 끝나면 이 화면으로 돌아오므로 결과를 보여준다.
        CremaAutomationService.takeResult(reset = false)?.let { result ->
            statusText.text = result.error ?: getString(
                if (result.target == Target.SLEEP) R.string.applied_sleep else R.string.applied_poweroff
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        setBusy(true)
        statusText.setText(R.string.loading_cover)
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    saved.localImage(cover.id)?.readBytes() ?: ImageLoader.download(cover.coverUrl)
                }
                val bitmap = withContext(Dispatchers.Default) {
                    val src = ImageLoader.decode(bytes, maxSide = 2400) ?: error(getString(R.string.decode_failed))
                    CoverRenderer.render(src, Prefs.WIDTH, Prefs.HEIGHT, prefs.fitMode, prefs.blackBackground)
                }
                originalBytes = bytes
                rendered = bitmap
                preview.setImageBitmap(bitmap)
                statusText.text = if (crema) getString(R.string.cover_ready, Prefs.WIDTH, Prefs.HEIGHT) else getString(R.string.not_crema)
                setBusy(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText.text = getString(R.string.load_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun requestRegister(target: Target) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            register(target)
        } else {
            pendingTarget = target
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /**
     * 표지를 Wallpaper 폴더에 저장한 뒤, 크레마 설정 화면을 자동으로 눌러
     * 슬립/종료 화면의 사용자 지정 이미지로 그 파일을 고른다(접근성 필요).
     */
    private fun register(target: Target) {
        val bitmap = rendered ?: return
        val file = target.fileName
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ScreenInstaller.install(this@CoverActivity, bitmap, file)
                    originalBytes?.let { saved.add(cover, it) }
                }
                updateSaveButton()
                if (CremaAutomationService.run(target)) {
                    statusText.setText(R.string.applying)
                } else {
                    statusText.text = getString(R.string.saved_need_accessibility, file)
                    askAccessibility()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText.text = getString(R.string.register_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun askAccessibility() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_title)
            .setMessage(R.string.accessibility_message)
            .setPositiveButton(R.string.accessibility_open) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 보관함에 없으면 저장, 있으면 확인 후 삭제한다. */
    private fun onSaveButton() {
        if (saved.contains(cover.id)) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.remove_confirm, cover.title))
                .setPositiveButton(R.string.remove) { _, _ ->
                    saved.remove(cover.id)
                    statusText.setText(R.string.removed_from_saved)
                    updateSaveButton()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            val bytes = originalBytes ?: return
            saved.add(cover, bytes)
            statusText.setText(R.string.added_to_saved)
            updateSaveButton()
        }
    }

    /** 저장 상태는 제목 아래 배지로 보여주고, 버튼은 상태에 맞는 동작(저장/삭제)만 보여준다. */
    private fun updateSaveButton() {
        val inSaved = saved.contains(cover.id)
        savedBadge.visibility = if (inSaved) View.VISIBLE else View.GONE
        saveButton.setText(if (inSaved) R.string.delete_saved else R.string.add_saved)
        saveButton.isEnabled = !busy && (inSaved || originalBytes != null)
    }

    private fun setBusy(busy: Boolean) {
        this.busy = busy
        val enabled = !busy && crema
        sleepButton.isEnabled = enabled
        powerOffButton.isEnabled = enabled
        updateSaveButton()
    }
}

private const val EXTRA_ID = "id"
private const val EXTRA_TITLE = "title"
private const val EXTRA_AUTHOR = "author"
private const val EXTRA_PUBLISHER = "publisher"
private const val EXTRA_THUMB = "thumb"
private const val EXTRA_COVER = "cover"

fun Intent.putCover(c: BookCover): Intent = putExtra(EXTRA_ID, c.id)
    .putExtra(EXTRA_TITLE, c.title)
    .putExtra(EXTRA_AUTHOR, c.author)
    .putExtra(EXTRA_PUBLISHER, c.publisher)
    .putExtra(EXTRA_THUMB, c.thumbUrl)
    .putExtra(EXTRA_COVER, c.coverUrl)

fun Intent.getCover() = BookCover(
    id = getStringExtra(EXTRA_ID).orEmpty(),
    title = getStringExtra(EXTRA_TITLE).orEmpty(),
    author = getStringExtra(EXTRA_AUTHOR).orEmpty(),
    publisher = getStringExtra(EXTRA_PUBLISHER).orEmpty(),
    thumbUrl = getStringExtra(EXTRA_THUMB).orEmpty(),
    coverUrl = getStringExtra(EXTRA_COVER).orEmpty(),
)
