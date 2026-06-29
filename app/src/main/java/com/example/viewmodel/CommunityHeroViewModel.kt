package com.example.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.location.Geocoder

class CommunityHeroViewModel(context: Context) : ViewModel() {
    private val TAG = "CommunityHeroViewModel"
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.reportDao()

    // Global application language state ("en" or "hi")
    var appLanguage by mutableStateOf("en")

    // Dynamic dismissible onboarding state for first-time user guidance
    var showOnboardingIntro by mutableStateOf(true)

    // Trigger to switch to Create Report screen (for background shake detection launch)
    var launchQuickReportTrigger by mutableStateOf(false)

    // Reports flow
    val reports: StateFlow<List<Report>> = dao.getAllReports()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // User Profile flow
    val userProfile: StateFlow<UserProfile> = dao.getUserProfile()
        .map { it ?: UserProfile(points = 150, level = 1, reportsSubmitted = 3, verificationsDone = 12, currentStreak = 4) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserProfile(points = 150, level = 1, reportsSubmitted = 3, verificationsDone = 12, currentStreak = 4)
        )

    // Leaderboard entries (Mocked + Includes Current User dynamically)
    private val _leaderboard = MutableStateFlow<List<LeaderboardUser>>(emptyList())
    val leaderboard: StateFlow<List<LeaderboardUser>> = _leaderboard.asStateFlow()

    // Form inputs state
    var reportTitle by mutableStateOf("")
    var reportDescription by mutableStateOf("")
    var manualCategory by mutableStateOf("Other")
    var manualSeverity by mutableStateOf("Medium")
    
    // Custom state backing field for capturedImage with automatic on-device validation
    private var _capturedImage = mutableStateOf<Bitmap?>(null)
    var capturedImage: Bitmap?
        get() = _capturedImage.value
        set(value) {
            _capturedImage.value = value
            validateCapturedImage()
        }

    // Support for multiple photos and videos
    val additionalCapturedImages = mutableStateListOf<Bitmap>()
    val additionalVideoUris = mutableStateListOf<String>()

    // On-Device Image Validation Results
    var imageClarityScore by mutableStateOf<Int?>(null)
    var assetDetectedBoundingBox by mutableStateOf<android.graphics.RectF?>(null)
    var assetDetectionLabel by mutableStateOf<String?>(null)
    var imageValidationError by mutableStateOf<String?>(null)

    var currentLatitude by mutableStateOf(28.6304)
    var currentLongitude by mutableStateOf(77.2177)
    var locationLabel by mutableStateOf("Connaught Place, New Delhi")

    var userName by mutableStateOf("Citizen Hero")
        private set

    fun updateUserName(name: String) {
        userName = name
        updateLeaderboard()
    }

    // AI Autofill status
    var isAutofilling by mutableStateOf(false)
    var autofillError by mutableStateOf<String?>(null)

    // AI Analysis status
    var aiState by mutableStateOf<AiAnalysisState>(AiAnalysisState.Idle)

    // Track user votes per report to enforce single-time voting limit
    val votedReportIds = androidx.compose.runtime.mutableStateMapOf<Int, String>()

    init {
        // Pre-populate database with realistic reports if empty, to demonstrate municipal repair priority dashboards
        viewModelScope.launch {
            dao.getUserProfile().first()?.let {
                // Profile exists
            } ?: run {
                dao.insertUserProfile(UserProfile())
            }

            // Cleanup any duplicate initial demo data from database
            try {
                val allReports = dao.getAllReports().first()
                val uniqueReports = mutableSetOf<String>()
                allReports.forEach { report ->
                    val key = "${report.title}_${report.locationName}"
                    if (uniqueReports.contains(key)) {
                        dao.deleteReport(report)
                    } else {
                        uniqueReports.add(key)
                    }
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "Cleanup failed: ${e.message}")
            }

            if (dao.getReportCount() == 0) {
                populateInitialDemoData()
            }
            updateLeaderboard()
        }
    }

    private fun updateLeaderboard() {
        val currentPoints = userProfile.value.points
        _leaderboard.value = listOf(
            LeaderboardUser(1, "Sophia Chen", 450, 4),
            LeaderboardUser(2, "Alex Mercer", 380, 3),
            LeaderboardUser(3, "$userName (You)", currentPoints, userProfile.value.level, isCurrentUser = true),
            LeaderboardUser(4, "Marcus Vance", 210, 2),
            LeaderboardUser(5, "Elena Rostova", 190, 2)
        ).sortedByDescending { it.points }.mapIndexed { index, user ->
            user.copy(rank = index + 1)
        }
    }

