package com.flowboard.ime.data.models

/**
 * A node in the dictionary trie (trie_dict.json).
 * Each node maps characters to child nodes.
 * The [isEndOfWord] flag corresponds to the "_f" key in the JSON.
 */
class TrieNode {
    /** Whether this node marks the end of a valid word */
    var isEndOfWord: Boolean = false

    /** Score frequency of the word ending at this node */
    var frequency: Int = 0

    /** Child nodes keyed by character */
    val children: HashMap<Char, TrieNode> = HashMap(4)

    /**
     * Check if this node has a child for the given character.
     */
    operator fun get(c: Char): TrieNode? = children[c]

    /**
     * Get or create a child node for the given character.
     */
    fun getOrPut(c: Char): TrieNode = children.getOrPut(c) { TrieNode() }
}
