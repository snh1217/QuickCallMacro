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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.quickcall.macro.PreferencesManager
import com.quickcall.macro.R
import com.quickcall.macro.data.DistrictRepository
import com.quickcall.macro.data.DistrictSlot
import com.quickcall.macro.databinding.ActivityDistrictSlotEditBinding

/**
 * 슬롯 편집 (v1.0.5):
 *  - 시도 헤더 (펼침/접힘)
 *    └ 시군구 행 (3-state 체크박스 + 동 갯수)
 *      └ 동 행 (단순 체크박스)
 *  - 시군구 ↔ 동 양방향 동기화
 *  - 검색창 (시군구 / 동 이름 부분 매칭)
 *  - 저장 시 PreferencesManager 에 JSON 으로 직렬화
 */
class DistrictSlotEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLOT_ID = "slot_id"
    }

    private lateinit var b: ActivityDistrictSlotEditBinding
    private var slotId = 1

    /** 작업본: sigungu path → 선택된 동 셋 */
    private val workSelection = HashMap<String, MutableSet<String>>()

    private val sidoSections = mutableListOf<SidoSection>()
    private val sigunguEntries = mutableListOf<SigunguEntry>()

    private data class SidoSection(
        val sidoName: String,
        val header: View,
        val container: LinearLayout,
        val countText: TextView,
        val indicator: TextView
    )

    private data class SigunguEntry(
        val path: String,
        val displayName: String,
        val sidoName: String,
        val sigunguRow: View,
        val sigunguCheckbox: MaterialCheckBox,
        val sigunguCountText: TextView,
        val sigunguIndicator: TextView,
        val dongContainer: LinearLayout,
        val dongs: List<String>,
        var dongRowsBuilt: Boolean = false,
        val dongCheckboxes: MutableList<CheckBox> = mutableListOf()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDistrictSlotEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        slotId = intent.getIntExtra(EXTRA_SLOT_ID, 1).coerceIn(1, PreferencesManager.SLOT_COUNT)
        title = getString(R.string.fmt_slot_edit_title, slotId)

        b.etSlotName.setText(PreferencesManager.getSlotName(slotId))

        DistrictRepository.ensureLoaded(this)
        // 기존 슬롯 데이터 로드
        val existing = DistrictSlot.fromJson(
            slotId,
            PreferencesManager.getSlotName(slotId),
            PreferencesManager.getSlotSelectionJson(slotId)
        )
        for ((path, dongs) in existing.selectedDongsBySigungu) {
            workSelection[path] = HashSet(dongs)
        }

        buildTree()
        refreshAllSigunguStates()
        refreshAllSidoCounts()

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        b.btnSave.setOnClickListener { saveAndExit() }
        b.btnCancel.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun saveAndExit() {
        val name = b.etSlotName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            Toast.makeText(this, R.string.toast_slot_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        PreferencesManager.setSlotName(slotId, name)
        // 빈 셋은 정리
        val cleaned = workSelection.filterValues { it.isNotEmpty() }
        val slot = DistrictSlot(slotId, name, cleaned)
        PreferencesManager.setSlotSelectionJson(slotId, slot.toJson())
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    // ───────────────────────── 트리 구축 ─────────────────────────

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
                buildSigunguEntry(inflater, sido, path, container)
            }

            sidoSections.add(SidoSection(sido, header, container, tvCount, tvIndicator))
            b.tree.addView(header)
            b.tree.addView(container)
        }
    }

    private fun buildSigunguEntry(
        inflater: LayoutInflater,
        sido: String,
        path: String,
        sidoContainer: LinearLayout
    ) {
        val sigunguRow = inflater.inflate(R.layout.item_district_sigungu_row, sidoContainer, false)
        val cb = sigunguRow.findViewById<MaterialCheckBox>(R.id.cb)
        val tvCount = sigunguRow.findViewById<TextView>(R.id.tvCount)
        val tvIndicator = sigunguRow.findViewById<TextView>(R.id.tvIndicator)
        val tvName = sigunguRow.findViewById<TextView>(R.id.tvName)

        // 표시 이름: 3-tier 면 "용인시 처인구" 결합으로 표시
        val parts = path.split('/')
        val displayName = if (parts.size >= 3) parts.subList(1, parts.size).joinToString(" ")
        else parts.last()
        tvName.text = displayName

        val dongs = DistrictRepository.getDongsForSigungu(path)

        val dongContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val entry = SigunguEntry(
            path = path,
            displayName = displayName,
            sidoName = sido,
            sigunguRow = sigunguRow,
            sigunguCheckbox = cb,
            sigunguCountText = tvCount,
            sigunguIndicator = tvIndicator,
            dongContainer = dongContainer,
            dongs = dongs
        )

        // 체크박스 클릭 처리 (MaterialCheckBox 의 auto-cycle 무시하고 workSelection 기준):
        //   비체크 → 모두 선택, 부분체크 → 모두 선택, 전체체크 → 모두 해제
        cb.setOnClickListener {
            val selected = workSelection[entry.path] ?: emptySet()
            val cnt = selected.size
            val total = entry.dongs.size
            val makeAllChecked = !(cnt == total && total > 0)
            applyAllDongs(entry, makeAllChecked)
            refreshSigunguState(entry)  // checkedState 재설정으로 auto-cycle 효과 상쇄
            refreshSidoCount(sido)
        }

        // 행 영역 (체크박스 외) 클릭: 펼침 토글
        sigunguRow.setOnClickListener {
            // 체크박스 클릭이 행 클릭 이벤트도 트리거하지 않도록 view 분리됨 (CheckBox 자체는 자기 영역만)
            ensureDongRowsBuilt(entry, inflater, sido)
            val expanding = dongContainer.visibility == View.GONE
            dongContainer.visibility = if (expanding) View.VISIBLE else View.GONE
            tvIndicator.text = if (expanding) "▾" else "▸"
        }

        sigunguEntries.add(entry)
        sidoContainer.addView(sigunguRow)
        sidoContainer.addView(dongContainer)
    }

    private fun ensureDongRowsBuilt(entry: SigunguEntry, inflater: LayoutInflater, sido: String) {
        if (entry.dongRowsBuilt) return
        val selected = workSelection.getOrPut(entry.path) { HashSet() }
        for (dong in entry.dongs) {
            val item = inflater.inflate(R.layout.item_district_dong, entry.dongContainer, false)
            val cb = item.findViewById<CheckBox>(R.id.cb)
            cb.text = dong
            cb.isChecked = dong in selected
            cb.setOnCheckedChangeListener { _, checked ->
                val s = workSelection.getOrPut(entry.path) { HashSet() }
                if (checked) s.add(dong) else s.remove(dong)
                refreshSigunguState(entry)
                refreshSidoCount(sido)
            }
            entry.dongCheckboxes.add(cb)
            entry.dongContainer.addView(item)
        }
        entry.dongRowsBuilt = true
    }

    private fun applyAllDongs(entry: SigunguEntry, allChecked: Boolean) {
        val s = workSelection.getOrPut(entry.path) { HashSet() }
        if (allChecked) {
            s.clear()
            s.addAll(entry.dongs)
        } else {
            s.clear()
        }
        // 이미 만들어진 동 체크박스는 동기화
        if (entry.dongRowsBuilt) {
            for (cb in entry.dongCheckboxes) {
                // setOnCheckedChange 콜백 무시
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = allChecked
                rebindDongListener(entry, cb)
            }
        }
    }

    private fun rebindDongListener(entry: SigunguEntry, cb: CheckBox) {
        val dongName = cb.text?.toString() ?: return
        cb.setOnCheckedChangeListener { _, checked ->
            val s = workSelection.getOrPut(entry.path) { HashSet() }
            if (checked) s.add(dongName) else s.remove(dongName)
            refreshSigunguState(entry)
            refreshSidoCount(entry.sidoName)
        }
    }

    // ───────────────────────── 상태 갱신 ─────────────────────────

    private fun refreshAllSigunguStates() {
        for (entry in sigunguEntries) refreshSigunguState(entry)
    }

    private fun refreshSigunguState(entry: SigunguEntry) {
        val selected = workSelection[entry.path] ?: emptySet()
        val cnt = selected.size
        val total = entry.dongs.size
        val state = when {
            total == 0 -> MaterialCheckBox.STATE_UNCHECKED
            cnt == 0 -> MaterialCheckBox.STATE_UNCHECKED
            cnt == total -> MaterialCheckBox.STATE_CHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
        entry.sigunguCheckbox.checkedState = state
        entry.sigunguCountText.text = if (cnt == 0) "${total}개"
        else if (cnt == total) "전체 ${total}"
        else "$cnt/$total"
    }

    private fun refreshAllSidoCounts() {
        for (s in sidoSections) refreshSidoCount(s.sidoName)
    }

    private fun refreshSidoCount(sido: String) {
        val section = sidoSections.firstOrNull { it.sidoName == sido } ?: return
        var cnt = 0
        for (e in sigunguEntries) {
            if (e.sidoName != sido) continue
            if ((workSelection[e.path]?.size ?: 0) > 0) cnt++
        }
        section.countText.text = if (cnt > 0) cnt.toString() else ""
    }

    // ───────────────────────── 검색 필터 ─────────────────────────

    private fun applyFilter(query: String) {
        val q = query.trim()
        for (section in sidoSections) section.container.removeAllViews()
        // 시군구 + 동 모두 매칭 검사 후 다시 추가
        val sidoVisible = HashMap<String, Boolean>()

        for (entry in sigunguEntries) {
            val containsName = q.isEmpty() || entry.displayName.contains(q, ignoreCase = true)
            val containsDong = q.isNotEmpty() && entry.dongs.any { it.contains(q, ignoreCase = true) }
            val show = containsName || containsDong
            if (show) {
                val section = sidoSections.firstOrNull { it.sidoName == entry.sidoName } ?: continue
                section.container.addView(entry.sigunguRow)
                section.container.addView(entry.dongContainer)
                sidoVisible[entry.sidoName] = true

                // 검색어가 있으면 펼치고, 동까지 매칭되면 동 목록도 펼침
                if (q.isNotEmpty()) {
                    section.container.visibility = View.VISIBLE
                    section.indicator.text = "▾"
                    if (containsDong) {
                        ensureDongRowsBuilt(entry, LayoutInflater.from(this), entry.sidoName)
                        entry.dongContainer.visibility = View.VISIBLE
                        entry.sigunguIndicator.text = "▾"
                    }
                }
            }
        }
        for (section in sidoSections) {
            section.header.visibility = if (q.isEmpty() || sidoVisible[section.sidoName] == true) View.VISIBLE else View.GONE
        }
    }
}
