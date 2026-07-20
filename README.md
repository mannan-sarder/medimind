# MediMind 🩺

> A fully offline Android app that scans medical lab reports via OCR, compares results against WHO/NIH standard ranges, and shows bilingual (Bangla/English) analysis with report history and medicine reminders.

**Built by [MD Mannan Sarder](https://github.com/mannan-sarder)**

---

## Screenshots
<img width="4479" height="2141" alt="Cover page" src="https://github.com/user-attachments/assets/d7b352c7-0421-462d-bbc8-3be61eb39e5d" />

> _Add screenshots here_

---

## Features

| Feature | Description |
|---|---|
| 📷 **Report Scanning** | Camera or gallery — auto-corrects image rotation via EXIF |
| 🔍 **On-Device OCR** | ML Kit extracts test names, values, and units — no internet needed |
| 📊 **Smart Analysis** | Results flagged Normal / Low / High against WHO/NIH reference ranges |
| 🌐 **Bilingual Output** | Analysis shown in both Bangla and English |
| 📁 **Report History** | All reports stored locally using Room database |
| 📈 **Compare Reports** | Side-by-side grouped bar chart comparison of any two reports |
| ⏰ **Medicine Reminders** | Alarm + notification based medicine reminders, restored on reboot |
| 👤 **Patient Profile** | Stores name, age, and gender per report |
| 🌙 **Dark Mode** | Full dark theme support |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java |
| Database | Room (SQLite) v2.6.1 |
| OCR | Google ML Kit Text Recognition (on-device) |
| Navigation | AndroidX Navigation Component v2.8.3 |
| Charts | MPAndroidChart v3.1.0 |
| JSON | Gson v2.11.0 |
| Architecture | Fragment + ViewModel + LiveData |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 36 |

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog or newer
- JDK 11
- Android device or emulator (API 24+)

### Clone & Run

```bash
git clone https://github.com/mannan-sarder/medimind.git
cd medimind
```

1. Open the project in **Android Studio**
2. Wait for Gradle sync to complete
3. Connect a device or start an emulator
4. Click **Run ▶**

> ✅ No API key required. No internet connection needed. Fully offline.

---

## Project Structure

```
app/src/main/java/com/mannan/medimind/
├── db/                    # Room entities and DAOs
├── remind/                # Reminder feature (alarm + notification)
├── AnalyzeFragment        # Camera capture + OCR trigger
├── ReportParser           # Raw OCR text → structured test items
├── ReportAnalyzer         # Test values vs WHO/NIH reference ranges
├── MedicalKnowledgeBase   # Built-in knowledge base (dataset.json)
├── CompareFragment        # Side-by-side report comparison chart
├── HistoryFragment        # Past reports list
├── ResultFragment         # Analysis result display
├── RemindFragment         # Medicine reminders
├── DatabaseHelper         # Room database singleton (v4 with migrations)
├── LoginActivity          # User authentication
├── SignupActivity         # User registration
├── DashboardActivity      # Main container
└── ProfileActivity        # User profile management
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | Capture report images |
| `POST_NOTIFICATIONS` | Medicine reminder notifications |
| `SCHEDULE_EXACT_ALARM` | Precise alarm timing |
| `RECEIVE_BOOT_COMPLETED` | Restore alarms after device reboot |
| `VIBRATE` | Alarm vibration |

---

## Connect

- **Portfolio** — [mannansarder.vercel.app](https://mannansarder.vercel.app)
- **GitHub** — [@mannan-sarder](https://github.com/mannan-sarder)
- **LinkedIn** — [mannansarder](https://linkedin.com/in/mannansarder)
- **Email** — [mannansarder00@gmail.com](mailto:mannansarder00@gmail.com)
