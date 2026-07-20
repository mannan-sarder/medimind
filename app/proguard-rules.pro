# ─────────────────────────────────────────────────────────────────────────────
# proguard-rules.pro  —  MediMind (offline)
# ─────────────────────────────────────────────────────────────────────────────

# ── ML Kit Text Recognition (on-device OCR) ───────────────────────────────────
# ML Kit uses reflection internally for its on-device model loading.
# Without these rules the release build can crash with ClassNotFoundException.
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**

# ── Gson (AnalysisResult / TestResult JSON serialisation) ────────────────────
# Gson uses field names via reflection to serialise/deserialise Room JSON.
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep all data model and DB classes used in Gson serialisation
-keep class com.mannan.medimind.ReportAnalyzer$AnalysisResult { *; }
-keep class com.mannan.medimind.TestResult { *; }
-keep class com.mannan.medimind.MedicalDataModel { *; }
-keep class com.mannan.medimind.MedicalDataModel$** { *; }

# ── Room database entities and DAOs ──────────────────────────────────────────
-keep class com.mannan.medimind.db.** { *; }
-keep class com.mannan.medimind.remind.Remind { *; }
-keep class com.mannan.medimind.remind.RemindDao { *; }
-keep class com.mannan.medimind.DatabaseHelper { *; }

# ── Suppress known harmless warnings ─────────────────────────────────────────
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**