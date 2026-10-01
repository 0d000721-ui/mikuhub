package me.rerere.rikkahub.browser

internal class BrowserDisplayOwnership {
    private var next = 0L
    private var active: Long? = null
    val hasOwner: Boolean get() = active != null
    fun newOwner(): Long = ++next
    fun claim(owner: Long) { active = owner }
    fun owns(owner: Long): Boolean = active == owner
    fun release(owner: Long): Boolean {
        if (!owns(owner)) return false
        active = null
        return true
    }
    fun clear() { active = null }
}

internal fun browserPageRendered(attached: Boolean, drawn: Boolean, visibleElements: Int): Boolean = attached && drawn && visibleElements > 0
