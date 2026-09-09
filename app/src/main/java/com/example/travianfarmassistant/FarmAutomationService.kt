package com.example.travianfarmassistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.lang.ref.WeakReference
import kotlin.random.Random

class FarmAutomationService : Service() {
    companion object {
        private var instanceRef: WeakReference<FarmAutomationService>? = null
        private var visibleWebViewRef: WeakReference<WebView>? = null

        fun attachVisibleWebView(view: WebView) {
            visibleWebViewRef = WeakReference(view)
        }

        fun detachVisibleWebView(view: WebView) {
            if (visibleWebViewRef?.get() === view) visibleWebViewRef = null
        }

        fun isRunningFromService(): Boolean = instanceRef?.get()?.running == true

        fun forwardPageFinished(url: String) {
            instanceRef?.get()?.handleVisiblePageFinished(url)
        }

        fun forwardLoginResult(result: String) {
            instanceRef?.get()?.handleLoginResultFromVisibleWebView(result)
        }

        fun onVisibleWebViewDetached() {
            instanceRef?.get()?.onVisibleWebViewDetachedInternal()
        }

        const val ACTION_START = "com.example.travianfarmassistant.START"
        const val ACTION_STOP = "com.example.travianfarmassistant.STOP"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_MINUTES_MIN = "minutes_min"
        const val EXTRA_MINUTES_MAX = "minutes_max"
        const val EXTRA_RESOURCE_BUILDER = "resource_builder"
        const val EXTRA_FARM_LIST_ENABLED = "farm_list_enabled"
        const val EXTRA_SELECTED_VILLAGES = "selected_villages"
        const val EXTRA_SELECTED_VILLAGES_JSON = "selected_villages_json"
        const val EXTRA_SELECTION_CONFIGURED = "selection_configured"

        private const val CHANNEL_ID = "farm_automation"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS = "config"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var running = false
    private var pendingStartAll = false
    private var loginInProgress = false
    private var reloginRequested = false
    private var loginRetryCount = 0
    private var startAllAttempt = 0
    private var raidCountBeforeStartAll = 0
    private var raidVerificationAttempt = 0
    private var farmListBeforeReady = 0
    private var farmListProgressObserved = false
    private var farmListLastState = ""
    private var farmListStableChecks = 0
    private var fallbackFarmListMode = false
    private var consentAttempt = 0
    private var server = ""
    private var username = ""
    private var password = ""
    private var minMinutes = 1L
    private var maxMinutes = 1L
    private var nextAt = 0L
    private var resourceBuilderEnabled = true
    private var farmListEnabled = true
    private var builderSelectionConfigured = false
    private var selectedBuilderVillageIds = emptySet<String>()
    private var selectedBuilderVillagesJson = "[]"
    private var builderInProgress = false
    private var builderVillages = mutableListOf<Pair<String, String>>()
    private var builderVillageIndex = 0
    private var builderAttempt = 0
    private var pendingUpgradeUrl = ""
    private var pendingUpgradeCosts = longArrayOf(0L, 0L, 0L, 0L)
    private var inventoryUseAttempt = 0

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val logFileName = "farm_assistant.log"
    private val logMaxAgeMs = 12 * 60 * 60 * 1000L

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Farm Assistant aktif"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAutomation()
            ACTION_START -> {
                server = normalizeServer(intent.getStringExtra(EXTRA_SERVER).orEmpty())
                username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim()
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                minMinutes = intent.getLongExtra(EXTRA_MINUTES_MIN, 1L).coerceAtLeast(1L)
                maxMinutes = intent.getLongExtra(EXTRA_MINUTES_MAX, minMinutes).coerceAtLeast(minMinutes)
                resourceBuilderEnabled = intent.getBooleanExtra(EXTRA_RESOURCE_BUILDER, true)
                farmListEnabled = intent.getBooleanExtra(EXTRA_FARM_LIST_ENABLED, true)
                builderSelectionConfigured = intent.getBooleanExtra(
                    EXTRA_SELECTION_CONFIGURED,
                    getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("resource_builder_selection_configured", false)
                )
                selectedBuilderVillageIds = intent.getStringArrayExtra(EXTRA_SELECTED_VILLAGES)?.toSet()
                    ?: (getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("resource_builder_selected_villages", emptySet()) ?: emptySet())
                selectedBuilderVillagesJson = intent.getStringExtra(EXTRA_SELECTED_VILLAGES_JSON)
                    ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString("resource_builder_villages_json", "[]").orEmpty()
                startAutomation()
            }
        }
        return START_STICKY
    }

    private fun automationWebView(): WebView? = visibleWebViewRef?.get() ?: webView

    private fun handleVisiblePageFinished(url: String) {
        if (!running) return
        val lower = url.lowercase(Locale.US)
        handlePageAfterConsent(url, lower, 0)
    }

    private fun handleLoginResultFromVisibleWebView(result: String) {
        if (!running) return
        handler.post {
            if (!running) return@post
            when (result) {
                "submitting" -> updateNotification("Farm Assistant — mengirim login")
                "no_login_form" -> {
                    loginInProgress = false
                    reloginRequested = false
                    loginRetryCount = 0
                    logEvent("Session aktif terdeteksi; membuka Farm List")
                    handler.postDelayed({ triggerStartAllFarmLists() }, 250)
                }
                "no_username_field", "no_form" -> {
                    if (loginRetryCount < 20) handler.postDelayed({ autoLoginIfNeeded() }, 1000)
                    else {
                        loginInProgress = false
                        reloginRequested = false
                        logEvent("Form login Travian tidak dikenali")
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureServiceWebView() {
        if (webView != null) return
        webView = WebView(this@FarmAutomationService).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(FarmBridge(), "AndroidFarm")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null || !running) return
                    handlePageAfterConsent(url, url.lowercase(Locale.US), 0)
                }
            }
        }
    }

    private fun onVisibleWebViewDetachedInternal() {
        if (running && webView == null) ensureServiceWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startAutomation() {
        running = true
        pendingStartAll = false
        loginInProgress = false
        reloginRequested = false
        loginRetryCount = 0
        startAllAttempt = 0
        consentAttempt = 0

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("username", username)
            .putLong("interval_min_minutes", minMinutes)
            .putLong("interval_max_minutes", maxMinutes)
            .putBoolean("farm_list_enabled", farmListEnabled)
            .putBoolean("resource_builder_enabled", resourceBuilderEnabled)
            .putBoolean("service_running", true)
            .apply()

        logEvent("Background service dimulai. Range interval=${minMinutes}-${maxMinutes} menit; Farm List=${if (farmListEnabled) "ON" else "OFF"}; Resource Builder=${if (resourceBuilderEnabled) "ON" else "OFF"}; Village terpilih=${if (builderSelectionConfigured) selectedBuilderVillageIds.size else "SEMUA"}")
        updateNextRun(0L)
        updateNotification("Farm Assistant aktif — menyiapkan siklus")

        if (username.isBlank() || password.isBlank()) {
            logEvent("Background service gagal: username/password kosong")
            stopAutomation()
            return
        }

        if (webView == null && visibleWebViewRef?.get() == null) {
            ensureServiceWebView()
        }

        triggerScheduledCycle()
    }

    private fun triggerScheduledCycle() {
        if (!running) return
        if (farmListEnabled) {
            triggerStartAllFarmLists()
        } else if (resourceBuilderEnabled) {
            logEvent("Farm List OFF — langsung menjalankan Resource Builder")
            startResourceBuilderCycle()
        } else {
            logEvent("Farm List OFF dan Resource Builder OFF — tidak ada aksi pada siklus ini")
            scheduleNextRandomRun()
        }
    }

    private fun triggerStartAllFarmLists() {
        if (!running || !farmListEnabled) return
        pendingStartAll = true
        startAllAttempt = 0
        consentAttempt = 0
        updateNotification("Farm Assistant aktif — membuka Farm List")
        logEvent("Memulai siklus Start All Farm Lists")
        automationWebView()?.loadUrl("$server/build.php?id=39&gid=16&tt=99")
    }

    private fun handlePageAfterConsent(url: String, lower: String, attempt: Int) {
        if (!running) return
        acceptCookiesIfPresent { result ->
            if (!running) return@acceptCookiesIfPresent
            val consentStillVisible = result.contains("visible") || result.contains("clicked")
            if (consentStillVisible && attempt < 8) {
                consentAttempt = attempt + 1
                CookieManager.getInstance().flush()
                handler.postDelayed({ handlePageAfterConsent(url, lower, attempt + 1) }, 700)
                return@acceptCookiesIfPresent
            }

            if (lower.contains("gid=16") && lower.contains("tt=99")) {
                loginInProgress = false
                reloginRequested = false
                loginRetryCount = 0
                if (pendingStartAll) {
                    startAllAttempt = 0
                    handler.postDelayed({ clickStartAllFarmLists() }, 1200)
                }
                return@acceptCookiesIfPresent
            }

            if (builderInProgress && lower.contains("dorf1.php")) {
                if (builderVillages.isEmpty()) {
                    handler.postDelayed({ discoverVillagesForBuilder() }, 700)
                } else {
                    handler.postDelayed({ inspectResourceVillage() }, 700)
                }
                return@acceptCookiesIfPresent
            }

            if (builderInProgress && lower.contains("build.php") && !lower.contains("gid=16")) {
                handler.postDelayed({ inspectUpgradeResources() }, 700)
                return@acceptCookiesIfPresent
            }

            if (builderInProgress && lower.contains("hero/inventory")) {
                handler.postDelayed({ useHeroInventoryForPendingUpgrade() }, 700)
                return@acceptCookiesIfPresent
            }

            if (isLikelyLoginPage(lower)) {
                if (username.isNotBlank() && password.isNotBlank()) {
                    loginInProgress = true
                    reloginRequested = true
                    loginRetryCount = 0
                    updateNotification("Farm Assistant — auto re-login")
                    logEvent("Session Travian habis; memulai auto re-login")
                    handler.postDelayed({ autoLoginIfNeeded() }, 500)
                } else {
                    logEvent("Session habis tetapi password tidak tersedia di RAM")
                }
                return@acceptCookiesIfPresent
            }

            if (loginInProgress) {
                handler.postDelayed({ autoLoginIfNeeded() }, 500)
            } else if (pendingStartAll) {
                detectLoginFormForScheduler()
            }
        }
    }

    private fun isLikelyLoginPage(url: String): Boolean =
        url.contains("login") || url.contains("logout") ||
            url.contains("anmelden") || url.contains("signin")

    private fun autoLoginIfNeeded() {
        if (!running || !loginInProgress) return
        if (username.isBlank() || password.isBlank()) return
        loginRetryCount++
        if (loginRetryCount > 20) {
            loginInProgress = false
            reloginRequested = false
            logEvent("Auto re-login gagal setelah 20 percobaan")
            return
        }

        val usernameJson = JSONObject.quote(username)
        val passwordJson = JSONObject.quote(password)
        val js = """
            (() => {
                const username = $usernameJson;
                const password = $passwordJson;
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el);
                    return s.display !== 'none' && s.visibility !== 'hidden' && el.offsetParent !== null;
                };
                const inputs = [...document.querySelectorAll('input')].filter(visible);
                const passwordInput = inputs.find(x =>
                    (x.type || '').toLowerCase() === 'password' || /pass|password/i.test(x.name || '') || /pass|password/i.test(x.id || '')
                );
                if (!passwordInput) { AndroidFarm.onLoginResult('no_login_form'); return; }
                const userInput = inputs.find(x =>
                    /user|username|email|login|name/i.test(x.name || '') || /user|username|email|login|name/i.test(x.id || '') || (x.type || '').toLowerCase() === 'email'
                );
                if (!userInput) { AndroidFarm.onLoginResult('no_username_field'); return; }
                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
                    if (setter) setter.call(el, value); else el.value = value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                };
                setValue(userInput, username);
                setValue(passwordInput, password);
                const form = passwordInput.closest('form') || userInput.closest('form');
                if (!form) { AndroidFarm.onLoginResult('no_form'); return; }
                const buttons = [...form.querySelectorAll('button,input[type=submit],input[type=button],a')].filter(visible);
                const submitButton = buttons.find(x => /login|log in|sign in|anmelden|connexion|entrar|acceder/i.test((x.innerText || x.value || x.title || '').trim()));
                AndroidFarm.onLoginResult('submitting');
                if (submitButton) submitButton.click();
                else if (typeof form.requestSubmit === 'function') form.requestSubmit();
                else form.submit();
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js, null)
    }

    private fun detectLoginFormForScheduler() {
        automationWebView()?.evaluateJavascript("""
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el); const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                return [...document.querySelectorAll('input[type=password]')].some(visible) ? 'login_form' : 'not_login';
            })();
        """.trimIndent()) { raw ->
            if (raw.orEmpty().contains("login_form") && running) {
                loginInProgress = true
                reloginRequested = true
                loginRetryCount = 0
                logEvent("Form login terdeteksi saat scheduler berjalan")
                handler.postDelayed({ autoLoginIfNeeded() }, 250)
            }
        }
    }

    private fun clickStartAllFarmLists() {
        if (!running || !pendingStartAll) return
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el); const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
                const statusCount = () => {
                    let total = 0;
                    for (const el of document.querySelectorAll('#rallyPointFarmList .farmListStatus, .farmListStatus')) {
                        const m = norm(el.textContent).match(/(\d+)\s*\/\s*(\d+)/);
                        if (m) total += parseInt(m[1], 10);
                    }
                    return total;
                };
                const readyCount = () => {
                    let ready = 0;
                    for (const b of document.querySelectorAll('#rallyPointFarmList .farmListWrapper button.startFarmList, button.startFarmList')) {
                        if (visible(b) && !b.disabled && b.getAttribute('disabled') === null && b.getAttribute('aria-disabled') !== 'true') ready++;
                    }
                    return ready;
                };
                const dispatch = btn => {
                    btn.scrollIntoView({block:'center'});
                    btn.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
                    btn.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
                    btn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                };
                const selectors = [
                    '#rallyPointFarmList button.startAllFarmLists',
                    'button.startAllFarmLists',
                    '.startAllFarmLists button',
                    '.startAllFarmLists'
                ];
                for (const selector of selectors) {
                    let nodes = [];
                    try { nodes = [...document.querySelectorAll(selector)]; } catch (_) {}
                    const btn = nodes.find(el => visible(el) && !el.disabled && el.getAttribute('aria-disabled') !== 'true');
                    if (btn) {
                        const before = statusCount();
                        const beforeReady = readyCount();
                        dispatch(btn);
                        return JSON.stringify({state:'clicked', before, beforeReady, selector});
                    }
                }
                const candidates = [...document.querySelectorAll('button,input[type=button],input[type=submit],a,[role=button]')];
                const textBtn = candidates.find(el => {
                    if (!visible(el) || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
                    const t = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                    return /^(start all|start all farm lists?|start all farmlists?|send all)$/.test(t) || /start all.*farm/i.test(t);
                });
                if (textBtn) {
                    const before = statusCount();
                    const beforeReady = readyCount();
                    dispatch(textBtn);
                    return JSON.stringify({state:'clicked', before, beforeReady, selector:'text'});
                }
                return JSON.stringify({state:'not-found', before:statusCount(), beforeReady:readyCount()});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            if (result.contains("\"state\":\"clicked\"")) {
                pendingStartAll = false
                startAllAttempt = 0
                raidVerificationAttempt = 0
                farmListProgressObserved = false
                farmListLastState = ""
                farmListStableChecks = 0
                fallbackFarmListMode = false
                raidCountBeforeStartAll = Regex("\"before\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                farmListBeforeReady = Regex("\"readyBefore\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val now = timeFormat.format(Date())
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_run", now).apply()
                logEvent("Send All Farm Lists diklik; raid aktif sebelum klik=$raidCountBeforeStartAll; tombol Farm List siap sebelum klik=$farmListBeforeReady")
                updateNotification("Farm Assistant — menunggu Farm List selesai")
                handler.post({ verifyRaidDispatch() })
            } else if (startAllAttempt < 10) {
                startAllAttempt++
                handler.postDelayed({ clickStartAllFarmLists() }, 1000)
            } else {
                pendingStartAll = false
                logEvent("Send All gagal: tombol tidak ditemukan setelah 10 percobaan; Farm List belum dianggap selesai")
                fallbackSequentialFarmListSend()
            }
        }
    }

    private fun verifyRaidDispatch() {
        if (!running) return
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
                let total = 0, wrappers = 0, ready = 0;
                for (const wrapper of document.querySelectorAll('#rallyPointFarmList .farmListWrapper')) {
                    wrappers++;
                    const status = wrapper.querySelector('.farmListStatus');
                    const m = norm(status?.textContent || '').match(/(\d+)\s*\/\s*(\d+)/);
                    if (m) total += parseInt(m[1], 10);
                    const btn = wrapper.querySelector('button.startFarmList');
                    if (btn && visible(btn) && !btn.disabled && btn.getAttribute('disabled') === null && btn.getAttribute('aria-disabled') !== 'true') ready++;
                }
                const allText = norm(document.querySelector('#rallyPointFarmList')?.innerText || '');
                const busy = /sending|loading|processing|mengirim|memproses/.test(allText);
                return JSON.stringify({total, wrappers, ready, busy});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val current = Regex("\"total\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ready = Regex("\"ready\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val busy = Regex("\"busy\":(true|false)").find(result)?.groupValues?.get(1) == "true"
            val wrappers = Regex("\"wrappers\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (current > raidCountBeforeStartAll || ready < farmListBeforeReady || busy) {
                farmListProgressObserved = true
            }
            val state = "$current|$ready|$busy|$wrappers"
            if (state == farmListLastState) farmListStableChecks++ else {
                farmListLastState = state
                farmListStableChecks = 1
            }
            val completionProven = wrappers > 0 && ready == 0 && !busy &&
                (farmListProgressObserved || farmListBeforeReady == 0) &&
                farmListStableChecks >= 2

            if (completionProven) {
                val sent = (current - raidCountBeforeStartAll).coerceAtLeast(0)
                logEvent("Hasil Send All Farm: $sent raid terkirim (being raided $raidCountBeforeStartAll → $current); SELURUH FARM LIST SELESAI TERVERIFIKASI")
                updateNotification("Farm List selesai — $sent raid terkirim")
                if (resourceBuilderEnabled) startResourceBuilderCycle() else scheduleNextRandomRun()
            } else if (raidVerificationAttempt < 20) {
                raidVerificationAttempt++
                logEvent("Farm List BELUM selesai (${raidVerificationAttempt}/20); raid=$current; tombol Start aktif=$ready; busy=$busy; progress=${if (farmListProgressObserved) "YA" else "BELUM"}")
                updateNotification("Farm List — verifikasi ${raidVerificationAttempt}/20; masih diproses")
                handler.postDelayed({ verifyRaidDispatch() }, 1500)
            } else {
                logEvent("Send All belum terverifikasi selesai setelah 20 pemeriksaan; Farm List TETAP dianggap belum selesai — fallback dijalankan")
                fallbackSequentialFarmListSend()
            }
        }
    }

    private fun fallbackSequentialFarmListSend() {
        if (!running) return
        updateNotification("Farm List — fallback, menyelesaikan pengiriman")
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const statusCount = () => {
                    let total = 0;
                    for (const wrapper of document.querySelectorAll('#rallyPointFarmList .farmListWrapper')) {
                        const m = (wrapper.querySelector('.farmListStatus')?.textContent || '').replace(/\s+/g,' ').match(/(\d+)\s*\/\s*(\d+)/);
                        if (m) total += parseInt(m[1],10);
                    }
                    return total;
                };
                const buttons = [...document.querySelectorAll('#rallyPointFarmList .farmListWrapper button.startFarmList, button.startFarmList')]
                    .filter(b => visible(b) && !b.disabled && b.getAttribute('disabled') === null && b.getAttribute('aria-disabled') !== 'true');
                const readyBefore = buttons.length;
                const totalBefore = statusCount();
                for (const btn of buttons) {
                    btn.scrollIntoView({block:'center'});
                    btn.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
                    btn.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
                    btn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                }
                return JSON.stringify({clicked:buttons.length, readyBefore, totalBefore});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val clicked = Regex("\"clicked\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                farmListBeforeReady = Regex("\"readyBefore\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            farmListProgressObserved = false
            farmListLastState = ""
            farmListStableChecks = 0
            fallbackFarmListMode = true
            logEvent("Fallback Start per Farm List: $clicked tombol diklik; sebelum=$farmListBeforeReady tombol siap; Farm List TETAP belum dianggap selesai")
            raidVerificationAttempt = 0
            handler.post({ verifyFallbackRaidCompletion() })
        }
    }

    private fun verifyFallbackRaidCompletion() {
        if (!running) return
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                let total = 0, ready = 0, wrappers = 0;
                for (const wrapper of document.querySelectorAll('#rallyPointFarmList .farmListWrapper')) {
                    wrappers++;
                    const m = (wrapper.querySelector('.farmListStatus')?.textContent || '').replace(/\s+/g,' ').match(/(\d+)\s*\/\s*(\d+)/);
                    if (m) total += parseInt(m[1],10);
                    const btn = wrapper.querySelector('button.startFarmList');
                    if (btn && visible(btn) && !btn.disabled && btn.getAttribute('disabled') === null && btn.getAttribute('aria-disabled') !== 'true') ready++;
                }
                const allText = (document.querySelector('#rallyPointFarmList')?.innerText || '').replace(/\s+/g,' ').toLowerCase();
                const busy = /sending|loading|processing|mengirim|memproses/.test(allText);
                return JSON.stringify({total, ready, wrappers, busy});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val current = Regex("\"total\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ready = Regex("\"ready\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val busy = Regex("\"busy\":(true|false)").find(result)?.groupValues?.get(1) == "true"
            val wrappers = Regex("\"wrappers\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (current > raidCountBeforeStartAll || ready < farmListBeforeReady || busy) {
                farmListProgressObserved = true
            }
            val state = "$current|$ready|$busy|$wrappers"
            if (state == farmListLastState) farmListStableChecks++ else {
                farmListLastState = state
                farmListStableChecks = 1
            }
            val completionProven = wrappers > 0 && ready == 0 && !busy &&
                (farmListProgressObserved || farmListBeforeReady == 0) &&
                farmListStableChecks >= 2

            if (completionProven) {
                val sent = (current - raidCountBeforeStartAll).coerceAtLeast(0)
                logEvent("Hasil Send All Farm: $sent raid terkirim setelah fallback (being raided $raidCountBeforeStartAll → $current); SEMUA FARM LIST SELESAI TERVERIFIKASI")
                updateNotification("Farm List selesai — $sent raid terkirim")
                fallbackFarmListMode = false
                if (resourceBuilderEnabled) startResourceBuilderCycle() else scheduleNextRandomRun()
            } else {
                raidVerificationAttempt++
                if (raidVerificationAttempt >= 20) {
                    logEvent("Fallback belum terverifikasi selesai; Farm List dimuat ulang dan verifikasi dilanjutkan")
                    raidVerificationAttempt = 0
                    farmListLastState = ""
                    farmListStableChecks = 0
                    automationWebView()?.loadUrl("$server/build.php?id=39&gid=16&tt=99")
                    handler.postDelayed({ verifyFallbackRaidCompletion() }, 2500)
                } else {
                    logEvent("Fallback BELUM selesai (${raidVerificationAttempt}/20); raid=$current; tombol Start aktif=$ready; busy=$busy; progress=${if (farmListProgressObserved) "YA" else "BELUM"}")
                    updateNotification("Farm List — fallback verifikasi ${raidVerificationAttempt}/20")
                    handler.postDelayed({ verifyFallbackRaidCompletion() }, 1500)
                }
            }
        }
    }

    /**
     * Resource Builder: setelah raid berhasil dijalankan, kunjungi setiap village
     * dan upgrade satu resource field dengan level terendah yang tersedia.
     * Strategi ini sengaja hanya melakukan satu upgrade per village per siklus.
     */
    private fun startResourceBuilderCycle() {
        if (!running) return
        builderInProgress = true
        builderVillages.clear()
        builderVillageIndex = 0
        builderAttempt = 0
        updateNotification("Farm Assistant — Resource Builder menyiapkan village")
        logEvent("Resource Builder: mulai siklus semua village")
        automationWebView()?.loadUrl("$server/dorf1.php")
    }

    private fun processResourceBuilderVillage() {
        if (!running || !builderInProgress) return
        if (builderVillageIndex >= builderVillages.size) {
            finishResourceBuilderCycle()
            return
        }
        val (villageId, villageName) = builderVillages[builderVillageIndex]
        builderAttempt = 0
        logEvent("Resource Builder: village ${builderVillageIndex + 1}/${builderVillages.size} — $villageName (ID $villageId)")
        updateNotification("Resource Builder — ${builderVillageIndex + 1}/${builderVillages.size}: $villageName")
        automationWebView()?.loadUrl("$server/dorf1.php?newdid=$villageId")
    }

    private fun inspectResourceVillage() {
        if (!running || !builderInProgress) return
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g,' ').trim();
                const container = document.querySelector('#resourceFieldContainer');
                if (!container) return JSON.stringify({state:'no_container'});

                const queue = document.querySelector('.boxes.buildingList, .buildingList');
                if (queue && queue.querySelector('li')) {
                    return JSON.stringify({state:'queue_busy'});
                }

                const fields = [];
                const anchors = [...container.querySelectorAll('a[href*="build.php?id="]')];
                const seen = new Set();
                for (const a of anchors) {
                    const href = a.getAttribute('href') || '';
                    const m = href.match(/[?&]id=(\d+)/);
                    if (!m || seen.has(m[1])) continue;
                    seen.add(m[1]);
                    let level = 0;
                    let node = a;
                    for (let i=0; i<5 && node; i++, node=node.parentElement) {
                        const text = norm(node.innerText || node.textContent || '');
                        const lm = text.match(/(?:level|lvl)\s*(\d+)/i) ||
                                   (node.className || '').toString().match(/level(\d+)/i);
                        if (lm) { level = parseInt(lm[1],10); break; }
                    }
                    if (!level) {
                        const lm = (a.parentElement?.className || '').toString().match(/level(\d+)/i);
                        if (lm) level = parseInt(lm[1],10);
                    }
                    const disabled = a.classList.contains('disabled') ||
                        a.closest('.disabled') || a.getAttribute('aria-disabled') === 'true';
                    fields.push({id:m[1], level, disabled:!!disabled, name:norm(a.getAttribute('title') || a.getAttribute('aria-label') || a.innerText || '')});
                }
                if (!fields.length) return JSON.stringify({state:'no_fields'});
                fields.sort((a,b) => a.level-b.level || parseInt(a.id)-parseInt(b.id));
                const candidate = fields.find(f => !f.disabled && f.level < 10);
                if (!candidate) return JSON.stringify({state:'none_available', fields});
                return JSON.stringify({state:'candidate', id:candidate.id, level:candidate.level, name:candidate.name});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            when {
                result.contains("queue_busy") -> {
                    logEvent("Resource Builder: village ${builderVillageIndex + 1} dilewati, construction queue sedang terisi")
                    goToNextBuilderVillage()
                }
                result.contains("no_container") || result.contains("no_fields") -> {
                    if (builderAttempt < 3) {
                        builderAttempt++
                        handler.postDelayed({ inspectResourceVillage() }, 800)
                    } else {
                        logEvent("Resource Builder: struktur resource field tidak ditemukan di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        goToNextBuilderVillage()
                    }
                }
                result.contains("none_available") -> {
                    logEvent("Resource Builder: tidak ada field yang bisa di-upgrade di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                    goToNextBuilderVillage()
                }
                result.contains("candidate") -> {
                    val idMatch = Regex("\\\"id\\\":\\\"?(\\d+)").find(result)
                    val levelMatch = Regex("\\\"level\\\":(\\d+)").find(result)
                    val id = idMatch?.groupValues?.get(1)
                    val level = levelMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (id == null) {
                        goToNextBuilderVillage()
                    } else {
                        logEvent("Resource Builder: memilih field id=$id level=$level di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        updateNotification("Resource Builder — field level $level → ${level + 1}")
                        automationWebView()?.loadUrl("$server/build.php?id=$id")
                    }
                }
                else -> goToNextBuilderVillage()
            }
        }
    }

    private fun inspectUpgradeResources() {
        if (!running || !builderInProgress) return
        val js = """
            (() => {
                const num = s => {
                    const m = String(s || '').replace(/[^0-9.,-]/g, '').replace(/,/g, '');
                    const n = parseInt(m, 10);
                    return Number.isFinite(n) ? n : 0;
                };
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g,' ').trim().toLowerCase();
                const root = document.querySelector('#build, #villageContent, #content') || document.body;
                const current = [
                    '#l4', '#l3', '#l2', '#l1'
                ].map(sel => {
                    const el = document.querySelector(sel);
                    const text = el ? (el.innerText || el.textContent || el.getAttribute('title') || '') : '';
                    return num(text.split('/')[0]);
                });

                const costs = [0,0,0,0];
                const contract = document.querySelector('#contract, .buildCosts, .costs, .buildingCosts, .resourceCosts') || root;
                for (let i=1;i<=4;i++) {
                    let value = 0;
                    const nodes = [...contract.querySelectorAll('img.r' + i + ', .r' + i + ', [class~="r' + i + '"]')];
                    for (const node of nodes) {
                        // Legacy/current Travian markup commonly places the cost directly
                        // after the r1/r2/r3/r4 icon as a text node.
                        let text = '';
                        let sibling = node.nextSibling;
                        for (let j=0; j<4 && sibling; j++, sibling=sibling.nextSibling) {
                            text += ' ' + (sibling.textContent || '');
                            if (/\d/.test(text)) break;
                        }
                        let matches = text.match(/\d[\d.,]*/g) || [];
                        if (!matches.length && node.parentElement) {
                            text = node.parentElement.innerText || node.parentElement.textContent || '';
                            matches = text.match(/\d[\d.,]*/g) || [];
                        }
                        if (matches.length) {
                            const candidate = num(matches[0]);
                            if (candidate > value) value = candidate;
                        }
                    }
                    costs[i-1] = value;
                }

                // Modern Travian can expose the costs through data attributes.
                if (costs.some(x => x > 0) === false) {
                    const text = norm(contract.innerText || contract.textContent || '');
                    const all = text.match(/(?:lumber|clay|iron|crop)[^0-9]{0,40}(\d[\d.,]*)/gi) || [];
                    const names = ['lumber','clay','iron','crop'];
                    for (let i=0;i<4;i++) {
                        const hit = all.find(x => x.toLowerCase().startsWith(names[i]));
                        if (hit) costs[i] = num(hit);
                    }
                }

                if (costs.some(x => x <= 0)) return JSON.stringify({state:'costs_unknown', current, costs});

                const all = [...root.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]')];
                const candidates = all.filter(el => visible(el) && !el.disabled && el.getAttribute('aria-disabled') !== 'true');
                const btn = candidates.find(el => {
                    const text = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                    const cls = (el.className || '').toString().toLowerCase();
                    return /upgrade|upgrade to level|build/.test(text) && !/cancel|demolish|destroy/.test(text) && !/disabled/.test(cls);
                }) || candidates.find(el => /green/.test((el.className || '').toString().toLowerCase()) && /build|upgrade/.test((el.className || '').toString().toLowerCase()));

                if (!btn) return JSON.stringify({state:'not_found', current, costs});
                const deficit = costs.map((c,i) => Math.max(0, c - current[i]));
                return JSON.stringify({state:'ready', current, costs, deficit});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            val deficitMatch = Regex("\\\"deficit\\\":\\[(.*?)\\]").find(result)
            val deficit = deficitMatch?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            val costMatch = Regex("\\\"costs\\\":\\[(.*?)\\]").find(result)
            val costs = costMatch?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            when {
                result.contains("costs_unknown") -> {
                    if (builderAttempt < 5) {
                        builderAttempt++
                        handler.postDelayed({ inspectUpgradeResources() }, 900)
                    } else {
                        logEvent("Resource Builder: biaya upgrade tidak terbaca di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        goToNextBuilderVillage()
                    }
                }
                result.contains("not_found") -> {
                    if (builderAttempt < 5) {
                        builderAttempt++
                        handler.postDelayed({ inspectUpgradeResources() }, 900)
                    } else {
                        logEvent("Resource Builder: tombol upgrade tidak ditemukan di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                        goToNextBuilderVillage()
                    }
                }
                deficit.isEmpty() || deficit.all { it <= 0L } -> clickResourceUpgrade()
                costs.size >= 4 && deficit.size >= 4 -> {
                    pendingUpgradeCosts = LongArray(4) { deficit[it].coerceAtLeast(0L).let { v -> if (v == 0L) 0L else ((v + 99L) / 100L) * 100L } }
                    pendingUpgradeUrl = webView?.url.orEmpty().ifBlank { "$server/build.php" }
                    val total = pendingUpgradeCosts.sum()
                    logEvent("Resource Builder: resource village kurang; kebutuhan inventory=${pendingUpgradeCosts.joinToString(",")}, total=$total")
                    inventoryUseAttempt = 0
                    updateNotification("Resource Builder — mengambil resource Hero")
                    automationWebView()?.loadUrl("$server/hero/inventory")
                }
                else -> {
                    logEvent("Resource Builder: biaya upgrade tidak terbaca; village dilewati")
                    goToNextBuilderVillage()
                }
            }
        }
    }

    private fun useHeroInventoryForPendingUpgrade() {
        if (!running || !builderInProgress || pendingUpgradeUrl.isBlank()) return
        inventoryUseAttempt++
        if (inventoryUseAttempt > 5) {
            logEvent("Resource Builder: gagal menggunakan resource Hero setelah 5 percobaan")
            pendingUpgradeUrl = ""
            goToNextBuilderVillage()
            return
        }

        val needed = pendingUpgradeCosts.joinToString(",")
        val js = """
            (() => {
                const needed = [$needed];
                const names = ['lumber','clay','iron','crop'];
                const patterns = [
                    /lumber|wood/i,
                    /clay/i,
                    /iron/i,
                    /crop/i
                ];
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
                    if (setter) setter.call(el, String(value)); else el.value = String(value);
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                };
                const inventoryRoot = document.querySelector('#heroInventory, .heroInventory, .inventory, [class*="inventory"]') || document.body;
                const elements = [...inventoryRoot.querySelectorAll('*')].filter(visible);
                const metadata = el => [
                    el.getAttribute('title'), el.getAttribute('alt'), el.getAttribute('aria-label'),
                    el.getAttribute('data-item'), el.getAttribute('data-item-type'), el.getAttribute('data-type'),
                    (el.className || '').toString(), el.id || ''
                ].filter(Boolean).join(' ');

                let used = false;
                const missing = [];
                for (let i=0;i<4;i++) {
                    if (needed[i] <= 0) continue;
                    const candidate = elements.find(el => {
                        const meta = metadata(el);
                        return patterns[i].test(meta) && (
                            /item|resource|inventory|slot/i.test(meta) || el.tagName === 'IMG'
                        );
                    });
                    if (!candidate) { missing.push(names[i]); continue; }
                    const slot = candidate.closest('[data-item-id],[data-slot],.item,.slot,[class*="item"],[class*="slot"]') || candidate.parentElement || candidate;
                    slot.scrollIntoView({block:'center'});
                    candidate.click();
                    used = true;
                    break;
                }
                if (!used) return JSON.stringify({state:'no_item', missing});
                return JSON.stringify({state:'item_clicked'});
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            when {
                result.contains("no_item") -> {
                    logEvent("Resource Builder: item resource Hero tidak ditemukan untuk kebutuhan ${pendingUpgradeCosts.joinToString(",")}")
                    pendingUpgradeUrl = ""
                    goToNextBuilderVillage()
                }
                result.contains("item_clicked") -> {
                    handler.postDelayed({ fillHeroResourceDialog() }, 600)
                }
                else -> handler.postDelayed({ useHeroInventoryForPendingUpgrade() }, 700)
            }
        }
    }

    private fun fillHeroResourceDialog() {
        if (!running || !builderInProgress || pendingUpgradeUrl.isBlank()) return
        val needed = pendingUpgradeCosts.joinToString(",")
        val js = """
            (() => {
                const needed = [$needed];
                const names = ['lumber','clay','iron','crop'];
                const inputs = [
                    document.querySelector('input[name="lumber"]'),
                    document.querySelector('input[name="clay"]'),
                    document.querySelector('input[name="iron"]'),
                    document.querySelector('input[name="crop"]')
                ];
                const setValue = (el, value) => {
                    if (!el) return false;
                    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
                    if (setter) setter.call(el, String(value)); else el.value = String(value);
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    return true;
                };
                let found = 0;
                for (let i=0;i<4;i++) if (needed[i] > 0 && setValue(inputs[i], needed[i])) found++;
                if (!found) {
                    const all = [...document.querySelectorAll('input[type=number], input[type=text]')];
                    const candidates = all.filter(x => x.offsetParent !== null && !x.disabled);
                    for (let i=0;i<4 && i<candidates.length;i++) if (needed[i] > 0) { setValue(candidates[i], needed[i]); found++; }
                }
                if (!found) return 'no_amount_inputs';
                const buttons = [...document.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]')]
                    .filter(x => x.offsetParent !== null && !x.disabled);
                const norm = x => (x || '').replace(/\s+/g,' ').trim().toLowerCase();
                const confirm = buttons.find(x => /confirm|use|transfer|send|ok|done|accept/.test(norm(x.innerText || x.textContent || x.value || x.title || x.getAttribute('aria-label'))));
                if (!confirm) return 'no_confirm';
                confirm.click();
                return 'confirmed';
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            when {
                result == "confirmed" -> {
                    logEvent("Resource Builder: resource Hero digunakan (${pendingUpgradeCosts.joinToString(",")})")
                    handler.postDelayed({
                        automationWebView()?.loadUrl(pendingUpgradeUrl)
                    }, 900)
                }
                result == "no_amount_inputs" || result == "no_confirm" -> {
                    if (inventoryUseAttempt < 5) handler.postDelayed({ fillHeroResourceDialog() }, 800)
                    else {
                        logEvent("Resource Builder: dialog penggunaan resource Hero tidak dikenali")
                        pendingUpgradeUrl = ""
                        goToNextBuilderVillage()
                    }
                }
                else -> handler.postDelayed({ fillHeroResourceDialog() }, 800)
            }
        }
    }

    private fun clickResourceUpgrade() {
        if (!running || !builderInProgress) return
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
                };
                const norm = s => (s || '').replace(/\s+/g,' ').trim().toLowerCase();
                const root = document.querySelector('#build, #villageContent') || document.body;
                const all = [...root.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]')];
                const candidates = all.filter(el => visible(el) && !el.disabled && el.getAttribute('aria-disabled') !== 'true');
                const btn = candidates.find(el => {
                    const text = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                    const cls = (el.className || '').toString().toLowerCase();
                    return /upgrade|upgrade to level|build/.test(text) && !/cancel|demolish|destroy/.test(text) && !/disabled/.test(cls);
                }) || candidates.find(el => /green/.test((el.className || '').toString().toLowerCase()) && /build|upgrade/.test((el.className || '').toString().toLowerCase()));
                if (!btn) return 'not-found';
                btn.scrollIntoView({block:'center'}); btn.click(); return 'clicked:' + (btn.innerText || btn.value || 'upgrade');
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').replace("\\\"", "\"")
            if (result.startsWith("clicked")) {
                pendingUpgradeUrl = ""
                pendingUpgradeCosts = longArrayOf(0L, 0L, 0L, 0L)
                logEvent("Resource Builder: upgrade berhasil diklik di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                handler.postDelayed({ goToNextBuilderVillage() }, 1200)
            } else if (builderAttempt < 5) {
                builderAttempt++
                handler.postDelayed({ inspectUpgradeResources() }, 900)
            } else {
                pendingUpgradeUrl = ""
                logEvent("Resource Builder: tombol upgrade tidak ditemukan di ${builderVillages.getOrNull(builderVillageIndex)?.second ?: "village ${builderVillageIndex + 1}"}")
                goToNextBuilderVillage()
            }
        }
    }

    private fun goToNextBuilderVillage() {
        builderVillageIndex++
        handler.postDelayed({ processResourceBuilderVillage() }, 700)
    }

    private fun finishResourceBuilderCycle() {
        builderInProgress = false
        builderVillages.clear()
        builderVillageIndex = 0
        logEvent("Resource Builder: siklus selesai")
        scheduleNextRandomRun()
        updateNotification("Menunggu siklus berikutnya — ${timeFormat.format(Date(nextAt))}")
    }

    private fun discoverVillagesForBuilder() {
        if (!running || !builderInProgress) return
        val js = """
            (async () => {
                const villages = [];
                const seen = new Set();
                const clean = s => String(s || '').replace(/\s+/g,' ').trim();
                const add = (value, nameHint='') => {
                    const text = String(value || '');
                    const patterns = [
                        /[?&]newdid=(\d+)/i,
                        /(?:newdid|did|villageId|village_id)[=:'" ]+(\d+)/i
                    ];
                    let id = null;
                    for (const re of patterns) {
                        const m = text.match(re);
                        if (m) { id=m[1]; break; }
                    }
                    if (id && !seen.has(id)) {
                        seen.add(id);
                        villages.push({id, name: clean(nameHint) || ('Village ' + id)});
                    }
                };
                const scan = root => {
                    if (!root) return;
                    for (const a of root.querySelectorAll('li a[href*="newdid="], a[href*="newdid="]')) {
                        const name = a.innerText || a.textContent || a.getAttribute('title') || a.getAttribute('aria-label') || '';
                        add(a.getAttribute('href'), name);
                        add(a.outerHTML, name);
                    }
                    for (const el of root.querySelectorAll('[data-did],[data-village-id],[data-villageid],[data-newdid]')) {
                        const name = el.innerText || el.textContent || el.getAttribute('title') || '';
                        add(el.getAttribute('data-did') || el.getAttribute('data-village-id') || el.getAttribute('data-villageid') || el.getAttribute('data-newdid'), name);
                    }
                    for (const el of root.querySelectorAll('[onclick],[title],[data-href]')) {
                        const name = el.innerText || el.textContent || el.getAttribute('title') || el.getAttribute('aria-label') || '';
                        add(el.getAttribute('onclick'), name);
                        add(el.getAttribute('data-href'), name);
                    }
                };

                [document.querySelector('#sidebarBoxVillagelist'),
                 document.querySelector('#villageList'),
                 document.querySelector('#villageList .list'),
                 document.querySelector('#side_info'),
                 document.body].filter(Boolean).forEach(scan);

                const current = location.search.match(/[?&]newdid=(\d+)/i);
                if (current && !seen.has(current[1])) {
                    seen.add(current[1]);
                    villages.unshift({id: current[1], name: 'Village ' + current[1]});
                }

                const bodyText = (document.body.innerText || '').replace(/\s+/g, ' ');
                let expected = 0;
                const countMatch = bodyText.match(/VILLAGES\s+(\d+)\s*\/\s*\d+/i) ||
                                   bodyText.match(/VILLAGES\s*\(?\s*(\d+)\s*\/\s*\d+\)?/i);
                if (countMatch) expected = parseInt(countMatch[1], 10) || 0;

                if (expected > 0 && villages.length < expected) {
                    try {
                        let uid = null;
                        const profileLink = [...document.querySelectorAll('a[href]')]
                            .map(a => a.getAttribute('href') || '')
                            .find(h => /spieler\.php\?uid=\d+/i.test(h));
                        if (profileLink) {
                            const m = profileLink.match(/[?&]uid=(\d+)/i);
                            if (m) uid = m[1];
                        }
                        if (uid) {
                            const response = await fetch('spieler.php?uid=' + uid, {
                                credentials: 'include', cache: 'no-store'
                            });
                            const html = await response.text();
                            const doc = new DOMParser().parseFromString(html, 'text/html');
                            for (const root of [doc.querySelector('#villageList'),
                                                doc.querySelector('#sidebarBoxVillagelist'),
                                                doc.body].filter(Boolean)) scan(root);
                        }
                    } catch (e) {}
                }

                AndroidFarm.onVillageListResult(JSON.stringify({villages, expected}));
            })().catch(e => AndroidFarm.onVillageListResult(JSON.stringify({villages:[],expected:0,error:String(e)})));
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js, null)
    }

    private fun handleVillageListResult(rawJson: String) {
        if (!running || !builderInProgress) return

        val json = runCatching { JSONObject(rawJson) }.getOrNull()
        val villages = mutableListOf<Pair<String, String>>()
        val villageArray = json?.optJSONArray("villages")
        if (villageArray != null) {
            for (i in 0 until villageArray.length()) {
                val item = villageArray.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { "Village $id" }
                if (id.isNotBlank()) villages.add(id to name)
            }
        }

        val uniqueVillages = villages.distinctBy { it.first }
        val expected = json?.optInt("expected", 0) ?: 0
        var selectedVillages = if (builderSelectionConfigured) {
            uniqueVillages.filter { selectedBuilderVillageIds.contains(it.first) }
        } else uniqueVillages

        // Gunakan daftar tersimpan dari UI bila halaman aktif tidak merender sidebar lengkap.
        if (builderSelectionConfigured && selectedVillages.size < selectedBuilderVillageIds.size) {
            val savedArray = runCatching { org.json.JSONArray(selectedBuilderVillagesJson) }.getOrNull()
            if (savedArray != null) {
                val savedVillages = mutableListOf<Pair<String, String>>()
                for (i in 0 until savedArray.length()) {
                    val item = savedArray.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim().ifBlank { "Village $id" }
                    if (id.isNotBlank() && selectedBuilderVillageIds.contains(id)) {
                        savedVillages.add(id to name)
                    }
                }
                if (savedVillages.size >= selectedBuilderVillageIds.size) selectedVillages = savedVillages
            }
        }

        if (builderSelectionConfigured && selectedBuilderVillageIds.isEmpty()) {
            builderVillages = mutableListOf()
            logEvent("Resource Builder: tidak ada village yang dicentang; Builder dilewati pada siklus ini")
            finishResourceBuilderCycle()
            return
        }

        val completeSelectedList = builderSelectionConfigured &&
            selectedBuilderVillageIds.isNotEmpty() &&
            selectedVillages.size >= selectedBuilderVillageIds.size

        if (uniqueVillages.isEmpty() || ((expected > 0 && uniqueVillages.size < expected) && !completeSelectedList)) {
            if (builderAttempt < 10) {
                builderAttempt++
                logEvent("Resource Builder: village terdeteksi ${uniqueVillages.size}${if (expected > 0) "/$expected" else ""}; retry ${builderAttempt}/10")
                handler.postDelayed({ discoverVillagesForBuilder() }, 1800)
            } else {
                logEvent("Resource Builder: daftar village belum lengkap setelah 10 retry (${uniqueVillages.size}${if (expected > 0) "/$expected" else ""}); siklus dibatalkan agar tidak memproses sebagian village")
                finishResourceBuilderCycle()
            }
            return
        }

        builderVillages = selectedVillages.toMutableList()
        builderAttempt = 0
        logEvent("Resource Builder: ${uniqueVillages.size} village ditemukan; ${selectedVillages.size} village dipilih: ${selectedVillages.joinToString(" | ") { "${it.second} [${it.first}]" }}")
        processResourceBuilderVillage()
    }

    private fun scheduleNextRandomRun() {
        if (!running) return
        val chosenMinutes = if (maxMinutes <= minMinutes) minMinutes
        else Random.nextLong(minMinutes, maxMinutes + 1)
        val delay = chosenMinutes * 60_000L
        nextAt = System.currentTimeMillis() + delay
        updateNextRun(delay)
        handler.removeCallbacks(nextRunRunnable)
        handler.postDelayed(nextRunRunnable, delay)
        logEvent("Interval berikutnya dipilih acak: $chosenMinutes menit")
    }

    private val nextRunRunnable = Runnable {
        if (running) triggerScheduledCycle()
    }

    private fun updateNextRun(delayMs: Long) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("next_run_at", if (delayMs == 0L) 0L else nextAt)
            .apply()
    }

    private fun acceptCookiesIfPresent(done: (String) -> Unit) {
        val js = """
            (() => {
              const visible = el => {
                if (!el) return false; const s = getComputedStyle(el); const r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
              const roots = [document];
              for (let i=0;i<roots.length;i++) {
                let els=[]; try { els=[...roots[i].querySelectorAll('*')]; } catch(_) {}
                for (const el of els) if (el.shadowRoot && !roots.includes(el.shadowRoot)) roots.push(el.shadowRoot);
              }
              const selectors=['#cmpwelcomebtnyes a','#cmpwelcomebtnyes','.cmpboxbtnyes','#cmpbntyestxt','[class*="cmpboxbtnyes"]','[id*="cmpwelcomebtnyes"]'];
              let bannerVisible=false;
              for (const root of roots) {
                try { const box=root.querySelector('#cmpbox,#cmpbox2,.cmpbox,.cmpmore'); if(box&&visible(box)) bannerVisible=true; } catch(_){}
                for(const sel of selectors){ let el=null; try{el=root.querySelector(sel);}catch(_){} if(el&&visible(el)){try{el.click();return 'clicked';}catch(_){} } }
                let candidates=[]; try{candidates=[...root.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button]')];}catch(_){}
                const accept=candidates.find(el=>visible(el)&&/^(accept all|accept all cookies|allow all|agree all|alle akzeptieren|tout accepter|aceptar todo)$/.test(norm(el.innerText||el.textContent||el.value||el.title||el.getAttribute('aria-label'))));
                if(accept){try{accept.click();return 'clicked';}catch(_){} }
              }
              const host=document.querySelector('#cmpwrapper'); if(host&&visible(host)) bannerVisible=true;
              return bannerVisible?'visible':'absent';
            })();
        """.trimIndent()
        automationWebView()?.evaluateJavascript(js) { raw -> done(raw.orEmpty().trim('"').lowercase(Locale.US)) }
    }

    private fun stopAutomation() {
        running = false
        pendingStartAll = false
        handler.removeCallbacksAndMessages(null)
        if (webView != null) {
            webView?.destroy()
            webView = null
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("service_running", false)
            .putLong("next_run_at", 0L)
            .putBoolean("farm_list_enabled", farmListEnabled)
            .putBoolean("resource_builder_enabled", resourceBuilderEnabled)
            .apply()
        logEvent("Background service dihentikan")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Travian Farm Assistant — AKTIF")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pending)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Travian Farm Assistant — AKTIF")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pending)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Farm Assistant Background", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun normalizeServer(value: String): String {
        var s = value.trim()
        if (s.isBlank()) s = "https://ts20.x2.europe.travian.com"
        if (!s.startsWith("http")) s = "https://$s"
        return s.trimEnd('/')
    }

    private fun logEvent(message: String) {
        val line = "${logTimeFormat.format(Date())} | $message"
        try {
            openFileOutput(logFileName, MODE_APPEND).bufferedWriter().use { it.appendLine(line) }
            pruneLogs()
        } catch (_: Exception) {}
    }

    private fun pruneLogs() {
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) return
            val cutoff = System.currentTimeMillis() - logMaxAgeMs
            val kept = file.readLines().filter { line ->
                try { logTimeFormat.parse(line.substringBefore(" | "))?.time ?: 0L >= cutoff }
                catch (_: Exception) { false }
            }
            file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        } catch (_: Exception) {}
    }

    inner class FarmBridge {
        @JavascriptInterface
        fun onLoginResult(result: String) {
            handler.post {
                if (!running) return@post
                when (result) {
                    "submitting" -> updateNotification("Farm Assistant — mengirim login")
                    "no_login_form" -> {
                        loginInProgress = false
                        reloginRequested = false
                        loginRetryCount = 0
                        logEvent("Session aktif terdeteksi; membuka Farm List")
                        handler.postDelayed({ triggerStartAllFarmLists() }, 250)
                    }
                    "no_username_field", "no_form" -> {
                        if (loginRetryCount < 20 && running) handler.postDelayed({ autoLoginIfNeeded() }, 1000)
                        else {
                            loginInProgress = false
                            reloginRequested = false
                            logEvent("Form login Travian tidak dikenali")
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun onVillageListResult(result: String) {
            handler.post {
                handleVillageListResult(result)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        webView = null
        instanceRef = null
        visibleWebViewRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
