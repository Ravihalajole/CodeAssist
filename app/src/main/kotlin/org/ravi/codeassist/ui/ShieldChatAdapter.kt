package org.ravi.codeassist.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.ravi.codeassist.R

data class ShieldMessage(val role: String, val text: String)

class ShieldChatAdapter(private val messages: MutableList<ShieldMessage> = mutableListOf()) :
    RecyclerView.Adapter<ShieldChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootLayout: LinearLayout = view as LinearLayout
        val cardMessage: MaterialCardView = view.findViewById(R.id.cardMessage)
        val tvMessageText: TextView = view.findViewById(R.id.tvMessageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shield_chat, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.tvMessageText.text = message.text

        if (message.role == "USER") {
            holder.rootLayout.gravity = Gravity.END
            holder.cardMessage.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(holder.rootLayout.context, R.color.brand_container))
            holder.tvMessageText.setTextColor(androidx.core.content.ContextCompat.getColor(holder.rootLayout.context, R.color.brand_on_container))
        } else {
            holder.rootLayout.gravity = Gravity.START
            holder.cardMessage.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(holder.rootLayout.context, R.color.surf_raised))
            holder.tvMessageText.setTextColor(androidx.core.content.ContextCompat.getColor(holder.rootLayout.context, R.color.text_hi))
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(message: ShieldMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}