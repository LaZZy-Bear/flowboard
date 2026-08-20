package com.flowboard.ime.ui

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.flowboard.ime.R

class EmojiAdapter(
    private var emojis: List<String>,
    private var emojiSizeSp: Float = 24f,
    private val onEmojiClick: (String) -> Unit
) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {

    class EmojiViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
        val density = parent.context.resources.displayMetrics.density
        val heightPx = (44 * density).toInt()
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.fn_key_bg)
            isClickable = true
            isFocusable = true
        }
        return EmojiViewHolder(textView)
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        val emoji = emojis[position]
        holder.textView.apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, emojiSizeSp)
            setOnClickListener {
                onEmojiClick(emoji)
            }
        }
    }

    override fun getItemCount(): Int = emojis.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateEmojis(newEmojis: List<String>) {
        emojis = newEmojis
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setEmojiSize(sizeSp: Float) {
        if (emojiSizeSp != sizeSp) {
            emojiSizeSp = sizeSp
            notifyDataSetChanged()
        }
    }
}
