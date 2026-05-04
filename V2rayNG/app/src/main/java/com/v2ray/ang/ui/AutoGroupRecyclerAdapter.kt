package com.v2ray.ang.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerAutoGroupBinding
import com.v2ray.ang.dto.AutoGroupRule

class AutoGroupRecyclerAdapter(
    private val context: Context,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<AutoGroupRecyclerAdapter.ViewHolder>() {

    private var rules = listOf<AutoGroupRule>()

    fun updateData(newRules: List<AutoGroupRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecyclerAutoGroupBinding.inflate(LayoutInflater.from(context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = rules[position]
        holder.binding.tvRemarks.text = rule.remarks
        holder.binding.tvRegex.text = rule.regex
        
        if (rule.isFromGist) {
            holder.binding.tvGistIndicator.visibility = View.VISIBLE
            holder.binding.root.setOnClickListener {
                Toast.makeText(context, R.string.toast_gist_rule_readonly, Toast.LENGTH_SHORT).show()
            }
        } else {
            holder.binding.tvGistIndicator.visibility = View.GONE
            holder.binding.root.setOnClickListener {
                onItemClick(rule.id)
            }
        }
    }

    override fun getItemCount() = rules.size

    class ViewHolder(val binding: ItemRecyclerAutoGroupBinding) : RecyclerView.ViewHolder(binding.root)
}

