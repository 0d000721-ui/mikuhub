package me.rerere.rikkahub.browser

data class AgentBrowserState(
    val url: String = "",
    val title: String = "内置浏览器",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val aiEnabled: Boolean = false,
    val aiBusy: Boolean = false,
    val lastAction: String? = null,
    val message: String? = null,
    val error: String? = null,
    val session: Long = 0,
)
