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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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

    private var currentLocation: Location? = null
    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            updateLocationStatus()
        }
    }

    private var lastFloodReport: String = "ยังไม่ได้ตรวจข้อมูลน้ำท่วม"
    private val currentVersionCode = 9
    private val currentVersionName = "0.7.1"
    private val updateManifestUrl = "https://raw.githubusercontent.com/kwanene08-ux/RiderRouteAI/main/latest.json"

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        preview.setImageURI(uri)
        status.text = "📸 เลือกแคปแล้ว กำลังอ่านข้อความ..."
        runOcr(uri)
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocation()) startLocationUpdates()
        updateLocationStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateLocationStatus()
        if (!hasLocation()) {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startLocationUpdates()
        }
        refreshFloodData()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        val title = TextView(this).apply { text = "Rider Route AI  •  V0.7.1"; textSize = 26f }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 18, 0, 18) }
        val capture = Button(this).apply { text = "📸 เลือกแคปงาน" }
        preview = ImageView(this).apply { adjustViewBounds = true; minimumHeight = 260 }
        result = TextView(this).apply { textSize = 17f; setPadding(0, 18, 0, 18) }
        analyze = Button(this).apply { text = "🗺️ วิเคราะห์ 3 จุด → จุดรับ → จุดส่ง"; isEnabled = false }
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
                        append("📦 จุดรับ: ").append(pickup ?: "ยังแยกไม่ได้").append("\n")
                        append("🏁 จุดส่ง: ").append(dropoff ?: "ยังแยกไม่ได้").append("\n\n")
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
        val from = pickup?.trim()
        val to = dropoff?.trim()

        if (from.isNullOrBlank() || to.isNullOrBlank()) return

        val current = currentLocation
        if (current == null) {
            status.text = "⚠️ ยังไม่มีตำแหน่ง GPS ปัจจุบัน • รอสัญญาณ GPS แล้วกดวิเคราะห์อีกครั้ง"
            return
        }

        status.text = "🔎 กำลังค้นหาพิกัด 3 จุด..."
        analyze.isEnabled = false
        navigate.isEnabled = false

        Thread {
            val currentSnapshot = Location(current)
            val currentAddress = reverseGeocode(currentSnapshot)
            val fromAddress = geocode(from)
            val toAddress = geocode(to)

            val floodReport =
                if (fromAddress != null && toAddress != null) {
                    routeFloodRisk(
                        currentSnapshot,
                        currentAddress,
                        fromAddress,
                        toAddress,
                        from,
                        to
                    )
                } else {
                    "🌊 น้ำท่วม\n⚪ ยังประเมินเส้นทางไม่ได้ เพราะหาพิกัดจุดรับ/จุดส่งไม่ครบ"
                }

            runOnUiThread {
                pickupAddress = fromAddress
                dropoffAddress = toAddress

                val out = StringBuilder()

                out.append("📍 จุดที่อยู่ปัจจุบัน\n")
                out.append(formatAddressBlock(currentAddress, "กำลังหาที่อยู่จาก GPS"))
                    .append("\n")
                out.append("พิกัด: ")
                    .append(fmt(currentSnapshot.latitude))
                    .append(", ")
                    .append(fmt(currentSnapshot.longitude))
                    .append("\n")

                out.append("\n📦 จุดรับ\n")
                out.append(formatAddressBlock(fromAddress, from))
                    .append("\n")
                if (fromAddress != null) {
                    out.append("พิกัด: ")
                        .append(fmt(fromAddress.latitude))
                        .append(", ")
                        .append(fmt(fromAddress.longitude))
                        .append("\n")
                } else {
                    out.append("พิกัด: หาไม่พบ\n")
                }

                out.append("\n🏁 จุดส่ง\n")
                out.append(formatAddressBlock(toAddress, to))
                    .append("\n")
                if (toAddress != null) {
                    out.append("พิกัด: ")
                        .append(fmt(toAddress.latitude))
                        .append(", ")
                        .append(fmt(toAddress.longitude))
                        .append("\n")
                } else {
                    out.append("พิกัด: หาไม่พบ\n")
                }

                if (fromAddress != null && toAddress != null) {
                    val leg1Km = distanceKm(
                        currentSnapshot.latitude,
                        currentSnapshot.longitude,
                        fromAddress.latitude,
                        fromAddress.longitude
                    )

                    val leg2Km = distanceKm(
                        fromAddress.latitude,
                        fromAddress.longitude,
                        toAddress.latitude,
                        toAddress.longitude
                    )

                    val totalKm = leg1Km + leg2Km
                    val leg1Eta = etaMinutes(leg1Km)
                    val leg2Eta = etaMinutes(leg2Km)
                    val totalEta = leg1Eta + leg2Eta

                    out.append("\n━━━━━━━━━━━━━━━━\n")
                    out.append("🛵 เส้นทางไปจุดรับ\n")
                    out.append("ระยะทาง: ")
                        .append(oneDecimal(leg1Km))
                        .append(" กม.\n")
                    out.append("เวลาโดยประมาณ: ")
                        .append(leg1Eta)
                        .append(" นาที\n")

                    out.append("\n📦 จากจุดรับ → จุดส่ง\n")
                    out.append("ระยะทาง: ")
                        .append(oneDecimal(leg2Km))
                        .append(" กม.\n")
                    out.append("เวลาโดยประมาณ: ")
                        .append(leg2Eta)
                        .append(" นาที\n")

                    out.append("\n📊 รวมทั้งหมด\n")
                    out.append("ระยะทาง: ")
                        .append(oneDecimal(totalKm))
                        .append(" กม.\n")
                    out.append("เวลาโดยประมาณ: ")
                        .append(totalEta)
                        .append(" นาที\n")

                    out.append("\n⚠️ ระยะทางด้านบนเป็นระยะเส้นตรงโดยประมาณ ไม่ใช่ระยะถนนจริง\n")
                    out.append("⏱️ เวลาใช้อัตราเฉลี่ย 25 กม./ชม. และยังไม่รวมรถติด\n")

                    navigate.isEnabled = true
                    status.text = "✅ วิเคราะห์ 3 จุดเสร็จแล้ว • GPS ปัจจุบันพร้อมใช้งาน"
                } else {
                    out.append("\n⚠️ หาได้ไม่ครบ 3 พิกัด • ตรวจข้อความ OCR หรือสถานที่อีกครั้ง")
                    status.text = "⚠️ หา GPS/จุดรับ/จุดส่งได้ไม่ครบ"
                }

                out.append("\n\n").append(floodReport)

                result.text = out.toString()
                analyze.isEnabled = !pickup.isNullOrBlank() && !dropoff.isNullOrBlank()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(location: Location): Address? {
        if (!Geocoder.isPresent()) return null
        return try {
            Geocoder(this, Locale.forLanguageTag("th-TH"))
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun formatAddressBlock(address: Address?, fallback: String): String {
        if (address == null) return fallback

        val line = address.getAddressLine(0)?.trim().orEmpty()
        val province = address.adminArea?.trim().orEmpty()
        val district = (address.subAdminArea ?: address.locality)?.trim().orEmpty()
        val subdistrict = address.subLocality?.trim().orEmpty()

        val areaLine = if (province.contains("กรุงเทพ", ignoreCase = true)) {
            listOf(
                province.takeIf { it.isNotBlank() }?.let { "จังหวัด$it" },
                subdistrict.takeIf { it.isNotBlank() }?.let { "แขวง$it" },
                district.takeIf { it.isNotBlank() }?.let { "เขต$it" }
            ).filterNotNull().distinct().joinToString(" ")
        } else {
            listOf(
                province.takeIf { it.isNotBlank() }?.let { "จังหวัด$it" },
                subdistrict.takeIf { it.isNotBlank() }?.let { "ตำบล$it" },
                district.takeIf { it.isNotBlank() }?.let { "อำเภอ$it" }
            ).filterNotNull().distinct().joinToString(" ")
        }

        return listOf(line, areaLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { fallback }
    }

    private fun distanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val meters = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, meters)
        return meters[0] / 1000.0
    }

    private fun etaMinutes(km: Double): Int =
        ((km / 25.0) * 60.0).roundToInt().coerceAtLeast(1)

    private fun oneDecimal(value: Double): String =
        String.format(Locale.US, "%.1f", value)

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

    private fun routeFloodRisk(
        current: Location,
        currentAddress: Address?,
        pickupAddress: Address,
        dropoffAddress: Address,
        pickupRaw: String,
        dropoffRaw: String
    ): String {
        val today = bangkokBuddhistDate()

        val rows = try {
            val urls = listOf(
                "https://weather.bangkok.go.th/flood/SummaryStation/IndexSummaryStation",
                "https://weather.bangkok.go.th/LastData/IndexFlood"
            )

            var latestRows: List<List<String>> = emptyList()

            for (urlString in urls) {
                try {
                    val parsed = parseRows(download(urlString))
                    val currentRows = parsed.filter { row ->
                        row.any { cell -> cell.contains(today) }
                    }
                    if (currentRows.isNotEmpty()) {
                        latestRows = currentRows
                        break
                    }
                } catch (_: Exception) {
                }
            }

            latestRows
        } catch (_: Exception) {
            emptyList()
        }

        if (rows.isEmpty()) {
            return buildString {
                append("🌊 น้ำท่วม\n")
                append("⚪ ไม่มีข้อมูลสถานีของวันที่ $today ให้ประเมินได้\n")
                append("⚠️ ไม่มีข้อมูล ≠ ถนนแห้ง")
            }
        }

        val wet = rows.filter { row ->
            row.any { cell ->
                cell.contains("น้ำท่วม")
            }
        }

        val nowText = rows.mapNotNull {
            it.firstOrNull {
                it.matches(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}"))
            }
        }.maxOrNull() ?: "ไม่ทราบ"

        fun hitStations(vararg texts: String): List<List<String>> {
            val query = texts.joinToString(" ")
            val normalizedQuery = normalizeForMatch(query).replace(" ", "")

            if (normalizedQuery.length < 6) return emptyList()

            return wet.filter { row ->
                val stationText = normalizeForMatch(row.joinToString(" "))
                    .replace(" ", "")

                if (stationText.isBlank()) {
                    false
                } else {
                    val maxN = minOf(12, normalizedQuery.length)
                    var matched = false

                    for (n in maxN downTo 6) {
                        var i = 0
                        while (i + n <= normalizedQuery.length) {
                            val piece = normalizedQuery.substring(i, i + n)
                            if (stationText.contains(piece)) {
                                matched = true
                                break
                            }
                            i++
                        }
                        if (matched) break
                    }

                    matched
                }
            }.distinctBy { it.joinToString("|") }
        }

        fun stationLine(row: List<String>): String {
            val road = row.firstOrNull {
                it.contains("ถ.") ||
                    it.contains("ถนน") ||
                    it.contains("ซอย")
            } ?: row.getOrNull(1) ?: "ไม่ทราบถนน"

            val time = row.firstOrNull {
                it.matches(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}"))
            } ?: "ไม่ทราบเวลา"

            val depth = row.firstOrNull {
                it.matches(Regex("\\d+(?:\\.\\d+)?"))
            } ?: "-"

            return "• $road | $depth ซม. | $time"
        }

        val currentText = buildString {
            currentAddress?.getAddressLine(0)?.let { append(it).append(" ") }
            currentAddress?.adminArea?.let { append(it).append(" ") }
            currentAddress?.subAdminArea?.let { append(it).append(" ") }
            currentAddress?.locality?.let { append(it).append(" ") }
            currentAddress?.subLocality?.let { append(it).append(" ") }
            append(fmt(current.latitude))
            append(" ")
            append(fmt(current.longitude))
        }

        val leg1Hits = hitStations(
            currentText,
            pickupRaw,
            formatAddressBlock(pickupAddress, pickupRaw)
        )

        val leg2Hits = hitStations(
            formatAddressBlock(pickupAddress, pickupRaw),
            pickupRaw,
            formatAddressBlock(dropoffAddress, dropoffRaw),
            dropoffRaw
        )

        return buildString {
            append("🌊 น้ำท่วมแบบสดจากข้อมูลสถานี กทม.\n")
            append("🕒 เวลาอัปเดตล่าสุดที่อ่านได้: $nowText\n")
            append("📡 สถานีที่อ่านได้วันนี้: ${rows.size} จุด\n\n")

            append("🛵 ไปจุดรับ: ")
            if (leg1Hits.isEmpty()) {
                append("🟢 ไม่พบสถานีท่วมที่จับคู่กับชื่อถนน/พื้นที่ของช่วงนี้\n")
            } else {
                append("🔴 พบ ${leg1Hits.size} จุดที่ชื่อถนน/พื้นที่จับคู่ได้\n")
                leg1Hits.take(5).forEach { row ->
                    append(stationLine(row)).append("\n")
                }
            }

            append("\n📦 จุดรับ → จุดส่ง: ")
            if (leg2Hits.isEmpty()) {
                append("🟢 ไม่พบสถานีท่วมที่จับคู่กับชื่อถนน/พื้นที่ของช่วงนี้\n")
            } else {
                append("🔴 พบ ${leg2Hits.size} จุดที่ชื่อถนน/พื้นที่จับคู่ได้\n")
                leg2Hits.take(5).forEach { row ->
                    append(stationLine(row)).append("\n")
                }
            }

            append("\n📍 จุดน้ำท่วมที่ กทม. รายงานล่าสุด\n")

            if (wet.isEmpty()) {
                append("🟢 ยังไม่พบสถานีที่สถานะเป็นน้ำท่วมในข้อมูลวันนี้\n")
            } else {
                wet.take(15).forEach { row ->
                    append(stationLine(row)).append("\n")
                }
                if (wet.size > 15) {
                    append("• และอีก ${wet.size - 15} จุด\n")
                }
            }

            append("\n⚠️ การจับคู่เส้นทางใช้ชื่อถนน/ข้อความจากที่อยู่ เพราะข้อมูลสถานีที่แอปอ่านได้ไม่มีเส้นเรขาคณิตของถนนจริง\n")
            append("⚠️ ไม่พบสถานีที่จับคู่ ≠ ถนนแห้ง\n")
            append("แหล่งข้อมูล: สำนักการระบายน้ำ กรุงเทพมหานคร")
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
    private fun hasLocation(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun startLocationUpdates() {
        if (!hasLocation()) return

        try {
            locationManager =
                getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )

            for (provider in providers) {
                try {
                    if (locationManager?.isProviderEnabled(provider) == true) {
                        locationManager?.getLastKnownLocation(provider)?.let {
                            currentLocation = it
                        }

                        locationManager?.requestLocationUpdates(
                            provider,
                            2000L,
                            5f,
                            locationListener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (_: SecurityException) {
                }
            }

            updateLocationStatus()
        } catch (_: Exception) {
            status.text = "⚠️ เริ่มติดตาม GPS ไม่สำเร็จ"
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
        }
        locationManager = null
    }

    private fun updateLocationStatus() {
        status.text = when {
            !hasLocation() ->
                "📍 GPS: ต้องอนุญาตตำแหน่ง"
            currentLocation == null ->
                "📍 GPS: พร้อม • กำลังรอตำแหน่งปัจจุบัน"
            else ->
                "📍 GPS: พร้อม • กำลังติดตามตำแหน่งปัจจุบัน"
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }
}
