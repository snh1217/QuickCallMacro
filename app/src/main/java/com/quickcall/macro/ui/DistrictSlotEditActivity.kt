package com.quickcall.macro.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.quickcall.macro.PreferencesManager
import com.quickcall.macro.R
import com.quickcall.macro.data.DistrictRepository
import com.quickcall.macro.databinding.ActivityDistrictSlotEditBinding

/**
 * 슬롯 편집:
 *  - 슬롯 이름 입력
 *  - 검색창 (한글 부분 매칭)
 *  - 시도 헤더(접힘/펼침) → 시군구 체크박스
 *  - 시군구 길게 누르면 동/읍/면 미리보기 다이얼로그
 *  - [저장] 시 PreferencesManager 에 반영
 */
class DistrictSlotEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLOT_ID = "slot_id"
    }

    private lateinit var b: ActivityDistrictSlotEditBinding
    private var slotId = 1
    private val checkBoxes = mutableListOf<CheckBox>()  // 모든 시군구 체크박스 (필터링용)
    private val sidoSections = mutableListOf<SidoSection>()
    private val selected = HashSet<String>()

    private data class SidoSection(
        val sidoName: String,
        val header: View,
        val container: LinearLayout,
        val countText: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDistrictSlotEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        slotId = intent.getIntExtra(EXTRA_SLOT_ID, 1).coerceIn(1, PreferencesManager.SLOT_COUNT)
        title = getString(R.string.fmt_slot_edit_title, slotId)

        b.etSlotName.setText(PreferencesManager.getSlotName(slotId))
        selected.addAll(PreferencesManager.getSlotKeys(slotId))

        DistrictRepository.ensureLoaded(this)
        buildTree()
        updateAllCounts()

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        b.btnSave.setOnClickListener {
            val name = b.etSlotName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, R.string.toast_slot_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PreferencesManager.setSlotName(slotId, name)
            PreferencesManager.setSlotKeys(slotId, selected)
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        b.btnCancel.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun buildTree() {
        b.tree.removeAllViews()
        val grouped = DistrictRepository.groupedBySido()
        val inflater = LayoutInflater.from(this)
        for ((sido, paths) in grouped) {
            if (paths.isEmpty()) continue
            val header = inflater.inflate(R.layout.item_district_sido_header, b.tree, false)
            val tvSido = header.findViewById<TextView>(R.id.tvSido)
            val tvCount = header.findViewById<TextView>(R.id.tvCount)
            val tvIndicator = header.findViewById<TextView>(R.id.tvIndicator)
            tvSido.text = sido

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

            header.setOnClickListener {
                val expanding = container.visibility == View.GONE
                container.visibility = if (expanding) View.VISIBLE else View.GONE
                tvIndicator.text = if (expanding) "▾" else "▸"
            }

            for (path in paths) {
                val item = inflater.inflate(R.layout.item_district_sigungu, container, false)
                val cb = item.findViewById<CheckBox>(R.id.cb)
                val tvSub = item.findViewById<TextView>(R.id.tvSub)
                cb.text = DistrictRepository.displayName(path)
                cb.tag = path
                val dongs = DistrictRepository.getDongs(path)
                tvSub.text = getString(R.string.fmt_dong_count, dongs.size)
                cb.isChecked = path in selected
                cb.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(path) else selected.remove(path)
                    updateSectionCount(sido)
                }
                item.setOnLongClickListener {
                    showDongPreview(path, dongs)
                    true
                }
                checkBoxes.add(cb)
                container.addView(item)
            }

            sidoSections.add(SidoSection(sido, header, container, tvCount))
            b.tree.addView(header)
            b.tree.addView(container)
        }
    }

    private fun showDongPreview(path: String, dongs: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(DistrictRepository.displayName(path))
            .setMessage(if (dongs.isEmpty()) "(데이터 없음)" else dongs.joinToString(", "))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        for (section in sidoSections) {
            var anyVisible = false
            for (i in 0 until section.container.childCount) {
                val item = section.container.getChildAt(i)
                val cb = item.findViewById<CheckBox>(R.id.cb)
                val name = cb.text.toString()
                val match = q.isEmpty() || name.contains(q, ignoreCase = true)
                item.visibility = if (match) View.VISIBLE else View.GONE
                if (match) anyVisible = true
            }
            section.header.visibility = if (q.isEmpty() || anyVisible) View.VISIBLE else View.GONE
            // 검색어 있을 때 자동 펼치기
            if (q.isNotEmpty() && anyVisible) {
                section.container.visibility = View.VISIBLE
                section.header.findViewById<TextView>(R.id.tvIndicator).text = "▾"
            }
        }
    }

    private fun updateSectionCount(sido: String) {
        val section = sidoSections.firstOrNull { it.sidoName == sido } ?: return
        val cnt = countSelectedInSection(section)
        section.countText.text = if (cnt > 0) cnt.toString() else ""
    }

    private fun updateAllCounts() {
        for (section in sidoSections) {
            val cnt = countSelectedInSection(section)
            section.countText.text = if (cnt > 0) cnt.toString() else ""
        }
    }

    private fun countSelectedInSection(section: SidoSection): Int {
        var n = 0
        for (i in 0 until section.container.childCount) {
            val cb = section.container.getChildAt(i).findViewById<CheckBox>(R.id.cb)
            if (cb.isChecked) n++
        }
        return n
    }
}
