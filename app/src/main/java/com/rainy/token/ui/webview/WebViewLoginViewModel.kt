package com.rainy.token.ui.webview

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import com.rainy.token.R
import com.rainy.token.data.repository.WebViewSessionSaver
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * WebView 登录容器 ViewModel。计划 4.1：
 *  - 持有目标 URL（来自 ServiceConfig）
 *  - 提供 [onLoginSuccess] 回调：抓 Cookie → 加密存 SecureStorage
 *  - 跟踪"用户手动确认登录"状态（N 秒超时未识别成功时显示按钮）
 */
@HiltViewModel
class WebViewLoginViewModel @Inject constructor(
    private val sessionSaver: WebViewSessionSaver
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebViewLoginUiState())
    val uiState: StateFlow<WebViewLoginUiState> = _uiState.asStateFlow()

    fun bind(service: ServiceType) {
        val config = ServiceConfigProvider.get(service)
        _uiState.update {
            it.copy(
                service = service,
                loginUrl = config.loginUrl
            )
        }
    }

    /**
     * 成功 URL 模式匹配（计划 4.1：精确匹配 + fallback）。
     *
     * 默认启发：URL 跳转到非登录域 + 路径不包含 /auth/login/signin 等关键词，
     * 即认为登录完成。具体规则各服务可在 [ServiceConfigProvider] 扩展。
     */
    fun onPageFinished(url: String) {
        val current = _uiState.value
        if (current.loginSucceeded) return
        val service = current.service ?: return

        // 第三方 OAuth（GitHub / Google / Microsoft…）只是中转页，绝不能当成登录完成，
        // 否则一跳转 GitHub 就被判成功、直接退登录界面 —— 这正是"还没输完密码就退出"的原因。
        if (isThirdPartyAuthUrl(url)) {
            _uiState.update { it.copy(pendingManualConfirm = false) }
            return
        }
        if (!looksLikeLoggedInPage(service, url)) {
            _uiState.update { it.copy(pendingManualConfirm = false) }
            return
        }
        // URL 回到目标站还不够，必须真的拿到会话 Cookie 才算成功
        if (!hasSessionCookie(service)) {
            _uiState.update { it.copy(pendingManualConfirm = true) }
            return
        }
        saveSession(url)
    }

    /** 第三方登录中转域 */
    private fun isThirdPartyAuthUrl(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "github.com", "google.com", "accounts.google", "appleid.apple",
            "microsoftonline.com", "microsoft.com", "okta.com", "auth0.com"
        ).any { u.contains(it) }
    }

    /** 只认「回到目标站且不在登录路径」 */
    private fun looksLikeLoggedInPage(service: ServiceType, url: String): Boolean {
        val u = url.lowercase()
        val loginPaths = listOf("/auth", "/login", "/signin", "/signup", "/oauth")
        return when (service) {
            ServiceType.OPEN_DESIGN -> u.contains("open-design.ai") && loginPaths.none { u.contains(it) }
            else -> loginPaths.none { u.contains(it) }
        }
    }

    /** Cookie 里必须出现会话类字段，避免被统计 cookie 误判成功 */
    private fun hasSessionCookie(service: ServiceType): Boolean {
        val urls = when (service) {
            ServiceType.OPEN_DESIGN -> listOf(
                "https://open-design.ai",
                "https://amr-api.open-design.ai"
            )
            else -> listOf(_uiState.value.loginUrl)
        }
        val names = urls
            .mapNotNull { CookieManager.getInstance().getCookie(it) }
            .flatMap { it.split(";") }
            .map { it.substringBefore("=").trim().lowercase() }
        return names.any { it.contains("session") || it.contains("auth") || it.contains("token") }
    }

    fun confirmLoginManually() {
        val url = _uiState.value.loginUrl
        saveSession(url)
    }

    fun dismissManualPrompt() {
        _uiState.update { it.copy(pendingManualConfirm = false) }
    }

    private fun saveSession(url: String) {
        val service = _uiState.value.service ?: return
        val saved = sessionSaver.saveFromCookieManager(service = service, url = url)
        if (saved != null) {
            _uiState.update {
                it.copy(
                    loginSucceeded = true,
                    savedSession = saved,
                    pendingManualConfirm = false
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    error = UiText.Resource(R.string.error_cookie_not_found),
                    pendingManualConfirm = true
                )
            }
        }
    }

}

data class WebViewLoginUiState(
    val service: ServiceType? = null,
    val loginUrl: String = "",
    val loginSucceeded: Boolean = false,
    val savedSession: Credential.SessionCredential? = null,
    val pendingManualConfirm: Boolean = false,
    val error: UiText? = null
)