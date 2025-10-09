package com.example.llama

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon

private const val VIEW_TYPE_SYSTEM = 0
private const val VIEW_TYPE_USER = 1
private const val VIEW_TYPE_MODEL = 2

class MessageAdapter(context: Context) :
    ListAdapter<UiMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private val markwon = Markwon.create(context)

    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(message: UiMessage, markwon: Markwon)

        class SystemMessageViewHolder(view: View) : MessageViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.messageTextView)
            override fun bind(message: UiMessage, markwon: Markwon) {
                textView.text = message.text
            }
        }

        class UserMessageViewHolder(view: View) : MessageViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.messageTextView)
            override fun bind(message: UiMessage, markwon: Markwon) {
                textView.text = message.text
            }
        }

        class ModelMessageViewHolder(view: View) : MessageViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.messageTextView)
            override fun bind(message: UiMessage, markwon: Markwon) {
                markwon.setMarkdown(textView, message.text)

                textView.movementMethod = LinkMovementMethod.getInstance()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).type) {
            MessageType.SYSTEM -> VIEW_TYPE_SYSTEM
            MessageType.USER -> VIEW_TYPE_USER
            MessageType.MODEL -> VIEW_TYPE_MODEL
            MessageType.TOOL_RESULT -> VIEW_TYPE_SYSTEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                val view = inflater.inflate(R.layout.item_message_system, parent, false)
                MessageViewHolder.SystemMessageViewHolder(view)
            }
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_message_user, parent, false)
                MessageViewHolder.UserMessageViewHolder(view)
            }
            VIEW_TYPE_MODEL -> {
                val view = inflater.inflate(R.layout.item_message_model, parent, false)
                MessageViewHolder.ModelMessageViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position), markwon)
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<UiMessage>() {
        override fun areItemsTheSame(oldItem: UiMessage, newItem: UiMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: UiMessage, newItem: UiMessage): Boolean {
            return oldItem.text == newItem.text && oldItem.type == newItem.type
        }
    }
}
