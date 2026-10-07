package dev.pam.nativeapp.render

/**
 * A small LRU of immutable values parsed from wire strings. List rows repeat
 * the same gesture and motion programs; parsing them once per distinct
 * source keeps string splitting out of every row bind.
 */
internal class PamParseCache<T : Any>(private val capacity: Int = 32) {
    private val entries = object : LinkedHashMap<String, Any>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > capacity
    }

    @Suppress("UNCHECKED_CAST")
    fun getOrParse(source: String, parse: (String) -> T?): T? = synchronized(entries) {
        val hit = entries[source]
        if (hit != null) return if (hit === MISSING) null else hit as T
        val value = parse(source)
        entries[source] = value ?: MISSING
        value
    }

    private companion object {
        val MISSING = Any()
    }
}
