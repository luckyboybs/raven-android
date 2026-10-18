package com.raven.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme
import androidx.security.crypto.MasterKey

/**
 * Raven 手机端：一个全屏 WebView 壳。
 *
 * 会话建立方式：网关没有 token 输入框，远程浏览器只能走「一次性 nonce 链接」流程
 * （见 [GatewayAuth]）。冷启动时若有存档凭据就在后台线程跑一遍 mint nonce → exchange，
 * 把拿到的 raven_session_<端口> cookie 写进 WebView 的 CookieManager，再加载首页；
 * 没有凭据就先弹登录界面。
 *
 * 真正的 Raven 能力（会话 / 流式输出 / 审批 / 文件交付）全部来自网关 Web UI，
 * 本 Activity 只负责容器层：登录、Cookie、文件选择、Basic Auth 兜底、下载、
 * 网页刷新钮（JS 桥 RavenAndroid.refresh）、错误页。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorLayout: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var errorLoginButton: Button
    private lateinit var loginLayout: View
    private lateinit var loginUrl: EditText
    private lateinit var loginBasicUser: EditText
    private lateinit var loginBasicPass: EditText
    private lateinit var loginToken: EditText
    private lateinit var loginStatus: TextView
    private lateinit var loginButton: Button
    private lateinit var loginNoSaveToken: CheckBox
    private lateinit var loginShowToken: CheckBox

    /**
     * 运行时网关地址/host：优先用加密 prefs 里用户输入并存下的值，
     * 没有存档时回退到构建期预填的默认值（local.properties 注入，公开构建为占位地址）。
     * 切换地址后由 [saveLoginCredentials] 同步刷新。
     */
    private var gatewayUrl: String = ""
    private var gatewayHost: String = ""

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var pendingAuthHandler: HttpAuthHandler? = null
    private val authAttempts = HashMap<String, Int>()
    private var mainFrameError = false

    /** 本次进程内存里的 token；勾选「不保存 token」时只存在这里。 */
    private var sessionToken: String? = null

    /** 从外部 intent 进来的 /auth 一次性链接，认证成功后优先加载它。 */
    private var pendingExternalUrl: String? = null

    private var authInProgress = false
    private var retryAction: () -> Unit = { webView.reload() }

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val uris = if (result.resultCode == RESULT_OK) extractUris(result.data) else null
            callback.onReceiveValue(uris)
        }

    private val storagePermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingDownload
            pendingDownload = null
            if (granted && pending != null) {
                startDownload(pending)
            } else if (!granted) {
                toast(getString(R.string.storage_permission_needed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorLayout = findViewById(R.id.errorLayout)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)
        retryButton = findViewById(R.id.retryButton)
        errorLoginButton = findViewById(R.id.errorLoginButton)
        loginLayout = findViewById(R.id.loginLayout)
        loginUrl = findViewById(R.id.loginUrl)
        loginBasicUser = findViewById(R.id.loginBasicUser)
        loginBasicPass = findViewById(R.id.loginBasicPass)
        loginToken = findViewById(R.id.loginToken)
        loginStatus = findViewById(R.id.loginStatus)
        loginButton = findViewById(R.id.loginButton)
        loginNoSaveToken = findViewById(R.id.loginNoSaveToken)
        loginShowToken = findViewById(R.id.loginShowToken)

        setupWebView()
        setupErrorPage()
        setupLogin()
        loadStoredGateway()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            if (savedInstanceState.getBoolean(STATE_LOGIN_VISIBLE, false)) {
                showLogin(prefill = false)
            } else {
                hideLogin()
            }
        } else {
            handleIntent(intent)
            startSession()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        // 外来 /auth 一次性链接：直接交给 WebView，网关自己的 /auth 页面会完成 exchange 并跳转 /
        pendingExternalUrl?.let { url ->
            hideLogin()
            hideErrorPage()
            webView.loadUrl(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LOGIN_VISIBLE, loginLayout.visibility == View.VISIBLE)
        webView.saveState(outState)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.web_bg))
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            @Suppress("DEPRECATION")
            saveFormData = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = RavenWebViewClient()
        webView.webChromeClient = RavenWebChromeClient()
        webView.setDownloadListener(downloadListener)

        // 网页里的刷新钮（raven-mobile.js 注入的 #mrailRefresh）走这里：
        // 重新 mint nonce 换 cookie 再 reload，网关重启后旧 cookie 失效时能自愈。
        webView.addJavascriptInterface(RavenJsBridge(), "RavenAndroid")

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun setupErrorPage() {
        retryButton.setOnClickListener { retryAction() }
        errorLoginButton.setOnClickListener { showLogin(prefill = true) }
    }

    private fun setupLogin() {
        loginButton.setOnClickListener { submitLogin() }
        loginShowToken.setOnCheckedChangeListener { _, checked ->
            val typeface = loginToken.typeface
            loginToken.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            // 改 inputType 会重置字体和变换方法，补回去
            loginToken.typeface = typeface
            loginToken.setSelection(loginToken.text?.length ?: 0)
        }
    }

    // ---------------------------------------------------------------- 会话启动

    private fun startSession() {
        // 外来 /auth 链接优先：不需要 token，网关页面自己完成 exchange
        pendingExternalUrl?.let { url ->
            hideLogin()
            showProgress(5)
            webView.loadUrl(url)
            return
        }
        if (loadCredentials() == null) {
            showLogin(prefill = true)
        } else {
            runAuth(AuthTrigger.STARTUP)
        }
    }

    private fun submitLogin() {
        val user = loginBasicUser.text.toString().trim()
        val pass = loginBasicPass.text.toString()
        val token = loginToken.text.toString().trim()
        val url = loginUrl.text.toString().trim()
        if (user.isEmpty() || pass.isEmpty() || token.isEmpty() || url.isEmpty()) {
            loginStatus.setText(R.string.login_err_empty)
            return
        }
        if (!URLUtil.isHttpsUrl(url)) {
            loginStatus.setText(R.string.login_err_url)
            return
        }
        if (!TOKEN_PATTERN.matches(token)) {
            loginStatus.setText(R.string.login_err_token_format)
        } else {
            loginStatus.text = ""
        }
        saveLoginCredentials(user, pass, token, url, rememberToken = !loginNoSaveToken.isChecked)
        runAuth(AuthTrigger.LOGIN_BUTTON)
    }

    private fun runAuth(trigger: AuthTrigger) {
        if (authInProgress) return
        val credentials = loadCredentials()
        if (credentials == null) {
            if (trigger != AuthTrigger.LOGIN_BUTTON) showLogin(prefill = true)
            return
        }
        authInProgress = true
        when (trigger) {
            AuthTrigger.STARTUP -> {
                hideErrorPage()
                showProgress(10)
            }

            AuthTrigger.REFRESH_BUTTON -> showProgress(10)

            AuthTrigger.LOGIN_BUTTON -> {
                loginButton.isEnabled = false
                loginButton.setText(R.string.login_submitting)
                loginStatus.setText(R.string.login_step_connecting)
                showProgress(10)
            }
        }
        Thread {
            val result = GatewayAuth.authenticate(
                baseUrl = credentials.gatewayUrl,
                basicUser = credentials.basicUser,
                basicPass = credentials.basicPass,
                token = credentials.token
            ) { step ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showProgress(
                        when (step) {
                            GatewayAuth.Step.MINT_NONCE -> 45
                            GatewayAuth.Step.EXCHANGE -> 80
                            GatewayAuth.Step.CONNECTING -> 10
                        }
                    )
                    if (trigger == AuthTrigger.LOGIN_BUTTON) {
                        loginStatus.setText(
                            when (step) {
                                GatewayAuth.Step.CONNECTING -> R.string.login_step_connecting
                                GatewayAuth.Step.MINT_NONCE -> R.string.login_step_nonce
                                GatewayAuth.Step.EXCHANGE -> R.string.login_step_exchange
                            }
                        )
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                authInProgress = false
                onAuthResult(result, trigger)
            }
        }.start()
    }

    private fun onAuthResult(result: GatewayAuth.Result, trigger: AuthTrigger) {
        when (result) {
            is GatewayAuth.Result.Success -> {
                applySessionCookie(result.cookieName, result.cookieValue)
                showProgress(100)
                if (trigger == AuthTrigger.REFRESH_BUTTON) {
                    hideErrorPage()
                    webView.reload()
                } else {
                    hideLogin()
                    hideErrorPage()
                    loadTarget()
                }
            }

            is GatewayAuth.Result.Rejected -> {
                showProgress(0)
                when (result.kind) {
                    GatewayAuth.RejectKind.BASIC_AUTH -> {
                        if (trigger == AuthTrigger.REFRESH_BUTTON) {
                            toast(getString(R.string.login_err_basic))
                        } else {
                            showLogin(prefill = true)
                            loginStatus.setText(R.string.login_err_basic)
                        }
                    }

                    GatewayAuth.RejectKind.TOKEN -> {
                        // token 变了/过期：清掉存档，让用户重新输入
                        clearStoredToken()
                        showLogin(prefill = true)
                        loginStatus.setText(R.string.login_err_token)
                    }
                }
            }

            is GatewayAuth.Result.NetworkError -> {
                showProgress(0)
                if (trigger == AuthTrigger.REFRESH_BUTTON) {
                    toast(getString(R.string.auth_refresh_network_short))
                } else {
                    loginButton.isEnabled = true
                    loginButton.setText(R.string.login_submit)
                    retryAction = { runAuth(trigger) }
                    showErrorPage(
                        titleRes = R.string.auth_err_network_title,
                        messageRes = R.string.auth_err_network_message,
                        messageText = getString(R.string.auth_err_network_message, gatewayHost),
                        showLoginButton = true
                    )
                }
            }

            is GatewayAuth.Result.ProtocolError -> {
                showProgress(0)
                when (trigger) {
                    AuthTrigger.REFRESH_BUTTON ->
                        toast(getString(R.string.auth_refresh_protocol_short))

                    // 明确点了「登录并进入」：把人留在表单里，消息显示在状态行，方便立刻改
                    AuthTrigger.LOGIN_BUTTON -> {
                        loginButton.isEnabled = true
                        loginButton.setText(R.string.login_submit)
                        loginStatus.setText(R.string.login_err_protocol)
                    }

                    AuthTrigger.STARTUP -> {
                        loginButton.isEnabled = true
                        loginButton.setText(R.string.login_submit)
                        retryAction = { runAuth(trigger) }
                        showErrorPage(
                            titleRes = R.string.auth_err_protocol_title,
                            messageRes = R.string.auth_err_protocol_message,
                            showLoginButton = true
                        )
                    }
                }
            }
        }
    }

    private fun applySessionCookie(name: String, value: String) {
        // cookie 名形如 raven_session_<端口>，端口会变，所以用响应头里解析出来的名字
        CookieManager.getInstance().setCookie(gatewayUrl, "$name=$value")
        CookieManager.getInstance().flush()
    }

    private fun loadTarget() {
        val url = pendingExternalUrl ?: gatewayUrl
        pendingExternalUrl = null
        showProgress(5)
        webView.loadUrl(url)
    }

    // ---------------------------------------------------------------- 凭据存取

    private data class Credentials(
        val basicUser: String,
        val basicPass: String,
        val token: String,
        val gatewayUrl: String
    )

    private fun loadCredentials(): Credentials? {
        val prefs = authPrefs() ?: return null
        val user = prefs.getString(KEY_BASIC_USER, null)
        val pass = prefs.getString(KEY_BASIC_PASS, null)
        val token = sessionToken ?: prefs.getString(KEY_TOKEN, null)
        if (user.isNullOrEmpty() || pass.isNullOrEmpty() || token.isNullOrEmpty()) return null
        return Credentials(user, pass, token, gatewayUrl)
    }

    private fun saveLoginCredentials(
        user: String,
        pass: String,
        token: String,
        url: String,
        rememberToken: Boolean
    ) {
        sessionToken = token
        gatewayUrl = url.trimEnd('/')
        gatewayHost = Uri.parse(gatewayUrl).host?.lowercase() ?: getString(R.string.home_host)
        val prefs = authPrefs() ?: return
        val host = gatewayHost
        prefs.edit()
            .putString(KEY_BASIC_USER, user)
            .putString(KEY_BASIC_PASS, pass)
            .putString(KEY_TOKEN, token.takeIf { rememberToken } ?: "")
            .putString(KEY_GATEWAY_URL, gatewayUrl)
            // 顺便喂给 WebView 的 Basic Auth 兜底对话框，让它也能自动 proceed
            .putString("${host}_user", user)
            .putString("${host}_pass", pass)
            .apply()
    }

    /** 从加密 prefs 读回上次输入的网关地址；没有存档就用构建期预填的默认值。 */
    private fun loadStoredGateway() {
        val stored = authPrefs()?.getString(KEY_GATEWAY_URL, null)?.takeIf { it.isNotBlank() }
        gatewayUrl = stored?.trimEnd('/') ?: getString(R.string.home_url)
        gatewayHost = Uri.parse(gatewayUrl).host?.lowercase() ?: getString(R.string.home_host)
    }

    private fun clearStoredToken() {
        sessionToken = null
        authPrefs()?.edit()?.remove(KEY_TOKEN)?.apply()
    }

    // ---------------------------------------------------------------- 状态栏 / 深色适配

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applySystemBarAppearance()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pendingAuthHandler?.cancel()
        pendingAuthHandler = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.setDownloadListener(null)
        webView.webChromeClient = null
        webView.removeJavascriptInterface("RavenAndroid")
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun applySystemBarAppearance() {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
        window.statusBarColor = ContextCompat.getColor(this, R.color.status_bar)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.nav_bar)
    }

    // ---------------------------------------------------------------- 登录界面 / 错误页

    private fun showLogin(prefill: Boolean) {
        loginLayout.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        showProgress(0)
        if (prefill) {
            val prefs = authPrefs()
            loginUrl.setText(gatewayUrl)
            loginBasicUser.setText(
                prefs?.getString(KEY_BASIC_USER, null) ?: getString(R.string.login_default_user)
            )
            loginBasicPass.setText(prefs?.getString(KEY_BASIC_PASS, null) ?: "")
            val token = sessionToken ?: prefs?.getString(KEY_TOKEN, null)
            loginToken.setText(token ?: "")
            loginNoSaveToken.isChecked = false
            loginStatus.text = ""
        }
        loginButton.isEnabled = true
        loginButton.setText(R.string.login_submit)
    }

    private fun hideLogin() {
        loginLayout.visibility = View.GONE
    }

    private fun showProgress(progress: Int) {
        progressBar.progress = progress
        progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
    }

    private fun showErrorPage(
        titleRes: Int,
        messageRes: Int,
        messageText: String? = null,
        showLoginButton: Boolean = false
    ) {
        errorTitle.setText(titleRes)
        errorMessage.text = messageText ?: getString(messageRes)
        errorLayout.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        loginLayout.visibility = View.GONE
        errorLoginButton.visibility = if (showLoginButton) View.VISIBLE else View.GONE
        showProgress(0)
    }

    private fun hideErrorPage() {
        errorLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        errorLoginButton.visibility = View.GONE
    }

    // ---------------------------------------------------------------- 外来 intent

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme?.lowercase() != "https") return
        if (!isSameSite(data)) return
        val path = data.path?.lowercase() ?: ""
        if (path.startsWith("/auth")) {
            pendingExternalUrl = data.toString()
        }
    }

    // ---------------------------------------------------------------- Basic Auth

    private fun authPrefs(): android.content.SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                AUTH_PREFS,
                masterKey,
                PrefKeyEncryptionScheme.AES256_SIV,
                PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * nginx 那层 Basic Auth 的头值；没有存档用户名/密码时返回 null。
     * 存档的 user/pass 与「不保存 token」无关——登录时总会写入，token 才是有条件的那个。
     */
    private fun basicAuthHeader(): String? {
        val prefs = authPrefs() ?: return null
        val user = prefs.getString(KEY_BASIC_USER, null)
        val pass = prefs.getString(KEY_BASIC_PASS, null)
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) return null
        return GatewayAuth.basicAuthValue(user, pass)
    }

    private fun showAuthDialog(handler: HttpAuthHandler, host: String, @Suppress("UNUSED_PARAMETER") realm: String?) {
        if (isFinishing || isDestroyed) {
            handler.cancel()
            return
        }
        pendingAuthHandler = handler

        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        val userInput = EditText(this).apply {
            hint = getString(R.string.auth_username)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val passInput = EditText(this).apply {
            hint = getString(R.string.auth_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setSingleLine()
        }
        container.addView(userInput)
        container.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.auth_title))
            .setMessage(getString(R.string.auth_desc) + "\n" + host)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.auth_login) { _, _ ->
                pendingAuthHandler = null
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (user.isEmpty() || pass.isEmpty()) {
                    handler.cancel()
                    return@setPositiveButton
                }
                val hostKey = gatewayHost
                authPrefs()?.edit()
                    ?.putString("${hostKey}_user", user)
                    ?.putString("${hostKey}_pass", pass)
                    ?.apply()
                handler.proceed(user, pass)
                toast(getString(R.string.auth_saved))
            }
            .setNegativeButton(R.string.auth_cancel) { _, _ ->
                pendingAuthHandler = null
                handler.cancel()
            }
            .setOnDismissListener {
                if (pendingAuthHandler === handler) {
                    pendingAuthHandler = null
                    handler.cancel()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- 文件选择

    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            val list = ArrayList<Uri>(clip.itemCount)
            for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
            return list.toTypedArray()
        }
        val single = data.data ?: return null
        return arrayOf(single)
    }

    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        if (params != null) {
            try {
                return params.createIntent()
            } catch (e: Exception) {
                // 落到手动构造
            }
        }
        val accept = params?.acceptTypes?.firstOrNull { !it.isNullOrBlank() }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = accept ?: "*/*"
            if (params != null && params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        return Intent.createChooser(intent, getString(R.string.file_chooser_title))
    }

    // ---------------------------------------------------------------- 下载

    private val downloadListener = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        runOnUiThread {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                toast(getString(R.string.download_no_app))
                return@runOnUiThread
            }
            val pending = PendingDownload(url, userAgent, contentDisposition, mimeType)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = pending
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startDownload(pending)
            }
        }
    }

    private fun startDownload(pending: PendingDownload) {
        val fileName = URLUtil.guessFileName(pending.url, pending.contentDisposition, pending.mimeType)
        val uri = Uri.parse(pending.url)
        val request = DownloadManager.Request(uri).apply {
            addRequestHeader("User-Agent", pending.userAgent)
            CookieManager.getInstance().getCookie(pending.url)?.let {
                addRequestHeader("Cookie", it)
            }
            // nginx 那层 Basic Auth：DownloadManager 是独立系统服务，不会像 WebView 那样
            // 弹对话框协商，也不继承 WebView 的凭据。不带这个头，/files/download 会被
            // nginx 401 挡掉，下载必然失败。
            if (isSameSite(uri)) {
                basicAuthHeader()?.let { addRequestHeader("Authorization", it) }
            }
            setMimeType(pending.mimeType)
            setTitle(fileName)
            setDescription(getString(R.string.app_name))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            @Suppress("DEPRECATION")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toast(getString(R.string.download_started, fileName))
        } catch (e: Exception) {
            toast(getString(R.string.download_no_app))
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun isSameSite(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val home = gatewayHost.lowercase()
        return host == home || host.endsWith(".$home")
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.download_no_app))
        }
    }

    // ---------------------------------------------------------------- 内部类

    private inner class RavenWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    if (isSameSite(uri)) {
                        false
                    } else {
                        openExternally(uri)
                        true
                    }
                }

                "mailto", "tel", "sms", "smsto", "geo", "market", "intent" -> {
                    openExternally(uri)
                    true
                }

                else -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            mainFrameError = false
            showProgress(5)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            authAttempts.clear()
            showProgress(100)
            if (!mainFrameError) {
                hideErrorPage()
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                mainFrameError = true
                retryAction = { webView.reload() }
                showProgress(100)
                showErrorPage(
                    titleRes = R.string.auth_err_network_title,
                    messageRes = R.string.auth_err_network_message,
                    messageText = getString(R.string.auth_err_network_message, gatewayHost),
                    showLoginButton = false
                )
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            handler.cancel()
            mainFrameError = true
            retryAction = { webView.reload() }
            showProgress(100)
            showErrorPage(
                titleRes = R.string.error_ssl_title,
                messageRes = R.string.error_ssl_message,
                showLoginButton = false
            )
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String?
        ) {
            val prefs = authPrefs()
            val user = prefs?.getString("${host}_user", null)
            val pass = prefs?.getString("${host}_pass", null)
            val attempts = authAttempts[host] ?: 0

            if (user != null && pass != null && attempts == 0) {
                authAttempts[host] = attempts + 1
                handler.proceed(user, pass)
                return
            }
            if (attempts >= 2) {
                prefs?.edit()?.remove("${host}_user")?.remove("${host}_pass")?.apply()
            }
            showAuthDialog(handler, host, realm)
        }
    }

    private inner class RavenWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            showProgress(newProgress)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(buildFileChooserIntent(fileChooserParams))
                true
            } catch (e: ActivityNotFoundException) {
                this@MainActivity.filePathCallback = null
                toast(getString(R.string.download_no_app))
                false
            }
        }
    }

    private enum class AuthTrigger { STARTUP, REFRESH_BUTTON, LOGIN_BUTTON }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    /** 网页刷新钮的 JS 桥：见 [setupWebView] 里的 addJavascriptInterface。 */
    private inner class RavenJsBridge {
        @JavascriptInterface
        fun refresh() {
            runOnUiThread { runAuth(AuthTrigger.REFRESH_BUTTON) }
        }
    }

    private companion object {
        const val AUTH_PREFS = "raven_gateway_auth"
        const val KEY_BASIC_USER = "login_basic_user"
        const val KEY_BASIC_PASS = "login_basic_pass"
        const val KEY_TOKEN = "login_raven_token"
        const val KEY_GATEWAY_URL = "login_gateway_url"
        const val STATE_LOGIN_VISIBLE = "login_visible"
        val TOKEN_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}
