package dev.pam.nativeapp.render

/**
 * A `Long`-keyed map for node ids, with the subset of `LongSparseArray`'s API
 * the renderer uses (`get`/`put`/`remove`/`size`/`keyAt`/`valueAt`/
 * `indexOfKey`/`clear`).
 *
 * Node ids are 60-bit hashes, so they arrive in random order:
 * `LongSparseArray.put` shifts half its arrays on every insert, O(n^2) for a
 * frame that creates 10,000 nodes (most of a list's mount time). Here entries
 * live in dense arrays (iteration by position, O(1) removal by moving the
 * last entry into the hole) indexed by an open-addressing table, so get, put
 * and remove are O(1). Iteration order is arbitrary, as callers only need
 * every entry once.
 */
internal class PamLongMap<V : Any>(initialCapacity: Int = 16) {
    private var denseKeys = LongArray(initialCapacity)
    private var denseValues = arrayOfNulls<Any>(initialCapacity)
    private var count = 0

    // Slot -> dense index + 1 (0 = empty, -1 = deleted).
    private var slots = IntArray(tableSize(initialCapacity))
    private var used = 0 // occupied or deleted slots

    fun size(): Int = count

    fun keyAt(position: Int): Long = denseKeys[position]

    @Suppress("UNCHECKED_CAST")
    fun valueAt(position: Int): V = denseValues[position] as V

    operator fun get(key: Long): V? {
        val slot = find(key)
        @Suppress("UNCHECKED_CAST")
        return if (slot < 0) null else denseValues[slots[slot] - 1] as V
    }

    /** The entry's position for [keyAt]/[valueAt], or -1 when absent. */
    fun indexOfKey(key: Long): Int {
        val slot = find(key)
        return if (slot < 0) -1 else slots[slot] - 1
    }

    fun put(key: Long, value: V) {
        val existing = find(key)
        if (existing >= 0) {
            denseValues[slots[existing] - 1] = value
            return
        }
        if ((used + 1) * 4 > slots.size * 3) rehash(maxOf(count + 1, 16))
        if (count == denseKeys.size) {
            denseKeys = denseKeys.copyOf(count * 2)
            denseValues = denseValues.copyOf(count * 2)
        }
        denseKeys[count] = key
        denseValues[count] = value
        var slot = mix(key) and (slots.size - 1)
        while (slots[slot] > 0) slot = (slot + 1) and (slots.size - 1)
        if (slots[slot] == 0) used++
        slots[slot] = count + 1
        count++
    }

    fun remove(key: Long) {
        val slot = find(key)
        if (slot < 0) return
        val position = slots[slot] - 1
        slots[slot] = -1
        val last = count - 1
        if (position != last) {
            val movedKey = denseKeys[last]
            denseKeys[position] = movedKey
            denseValues[position] = denseValues[last]
            slots[find(movedKey)] = position + 1
        }
        denseValues[last] = null
        count--
    }

    fun clear() {
        java.util.Arrays.fill(denseValues, 0, count, null)
        count = 0
        java.util.Arrays.fill(slots, 0)
        used = 0
    }

    private fun find(key: Long): Int {
        val mask = slots.size - 1
        var slot = mix(key) and mask
        while (true) {
            val entry = slots[slot]
            if (entry == 0) return -1
            if (entry > 0 && denseKeys[entry - 1] == key) return slot
            slot = (slot + 1) and mask
        }
    }

    private fun rehash(capacity: Int) {
        slots = IntArray(tableSize(capacity))
        used = count
        val mask = slots.size - 1
        for (position in 0 until count) {
            var slot = mix(denseKeys[position]) and mask
            while (slots[slot] != 0) slot = (slot + 1) and mask
            slots[slot] = position + 1
        }
    }

    private companion object {
        fun tableSize(capacity: Int): Int {
            var size = 16
            while (size * 3 < capacity * 4 + 4) size = size shl 1
            return size shl 1
        }

        fun mix(key: Long): Int {
            val h = key * -0x61c8864680b583ebL
            return (h xor (h ushr 32)).toInt()
        }
    }
}
