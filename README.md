# ShareLoc

**ShareLoc** is an Android application designed to monitor cellular data connectivity, track device location telemetry in the background, send SMS notifications, and log status updates to a remote API.

---

## 🚀 Features

- **Live Data & Network Monitoring**: Real-time detection of active cellular data vs. Wi-Fi connection state.
- **Background Task Execution**: Periodic background scheduling using **AndroidX WorkManager** to record device metrics automatically.
- **Location & Telemetry Logging**: Retrieves device GPS coordinates (latitude, longitude, altitude) and sends telemetry data to a remote backend service via Retrofit.
- **SMS & System Notifications**: Automated alerting via SMS and Android system notifications when connectivity state changes occur.
- **Location History & Maps Integration**: Fetches saved locations from the backend and opens coordinates directly in Google Maps.
- **Modern Jetpack Compose UI**: Built with Material 3 components, tab navigation, and settings configuration dialogs.

---

## 🛠️ Tech Stack & Architecture

- **Language**: [Kotlin](https://kotlinlang.org/)
- **UI Framework**: [Jetpack Compose](https://developer.android.com/compose) with Material 3
- **Asynchronous Processing**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & `StateFlow` / `LiveData`
- **Background Scheduling**: [AndroidX WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- **Networking**: [Retrofit 2](https://square.github.io/retrofit/) & Gson Converter
- **Location Services**: Google Play Services (`FusedLocationProviderClient`)
- **Logging**: [Timber](https://github.com/JakeWharton/timber)

---

## 🔐 Required Permissions

The app utilizes several Android system permissions:
- `INTERNET` & `ACCESS_NETWORK_STATE`: Network monitoring and API communication.
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`: High-precision location capture.
- `ACCESS_BACKGROUND_LOCATION`: Background location updates during scheduled worker runs.
- `READ_PHONE_STATE` & `READ_BASIC_PHONE_STATE`: Telephony state inspection.
- `SEND_SMS`: Sending SMS status alerts to configured contact numbers.
- `POST_NOTIFICATIONS`: Displaying system alerts on Android 13+.

---

## 🏁 Getting Started

### Prerequisites
- **Android Studio** Ladybug or newer
- **JDK 17**
- **Android SDK** API 24 (Android 7.0) minimum, compiled against API 35/37

### Building the Project

1. **Clone the repository**:
   ```bash
   git clone https://github.com/alminav/ShareLoc.git
   cd ShareLoc
   ```

2. **Open in Android Studio**:
   Import the project directory and wait for Gradle sync to complete.

3. **Build & Run**:
   Use `Shift + F10` in Android Studio or run via command line:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
