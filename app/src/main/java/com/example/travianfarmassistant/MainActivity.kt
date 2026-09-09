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

    // Scanner village UI: setelah daftar link ditemukan, WebView benar-benar
    // berpindah ke village satu per satu agar nama + resource dibaca dari halaman
    // village yang sebenarnya. Ini sengaja dibuat terlihat di Live WebView.
    private var villageScanActive = false
    private var villageScanTargets = mutableListOf<Pair<String, String>>()
    private var villageScanIndex = 0
    private var villageScanResults = mutableListOf<Pair<String, String>>()
    private var villageScanExpected = 0
    private var villageScanRetry = 0
    private var villageScanPageRetry = 0

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
            // Klik baru = scan baru. Retry internal tidak mereset counter.
            villageScanActive = false
            villageScanTargets.clear()
            villageScanResults.clear()
            villageScanIndex = 0
            villageScanExpected = 0
            villageScanRetry = 0
            villageScanPageRetry = 0
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

                // LOAD VILLAGE memiliki prioritas atas scheduler/service agar WebView
                // benar-benar dipakai untuk scan village satu per satu.
                if (villageScanActive) {
                    handler.postDelayed({
                        val target = villageScanTargets.getOrNull(villageScanIndex)
                        if (target != null) {
                            collectCurrentVillageData()
                        } else {
                            collectVillageTargetsFromSidebar()
                        }
                    }, 450)
                    return
                }

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

    /**
     * LOAD VILLAGE sekarang tidak hanya membaca sidebar lalu selesai.
     *
     * Tahap 1: ambil semua link village dari daftar Travian.
     * Tahap 2: WebView terlihat berpindah ke village 1, 2, 3, dst.
     * Tahap 3: pada setiap halaman, baca nama village + resource bar + level field.
     * Tahap 4: setelah semua selesai, baru render checklist Resource Builder.
     */
    /**
     * LOAD VILLAGE:
     * 1) selalu mulai dari dorf1.php
     * 2) baca seluruh village dari sidebar
     * 3) pindah dengan loadUrl(dorf1.php?newdid=...)
     * 4) setelah halaman target selesai, scan nama + resource fields
     * 5) lanjut langsung ke village berikutnya
     */
    private fun loadVillagesForUi() {
        villageScanActive = true
        villageScanTargets.clear()
        villageScanResults.clear()
        villageScanIndex = 0
        villageScanExpected = 0
        villageScanRetry = 0
        villageScanPageRetry = 0

        farmStatus.text = "Membuka dorf1.php..."
        logEvent("UI: LOAD VILLAGE → membuka dorf1.php")
        val server = normalizeServer(serverInput.text.toString())
        webView.loadUrl("$server/dorf1.php")
    }

    private fun collectVillageTargetsFromSidebar() {
        if (!villageScanActive) return

        val js = """
            (() => {
                const clean = s => String(s || '').replace(/\s+/g,' ').trim();
                const out = [];
                const seen = new Set();
                const root = document.querySelector('#sidebarBoxVillagelist');

                if (!root) {
                    AndroidFarm.onVillageTargetsResult(JSON.stringify({notReady:true, villages:[], expected:0}));
                    return;
                }

                const entries = [...root.querySelectorAll(
                    '.villageList .dropContainer .listEntry[data-did], ' +
                    '.villageList .listEntry[data-did], ' +
                    '.listEntry[data-did]'
                )];

                for (const entry of entries) {
                    const id = clean(entry.getAttribute('data-did'));
                    if (!/^\d+$/.test(id) || seen.has(id)) continue;

                    const name = clean(entry.querySelector('.name')?.textContent || '');
                    const a = entry.querySelector('a[href]');
                    const href = a?.getAttribute('href') || ('dorf1.php?newdid=' + encodeURIComponent(id));

                    seen.add(id);
                    out.push({id, name: name || ('Village ' + id), href});
                }

                const text = clean(root.innerText || document.body.innerText || '');
                const m = text.match(/VILLAGES\s+(\d+)\s*\/\s*\d+/i);
                const expected = m ? parseInt(m[1], 10) || 0 : out.length;

                // Travian dapat menampilkan sidebar beberapa saat setelah onPageFinished.
                // Jangan anggap kosong sebagai gagal; beri waktu DOM villageList muncul.
                if (out.length === 0) {
                    AndroidFarm.onVillageTargetsResult(JSON.stringify({
                        notReady:true,
                        villages:[],
                        expected
                    }));
                    return;
                }

                AndroidFarm.onVillageTargetsResult(
                    JSON.stringify({villages:out, expected})
                );
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun handleVillageTargetsResult(rawJson: String) {
        if (!villageScanActive) return

        val json = runCatching { JSONObject(rawJson) }.getOrNull()
        val targets = mutableListOf<Pair<String, String>>()
        val array = json?.optJSONArray("villages")

        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { "Village $id" }
                if (id.isNotBlank()) targets.add(id to name)
            }
        }

        val unique = targets.distinctBy { it.first }
        villageScanExpected = json?.optInt("expected", unique.size) ?: unique.size

        // Jangan matikan scanner hanya karena sidebar belum selesai dirender.
        // WebView onPageFinished bisa terpanggil sebelum villageList tersedia.
        if (json?.optBoolean("notReady", false) == true || unique.isEmpty()) {
            villageScanRetry++
            if (villageScanRetry <= 15) {
                if (villageScanRetry == 1 || villageScanRetry % 5 == 0) {
                    logEvent("UI: menunggu sidebar village muncul (${villageScanRetry}/15)")
                }
                handler.postDelayed({
                    if (villageScanActive) collectVillageTargetsFromSidebar()
                }, 700)
            } else {
                villageScanActive = false
                farmStatus.text = "Daftar village tidak tersedia."
                logEvent("UI: sidebar village tidak muncul setelah 15 percobaan; LOAD VILLAGE dihentikan")
            }
            return
        }

        if (villageScanExpected > 0 &&
            unique.size < villageScanExpected &&
            villageScanRetry < 3
        ) {
            villageScanRetry++
            logEvent(
                "UI: sidebar village ${unique.size}/${villageScanExpected}; " +
                    "reload dorf1 (${villageScanRetry}/3)"
            )
            val server = normalizeServer(serverInput.text.toString())
            handler.postDelayed({
                if (villageScanActive) webView.loadUrl("$server/dorf1.php")
            }, 1000)
            return
        }

        villageScanTargets = unique.toMutableList()
        villageScanResults.clear()
        villageScanIndex = 0
        villageScanRetry = 0

        logEvent(
            "UI: ${villageScanTargets.size}/${villageScanExpected} village ditemukan; " +
                "mulai scan berurutan"
        )
        visitNextVillageForScan()
    }

    private fun visitNextVillageForScan() {
        if (!villageScanActive) return

        if (villageScanIndex >= villageScanTargets.size) {
            finishVillageScan()
            return
        }

        val (id, name) = villageScanTargets[villageScanIndex]
        val progress = "${villageScanIndex + 1}/${villageScanTargets.size}"

        farmStatus.text = "Village $progress — $name"
        logEvent("UI: [$progress] pindah → $name (ID $id)")
        villageScanPageRetry = 0

        // Navigasi langsung. Tidak memakai click(), location.href, atau retry DOM.
        val server = normalizeServer(serverInput.text.toString())
        webView.loadUrl("$server/dorf1.php?newdid=$id")
    }

    private fun collectCurrentVillageData() {
        if (!villageScanActive) return

        // Saat dorf1.php pertama kali selesai, belum ada target.
        if (villageScanTargets.isEmpty()) {
            collectVillageTargetsFromSidebar()
            return
        }

        val target = villageScanTargets.getOrNull(villageScanIndex) ?: return
        val expectedId = target.first
        val expectedIdJson = JSONObject.quote(expectedId)

        val js = """
            (() => {
                const clean = s => String(s || '').replace(/\s+/g,' ').trim();
                const expectedId = $expectedIdJson;
                const match = location.href.match(/[?&]newdid=(\d+)/i);
                const currentId = match ? match[1] : '';

                if (currentId !== expectedId) {
                    AndroidFarm.onVillageScanResult(JSON.stringify({
                        notReady:true,
                        id:currentId,
                        expectedId
                    }));
                    return;
                }

                let name = '';
                const entry = [...document.querySelectorAll(
                    '#sidebarBoxVillagelist .listEntry[data-did]'
                )].find(e => String(e.getAttribute('data-did') || '') === expectedId);

                if (entry) {
                    name = clean(entry.querySelector('.name')?.textContent || '');
                }
                if (!name) name = 'Village ' + expectedId;

                const container = document.querySelector('#resourceFieldContainer');
                const fields = [];

                if (container) {
                    // Travian resource fields are represented by 18 links/areas.
                    // The level is exposed through levelN classes, level text,
                    // title/aria-label or a nearby labelLayer.
                    const nodes = [...container.querySelectorAll(
                        'a[href*="build.php?id="], area, [class*="level"]'
                    )];

                    for (const node of nodes) {
                        const candidates = [];
                        const push = v => {
                            if (v != null && String(v).trim()) candidates.push(String(v));
                        };

                        push(node.getAttribute('data-level'));
                        push(node.getAttribute('title'));
                        push(node.getAttribute('aria-label'));
                        push(node.textContent);
                        push(node.className);

                        let parent = node.parentElement;
                        for (let i = 0; i < 3 && parent; i++, parent = parent.parentElement) {
                            push(parent.className);
                            push(parent.getAttribute('data-level'));
                            push(parent.getAttribute('title'));
                            push(parent.textContent);
                        }

                        for (const text of candidates) {
                            const m = text.match(/\blevel\s*(\d+)\b/i) ||
                                      text.match(/\blevel(\d+)\b/i);
                            if (m) {
                                const n = parseInt(m[1], 10);
                                if (Number.isFinite(n)) {
                                    fields.push(n);
                                    break;
                                }
                            }
                        }
                    }
                }

                const levels = fields.filter(n => Number.isFinite(n));
                const minLevel = levels.length ? Math.min(...levels) : -1;

                const resources = {};
                for (const rid of ['l1','l2','l3','l4']) {
                    const el = document.getElementById(rid);
                    if (el) resources[rid] = clean(el.textContent || '');
                }

                AndroidFarm.onVillageScanResult(JSON.stringify({
                    id:expectedId,
                    name,
                    minLevel,
                    fields:levels.slice(0, 18),
                    resources
                }));
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun handleVillageScanResult(rawJson: String) {
        if (!villageScanActive) return

        val json = runCatching { JSONObject(rawJson) }.getOrNull()

        if (json?.optBoolean("notReady", false) == true) {
            villageScanPageRetry++

            if (villageScanPageRetry <= 6) {
                if (villageScanPageRetry == 1 || villageScanPageRetry == 6) {
                    logEvent(
                        "UI: [${villageScanIndex + 1}/${villageScanTargets.size}] " +
                            "belum pindah target=${json.optString("expectedId")}, " +
                            "current=${json.optString("id").ifBlank { "-" }}"
                    )
                }
                handler.postDelayed({ collectCurrentVillageData() }, 500)
            } else {
                // Satu village gagal tidak boleh mengunci seluruh scanner.
                val (_, name) = villageScanTargets[villageScanIndex]
                logEvent(
                    "UI: [${villageScanIndex + 1}/${villageScanTargets.size}] " +
                        "$name timeout; village dilewati"
                )
                villageScanIndex++
                handler.postDelayed({ visitNextVillageForScan() }, 300)
            }
            return
        }

        villageScanPageRetry = 0

        val target = villageScanTargets.getOrNull(villageScanIndex) ?: return
        val id = json?.optString("id").orEmpty().ifBlank { target.first }
        val name = json?.optString("name").orEmpty().trim().ifBlank { target.second }
        val minLevel = json?.optInt("minLevel", -1) ?: -1

        val resources = json?.optJSONObject("resources")
        val resourceText = resources?.let {
            listOf("l1","l2","l3","l4").mapNotNull { key ->
                val v = it.optString(key, "").trim()
                if (v.isNotBlank()) "$key=$v" else null
            }.joinToString(", ")
        }.orEmpty()

        val existing = villageScanResults.indexOfFirst { it.first == id }
        if (existing >= 0) villageScanResults[existing] = id to name
        else villageScanResults.add(id to name)

        val progress = "${villageScanIndex + 1}/${villageScanTargets.size}"
        logEvent(
            "UI: [$progress] $name selesai — resource min=L$minLevel" +
                if (resourceText.isNotBlank()) "; $resourceText" else ""
        )

        villageScanIndex++
        handler.postDelayed({ visitNextVillageForScan() }, 250)
    }

    private fun finishVillageScan() {
        villageScanActive = false

        val unique = villageScanResults.distinctBy { it.first }
        farmStatus.text = "${unique.size} village selesai dicek dan siap dipilih."
        logEvent(
            "UI: scan selesai — ${unique.size}/${villageScanTargets.size} village " +
                "berhasil diproses"
        )

        renderVillageChecklist(unique)
        villageScanTargets.clear()
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

        @JavascriptInterface
        fun onVillageTargetsResult(result: String) {
            runOnUiThread { handleVillageTargetsResult(result) }
        }

        @JavascriptInterface
        fun onVillageScanResult(result: String) {
            runOnUiThread { handleVillageScanResult(result) }
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
        val last = prefs.getString("last_run", "").orEmpty()
        lastRun.text = if (last.isBlank()) "Last run: --" else "Last run: $last"
    }

    private fun updateCountdown() {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val serviceRunning = prefs.getBoolean("service_running", false)
        val next = prefs.getLong("next_run_at", 0L)
        val last = prefs.getString("last_run", "").orEmpty()
        lastRun.text = if (last.isBlank()) "Last run: --" else "Last run: $last"
        if (!serviceRunning) {
            nextRun.text = "Next run: --"
            return
        }
        if (next <= 0L) {
            nextRun.text = "Next run: setelah siklus selesai"
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

    }

    override fun onDestroy() {
        villageScanActive = false
        villageScanTargets.clear()
        FarmAutomationService.detachVisibleWebView(webView)
        FarmAutomationService.onVisibleWebViewDetached()
        handler.removeCallbacks(countdownUpdater)
        logEvent("MainActivity ditutup; background service tetap dapat berjalan")
        super.onDestroy()
    }
}
