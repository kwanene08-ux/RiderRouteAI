package com.riderrouteai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var preview: ImageView
    private lateinit var analyze: Button
    private lateinit var navigate: Button
    private lateinit var flood: TextView
    private lateinit var floodButton: Button
    private lateinit var updateButton: Button
    private var pickup: String? = null
    private var dropoff: String? = null
    private var pickupAddress: Address? = null
    private var dropoffAddress: Address? = null
    private var lastFloodReport: String = "ยังไม่ได้ตรวจข้อมูลน้ำท่วม"
    private val currentVersionCode = 7
    private val currentVersionName = "0.6.0"
    private val updateManifestUrl = "https://raw.githubusercontent.com/kwanene08-ux/RiderRouteAI/main/latest.json"

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        preview.setImageURI(uri)
        status.text = "📸 เลือกแคปแล้ว กำลังอ่านข้อความ..."
        runOcr(uri)
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateLocationStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateLocationStatus()
        if (!hasLocation()) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        refreshFloodData()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        val title = TextView(this).apply { text = "Rider Route AI  •  V0.6.0"; textSize = 26f }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 18, 0, 18) }
        val capture = Button(this).apply { text = "📸 เลือกแคปงาน" }
        preview = ImageView(this).apply { adjustViewBounds = true; minimumHeight = 260 }
        result = TextView(this).apply { textSize = 17f; setPadding(0, 18, 0, 18) }
        analyze = Button(this).apply { text = "🗺️ วิเคราะห์ต้นทาง → ปลายทาง"; isEnabled = false }
        navigate = Button(this).apply { text = "🧭 เปิดนำทางใน Google Maps"; isEnabled = false }
        floodButton = Button(this).apply { text = "🌊 ตรวจน้ำท่วมล่าสุดของวันนี้" }
        updateButton = Button(this).apply { text = "🔄 ตรวจสอบอัปเดต V$currentVersionName" }
        flood = TextView(this).apply {
            text = "🌊 น้ำท่วมวันนี้: กำลังตรวจข้อมูล..."
            textSize = 15f
            setPadding(0, 18, 0, 18)
        }
        capture.setOnClickListener { picker.launch("image/*") }
        analyze.setOnClickListener { analyzeAddresses() }
        navigate.setOnClickListener { openNavigation() }
        floodButton.setOnClickListener { refreshFloodData() }
        updateButton.setOnClickListener { checkForUpdate(true) }
        root.addView(title); root.addView(status); root.addView(capture); root.addView(preview)
        root.addView(result); root.addView(analyze); root.addView(navigate); root.addView(floodButton); root.addView(flood); root.addView(updateButton)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun runOcr(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { vision ->
                    // Keep ML Kit's complete recognized text. Do not replace it with only
                    // the two extracted address fields.
                    val fullText = vision.text.trim()
                    val lines = vision.textBlocks.flatMap { block -> block.lines.map { it.text } }
                        .map { normalize(it) }
                        .filter { it.isNotEmpty() }
                    val lineText = lines.joinToString("\n")
                    val searchableText = if (lineText.isNotBlank()) lineText else fullText

                    pickup = findLabeled(searchableText, listOf("ต้นทาง", "รับที่", "จุดรับ", "pickup", "origin"))
                    dropoff = findLabeled(searchableText, listOf("ปลายทาง", "ส่งที่", "จุดส่ง", "drop", "destination"))
                    if (pickup == null || dropoff == null) {
                        val candidates = addressCandidates(searchableText)
                        if (pickup == null) pickup = candidates.firstOrNull { it != dropoff }
                        if (dropoff == null) dropoff = candidates.firstOrNull { it != pickup }
                    }

                    result.text = buildString {
                        append("🧠 OCR อ่านได้ครบ\n\n")
                        append("📍 ต้นทาง: ").append(pickup ?: "ยังแยกไม่ได้").append("\n")
                        append("🏁 ปลายทาง: ").append(dropoff ?: "ยังแยกไม่ได้").append("\n\n")
                        append("📄 ข้อความทั้งหมดจาก OCR:\n")
                        append(if (fullText.isBlank()) "ไม่พบข้อความ" else fullText)
                    }
                    analyze.isEnabled = !pickup.isNullOrBlank() && !dropoff.isNullOrBlank()
                    navigate.isEnabled = false
                    status.text = if (analyze.isEnabled) "✅ อ่านแคปเสร็จ • ตรวจข้อความทั้งหมดแล้ว • กดวิเคราะห์ได้" else "⚠️ อ่านได้ แต่ยังแยกต้นทาง/ปลายทางไม่ครบ • ข้อความทั้งหมดด้านล่างยังดูได้"
                    recognizer.close()
                }
                .addOnFailureListener { e ->
                    status.text = "❌ OCR ไม่สำเร็จ: ${e.message ?: "unknown error"}"
                    recognizer.close()
                }
        } catch (e: Exception) {
            status.text = "❌ เปิดภาพไม่ได้: ${e.message ?: "unknown error"}"
        }
    }

    private fun findLabeled(text: String, labels: List<String>): String? {
        val lines = text.lines().map { normalize(it) }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val line = lines[i]
            val lower = line.lowercase(Locale.ROOT)
            val hit = labels.firstOrNull { lower.contains(it.lowercase(Locale.ROOT)) } ?: continue
            val after = line.substringAfter(hit, "").trim().trim(':', '-', '–', '—', ' ')
            if (isUsefulAddress(after)) return cleanAddress(after)
            if (i + 1 < lines.size && isUsefulAddress(lines[i + 1])) return cleanAddress(lines[i + 1])
            if (i + 2 < lines.size && isUsefulAddress(lines[i + 2])) return cleanAddress(lines[i + 2])
        }
        return null
    }

    private fun addressCandidates(text: String): List<String> = text.lines()
        .map { cleanAddress(normalize(it)) }
        .filter { isUsefulAddress(it) }
        .distinct()
        .sortedByDescending { addressScore(it) }
        .take(8)

    private fun addressScore(value: String): Int {
        val lower = value.lowercase(Locale.ROOT)
        var score = 0
        if (lower.contains("ถนน") || lower.contains("ถ.") || lower.contains("ซอย") || lower.contains("แขวง") || lower.contains("เขต")) score += 5
        if (lower.contains("กรุงเทพ") || lower.contains("สมุทรสาคร") || lower.contains("สมุทรปราการ") || lower.contains("นนทบุรี") || lower.contains("ปทุมธานี")) score += 3
        if (Regex("\\d").containsMatchIn(value)) score += 2
        if (value.length >= 12) score += 1
        return score
    }

    private fun normalize(value: String): String = value.replace("\u00A0", " ").replace(Regex("\\s+"), " ").trim()
    private fun cleanAddress(value: String): String = value.replace(Regex("^[•·|]+"), "").replace(Regex("\\s{2,}"), " ").trim(' ', ':', '-', '–', '—')
    private fun isUsefulAddress(value: String): Boolean {
        if (value.length < 7 || value.length > 180) return false
        val lower = value.lowercase(Locale.ROOT)
        val bad = listOf("โทร", "เบอร์", "ราคา", "บาท", "วิธีชำระ", "รายละเอียด", "หมายเหตุ", "line man", "google", "2026", "am", "pm")
        if (bad.any { lower.contains(it) }) return false
        if (Regex("^\\d{1,2}:\\d{2}").containsMatchIn(value)) return false
        if (Regex("^[0-9A-Za-z]{1,10}$").matches(value)) return false
        return true
    }

    private fun analyzeAddresses() {
        val from = pickup?.trim(); val to = dropoff?.trim()
        if (from.isNullOrBlank() || to.isNullOrBlank()) return
        status.text = "🔎 กำลังค้นหาพิกัดต้นทางและปลายทาง..."; analyze.isEnabled = false
        Thread {
            val fromAddress = geocode(from); val toAddress = geocode(to)
            runOnUiThread {
                pickupAddress = fromAddress; dropoffAddress = toAddress
                val out = StringBuilder()
                out.append("📍 ต้นทาง\n").append(from).append("\n")
                if (fromAddress != null) out.append("พิกัด: ${fmt(fromAddress.latitude)}, ${fmt(fromAddress.longitude)}\n") else out.append("พิกัด: หาไม่พบ\n")
                out.append("\n🏁 ปลายทาง\n").append(to).append("\n")
                if (toAddress != null) out.append("พิกัด: ${fmt(toAddress.latitude)}, ${fmt(toAddress.longitude)}\n") else out.append("พิกัด: หาไม่พบ\n")
                if (fromAddress != null && toAddress != null) {
                    val meters = FloatArray(1)
                    android.location.Location.distanceBetween(fromAddress.latitude, fromAddress.longitude, toAddress.latitude, toAddress.longitude, meters)
                    val km = meters[0] / 1000.0
                    val eta = ((km / 25.0) * 60.0).roundToInt().coerceAtLeast(1)
                    out.append("\n📏 ระยะเส้นตรงโดยประมาณ: ${String.format(Locale.US, "%.1f", km)} กม.")
                    out.append("\n⏱️ เวลาเบื้องต้นที่ 25 กม./ชม.: ~${eta} นาที")
                    out.append("\n⚠️ ยังไม่ใช่ระยะทางตามถนนจริง และยังไม่รวมรถติด")
                    navigate.isEnabled = true; status.text = "✅ หา 2 พิกัดแล้ว • พร้อมเปิดนำทาง"
                } else status.text = "⚠️ หาได้ไม่ครบ 2 พิกัด • ลองแก้ข้อความที่ OCR อ่าน"
                if (fromAddress != null && toAddress != null) out.append("\n\n").append(routeFloodRisk(from, to))
                result.text = out.toString(); analyze.isEnabled = true
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun geocode(query: String): Address? {
        if (!Geocoder.isPresent()) return null
        return try {
            Geocoder(this, Locale.forLanguageTag("th-TH")).getFromLocationName("$query, ประเทศไทย", 1)?.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun openNavigation() {
        val to = dropoffAddress ?: return
        val uri = Uri.parse("google.navigation:q=${to.latitude},${to.longitude}&mode=m")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }) }
        catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    private fun checkForUpdate(showNoUpdate: Boolean) {
        updateButton.isEnabled = false
        updateButton.text = "🔄 กำลังตรวจสอบอัปเดต..."
        Thread {
            try {
                val jsonText = download(updateManifestUrl)
                val json = JSONObject(jsonText)
                val remoteCode = json.optInt("versionCode", 0)
                val remoteName = json.optString("versionName", "รุ่นใหม่")
                val apkUrl = json.optString("apkUrl", "")
                val notes = json.optString("releaseNotes", "")
                runOnUiThread {
                    if (remoteCode > currentVersionCode && apkUrl.startsWith("https://")) {
                        updateButton.text = "⬇️ ดาวน์โหลด V$remoteName"
                        updateButton.setOnClickListener { downloadUpdate(apkUrl, remoteName) }
                        status.text = "🆕 พบอัปเดต V$remoteName\n${notes.takeIf { it.isNotBlank() } ?: "มีเวอร์ชันใหม่พร้อมติดตั้ง"}"
                    } else {
                        updateButton.text = "🔄 ตรวจสอบอัปเดต V$currentVersionName"
                        updateButton.setOnClickListener { checkForUpdate(true) }
                        if (showNoUpdate) status.text = "✅ Rider Route AI V$currentVersionName เป็นเวอร์ชันล่าสุด"
                    }
                    updateButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateButton.text = "🔄 ตรวจสอบอัปเดต V$currentVersionName"
                    updateButton.setOnClickListener { checkForUpdate(true) }
                    updateButton.isEnabled = true
                    if (showNoUpdate) status.text = "⚠️ ตรวจสอบอัปเดตไม่ได้\n${e.message ?: "เครือข่ายหรือเซิร์ฟเวอร์ไม่พร้อม"}"
                }
            }
        }.start()
    }

    private fun downloadUpdate(apkUrl: String, versionName: String) {
        if (!apkUrl.startsWith("https://")) {
            status.text = "❌ URL อัปเดตไม่ปลอดภัย"
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "⚙️ ต้องอนุญาตให้ Rider Route AI ติดตั้งอัปเดตจากแหล่งนี้ก่อน"
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val fileName = "RiderRouteAI-$versionName.apk"
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Rider Route AI V$versionName")
                .setDescription("กำลังดาวน์โหลดอัปเดต")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            status.text = "⬇️ กำลังดาวน์โหลด V$versionName..."
            Thread {
                var done = false
                while (!done) {
                    Thread.sleep(700)
                    val query = DownloadManager.Query().setFilterById(id)
                    manager.query(query).use { cursor ->
                        if (!cursor.moveToFirst()) return@use
                        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                done = true
                                val uri = Uri.fromFile(File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName))
                                runOnUiThread { status.text = "📦 ดาวน์โหลด V$versionName เสร็จแล้ว • กำลังเปิดตัวติดตั้ง"; launchInstaller(uri) }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                done = true
                                runOnUiThread { status.text = "❌ ดาวน์โหลดอัปเดตไม่สำเร็จ" }
                            }
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            status.text = "❌ เริ่มดาวน์โหลดไม่ได้: ${e.message ?: "unknown error"}"
        }
    }

    private fun launchInstaller(downloadUri: Uri) {
        try {
            val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), downloadUri.lastPathSegment ?: "update.apk")
            if (!apkFile.exists()) throw IllegalStateException("ไม่พบไฟล์อัปเดตที่ดาวน์โหลด")
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            status.text = "❌ เปิดตัวติดตั้งไม่ได้: ${e.message ?: "unknown error"}"
        }
    }

    private fun refreshFloodData() {
        floodButton.isEnabled = false
        flood.text = "🌊 กำลังดึงข้อมูลน้ำท่วมล่าสุดของวันนี้...\nแหล่งข้อมูล: สำนักการระบายน้ำ กทม."
        Thread {
            val buddhistDate = bangkokBuddhistDate()
            val resultText = try { fetchFloodPage(buddhistDate) } catch (e: Exception) {
                "🌊 น้ำท่วมวันนี้\n❌ ดึงข้อมูลไม่สำเร็จ\n${e.message ?: "เครือข่ายหรือแหล่งข้อมูลไม่พร้อม"}\n\n⚠️ ไม่สรุปว่าถนนปลอดน้ำ เพราะไม่มีข้อมูลสด"
            }
            lastFloodReport = resultText
            runOnUiThread { flood.text = resultText; floodButton.isEnabled = true }
        }.start()
    }

    private fun bangkokBuddhistDate(): String {
        val parts = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Bangkok") }.format(Date()).split("/")
        return "${parts[0]}/${parts[1]}/${parts[2].toInt() + 543}"
    }

    private fun fetchFloodPage(today: String): String {
        val urls = listOf(
            "https://weather.bangkok.go.th/flood/SummaryStation/IndexSummaryStation",
            "https://weather.bangkok.go.th/LastData/IndexFlood"
        )
        var lastError: Exception? = null
        for (urlString in urls) {
            try {
                val html = download(urlString)
                val rows = parseRows(html)
                val current = rows.filter { it.any { cell -> cell.contains(today) } }
                if (current.isNotEmpty()) return formatFloodReport(today, current)
            } catch (e: Exception) { lastError = e }
        }
        return if (lastError != null) {
            "🌊 น้ำท่วมวันนี้\n⚠️ ติดต่อแหล่งข้อมูล กทม. ไม่สำเร็จ\n${lastError.message ?: "unknown error"}\n\nจึงไม่ถือว่าถนนปลอดน้ำ"
        } else {
            "🌊 น้ำท่วมวันนี้\n⚠️ แหล่งข้อมูล กทม. ไม่ส่งรายการของวันที่ $today ให้แอปอ่านได้\n\nจึงไม่ถือว่าถนนปลอดน้ำ\nแหล่งข้อมูล: สำนักการระบายน้ำ กทม."
        }
    }

    private fun download(urlString: String): String {
        val c = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("User-Agent", "RiderRouteAI/0.5.1")
        }
        return try { c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { c.disconnect() }
    }

    private fun parseRows(html: String): List<List<String>> = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(html).mapNotNull { m ->
        val cells = Regex("(?is)<(?:td|th)[^>]*>(.*?)</(?:td|th)>").findAll(m.groupValues[1]).map { stripHtml(it.groupValues[1]) }.toList()
        if (cells.size >= 5) cells else null
    }.toList()

    private fun formatFloodReport(today: String, current: List<List<String>>): String {
        val wet = current.filter { row -> row.any { it.contains("น้ำท่วม") } }
        val statusCounts = current.groupBy { row -> row.firstOrNull { it.contains("ปกติ") || it.contains("น้ำท่วม") || it.contains("ขัดข้อง") } ?: "ไม่ทราบ" }.map { "${it.key} ${it.value.size}" }
        val latest = current.mapNotNull { row -> row.firstOrNull { it.matches(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}")) } }.maxOrNull() ?: "ไม่ทราบ"
        return buildString {
            append("🌊 น้ำท่วมวันนี้ • $today (พ.ศ.)\n")
            append("🕒 เวลาอัปเดตล่าสุดที่อ่านได้: $latest\n")
            append("📡 สถานีของวันนี้: ${current.size} จุด\n")
            append("📊 ${statusCounts.joinToString(" • ")}\n\n")
            if (wet.isEmpty()) append("🟢 ยังไม่พบแถวที่ระบุว่า ‘น้ำท่วม’ ในข้อมูลวันนี้\n") else {
                append("🔴 จุดที่รายงานน้ำท่วมวันนี้: ${wet.size} จุด\n")
                wet.take(15).forEach { row ->
                    val road = row.firstOrNull { it.contains("ถ.") || it.contains("ถนน") || it.contains("ซอย") } ?: row.getOrNull(1) ?: "ไม่ทราบถนน"
                    val time = row.firstOrNull { it.matches(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}")) } ?: "ไม่ทราบเวลา"
                    val level = row.firstOrNull { it.matches(Regex("\\d+(?:\\.\\d+)?")) } ?: "-"
                    append("• $road | $level ซม. | $time\n")
                }
                if (wet.size > 15) append("• และอีก ${wet.size - 15} จุด\n")
            }
            append("\n⚠️ เป็นข้อมูลสถานีตรวจวัด ไม่ใช่การรับรองว่าเส้นทางทุกเมตรปลอดน้ำ\nแหล่งข้อมูล: สำนักการระบายน้ำ กทม.")
        }
    }

    private fun routeFloodRisk(from: String, to: String): String {
        val combined = normalizeForMatch("$from $to")
        val today = bangkokBuddhistDate()
        return try {
            val rows = listOf(
                "https://weather.bangkok.go.th/flood/SummaryStation/IndexSummaryStation",
                "https://weather.bangkok.go.th/LastData/IndexFlood"
            ).asSequence().flatMap { urlString ->
                try { parseRows(download(urlString)).asSequence() } catch (_: Exception) { emptySequence() }
            }.filter { it.any { cell -> cell.contains(today) } }.toList()
            val wet = rows.filter { row -> row.any { it.contains("น้ำท่วม") } }
            if (rows.isEmpty()) return "🧭 ความเสี่ยงน้ำท่วมเส้นทาง: ⚪ ไม่มีข้อมูลของวันนี้ที่ใช้ประเมินได้\n⚠️ ไม่ได้แปลว่าถนนแห้ง"
            val tokens = combined.split(" ").filter { it.length >= 4 }.distinct()
            val hits = wet.filter { row -> tokens.any { normalizeForMatch(row.joinToString(" ")).contains(it) } }
            val score = when {
                hits.any { it.any { cell -> cell.contains("น้ำท่วม") && !cell.contains("น้ำท่วมเล็กน้อย") } } -> 90
                hits.isNotEmpty() -> 60
                else -> 10
            }
            val level = when { score >= 90 -> "🔴 สูง"; score >= 60 -> "🟡 ระวัง"; else -> "🟢 ไม่พบจุดท่วมที่ชื่อถนนตรงกับต้นทาง/ปลายทาง" }
            buildString {
                append("🧭 ความเสี่ยงน้ำท่วมเส้นทาง: $level ($score/100)\n")
                if (hits.isNotEmpty()) {
                    append("จุดที่ชื่อถนนตรง/ใกล้เคียง: ${hits.size} จุด\n")
                    hits.take(5).forEach { row ->
                        val road = row.firstOrNull { it.contains("ถ.") || it.contains("ถนน") || it.contains("ซอย") } ?: row.getOrNull(1) ?: "ไม่ทราบถนน"
                        val depth = row.firstOrNull { it.matches(Regex("\\d+(?:\\.\\d+)?")) } ?: "-"
                        append("• $road | $depth ซม.\n")
                    }
                } else append("ไม่พบชื่อถนนที่ตรงกับข้อความต้นทาง/ปลายทางในสถานีท่วมวันนี้\n")
                append("⚠️ คะแนนนี้เป็นการจับคู่ชื่อถนน ไม่ใช่เส้นทางจริงจากแผนที่ และไม่มีข้อมูลตรง ≠ ถนนแห้ง")
            }
        } catch (_: Exception) {
            "🧭 ความเสี่ยงน้ำท่วมเส้นทาง: ⚪ ตรวจไม่ได้ตอนนี้\n⚠️ ไม่มีข้อมูลสด จึงไม่สรุปว่าถนนปลอดน้ำ"
        }
    }

    private fun normalizeForMatch(value: String): String = value
        .lowercase(Locale.forLanguageTag("th-TH"))
        .replace("ถนน", " ")
        .replace("ถ.", " ")
        .replace("ซอย", " ")
        .replace("แขวง", " ")
        .replace("เขต", " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripHtml(value: String): String = URLDecoder.decode(value.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&"), "UTF-8").replace(Regex("\\s+"), " ").trim()
    private fun fmt(v: Double) = String.format(Locale.US, "%.6f", v)
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun updateLocationStatus() { status.text = if (hasLocation()) "📍 GPS: พร้อมใช้งาน" else "📍 GPS: ต้องอนุญาตตำแหน่ง" }
}
