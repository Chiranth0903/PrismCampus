package com.prismcampus.app.tour

import android.content.Context
import com.prismcampus.app.ai.SupportedLanguage
import com.prismcampus.app.ai.VoiceAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CampusPersona {
    STUDENT,
    VISITOR_PARENT_TOUR
}

data class LandmarkTourPoint(
    val id: String,
    val name: String,
    val code: String,
    val latitude: Double,
    val longitude: Double,
    val yearEstablished: String,
    val shortSubtitle: String,
    val departmentsHoused: List<String>,
    val accreditationHighlights: String,
    val narrationEn: String,
    val narrationHi: String,
    val narrationKn: String
)

class TourGuideController(
    private val voiceManager: VoiceAssistantManager
) {
    private val _activePersona = MutableStateFlow(CampusPersona.STUDENT)
    val activePersona: StateFlow<CampusPersona> = _activePersona.asStateFlow()

    private val _selectedLandmark = MutableStateFlow<LandmarkTourPoint?>(null)
    val selectedLandmark: StateFlow<LandmarkTourPoint?> = _selectedLandmark.asStateFlow()

    val landmarks: List<LandmarkTourPoint> = listOf(
        LandmarkTourPoint(
            id = "apex_block",
            name = "Apex Block (Academic Headquarters)",
            code = "AB",
            latitude = 13.0303,
            longitude = 77.5647,
            yearEstablished = "1962 Foundation Complex",
            shortSubtitle = "Administrative Headquarters, Advanced Computing Labs, Principal's Office",
            departmentsHoused = listOf(
                "Computer Science & Engineering (NBA Tier-1)",
                "Information Science & Engineering",
                "Artificial Intelligence & Machine Learning",
                "Principal & Dean of Academics Secretariat",
                "Central Robotics & Edge-AI Foundry"
            ),
            accreditationHighlights = "NAAC A+ Accredited Institution • Autonomous Under VTU • NIRF Top 100",
            narrationEn = "Welcome to the Apex Block, the intellectual heart of Ramaiah Institute of Technology. Established by visionary industrialist Dr. M.S. Ramaiah, this 7-story facility houses the Department of Computer Science and cutting-edge edge AI laboratories. RIT is NAAC A+ accredited and consistently ranked among India's top engineering colleges.",
            narrationHi = "रामाया इंस्टीट्यूट ऑफ टेक्नोलॉजी के एपेक्स ब्लॉक में आपका स्वागत है। दूरदर्शी उद्योगपति डॉ. एम.एस. रामाया द्वारा स्थापित, यह 7 मंजिला इमारत कंप्यूटर साइंस विभाग और उन्नत एआई प्रयोगशालाओं का केंद्र है। कॉलेज NAAC A+ से मान्यता प्राप्त है और भारत के शीर्ष इंजीनियरिंग संस्थानों में गिना जाता है।",
            narrationKn = "ರಾಮಯ್ಯ ತಾಂತ್ರಿಕ ಮಹಾವಿದ್ಯಾಲಯದ ಅಪೆಕ್ಸ್ ಬ್ಲಾಕ್‌ಗೆ ಆತ್ಮೀಯ ಸ್ವಾಗತ. ಡಾ. ಎಂ.ಎಸ್. ರಾಮಯ್ಯನವರಿಂದ ಸ್ಥಾಪಿತವಾದ ಈ 7 ಮಹಡಿಗಳ ಶೈಕ್ಷಣಿಕ ಸಂಕೀರ್ಣವು ಕಂಪ್ಯೂಟರ್ ಸೈನ್ಸ್ ವಿಭಾಗ ಮತ್ತು ಪ್ರಮುಖ ಸಂಶೋಧನಾ ಪ್ರಯೋಗಾಲಯಗಳನ್ನು ಹೊಂದಿದೆ. ನಮ್ಮ ಸಂಸ್ಥೆಯು NAAC A+ ಮಾನ್ಯತೆ ಪಡೆದ ಹೆಮ್ಮೆಯ ಶಿಕ್ಷಣ ಕೇಂದ್ರವಾಗಿದೆ."
        ),
        LandmarkTourPoint(
            id = "eng_sciences_block",
            name = "Engineering Sciences Block",
            code = "ESB",
            latitude = 13.0313,
            longitude = 77.5648,
            yearEstablished = "Modernized 2018",
            shortSubtitle = "Advanced Robotics, CAD Centers & Core Engineering Labs",
            departmentsHoused = listOf(
                "Mechanical Engineering & Advanced CAD/CAM",
                "Civil Engineering & Material Testing Center",
                "Siemens Center of Digital Excellence",
                "Rapid Prototyping & 3D Additive Manufacturing"
            ),
            accreditationHighlights = "Funded by DST & Industry Partners • 18 State Patents Filed",
            narrationEn = "You are standing near the Engineering Sciences Block. This hub features industrial-grade Computer Aided Design studios, advanced CNC equipment, and robotics centers where students engineer autonomous systems and sustainable infrastructure.",
            narrationHi = "यह इंजीनियरिंग साइंसेज ब्लॉक है। इस संकुल में उन्नत कंप्यूटर एडेड डिजाइन (CAD) स्टूडियो, रोबोटिक्स केंद्र और आधुनिक कार्यशालाएं हैं जहां छात्र भविष्य की तकनीकी परियोजनाओं का निर्माण करते हैं।",
            narrationKn = "ಇದು ಎಂಜಿನಿಯರಿಂಗ್ ಸೈನ್ಸಸ್ ಬ್ಲಾಕ್. ಇಲ್ಲಿ ಅತ್ಯಾಧುನಿಕ ಸಿಎಡಿ (CAD) ಲ್ಯಾಬ್‌ಗಳು, ರೊಬೊಟಿಕ್ಸ್ ಮತ್ತು ಕೈಗಾರಿಕಾ ಸಂಶೋಧನಾ ಕೇಂದ್ರಗಳಿವೆ. ನಮ್ಮ ವಿದ್ಯಾರ್ಥಿಗಳು ನಾವೀನ್ಯತೆಯ ತಂತ್ರಜ್ಞಾನಗಳನ್ನು ಇಲ್ಲಿ ರೂಪಿಸುತ್ತಾರೆ."
        ),
        LandmarkTourPoint(
            id = "lecture_hall_complex",
            name = "Lecture Hall Complex (LHC)",
            code = "LHC",
            latitude = 13.0324,
            longitude = 77.5659,
            yearEstablished = "Expansion Wing 2012",
            shortSubtitle = "Auditoriums, Seminar Halls & Placement Training Facility",
            departmentsHoused = listOf(
                "Department of Training & Placement (1200+ Offers annually)",
                "Dr. M.S. Ramaiah Memorial Auditorium",
                "Soft Skills & Global Language Studios (HSCP15)",
                "Executive Seminar Suites"
            ),
            accreditationHighlights = "Over 350+ Global Recruiters Visit Annually (Google, Cisco, Intel, Boeing)",
            narrationEn = "This is the Lecture Hall Complex and Placement Center. Over 350 premier multinational enterprises recruit here every year, offering students stellar career trajectories across engineering, AI, and management disciplines.",
            narrationHi = "यह लेक्चर हॉल कॉम्प्लेक्स और ट्रेनिंग व प्लेसमेंट सेंटर है। हर साल 350 से अधिक वैश्विक तकनीकी कंपनियां यहां छात्रों को बेहतरीन रोजगार अवसर प्रदान करने आती हैं।",
            narrationKn = "ಇದು ಲೆಕ್ಚರ್ ಹಾಲ್ ಕಾಂಪ್ಲೆಕ್ಸ್ ಮತ್ತು ಪ್ಲೇಸ್‌ಮೆಂಟ್ ಕೇಂದ್ರ. ಪ್ರತಿ ವರ್ಷ 350 ಕ್ಕೂ ಹೆಚ್ಚು ಪ್ರಮುಖ ಜಾಗತಿಕ ಕಂಪನಿಗಳು ನಮ್ಮ ವಿದ್ಯಾರ್ಥಿಗಳಿಗೆ ಅತ್ಯುತ್ತಮ ಉದ್ಯೋಗಾವಕಾಶಗಳನ್ನು ಒದಗಿಸುತ್ತವೆ."
        )
    )

    fun togglePersona() {
        _activePersona.value = if (_activePersona.value == CampusPersona.STUDENT) {
            CampusPersona.VISITOR_PARENT_TOUR
        } else {
            CampusPersona.STUDENT
        }
    }

    fun setPersona(persona: CampusPersona) {
        _activePersona.value = persona
    }

    fun selectLandmark(landmark: LandmarkTourPoint) {
        _selectedLandmark.value = landmark
    }

    fun clearSelectedLandmark() {
        _selectedLandmark.value = null
    }

    fun playNarrationForLandmark(landmark: LandmarkTourPoint, language: SupportedLanguage) {
        _selectedLandmark.value = landmark
        val text = when (language) {
            SupportedLanguage.KANNADA -> landmark.narrationKn
            SupportedLanguage.HINDI -> landmark.narrationHi
            SupportedLanguage.ENGLISH -> landmark.narrationEn
        }
        voiceManager.speakDirectly(text)
    }
}