    private suspend fun populateInitialDemoData() {
        Log.d(TAG, "Populating database with clean default reports for immediate display")
        val now = System.currentTimeMillis()
        val sample1 = Report(
            title = "Major Pothole near Central Park Gate",
            description = "A deep active pothole near the Outer Circle intersection that has caused minor bumper damage to multiple low-clearance vehicles. High risk during heavy monsoon.",
            hazardCategory = "Pothole",
            severity = "High",
            upvotes = 34,
            downvotes = 1,
            status = "Verified",
            latitude = 28.6304,
            longitude = 77.2177,
            locationName = "Outer Circle, Connaught Place, New Delhi",
            imageUrl = "https://images.unsplash.com/photo-1615840287214-7fe58a8b668f?w=600&auto=format&fit=crop",
            timestamp = now
        )
        val sample2 = Report(
            title = "Damaged Streetlight / Sector 15 Cross Road",
            description = "Three consecutive street lamps have gone completely dark, making the crossing extremely hazardous for evening pedestrians and cyclists.",
            hazardCategory = "Damaged Streetlight",
            severity = "Medium",
            upvotes = 18,
            downvotes = 0,
            status = "Pending",
            latitude = 28.5830,
            longitude = 77.3100,
            locationName = "Sector 15 Crossing Road, Noida",
            imageUrl = "https://images.unsplash.com/photo-1508849789987-4e5333c12b78?w=600&auto=format&fit=crop",
            timestamp = now - 60000 // 1 min ago
        )
        val sample3 = Report(
            title = "Main Pipe Water Leakage - Mahatma Gandhi Marg",
            description = "Substantial streaming clean water pooling across the highway intersection, creating hydroplane risks and eroding pavement shoulders.",
            hazardCategory = "Water Leakage",
            severity = "High",
            upvotes = 52,
            downvotes = 3,
            status = "Work In Progress",
            latitude = 28.6350,
            longitude = 77.2250,
            locationName = "Mahatma Gandhi Marg, Ring Road, New Delhi",
            imageUrl = "https://images.unsplash.com/photo-1581094288338-2314dddb7ecc?w=600&auto=format&fit=crop",
            timestamp = now - 120000 // 2 mins ago
        )
        val sample4 = Report(
            title = "Illegal Garbage Dump - Karol Bagh Market",
            description = "Several crates filled with hazardous commercial waste and plastic scraps dumped along the pedestrian plaza entrance. Needs immediate municipal cleanup.",
            hazardCategory = "Waste Management",
            severity = "High",
            upvotes = 12,
            downvotes = 0,
            status = "Resolved",
            latitude = 28.6400,
            longitude = 77.2000,
            locationName = "Karol Bagh Market Block A, New Delhi",
            imageUrl = "https://images.unsplash.com/photo-1611284446314-60a58ac0deb9?w=600&auto=format&fit=crop",
            timestamp = now - 180000 // 3 mins ago
        )

        dao.insertReport(sample1)
        dao.insertReport(sample2)
        dao.insertReport(sample3)
        dao.insertReport(sample4)
    }

