package com.woody.cremacover

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.woody.cremacover.CremaAutomationService.Target

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var fitGroup: RadioGroup
    private lateinit var bgGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        fitGroup = findViewById(R.id.fit_group)
        bgGroup = findViewById(R.id.bg_group)
        findViewById<TextView>(R.id.version).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        fitGroup.check(if (prefs.fitMode == FitMode.FIT) R.id.fit else R.id.fill)
        bgGroup.check(if (prefs.blackBackground) R.id.bg_black else R.id.bg_white)

        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        findViewById<Button>(R.id.reset_sleep).setOnClickListener { confirmReset(Target.SLEEP) }
        findViewById<Button>(R.id.reset_poweroff).setOnClickListener { confirmReset(Target.POWER_OFF) }
        // 기기 진단은 개발용이라 릴리스 빌드에서는 숨긴다.
        findViewById<Button>(R.id.open_diagnostics).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java)) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 초기화 자동화가 끝나면 이 화면으로 돌아오므로 결과를 보여준다.
        CremaAutomationService.takeResult(reset = true)?.let { result ->
            findViewById<TextView>(R.id.reset_status).text = result.error ?: getString(
                if (result.target == Target.SLEEP) R.string.reset_sleep_done else R.string.reset_poweroff_done
            )
        }
        // 접근성 설정에서 돌아왔을 때 상태를 다시 보여준다.
        findViewById<TextView>(R.id.accessibility_status).setText(
            if (CremaAutomationService.isEnabled(this)) R.string.accessibility_on else R.string.accessibility_off
        )
    }

    private fun confirmReset(target: Target) {
        AlertDialog.Builder(this)
            .setMessage(if (target == Target.SLEEP) R.string.reset_sleep_confirm else R.string.reset_poweroff_confirm)
            .setPositiveButton(R.string.reset) { _, _ ->
                if (!CremaAutomationService.run(target, reset = true)) {
                    findViewById<TextView>(R.id.reset_status).setText(R.string.accessibility_off)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        prefs.fitMode = if (fitGroup.checkedRadioButtonId == R.id.fill) FitMode.FILL else FitMode.FIT
        prefs.blackBackground = bgGroup.checkedRadioButtonId == R.id.bg_black
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
