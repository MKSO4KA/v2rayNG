package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAutoGroupListBinding
import com.v2ray.ang.handler.MmkvManager

class AutoGroupListActivity : BaseActivity() {
    private val binding by lazy { ActivityAutoGroupListBinding.inflate(layoutInflater) }
    private val subId by lazy { intent.getStringExtra("subId").orEmpty() }
    private lateinit var classAdapter: AutoGroupRecyclerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_auto_group_list))

        classAdapter = AutoGroupRecyclerAdapter(this) { ruleId ->
            val intent = Intent(this, AutoGroupEditActivity::class.java)
            intent.putExtra("subId", subId)
            intent.putExtra("ruleId", ruleId)
            startActivity(intent)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = classAdapter

        binding.fabAdd.setOnClickListener {
            val intent = Intent(this, AutoGroupEditActivity::class.java)
            intent.putExtra("subId", subId)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val subItem = MmkvManager.decodeSubscription(subId)
        val rules = subItem?.autoGroupRules ?: emptyList()
        classAdapter.updateData(rules)
    }
}

