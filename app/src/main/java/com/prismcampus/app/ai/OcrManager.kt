package com.prismcampus.app.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.OCRParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrManager(
    private val repository: CampusRepository,
    private val slmManager: LocalSLMManager
) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _lastResult = MutableStateFlow<OCRParseResult?>(null)
    val lastResult: StateFlow<OCRParseResult?> = _lastResult.asStateFlow()

    /**
     * Processes a camera/gallery Bitmap using Google ML Kit on-device Text Recognition.
     */
    suspend fun processImage(bitmap: Bitmap): OCRParseResult = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizeTextAsync(image)
            val parsedResult = slmManager.analyzeText(visionText)

            applyParsedResultToCampus(parsedResult)

            _lastResult.value = parsedResult
            parsedResult
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Parses raw string text directly (e.g. from simulation buttons or manual input).
     */
    suspend fun processTextDirectly(text: String): OCRParseResult = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            val parsedResult = slmManager.analyzeText(text)
            applyParsedResultToCampus(parsedResult)
            _lastResult.value = parsedResult
            parsedResult
        } finally {
            _isProcessing.value = false
        }
    }

    private fun applyParsedResultToCampus(parsedResult: OCRParseResult) {
        // Automatically switch section if detected
        parsedResult.detectedSection?.let { sectionId ->
            repository.setSelectedSection(sectionId)
        }

        // Automatically store urgent override if detected
        parsedResult.detectedOverride?.let { override ->
            repository.addOverride(override)
        }
    }

    // Quick Simulation Handlers requested by project specification
    suspend fun simulateSectionDScan(): OCRParseResult {
        val sampleD = """
            RAMAIAH INSTITUTE OF TECHNOLOGY, BENGALURU
            DEPARTMENT OF INFORMATION SCIENCE & ENGINEERING
            ODD SEMESTER TIME TABLE 2026-2027
            CLASS: 1ST YEAR B.E. | SECTION: D
            CLASS ROOM: AB - 504 (Apex Block, 3rd Floor)
            MON: PYC12 (09:00-10:00), MAT11 (10:00-11:00)
            TUE: PSCLCS18 LAB (14:00-16:00 Room 703)
        """.trimIndent()
        return processTextDirectly(sampleD)
    }

    suspend fun simulateSectionIScan(): OCRParseResult {
        val sampleI = """
            RAMAIAH INSTITUTE OF TECHNOLOGY, BENGALURU
            DEPARTMENT OF ARTIFICIAL INTELLIGENCE & MACHINE LEARNING
            TIMETABLE - 1ST SEMESTER 2026
            SECTION: I | ROOM: AB - 514
            FACULTY ADVISOR: Dr. Ravindra B.
            MONDAY: ELE13 (09:00), PYC12 (10:00), MAT11 (11:15)
            AFTERNOON: HSCP15 Soft Skills Lab in LHC Room 212
        """.trimIndent()
        return processTextDirectly(sampleI)
    }

    suspend fun simulateUrgentShiftScan(): OCRParseResult {
        val sampleShift = """
            *** MSRIT URGENT CIRCULAR / NOTICE ***
            DATE: 21-OCT-2026 | DEAN OF ACADEMICS
            ATTENTION 1ST YEAR STUDENTS (ALL SECTIONS):
            Due to ongoing renovation in AB-501,
            the Mathematics course MAC11 shifted to LHC Room 212 (Lecture Hall Complex, 2nd Floor).
            Please proceed directly to LHC 2nd Floor.
        """.trimIndent()
        return processTextDirectly(sampleShift)
    }

    private suspend fun recognizeTextAsync(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { exception ->
                    cont.resumeWithException(exception)
                }
        }
}
