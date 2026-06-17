package com.example.myapplication

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter : ListAdapter<Message, MessageAdapter.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_SELF = 1
        private const val TYPE_OTHER = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isSelf) TYPE_SELF else TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (viewType == TYPE_SELF) {
            R.layout.item_message_self
        } else {
            R.layout.item_message_other
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = getItem(position)
        holder.tvMessage.text = message.content
        holder.itemView.setOnLongClickListener {
            val clipboard = holder.itemView.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("message", message.content)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(holder.itemView.context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            true
        }
    }

    class ViewHolder(view: ViewGroup) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    class DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Message, newItem: Message) = oldItem == newItem
    }
}
