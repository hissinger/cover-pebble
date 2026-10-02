package com.woody.cremacover

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var queryInput: EditText
    private lateinit var statusText: TextView
    private lateinit var sectionTitle: TextView
    private lateinit var backToSaved: Button
    private lateinit var adapter: CoverAdapter
    private lateinit var saved: SavedCovers

    private var searchJob: Job? = null
    private var showingResults = false

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        saved = SavedCovers(this)

        queryInput = findViewById(R.id.query)
        statusText = findViewById(R.id.status)
        sectionTitle = findViewById(R.id.section_title)
        backToSaved = findViewById(R.id.back_to_saved)
        backToSaved.setOnClickListener { leaveResults() }
        // 검색 결과를 보고 있을 때 뒤로 가기는 앱을 닫지 않고 보관함으로 돌아간다.
        onBackPressedDispatcher.addCallback(this, backToSavedCallback)
        adapter = CoverAdapter(
            onClick = { cover -> startActivity(Intent(this, CoverActivity::class.java).putCover(cover)) },
            onLongClick = { cover -> if (!showingResults) confirmRemove(cover) },
        )
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            itemAnimator = null // 전자잉크 잔상 방지
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.search).setOnClickListener { search() }
        queryInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { search(); true } else false
        }

        storagePermission.launch(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        )
    }

    override fun onResume() {
        super.onResume()
        if (!showingResults) showSaved()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, R.string.settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private val backToSavedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = leaveResults()
    }

    private fun leaveResults() {
        queryInput.text.clear()
        showSaved()
    }

    private fun setMode(results: Boolean) {
        showingResults = results
        backToSavedCallback.isEnabled = results
        sectionTitle.setText(if (results) R.string.section_results else R.string.section_saved)
        backToSaved.visibility = if (results) View.VISIBLE else View.GONE
    }

    private fun showSaved() {
        searchJob?.cancel()
        setMode(results = false)
        val covers = saved.list()
        adapter.submit(covers.map { it to saved.imageFile(it.id).path })
        statusText.text = if (covers.isEmpty()) getString(R.string.saved_empty)
        else getString(R.string.saved_count, covers.size)
    }

    private fun confirmRemove(cover: BookCover) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_confirm, cover.title))
            .setPositiveButton(R.string.remove) { _, _ ->
                saved.remove(cover.id)
                showSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun search() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(queryInput.windowToken, 0)

        searchJob?.cancel()
        setMode(results = true)
        statusText.text = getString(R.string.searching, query)
        adapter.submit(emptyList())
        searchJob = lifecycleScope.launch {
            try {
                val results = CoverSearch.search(query)
                adapter.submit(results.map { it to it.thumbUrl })
                statusText.text = if (results.isEmpty()) getString(R.string.no_results, query)
                else getString(R.string.result_count, query, results.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText.text = getString(R.string.search_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** (표지, 썸네일 경로) 목록을 보여주는 그리드 */
    private inner class CoverAdapter(
        private val onClick: (BookCover) -> Unit,
        private val onLongClick: (BookCover) -> Unit,
    ) : RecyclerView.Adapter<CoverAdapter.Holder>() {
        private var items: List<Pair<BookCover, String>> = emptyList()

        fun submit(newItems: List<Pair<BookCover, String>>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val author: TextView = view.findViewById(R.id.author)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_cover, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (cover, thumb) = items[position]
            holder.title.text = cover.title
            holder.author.text = cover.author
            ImageLoader.loadThumb(lifecycleScope, holder.image, thumb)
            holder.itemView.setOnClickListener { onClick(cover) }
            holder.itemView.setOnLongClickListener { onLongClick(cover); true }
        }
    }

    companion object {
        private const val MENU_SETTINGS = 1
    }
}
