package com.woody.cremacover

import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 기기 설정에서 슬립 이미지를 고를 때 무엇이 바뀌는지 기록한다.
 * 1) 기록 시작 → 기기 설정에서 이미지 선택 → 2) 결과 보기
 */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var reportText: TextView

    private val sp by lazy { getSharedPreferences("diagnostics", MODE_PRIVATE) }
    private var since: Long
        get() = sp.getLong("since", System.currentTimeMillis() - 10 * 60_000)
        set(v) = sp.edit().putLong("since", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        reportText = findViewById(R.id.report)
        findViewById<Button>(R.id.start).setOnClickListener {
            since = System.currentTimeMillis()
            reportText.text = getString(R.string.diag_started, DeviceProbe.formatTime(since))
        }
        findViewById<Button>(R.id.show).setOnClickListener { showReport() }
        findViewById<Button>(R.id.export_apks).setOnClickListener { exportApks() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun exportApks() {
        reportText.setText(R.string.diag_exporting)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DeviceProbe.exportSystemApks(this@DiagnosticsActivity) } }
            reportText.text = result.fold(
                onSuccess = { r ->
                    getString(R.string.diag_exported, r.dir.path, r.copied.size) + "\n\n" +
                        (r.copied + r.failed.map { "실패: $it" }).joinToString("\n")
                },
                onFailure = { getString(R.string.diag_export_failed, it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    private fun showReport() {
        reportText.setText(R.string.diag_collecting)
        lifecycleScope.launch {
            val (report, saved) = withContext(Dispatchers.IO) {
                val report = DeviceProbe.report(this@DiagnosticsActivity, since)
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cremacover_report.txt")
                report to runCatching { file.parentFile?.mkdirs(); file.writeText(report); file.path }.getOrNull()
            }
            reportText.text = if (saved != null) getString(R.string.diag_saved, saved) + "\n\n" + report else report
        }
    }
}
