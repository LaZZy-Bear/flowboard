package com.flowboard.ime.data.models

/**
 * A node in the dictionary trie (trie_dict_compressed.json).
 * Each node maps characters (or charMap IDs) to child nodes.
 * The [isEndOfWord] flag corresponds to the "_w" key in the JSON.
 */
class TrieNode {
    /** Whether this node marks the end of a valid word */
    var isEndOfWord: Boolean = false

    /** Score frequency of the word ending at this node */
    var frequency: Int = 0

    /** Child nodes keyed by string (either raw char or charMap ID) */
    val children: HashMap<String, TrieNode> = HashMap(4)

    /**
     * Check if this node has a child for the given key.
     */
    operator fun get(key: String): TrieNode? = children[key]

    /**
     * Get or create a child node for the given key.
     */
    fun getOrPut(key: String): TrieNode = children.getOrPut(key) { TrieNode() }
}