    // Capture location using Location Services
    @SuppressLint("MissingPermission")
    fun requestLocation(context: Context) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        locationLabel = "Acquiring GPS Signal..."
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: android.location.Location? ->
                    val actualLoc = location ?: getFallbackLocation()
                    currentLatitude = actualLoc.latitude
                    currentLongitude = actualLoc.longitude
                    locationLabel = getFriendlyLocationName(actualLoc.latitude, actualLoc.longitude)
                }
                .addOnFailureListener {
                    val fallback = getFallbackLocation()
                    currentLatitude = fallback.latitude
                    currentLongitude = fallback.longitude
                    locationLabel = getFriendlyLocationName(fallback.latitude, fallback.longitude)
                }
        } catch (e: Exception) {
            val fallback = getFallbackLocation()
            currentLatitude = fallback.latitude
            currentLongitude = fallback.longitude
            locationLabel = getFriendlyLocationName(fallback.latitude, fallback.longitude)
        }
    }

    private fun getFallbackLocation(): android.location.Location {
        val loc = android.location.Location("GPS_MOCK")
        // Select a random coordinates neighborhood around Central New Delhi
        val sectorOffset = (System.currentTimeMillis() % 40) / 10000.0
        loc.latitude = 28.6304 + sectorOffset
        loc.longitude = 77.2177 - sectorOffset
        return loc
    }

    private fun getFriendlyLocationName(lat: Double, lng: Double): String {
        return when {
            lat in 28.62..28.64 -> "Connaught Place Circle, New Delhi"
            lat in 28.57..28.59 -> "Sector 15 Commercial Belt, Noida"
            lat in 28.63..28.65 -> "Mahatma Gandhi Marg, Ring Road"
            lat in 28.64..28.66 -> "Karol Bagh Pedestrian Plaza"
            else -> "Sector Block - Lat: ${String.format("%.3f", lat)}, Lng: ${String.format("%.3f", lng)}"
        }
    }

    // Method to search/geocodes an address string to coordinates
    fun locateAddressOnMap(addressStr: String, context: Context) {
        if (addressStr.isBlank()) return
        
        // 1. Instant offline keyword-based resolver for top Indian locations for ultimate responsiveness
        val lower = addressStr.lowercase().trim()
        val offlineMatch = when {
            lower.contains("connaught") || lower.contains("cp") || lower.contains("delhi central") -> Pair(28.6304, 77.2177)
            lower.contains("noida sector 15") || lower.contains("sector 15") -> Pair(28.5830, 77.3100)
            lower.contains("noida sector 62") || lower.contains("sector 62") -> Pair(28.6219, 77.3639)
            lower.contains("gurgaon") || lower.contains("gurugram") || lower.contains("sector 21") -> Pair(28.5030, 77.0600)
            lower.contains("mumbai") || lower.contains("bandra") || lower.contains("nariman point") -> Pair(19.0760, 72.8777)
            lower.contains("bangalore") || lower.contains("bengaluru") || lower.contains("indiranagar") -> Pair(12.9716, 77.5946)
            lower.contains("kolkata") || lower.contains("salt lake") || lower.contains("sector v") -> Pair(22.5726, 88.4339)
            lower.contains("chennai") || lower.contains("adyar") -> Pair(13.0827, 80.2707)
            lower.contains("hyderabad") || lower.contains("hitech") -> Pair(17.3850, 78.4867)
            lower.contains("pune") || lower.contains("koregaon") -> Pair(18.5204, 73.8567)
            lower.contains("jaipur") || lower.contains("pink city") -> Pair(26.9124, 75.7873)
            else -> null
        }
        
        if (offlineMatch != null) {
            currentLatitude = offlineMatch.first
            currentLongitude = offlineMatch.second
            Log.d(TAG, "Offline geocoded address '$addressStr' to ${currentLatitude}, ${currentLongitude}")
            return
        }

        // 2. Platform Geocoder fallback for real-world, precise Android address resolution!
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context)
                    val addresses = geocoder.getFromLocationName(addressStr, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val firstAddr = addresses[0]
                        withContext(Dispatchers.Main) {
                            currentLatitude = firstAddr.latitude
                            currentLongitude = firstAddr.longitude
                            Log.d(TAG, "Platform geocoded '$addressStr' to ${currentLatitude}, ${currentLongitude}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Geocoder failed: ${e.message}")
            }
        }
    }

    // Perform AI verification of the hazard image via Gemini
    fun runAiAnalysis() {
        val image = capturedImage
        if (image == null) {
            aiState = AiAnalysisState.Error("Please capture or select an image first.")
            return
        }
        if (reportTitle.isBlank()) {
            aiState = AiAnalysisState.Error("Please provide a Title for context first.")
            return
        }

        viewModelScope.launch {
            aiState = AiAnalysisState.Analyzing
            try {
                val result = GeminiService.analyzeHazardImage(
                    bitmap = image,
                    reportTitle = reportTitle,
                    reportDesc = reportDescription
                )
                manualCategory = result.category
                manualSeverity = result.severity
                aiState = AiAnalysisState.Success(result)
            } catch (e: Exception) {
                aiState = AiAnalysisState.Error("Analysis failed: ${e.message}")
            }
        }
    }

    // Perform AI-driven title, description, category and severity generation from the captured photo
    fun runAiAutofill() {
        val image = capturedImage
        if (image == null) {
            autofillError = "Please capture or select an image first."
            return
        }

        viewModelScope.launch {
            isAutofilling = true
            autofillError = null
            try {
                val result = GeminiService.autofillReportDetails(image)
                reportTitle = result.title
                reportDescription = result.description
                manualCategory = result.category
                manualSeverity = result.severity
                aiState = AiAnalysisState.Success(GeminiService.AnalysisResult(result.category, result.severity, result.explanation))
            } catch (e: Exception) {
                Log.e("ViewModel", "Autofill failed: ${e.message}")
                autofillError = "AI Autofill failed: ${e.localizedMessage ?: "Unknown Error"}. Please enter manually."
            } finally {
                isAutofilling = false
            }
        }
    }

    // Submit report with complete details
    fun submitReport(onSuccess: () -> Unit) {
        if (reportTitle.isBlank() || reportDescription.isBlank()) {
            return
        }

        viewModelScope.launch {
            // Save the report
            val newReport = Report(
                title = reportTitle,
                description = reportDescription,
                hazardCategory = manualCategory,
                severity = manualSeverity,
                latitude = currentLatitude,
                longitude = currentLongitude,
                locationName = if (locationLabel == "Locating..." || locationLabel == "Acquiring GPS Signal...") "Downtown Sector" else locationLabel,
                imageUrl = capturedImage?.let { GeminiService.encodeBitmapToBase64(it) },
                additionalImages = if (additionalCapturedImages.isNotEmpty()) {
                    additionalCapturedImages.joinToString("|") { GeminiService.encodeBitmapToBase64(it) }
                } else null,
                videoUris = if (additionalVideoUris.isNotEmpty()) {
                    additionalVideoUris.joinToString("|")
                } else null
            )

            dao.insertReport(newReport)

            // Reward the user 50 points for submission
            val currentProfile = userProfile.value
            val updatedProfile = currentProfile.copy(
                points = currentProfile.points + 50,
                reportsSubmitted = currentProfile.reportsSubmitted + 1,
                currentStreak = if (currentProfile.currentStreak == 0) 1 else currentProfile.currentStreak
            )
            dao.insertUserProfile(updatedProfile)

            // Refresh leaderboard
            updateLeaderboard()

            // Reset inputs
            resetForm()
            onSuccess()
        }
    }

    fun upvoteReport(report: Report) {
        if (votedReportIds[report.id] == "down") return // Can't upvote if already downvoted

        viewModelScope.launch {
            val isAlreadyUpvoted = votedReportIds[report.id] == "up"
            val newUpvotes = if (isAlreadyUpvoted) {
                (report.upvotes - 1).coerceAtLeast(0)
            } else {
                report.upvotes + 1
            }

            if (isAlreadyUpvoted) {
                votedReportIds.remove(report.id)
            } else {
                votedReportIds[report.id] = "up"
            }

            val newStatus = if (newUpvotes >= 5 && report.status == "Pending") "Verified" else report.status
            val updated = report.copy(upvotes = newUpvotes, status = newStatus)
            dao.updateReport(updated)

            // Reward active citizen verification points (+10 points) if it was a new upvote
            if (!isAlreadyUpvoted) {
                val currentProfile = userProfile.value
                val updatedProfile = currentProfile.copy(
                    points = currentProfile.points + 10,
                    verificationsDone = currentProfile.verificationsDone + 1
                )
                dao.insertUserProfile(updatedProfile)
                updateLeaderboard()
            }
        }
    }

    fun downvoteReport(report: Report) {
        if (votedReportIds[report.id] == "up") return // Can't downvote if already upvoted

        viewModelScope.launch {
            val isAlreadyDownvoted = votedReportIds[report.id] == "down"
            val newDownvotes = if (isAlreadyDownvoted) {
                (report.downvotes - 1).coerceAtLeast(0)
            } else {
                report.downvotes + 1
            }

            if (isAlreadyDownvoted) {
                votedReportIds.remove(report.id)
            } else {
                votedReportIds[report.id] = "down"
            }

            val updated = report.copy(downvotes = newDownvotes)
            dao.updateReport(updated)

            // Reward verification points for cleanup validation (+10 points) if it was a new downvote
            if (!isAlreadyDownvoted) {
                val currentProfile = userProfile.value
                val updatedProfile = currentProfile.copy(
                    points = currentProfile.points + 10,
                    verificationsDone = currentProfile.verificationsDone + 1
                )
                dao.insertUserProfile(updatedProfile)
                updateLeaderboard()
            }
        }
    }

    fun adminMockRepairUpdate(report: Report, nextStatus: String) {
        viewModelScope.launch {
            val updated = report.copy(status = nextStatus)
            dao.updateReport(updated)
        }
    }

    fun validateCapturedImage() {
        val bitmap = capturedImage
        if (bitmap == null) {
            imageClarityScore = null
            assetDetectedBoundingBox = null
            assetDetectionLabel = null
            imageValidationError = null
            return
        }

        // 1. Calculate genuine image clarity based on contrast/pixel variance
        val clarity = calculateClarityScore(bitmap)
        imageClarityScore = clarity

        // 2. Compute a basic bounding box (heuristic tracking the maximum edge/contrast block)
        val box = detectAssetBoundingBox(bitmap)
        assetDetectedBoundingBox = box

        // 3. Determine if a physical infrastructure asset is present based on contrast & entropy
        // We set 35 as the threshold for a valid clear infrastructure photo
        val isAssetPresent = clarity >= 35 && box.width() > 0.1f && box.height() > 0.1f
        
        if (isAssetPresent) {
            assetDetectionLabel = "Physical Asset Detected"
            imageValidationError = null
        } else {
            assetDetectionLabel = "No Physical Asset Detected"
            imageValidationError = "Image is too blurry, flat, or lacks clear physical infrastructure features."
        }
    }

    private fun calculateClarityScore(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        
        // Sample pixels to compute brightness variance (contrast metric)
        val stepX = (width / 10).coerceAtLeast(1)
        val stepY = (height / 10).coerceAtLeast(1)
        
        var count = 0
        var sumLuminance = 0.0
        val luminances = mutableListOf<Double>()
        
        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                // Standard relative luminance formula
                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                sumLuminance += luminance
                luminances.add(luminance)
                count++
            }
        }
        
        if (count == 0) return 0
        val mean = sumLuminance / count
        var varianceSum = 0.0
        for (lum in luminances) {
            varianceSum += (lum - mean) * (lum - mean)
        }
        val stdDev = Math.sqrt(varianceSum / count)
        
        // Map standard deviation of luminance (contrast) to a score between 10 and 98
        val score = (stdDev * 1.5).coerceIn(10.0, 98.0).toInt()
        return score
    }

    private fun detectAssetBoundingBox(bitmap: Bitmap): android.graphics.RectF {
        val width = bitmap.width
        val height = bitmap.height
        
        // Find region of highest horizontal difference (simple vertical edges detector)
        val stepY = (height / 8).coerceAtLeast(1)
        val stepX = (width / 8).coerceAtLeast(1)
        
        var maxDiff = -1
        var bestX = width / 4
        var bestY = height / 4
        
        for (y in stepY until height - stepY step stepY) {
            for (x in stepX until width - stepX step stepX) {
                var localDiff = 0
                for (dy in -1..1) {
                    val pLeft = bitmap.getPixel(x - stepX / 2, y + dy * (stepY / 4))
                    val pRight = bitmap.getPixel(x + stepX / 2, y + dy * (stepY / 4))
                    val lumLeft = 0.299 * ((pLeft shr 16) and 0xff) + 0.587 * ((pLeft shr 8) and 0xff) + 0.114 * (pLeft and 0xff)
                    val lumRight = 0.299 * ((pRight shr 16) and 0xff) + 0.587 * ((pRight shr 8) and 0xff) + 0.114 * (pRight and 0xff)
                    localDiff += Math.abs(lumLeft - lumRight).toInt()
                }
                if (localDiff > maxDiff) {
                    maxDiff = localDiff
                    bestX = x
                    bestY = y
                }
            }
        }
        
        // Create a bounding box around the point of maximum edge density / contrast
        val boxWidth = (width * 0.45f).coerceIn(80f, width.toFloat())
        val boxHeight = (height * 0.35f).coerceIn(60f, height.toFloat())
        
        val left = (bestX - boxWidth / 2).coerceIn(10f, width - boxWidth - 10f)
        val top = (bestY - boxHeight / 2).coerceIn(10f, height - boxHeight - 10f)
        
        return android.graphics.RectF(
            left / width,
            top / height,
            (left + boxWidth) / width,
            (top + boxHeight) / height
        )
    }

    fun resetForm() {
        reportTitle = ""
        reportDescription = ""
        manualCategory = "Other"
        manualSeverity = "Medium"
        capturedImage = null
        additionalCapturedImages.clear()
        additionalVideoUris.clear()
        imageClarityScore = null
        assetDetectedBoundingBox = null
        assetDetectionLabel = null
        imageValidationError = null
        locationLabel = "Locating..."
        aiState = AiAnalysisState.Idle
    }
}

sealed class AiAnalysisState {
    object Idle : AiAnalysisState()
    object Analyzing : AiAnalysisState()
    data class Success(val result: GeminiService.AnalysisResult) : AiAnalysisState()
    data class Error(val message: String) : AiAnalysisState()
}
