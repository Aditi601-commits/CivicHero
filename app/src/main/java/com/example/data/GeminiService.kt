package com.example.data

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object GeminiService {
    private const val TAG = "GeminiService"
    private const val MODEL_NAME = "gemini-2.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Convert Bitmap to Base64
    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Resize bitmap to a reasonable size to save bandwidth and fit limits
        val maxDimension = 800
        val ratio = maxDimension.toFloat() / Math.max(width, height).toFloat()
        val finalBitmap = if (ratio < 1.0) {
            Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
        } else {
            this
        }
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun analyzeHazardImage(
        bitmap: Bitmap,
        reportTitle: String,
        reportDesc: String
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API key is missing or is placeholder value")
            return@withContext AnalysisResult(
                category = "Unclassified",
                severity = "Medium",
                explanation = "AI analysis not available: Gemni API key is not configured in Secrets panel. Please update your AI studio Secrets."
            )
        }

        val base64Image = bitmap.toBase64()
        val prompt = """
            You are a civic hazard inspector for the 'Community Hero' municipal system.
            Review this photograph of an reported municipal/citizen issue:
            Title: "$reportTitle"
            Description: "$reportDesc"
            
            Determine:
            1. Hazard Category (Select exactly one of these: "Pothole", "Water Leakage", "Damaged Streetlight", "Waste Management", "Public Infrastructure", or "Other").
            2. Severity (Select exactly one of these: "Low", "Medium", "High" - based on safety risk, road blocks, or public threat).
            3. Detailed explanation (1 to 2 sentences summarizing the safety risk or priority for municipal priority queue).
            
            Return output strictly as a JSON object, with no markdown code blocks, containing exactly three string fields: "category", "severity", and "explanation".
        """.trimIndent()

        try {
            // Build direct REST post body using standard org.json to avoid deserializing mismatch issues
            val partsArray = JSONArray().apply {
                // Add text prompt
                put(JSONObject().put("text", prompt))
                // Add image part
                put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    })
                })
            }

            val contentObject = JSONObject().apply {
                put("parts", partsArray)
            }

            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().put(contentObject))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }

            val requestBody = requestBodyJson.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val url = "$BASE_URL?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "API call failed with code: $code, message: $errorBody")
                    throw Exception("API call failed with code $code")
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response body")
                Log.d(TAG, "Full response: $responseBody")

                // Parse standard Gemini structure
                val rootJson = JSONObject(responseBody)
                val candidates = rootJson.getJSONArray("candidates")
                val firstCandidate = candidates.getJSONObject(0)
                val responseContent = firstCandidate.getJSONObject("content")
                val responseParts = responseContent.getJSONArray("parts")
                val firstPart = responseParts.getJSONObject(0)
                val textResponse = firstPart.getString("text").trim()

                Log.d(TAG, "Extracted text: $textResponse")

                // Try to parse the inner response text as a JSON object
                val cleanedJson = extractJson(textResponse)
                val innerJson = JSONObject(cleanedJson)
                val category = innerJson.optString("category", "Other")
                val severity = innerJson.optString("severity", "Medium")
                val explanation = innerJson.optString("explanation", "Analyzed successfully.")

                AnalysisResult(normalizeCategory(category), normalizeSeverity(severity), explanation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Gemini REST call", e)
            AnalysisResult(
                category = "Other",
                severity = "Medium",
                explanation = "AI analysis failed temporarily: ${e.localizedMessage ?: "Unknown Error"}. Please select values manually."
            )
        }
    }

    private fun extractJson(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            val startIdx = cleaned.indexOf("{")
            val endIdx = cleaned.lastIndexOf("}")
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                return cleaned.substring(startIdx, endIdx + 1)
            }
        }
        cleaned = cleaned.replace("```json", "").replace("```", "").trim()
        return cleaned
    }

    private fun normalizeCategory(cat: String): String {
        val valid = listOf("Pothole", "Water Leakage", "Damaged Streetlight", "Waste Management", "Public Infrastructure", "Other")
        return valid.firstOrNull { it.equals(cat.trim(), ignoreCase = true) } ?: "Other"
    }

    private fun normalizeSeverity(sev: String): String {
        val valid = listOf("Low", "Medium", "High")
        return valid.firstOrNull { it.equals(sev.trim(), ignoreCase = true) } ?: "Medium"
    }

    suspend fun autofillReportDetails(bitmap: Bitmap): AutofillResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API key is missing or is placeholder value")
            return@withContext AutofillResult(
                title = "Hazard Issue",
                description = "Reported community hazard. Details to be updated.",
                category = "Other",
                severity = "Medium",
                explanation = "AI Autofill not available: Gemini API key is not configured in Secrets panel. Please update your AI studio Secrets."
            )
        }

        val base64Image = bitmap.toBase64()
        val prompt = """
            You are a civic hazard inspector for the 'CivicHero' municipal system.
            Review this photograph of an reported municipal/citizen issue.
            
            Determine:
            1. A clear, concise, and professional Title for this report (e.g., "Deep Pothole at Main Intersection", "Broken Street Lamp causing Darkness"). Must be 2 to 6 words.
            2. A descriptive, helpful, and friendly Description of the hazard and its potential risks (e.g., "A deep active pothole that has caused minor bumper damage. High risk during evening hours.").
            3. Hazard Category (Select exactly one of these: "Pothole", "Water Leakage", "Damaged Streetlight", "Waste Management", "Public Infrastructure", or "Other").
            4. Severity (Select exactly one of these: "Low", "Medium", "High" - based on safety risk, road blocks, or public threat).
            5. Detailed explanation (1 to 2 sentences summarizing the safety risk).
            
            Return output strictly as a JSON object, with no markdown code blocks, containing exactly five string fields: "title", "description", "category", "severity", and "explanation".
        """.trimIndent()

        try {
            val partsArray = JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    })
                })
            }

            val contentObject = JSONObject().apply {
                put("parts", partsArray)
            }

            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().put(contentObject))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }

            val requestBody = requestBodyJson.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val url = "$BASE_URL?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("API call failed with code ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response body")
                val rootJson = JSONObject(responseBody)
                val candidates = rootJson.getJSONArray("candidates")
                val firstCandidate = candidates.getJSONObject(0)
                val responseContent = firstCandidate.getJSONObject("content")
                val responseParts = responseContent.getJSONArray("parts")
                val firstPart = responseParts.getJSONObject(0)
                val textResponse = firstPart.getString("text").trim()

                val cleanedJson = extractJson(textResponse)
                val innerJson = JSONObject(cleanedJson)
                val title = innerJson.optString("title", "Hazard Issue")
                val description = innerJson.optString("description", "Reported community hazard. Details to be updated.")
                val category = innerJson.optString("category", "Other")
                val severity = innerJson.optString("severity", "Medium")
                val explanation = innerJson.optString("explanation", "Autofilled successfully.")

                AutofillResult(title, description, normalizeCategory(category), normalizeSeverity(severity), explanation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Gemini REST autofill call", e)
            AutofillResult(
                title = "Hazard Issue",
                description = "Reported community hazard. Details to be updated.",
                category = "Other",
                severity = "Medium",
                explanation = "AI Autofill failed temporarily: ${e.localizedMessage ?: "Unknown Error"}."
            )
        }
    }

    data class AnalysisResult(
        val category: String,
        val severity: String,
        val explanation: String
    )

    data class AutofillResult(
        val title: String,
        val description: String,
        val category: String,
        val severity: String,
        val explanation: String
    )

    fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        val maxDimension = 500 // Balanced size for database and high performance display
        val ratio = maxDimension.toFloat() / Math.max(bitmap.width, bitmap.height).toFloat()
        val finalBitmap = if (ratio < 1.0) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else {
            bitmap
        }
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
