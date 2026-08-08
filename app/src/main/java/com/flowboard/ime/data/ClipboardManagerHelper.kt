package com.flowboard.ime.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ClipboardItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    var isPinned: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class ClipboardManagerHelper(context: Context) {

    private val prefs = context.getSharedPreferences("flowboard_clipboard_history", Context.MODE_PRIVATE)
    private val maxItems = 30

    @Synchronized
    fun getItems(): List<ClipboardItem> {
        val jsonStr = prefs.getString("items", null) ?: return emptyList()
        val items = mutableListOf<ClipboardItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val text = obj.optString("text", "")
                if (text.isNotEmpty()) {
                    items.add(
                        ClipboardItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            text = text,
                            isPinned = obj.optBoolean("isPinned", false),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    @Synchronized
    fun saveItems(items: List<ClipboardItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("isPinned", item.isPinned)
                put("timestamp", item.timestamp)
            }
            array.put(obj)
        }
        prefs.edit { putString("items", array.toString()) }
    }

    @Synchronized
    fun addClip(text: String): ClipboardItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val currentItems = getItems().toMutableList()

        val existingIndex = currentItems.indexOfFirst { it.text == trimmed }
        if (existingIndex != -1) {
            val existing = currentItems.removeAt(existingIndex)
            currentItems.add(0, existing.copy(timestamp = System.currentTimeMillis()))
            saveItems(currentItems)
            return existing
        }

        val newItem = ClipboardItem(text = trimmed)
        currentItems.add(0, newItem)

        if (currentItems.size > maxItems) {
            val unpinnedIndices = currentItems.indices.filter { !currentItems[it].isPinned }
            if (unpinnedIndices.isNotEmpty()) {
                val lastUnpinnedIndex = unpinnedIndices.last()
                currentItems.removeAt(lastUnpinnedIndex)
            } else {
                currentItems.removeAt(currentItems.size - 1)
            }
        }

        saveItems(currentItems)
        return newItem
    }

    @Synchronized
    fun togglePin(id: String): Boolean {
        val currentItems = getItems().toMutableList()
        val index = currentItems.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = currentItems[index]
            val newPinned = !item.isPinned
            currentItems[index] = item.copy(isPinned = newPinned)
            
            val sorted = currentItems.sortedWith(
                compareByDescending<ClipboardItem> { it.isPinned }
                    .thenByDescending { it.timestamp }
            )
            saveItems(sorted)
            return newPinned
        }
        return false
    }

    @Synchronized
    fun deleteItem(id: String) {
        val currentItems = getItems().toMutableList()
        currentItems.removeAll { it.id == id }
        saveItems(currentItems)
    }

    @Synchronized
    fun clearUnpinned() {
        val currentItems = getItems().filter { it.isPinned }
        saveItems(currentItems)
    }
}
