package com.quickcall.macro.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.quickcall.macro.PreferencesManager
import com.quickcall.macro.R
import com.quickcall.macro.data.DistrictSlot
import com.quickcall.macro.databinding.ActivityDistrictSettingsBinding
import com.quickcall.macro.databinding.ItemDistrictSlotBinding

/**
 * 구 필터 슬롯 선택 화면.
 *  - 라디오: "사용 안 함" + 슬롯 1~SLOT_COUNT
 *  - 각 슬롯 행 옆 [편집] 버튼 → DistrictSlotEditActivity
 *  - 라디오 변경 즉시 PreferencesManager.activeSlotId 저장
 */
class DistrictSettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDistrictSettingsBinding
    private val slotRows = mutableListOf<ItemDistrictSlotBinding>()
    private var noneRow: View? = null
    private lateinit var noneIndicator: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDistrictSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_district_settings)
        buildRows()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun buildRows() {
        b.container.removeAllViews()
        // "사용 안 함" 라디오 행
        val none = LayoutInflater.from(this).inflate(R.layout.item_district_none, b.container, false)
        noneIndicator = none.findViewById(R.id.indicator)
        none.findViewById<View>(R.id.row).setOnClickListener {
            PreferencesManager.activeSlotId = 0
            refresh()
        }
        b.container.addView(none)
        noneRow = none

        slotRows.clear()
        for (id in 1..PreferencesManager.SLOT_COUNT) {
            val row = ItemDistrictSlotBinding.inflate(LayoutInflater.from(this), b.container, false)
            row.row.setOnClickListener {
                PreferencesManager.activeSlotId = id
                refresh()
            }
            row.btnEdit.setOnClickListener {
                val i = Intent(this, DistrictSlotEditActivity::class.java)
                    .putExtra(DistrictSlotEditActivity.EXTRA_SLOT_ID, id)
                startActivity(i)
            }
            slotRows.add(row)
            b.container.addView(row.root)
        }
    }

    private fun refresh() {
        val active = PreferencesManager.activeSlotId
        noneIndicator.isSelected = (active == 0)
        for ((i, row) in slotRows.withIndex()) {
            val id = i + 1
            val name = PreferencesManager.getSlotName(id)
            val slot = DistrictSlot.fromJson(id, name, PreferencesManager.getSlotSelectionJson(id))
            row.tvSlot.text = getString(
                R.string.fmt_slot_label,
                id, name,
                slot.activeSigunguCount(), slot.activeDongCount()
            )
            row.indicator.isSelected = (id == active)
        }
    }
}
