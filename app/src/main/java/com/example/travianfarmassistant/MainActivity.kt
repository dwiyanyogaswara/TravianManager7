package com.example.travianfarmassistant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var farmStatus: TextView
    private lateinit var status: TextView
    private lateinit var lastRun: TextView
    private lateinit var nextRun: TextView
    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var villageChecklist: LinearLayout
    private var loadedVillages = linkedMapOf<String, String>()

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var nextAt = 0L
    private lateinit var minIntervalInput: EditText
    private lateinit var maxIntervalInput: EditText
    private var loginInProgress = false
    private var loginRetryCount = 0
    private var reloginRequested = false
    private var farmListRequested = false
    private var pendingStartAll = false
    private var startAllAttempt = 0
    private var pendingUsername = ""
    private var pendingPassword = ""
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val logFileName = "farm_assistant.log"
    private val logMaxAgeMs = 12 * 60 * 60 * 1000L
    private val logCleanup = object : Runnable {
        override fun run() {
            pruneLogs()
            handler.postDelayed(this, 60 * 60 * 1000L)
        }
    }

    private val countdownUpdater = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverInput = findViewById(R.id.server)
        usernameInput = findViewById(R.id.username)
        passwordInput = findViewById(R.id.password)
        farmStatus = findViewById(R.id.farmListStatus)
        status = findViewById(R.id.status)
        lastRun = findViewById(R.id.lastRun)
        nextRun = findViewById(R.id.nextRun)
        webView = findViewById(R.id.webView)
        pruneLogs()
        handler.postDelayed(logCleanup, 60 * 60 * 1000L)
        handler.post(countdownUpdater)
        logEvent("Aplikasi v4.8 dimulai")

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        serverInput.setText(prefs.getString("server", "https://ts20.x2.europe.travian.com"))
        usernameInput.setText(prefs.getString("username", ""))

        minIntervalInput = findViewById(R.id.intervalMin)
        maxIntervalInput = findViewById(R.id.intervalMax)
        val savedMin = prefs.getLong("interval_min_minutes", 5L)
        val savedMax = prefs.getLong("interval_max_minutes", savedMin)
        minIntervalInput.setText(savedMin.toString())
        maxIntervalInput.setText(savedMax.toString())

        val farmListEnabledCheck = findViewById<CheckBox>(R.id.farmListEnabled)
        val resourceBuilderCheck = findViewById<CheckBox>(R.id.resourceBuilder)
        farmListEnabledCheck.isChecked = prefs.getBoolean("farm_list_enabled", true)
        resourceBuilderCheck.isChecked = prefs.getBoolean("resource_builder_enabled", true)

        villageChecklist = findViewById(R.id.villageChecklist)
        restoreVillageSelection(prefs)

        findViewById<Button>(R.id.loadVillages).setOnClickListener {
            loadVillagesForUi()
        }

        CookieManager.getInstance().setAcceptCookie(true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // Live WebView selalu memakai User-Agent desktop + wide viewport supaya
        // halaman Travian dirender seperti desktop dan area Farm List lebih lengkap.
        webView.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.textZoom = 100
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(FarmBridge(), "AndroidFarm")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return

                if (FarmAutomationService.isRunningFromService()) {
                    FarmAutomationService.forwardPageFinished(url)
                    return
                }

                val lower = url.lowercase(Locale.US)

                // ConsentManager yang dipakai situs dapat berada di Shadow DOM.
                // Jangan lanjut login / parsing Farm List sampai layer consent benar-benar hilang.
                handlePageAfterConsent(url, lower, 0)
            }
        }

        FarmAutomationService.attachVisibleWebView(webView)

        findViewById<Button>(R.id.loginTravian).setOnClickListener {
            logEvent("Tombol LOGIN / BUKA FARM LIST ditekan")
            startAutomaticLogin()
        }

        findViewById<Button>(R.id.openFarm).setOnClickListener {
            logEvent("Tombol BUKA FARM LIST ditekan")
            openFarmList()
        }

        findViewById<Button>(R.id.openLogs).setOnClickListener {
            startActivity(android.content.Intent(this, LogActivity::class.java))
        }

        findViewById<Button>(R.id.start).setOnClickListener {
            val minMinutes = minIntervalInput.text.toString().trim().toLongOrNull()
            val maxMinutes = maxIntervalInput.text.toString().trim().toLongOrNull()
            if (minMinutes == null || minMinutes < 1) {
                minIntervalInput.error = "Minimum 1 menit"
                minIntervalInput.requestFocus()
                farmStatus.text = "Interval minimum tidak valid."
                logEvent("Background service gagal dimulai: minimum interval tidak valid")
                return@setOnClickListener
            }
            if (maxMinutes == null || maxMinutes < minMinutes) {
                maxIntervalInput.error = "Harus >= minimum"
                maxIntervalInput.requestFocus()
                farmStatus.text = "Interval maksimum harus >= minimum."
                logEvent("Background service gagal dimulai: maksimum interval tidak valid")
                return@setOnClickListener
            }

            val server = normalizeServer(serverInput.text.toString())
            val farmListEnabled = farmListEnabledCheck.isChecked
            val resourceBuilderEnabled = resourceBuilderCheck.isChecked
            val user = usernameInput.text.toString().trim()
            val pass = passwordInput.text.toString()
            if (user.isBlank() || pass.isBlank()) {
                farmStatus.text = "Username dan password harus diisi sebelum MULAI."
                logEvent("Background service gagal dimulai: username/password kosong")
                return@setOnClickListener
            }

            getSharedPreferences("config", MODE_PRIVATE).edit()
                .putString("server", server)
                .putString("username", user)
                .putLong("interval_min_minutes", minMinutes)
                .putLong("interval_max_minutes", maxMinutes)
                .putBoolean("farm_list_enabled", farmListEnabled)
                .putBoolean("resource_builder_enabled", resourceBuilderEnabled)
                .putBoolean("resource_builder_selection_configured", loadedVillages.isNotEmpty())
                .putStringSet("resource_builder_selected_villages", selectedVillageIds())
                .putString("resource_builder_villages_json", villageSelectionJson())
                .apply()

            pendingUsername = user
            pendingPassword = pass
            running = true
            status.text = "Status: RUNNING — BACKGROUND"
            farmStatus.text = "Background automation sedang dimulai..."
            logEvent("Memulai background automation. Range=${minMinutes}-${maxMinutes} menit; Farm List=${if (farmListEnabled) "ON" else "OFF"}; Resource Builder=${if (resourceBuilderEnabled) "ON" else "OFF"}")

            val intent = android.content.Intent(this, FarmAutomationService::class.java).apply {
                action = FarmAutomationService.ACTION_START
                putExtra(FarmAutomationService.EXTRA_SERVER, server)
                putExtra(FarmAutomationService.EXTRA_USERNAME, user)
                putExtra(FarmAutomationService.EXTRA_PASSWORD, pass)
                putExtra(FarmAutomationService.EXTRA_MINUTES_MIN, minMinutes)
                putExtra(FarmAutomationService.EXTRA_MINUTES_MAX, maxMinutes)
                putExtra(FarmAutomationService.EXTRA_FARM_LIST_ENABLED, farmListEnabled)
                putExtra(FarmAutomationService.EXTRA_RESOURCE_BUILDER, resourceBuilderEnabled)
                putExtra(FarmAutomationService.EXTRA_SELECTED_VILLAGES, selectedVillageIds().toTypedArray())
                putExtra(FarmAutomationService.EXTRA_SELECTED_VILLAGES_JSON, villageSelectionJson())
                putExtra(FarmAutomationService.EXTRA_SELECTION_CONFIGURED, loadedVillages.isNotEmpty())
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            logEvent("Background automation dihentikan")
            stopScheduler()
        }

        createNotificationChannel()
    }

    /**
     * Login sekarang benar-benar otomatis:
     * 1. Ambil username/password dari form.
     * 2. Buka server.
     * 3. Jika session masih aktif -> langsung Farm List.
     * 4. Jika belum login -> cari form login dan submit dari WebView.
     * 5. Setelah redirect sukses -> otomatis membuka Farm List.
     *
     * Password hanya disimpan di RAM selama aplikasi hidup dan tidak ditulis
     * ke SharedPreferences. Ini memungkinkan auto re-login ketika session
     * Travian expired, selama proses aplikasi masih berjalan.
     */
    private fun startAutomaticLogin() {
        val server = normalizeServer(serverInput.text.toString())
        pendingUsername = usernameInput.text.toString().trim()
        pendingPassword = passwordInput.text.toString()

        if (pendingUsername.isBlank() || pendingPassword.isBlank()) {
            farmStatus.text = "Username dan password harus diisi."
            logEvent("Login gagal: username/password kosong")
            return
        }

        getSharedPreferences("config", MODE_PRIVATE)
            .edit()
            .putString("server", server)
            .putString("username", pendingUsername)
            .apply()

        loginInProgress = true
        reloginRequested = false
        loginRetryCount = 0
        farmListRequested = false
        farmStatus.text = "Menghubungkan ke Travian..."
        logEvent("Memulai login ke $server sebagai $pendingUsername")

        webView.loadUrl(server)
    }

    private fun isLikelyLoginPage(url: String): Boolean {
        return url.contains("login") ||
            url.contains("logout") ||
            url.contains("anmelden") ||
            url.contains("signin")
    }

    private fun autoLoginIfNeeded() {
        if (!loginInProgress) return

        if (pendingUsername.isBlank() || pendingPassword.isBlank()) {
            loginInProgress = false
            farmStatus.text = "Tidak bisa auto re-login: username/password tidak tersedia."
            return
        }

        loginRetryCount++
        if (loginRetryCount > 20) {
            loginInProgress = false
            reloginRequested = false
            farmStatus.text = "Auto re-login gagal setelah beberapa percobaan. Silakan login manual."
            logEvent("Auto re-login gagal setelah 20 percobaan")
            return
        }

        val usernameJson = JSONObject.quote(pendingUsername)
        val passwordJson = JSONObject.quote(pendingPassword)

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
                    (x.type || '').toLowerCase() === 'password' ||
                    /pass|password/i.test(x.name || '') ||
                    /pass|password/i.test(x.id || '')
                );

                if (!passwordInput) {
                    AndroidFarm.onLoginResult('no_login_form');
                    return;
                }

                const userInput = inputs.find(x =>
                    /user|username|email|login|name/i.test(x.name || '') ||
                    /user|username|email|login|name/i.test(x.id || '') ||
                    (x.type || '').toLowerCase() === 'email'
                );

                if (!userInput) {
                    AndroidFarm.onLoginResult('no_username_field');
                    return;
                }

                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(
                        Object.getPrototypeOf(el), 'value'
                    )?.set;
                    if (setter) setter.call(el, value); else el.value = value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                };

                setValue(userInput, username);
                setValue(passwordInput, password);

                const form = passwordInput.closest('form') || userInput.closest('form');
                if (!form) {
                    AndroidFarm.onLoginResult('no_form');
                    return;
                }

                const buttons = [...form.querySelectorAll('button,input[type=submit],input[type=button],a')]
                    .filter(visible);

                const submitButton = buttons.find(x =>
                    /login|log in|sign in|anmelden|connexion|entrar|acceder/i.test(
                        (x.innerText || x.value || x.title || '').trim()
                    )
                );

                AndroidFarm.onLoginResult('submitting');

                if (submitButton) {
                    submitButton.click();
                } else if (typeof form.requestSubmit === 'function') {
                    form.requestSubmit();
                } else {
                    form.submit();
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun villageSelectionJson(): String {
        val selected = selectedVillageIds()
        val array = org.json.JSONArray()
        loadedVillages.forEach { (id, name) ->
            if (selected.contains(id)) {
                array.put(JSONObject().apply { put("id", id); put("name", name) })
            }
        }
        return array.toString()
    }

    private fun selectedVillageIds(): Set<String> {
        if (!::villageChecklist.isInitialized) return emptySet()
        return (0 until villageChecklist.childCount)
            .mapNotNull { villageChecklist.getChildAt(it) as? CheckBox }
            .filter { it.isChecked }
            .mapNotNull { it.tag?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .toSet()
    }

    private fun restoreVillageSelection(prefs: android.content.SharedPreferences) {
        // UI akan diisi ulang ketika daftar village berhasil dibaca.
        // Selection lama dipakai sebagai acuan centang berdasarkan ID village.
        loadedVillages.clear()
    }

    private fun renderVillageChecklist(villages: List<Pair<String, String>>) {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val saved = prefs.getStringSet("resource_builder_selected_villages", emptySet()) ?: emptySet()
        val configured = prefs.getBoolean("resource_builder_selection_configured", false)

        loadedVillages.clear()
        villages.distinctBy { it.first }.forEach { (id, name) ->
            if (id.isNotBlank()) loadedVillages[id] = name.ifBlank { "Village $id" }
        }

        villageChecklist.removeAllViews()
        if (loadedVillages.isEmpty()) {
            villageChecklist.addView(TextView(this).apply {
                text = "Village belum ditemukan. Buka Travian lalu tekan LOAD VILLAGE."
                setPadding(8, 8, 8, 8)
            })
            return
        }

        val selectAll = CheckBox(this).apply {
            text = "PILIH SEMUA VILLAGE"
            isChecked = if (configured) loadedVillages.keys.all { saved.contains(it) } else true
            setOnCheckedChangeListener { _, checked ->
                for (i in 1 until villageChecklist.childCount) {
                    (villageChecklist.getChildAt(i) as? CheckBox)?.isChecked = checked
                }
            }
        }
        villageChecklist.addView(selectAll)

        loadedVillages.forEach { (id, name) ->
            villageChecklist.addView(CheckBox(this).apply {
                text = name
                tag = id
                isChecked = if (configured) saved.contains(id) else true
                setOnCheckedChangeListener { _, _ ->
                    // Simpan segera agar pilihan tidak hilang ketika Activity ditutup.
                    getSharedPreferences("config", MODE_PRIVATE).edit()
                        .putBoolean("resource_builder_selection_configured", true)
                        .putStringSet("resource_builder_selected_villages", selectedVillageIds())
                        .putString("resource_builder_villages_json", villageSelectionJson())
                        .apply()
                }
            })
        }

        logEvent("UI: ${loadedVillages.size} village dimuat: ${loadedVillages.values.joinToString(" | ")}")
    }

    private fun loadVillagesForUi() {
        farmStatus.text = "Memuat daftar village..."
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
                    if (/^\d+$/.test(text.trim())) {
                        // data-did contains the numeric ID directly.
                        id = text.trim();
                    } else {
                        for (const re of patterns) {
                            const m = text.match(re);
                            if (m) { id=m[1]; break; }
                        }
                    }
                    if (id && !seen.has(id)) {
                        seen.add(id);
                        villages.push({id:id,name:clean(nameHint)||('Village '+id)});
                    }
                };
                const scan = root => {
                    if (!root) return;

                    // Travian T4/T5: village entries are .listEntry and the
                    // village ID is stored in data-did. Do not depend only on
                    // href containing newdid because newer village-list markup
                    // can use data-did on the entry itself.
                    const entries = root.querySelectorAll('.listEntry');
                    for (const entry of entries) {
                        const id = entry.getAttribute('data-did') ||
                                   entry.dataset.did ||
                                   entry.getAttribute('data-village-id') ||
                                   entry.getAttribute('data-villageid');
                        const nameEl = entry.querySelector('.name');
                        const link = entry.querySelector('a[href]');
                        const name = clean(
                            nameEl ? (nameEl.textContent || '') :
                            (entry.getAttribute('data-name') || '')
                        );
                        if (id) add(id, name);
                        if (link) {
                            add(link.getAttribute('href'), name);
                            add(link.outerHTML, name);
                        }
                    }

                    // Older Travian markup / profile village list.
                    for (const a of root.querySelectorAll('a[href*="newdid="], a[title][href]')) {
                        const name = clean(
                            a.querySelector('.name')?.textContent ||
                            a.getAttribute('title') ||
                            a.textContent ||
                            a.getAttribute('aria-label') || ''
                        );
                        add(a.getAttribute('href'), name);
                    }

                    // Last-resort data attributes used by some layouts.
                    for (const el of root.querySelectorAll('[data-did],[data-village-id],[data-villageid],[data-newdid]')) {
                        const id = el.getAttribute('data-did') ||
                                   el.getAttribute('data-village-id') ||
                                   el.getAttribute('data-villageid') ||
                                   el.getAttribute('data-newdid');
                        const nameEl = el.querySelector?.('.name');
                        const name = clean(
                            nameEl ? nameEl.textContent :
                            el.getAttribute('title') ||
                            el.textContent || ''
                        );
                        add(id, name);
                    }
                };

                [document.querySelector('#sidebarBoxVillagelist'),
                 document.querySelector('#villageList'),
                 document.querySelector('#side_info'),
                 document.body].filter(Boolean).forEach(scan);

                const current = location.search.match(/[?&]newdid=(\d+)/i);
                if (current && !seen.has(current[1])) {
                    seen.add(current[1]);
                    villages.unshift({id:current[1],name:'Village '+current[1]});
                }

                const bodyText = (document.body.innerText || '').replace(/\s+/g,' ');
                let expected = 0;
                const countMatch = bodyText.match(/VILLAGES\s+(\d+)\s*\/\s*\d+/i) ||
                                   bodyText.match(/VILLAGES\s*\(?\s*(\d+)\s*\/\s*\d+\)?/i);
                if (countMatch) expected = parseInt(countMatch[1], 10) || 0;

                // Farm List/mobile layout kadang tidak merender sidebar village.
                // Ambil uid dari link profile lalu fetch halaman pemain.
                if (expected > 0 && villages.length < expected) {
                    try {
                        let uid = null;
                        const links = [...document.querySelectorAll('a[href]')];
                        const profileLink = links.map(a => a.getAttribute('href') || '')
                            .find(h => /spieler\.php\?uid=\d+/i.test(h));
                        if (profileLink) {
                            const m = profileLink.match(/[?&]uid=(\d+)/i);
                            if (m) uid = m[1];
                        }
                        if (uid) {
                            const response = await fetch('spieler.php?uid=' + uid, {
                                credentials:'include', cache:'no-store'
                            });
                            const html = await response.text();
                            const doc = new DOMParser().parseFromString(html, 'text/html');
                            for (const root of [doc.querySelector('#villageList'),
                                                doc.querySelector('#sidebarBoxVillagelist'),
                                                doc.body].filter(Boolean)) scan(root);

                            // Profile pages have a dedicated village list. Parse each
                            // item explicitly so grouped/hidden sidebar villages are
                            // not lost.
                            for (const a of doc.querySelectorAll('#villageList .list li a[title][href], #villageList li a[href*="newdid="]')) {
                                const name = clean(a.getAttribute('title') || a.textContent || '');
                                add(a.getAttribute('href'), name);
                            }
                        }
                    } catch (e) {}
                }

                AndroidFarm.onVillageListResult(JSON.stringify({villages:villages,expected:expected}));
            })().catch(e => AndroidFarm.onVillageListResult(JSON.stringify({villages:[],expected:0,error:String(e)})));
        """.trimIndent()

        // Penting: jangan memakai callback evaluateJavascript untuk hasil async.
        // WebView tidak menunggu Promise; JS mengirim hasil lewat AndroidFarm.
        webView.evaluateJavascript(js, null)
    }

    private fun handleVillageListResult(rawJson: String) {
        val json = runCatching { JSONObject(rawJson) }.getOrNull()
        val villages = mutableListOf<Pair<String,String>>()
        val array = json?.optJSONArray("villages")
        if (array != null) for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim().ifBlank { "Village $id" }
            if (id.isNotBlank()) villages.add(id to name)
        }
        val unique = villages.distinctBy { it.first }
        val expected = json?.optInt("expected", 0) ?: 0
        if (unique.isEmpty()) {
            farmStatus.text = "Village belum terlihat. Pastikan sudah login di Live WebView."
            logEvent("UI: daftar village tidak ditemukan dari halaman aktif")
        } else {
            renderVillageChecklist(unique)
            farmStatus.text = if (expected > 0 && unique.size < expected) {
                "${unique.size}/$expected village terbaca; masih belum lengkap."
            } else {
                "${unique.size} village siap dipilih untuk Resource Builder."
            }
        }
    }

    private fun openFarmList() {
        val server = normalizeServer(serverInput.text.toString())
        farmStatus.text = "Membuka halaman Farm List..."
        farmListRequested = true
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
    }

    /**
     * ConsentManager (consentmanager.net) sering memasang UI di #cmpwrapper.shadowRoot.
     * querySelector biasa dari document tidak akan melihat tombol di dalam Shadow DOM.
     * Karena itu kita cari di document + semua open shadowRoot dan baru lanjut setelah
     * banner tidak terlihat lagi.
     */
    private fun handlePageAfterConsent(url: String, lower: String, attempt: Int) {
        acceptCookiesIfPresent { result ->
            val consentStillVisible = result.contains("visible") || result.contains("clicked")

            if (consentStillVisible && attempt < 8) {
                farmStatus.text = if (result.contains("clicked")) {
                    "Cookie consent ditemukan. Menerima cookies..."
                } else {
                    "Menunggu cookie consent ditutup..."
                }
                CookieManager.getInstance().flush()
                handler.postDelayed({ handlePageAfterConsent(url, lower, attempt + 1) }, 700)
                return@acceptCookiesIfPresent
            }

            if (lower.contains("gid=16") && lower.contains("tt=99")) {
                loginInProgress = false
                reloginRequested = false
                loginRetryCount = 0
                farmListRequested = true
                farmStatus.text = "Halaman Farm List siap. Tidak perlu membaca daftar Farm List."
                logEvent("Farm List siap; tidak melakukan parsing daftar")
                if (pendingStartAll) {
                    startAllAttempt = 0
                    handler.postDelayed({ clickStartAllFarmLists() }, 1200)
                }
                return@acceptCookiesIfPresent
            }

            // Jika scheduler aktif dan Travian mengarahkan kembali ke halaman login,
            // lakukan re-login otomatis menggunakan kredensial yang masih tersedia di RAM.
            if (isLikelyLoginPage(lower)) {
                if (pendingUsername.isNotBlank() && pendingPassword.isNotBlank()) {
                    if (!loginInProgress || reloginRequested) {
                        loginInProgress = true
                        reloginRequested = true
                        loginRetryCount = 0
                        farmStatus.text = "Session Travian habis. Melakukan auto re-login..."
                    logEvent("Session Travian habis; memulai auto re-login")
                    }
                    handler.postDelayed({ autoLoginIfNeeded() }, 500)
                } else {
                    loginInProgress = false
                    reloginRequested = false
                    farmStatus.text = "Session habis. Password tidak tersedia untuk auto re-login."
                    logEvent("Session habis tetapi password tidak tersedia di RAM")
                }
                return@acceptCookiesIfPresent
            }

            if (loginInProgress) {
                handler.postDelayed({ autoLoginIfNeeded() }, 500)
            } else if (running && pendingStartAll) {
                // Session Travian kadang expired tanpa mengubah URL menjadi login.php.
                // Cek DOM untuk form password agar auto re-login tetap terpicu.
                detectLoginFormForScheduler()
            }
        }
    }

    private fun detectLoginFormForScheduler() {
        val js = """
            (() => {
                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden' &&
                           r.width > 0 && r.height > 0;
                };
                const hasPassword = [...document.querySelectorAll('input[type=password]')]
                    .some(visible);
                return hasPassword ? 'login_form' : 'not_login';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            if (raw.orEmpty().contains("login_form")) {
                loginInProgress = true
                reloginRequested = true
                loginRetryCount = 0
                farmStatus.text = "Session Travian habis. Melakukan auto re-login..."
                handler.postDelayed({ autoLoginIfNeeded() }, 250)
            }
        }
    }

    /** Klik Accept All, termasuk jika tombol berada di Shadow DOM ConsentManager. */
    private fun acceptCookiesIfPresent(done: (String) -> Unit) {
        val js = """
            (() => {
              const visible = el => {
                if (!el) return false;
                const s = getComputedStyle(el);
                const r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();

              // Ambil semua root yang dapat diakses: document + open Shadow DOM bertingkat.
              const roots = [document];
              for (let i = 0; i < roots.length; i++) {
                const root = roots[i];
                let els = [];
                try { els = [...root.querySelectorAll('*')]; } catch (_) {}
                for (const el of els) {
                  if (el.shadowRoot && !roots.includes(el.shadowRoot)) roots.push(el.shadowRoot);
                }
              }

              // ConsentManager dikenal memakai #cmpwrapper dengan shadowRoot dan
              // #cmpwelcomebtnyes / .cmpboxbtnyes untuk tombol opt-in.
              const selectors = [
                '#cmpwelcomebtnyes a',
                '#cmpwelcomebtnyes',
                '.cmpboxbtnyes',
                '#cmpbntyestxt',
                '[class*="cmpboxbtnyes"]',
                '[id*="cmpwelcomebtnyes"]'
              ];

              let bannerVisible = false;
              for (const root of roots) {
                try {
                  const box = root.querySelector('#cmpbox, #cmpbox2, .cmpbox, .cmpmore');
                  if (box && visible(box)) bannerVisible = true;
                } catch (_) {}

                for (const sel of selectors) {
                  let el = null;
                  try { el = root.querySelector(sel); } catch (_) {}
                  if (el && visible(el)) {
                    try {
                      el.click();
                      return 'clicked';
                    } catch (_) {}
                  }
                }

                // Fallback berbasis teks untuk varian markup lain.
                let candidates = [];
                try {
                  candidates = [...root.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button]')];
                } catch (_) {}
                const accept = candidates.find(el => {
                  if (!visible(el)) return false;
                  const text = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                  return /^(accept all|accept all cookies|allow all|agree all|alle akzeptieren|tout accepter|aceptar todo)$/.test(text);
                });
                if (accept) {
                  try {
                    accept.click();
                    return 'clicked';
                  } catch (_) {}
                }
              }

              // #cmpwrapper sendiri mungkin host Shadow DOM, jadi kehadirannya juga dicek.
              const host = document.querySelector('#cmpwrapper');
              if (host && visible(host)) bannerVisible = true;

              return bannerVisible ? 'visible' : 'absent';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').lowercase(Locale.US)
            done(result)
        }
    }

    /**
     * Setiap periode kita tidak membaca nama/ID Farm List sama sekali.
     * App selalu membuka halaman Farm List, lalu menekan tombol "Start all"
     * begitu React selesai merender tombolnya.
     */
    private fun triggerStartAllFarmLists() {
        if (!running) return

        pendingStartAll = true
        startAllAttempt = 0
        val server = normalizeServer(serverInput.text.toString())

        if (pendingUsername.isBlank()) pendingUsername = usernameInput.text.toString().trim()
        if (pendingPassword.isBlank()) pendingPassword = passwordInput.text.toString()

        if (pendingUsername.isBlank() || pendingPassword.isBlank()) {
            pendingStartAll = false
            farmStatus.text = "Username dan password diperlukan untuk Start All otomatis."
            status.text = "Status: RUNNING — menunggu login"
            return
        }
        farmStatus.text = "Menyiapkan Start All Farm Lists..."
        logEvent("Memulai siklus Start All Farm Lists")

        // Selalu kembali ke Farm List agar tetap bekerja walaupun pengguna
        // sebelumnya membuka halaman Travian lain di WebView.
        farmListRequested = true
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
    }

    private fun clickStartAllFarmLists() {
        if (!running || !pendingStartAll) return

        val js = """
            (() => {
              const visible = el => {
                if (!el) return false;
                const s = getComputedStyle(el);
                const r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' &&
                       r.width > 0 && r.height > 0;
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();

              const selectors = [
                'button.startAllFarmLists',
                '.startAllFarmLists button',
                '.startAllFarmLists',
                'button[class*="startAllFarm"]',
                '[class*="startAllFarmLists"]'
              ];

              for (const selector of selectors) {
                let nodes = [];
                try { nodes = [...document.querySelectorAll(selector)]; } catch (_) {}
                const btn = nodes.find(el => visible(el) && !el.disabled &&
                  el.getAttribute('aria-disabled') !== 'true');
                if (btn) {
                  btn.scrollIntoView({ block: 'center' });
                  btn.click();
                  return 'clicked-selector:' + selector;
                }
              }

              // Fallback jika class Travian berubah lagi: cari berdasarkan teks tombol.
              const candidates = [...document.querySelectorAll(
                'button, input[type=button], input[type=submit], a, [role=button]'
              )];
              const textBtn = candidates.find(el => {
                if (!visible(el) || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
                const t = norm(el.innerText || el.textContent || el.value || el.title ||
                               el.getAttribute('aria-label'));
                return /^(start all|start all farm lists?|start all farmlists?|send all)$/.test(t) ||
                       /start all.*farm/i.test(t);
              });

              if (textBtn) {
                textBtn.scrollIntoView({ block: 'center' });
                textBtn.click();
                return 'clicked-text';
              }

              return 'not-found';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"')
            if (result.startsWith("clicked")) {
                pendingStartAll = false
                startAllAttempt = 0
                val now = timeFormat.format(Date())
                lastRun.text = "Last run: $now"
                farmStatus.text = "Start All Farm Lists ditekan pada $now"
                logEvent("Start All Farm Lists berhasil ditekan")
                status.text = "Status: RUNNING — Start All berhasil"
            } else if (startAllAttempt < 10) {
                startAllAttempt++
                farmStatus.text = "Menunggu tombol Start All Farm Lists... (${startAllAttempt}/10)"
                handler.postDelayed({ clickStartAllFarmLists() }, 1000)
            } else {
                pendingStartAll = false
                lastRun.text = "Last run: ${timeFormat.format(Date())}"
                farmStatus.text = "Tombol Start All Farm Lists tidak ditemukan setelah menunggu halaman selesai dimuat."
                logEvent("Start All gagal: tombol tidak ditemukan setelah 10 percobaan")
                status.text = "Status: RUNNING — Start All gagal"
            }
        }
    }

    private fun updateNextRun() {
        nextRun.text = "Next run: ${timeFormat.format(Date(nextAt))}"
    }

    private fun stopScheduler() {
        running = false
        pendingStartAll = false
        val intent = android.content.Intent(this, FarmAutomationService::class.java).apply {
            action = FarmAutomationService.ACTION_STOP
        }
        startService(intent)
        status.text = "Status: STOPPED"
        logEvent("Status STOPPED")
        nextRun.text = "Next run: --"
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
            openFileOutput(logFileName, MODE_APPEND).bufferedWriter().use {
                it.appendLine(line)
            }
            pruneLogs()
        } catch (_: Exception) {
            // Logging must never interrupt the automation.
        }
    }

    private fun pruneLogs() {
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) return
            val cutoff = System.currentTimeMillis() - logMaxAgeMs
            val kept = file.readLines().filter { line ->
                try {
                    val stamp = line.substringBefore(" | ")
                    val time = logTimeFormat.parse(stamp)?.time ?: return@filter false
                    time >= cutoff
                } catch (_: Exception) {
                    false
                }
            }
            file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        } catch (_: Exception) {
            // Ignore cleanup errors; automation continues normally.
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("farm", "Farm reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    inner class FarmBridge {
        @JavascriptInterface
        fun onLoginResult(result: String) {
            if (FarmAutomationService.isRunningFromService()) {
                FarmAutomationService.forwardLoginResult(result)
                return
            }
            runOnUiThread {
                when (result) {
                    "submitting" -> {
                        farmStatus.text = "Mengirim login ke Travian..."
                    }
                    "no_login_form" -> {
                        // Tidak ada form password berarti kemungkinan session masih aktif.
                        loginInProgress = false
                        reloginRequested = false
                        loginRetryCount = 0
                        farmStatus.text = "Session ditemukan. Membuka Farm List..."
                        handler.postDelayed({ openFarmList() }, 250)
                    }
                    "no_username_field", "no_form" -> {
                        if (loginRetryCount < 20 && (running || reloginRequested)) {
                            farmStatus.text = "Menunggu form login Travian... ($loginRetryCount/20)"
                            handler.postDelayed({ autoLoginIfNeeded() }, 1000)
                        } else {
                            farmStatus.text = "Form login Travian tidak dikenali. Silakan cek halaman login."
                            loginInProgress = false
                            reloginRequested = false
                        }
                    }
                }
            }
        }


    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val serviceRunning = prefs.getBoolean("service_running", false)
        if (serviceRunning) {
            status.text = "Status: RUNNING — BACKGROUND"
            updateCountdown()
        }
    }

    private fun updateCountdown() {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val serviceRunning = prefs.getBoolean("service_running", false)
        val next = prefs.getLong("next_run_at", 0L)
        if (!serviceRunning || next <= 0L) {
            nextRun.text = "Next run: --"
            return
        }

        val remaining = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val totalSeconds = remaining / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val target = timeFormat.format(Date(next))
        nextRun.text = String.format(
            Locale.getDefault(),
            "Next run: %s | Countdown: %02d:%02d:%02d",
            target, hours, minutes, seconds
        )

        if (remaining == 0L) {
            status.text = "Status: RUNNING — menunggu siklus berikutnya"
        } else {
            status.text = "Status: RUNNING — BACKGROUND"
        }

        @JavascriptInterface
        fun onVillageListResult(result: String) {
            handler.post { handleVillageListResult(result) }
        }
    }

    override fun onDestroy() {
        FarmAutomationService.detachVisibleWebView(webView)
        FarmAutomationService.onVisibleWebViewDetached()
        handler.removeCallbacks(countdownUpdater)
        logEvent("MainActivity ditutup; background service tetap dapat berjalan")
        super.onDestroy()
    }
}
