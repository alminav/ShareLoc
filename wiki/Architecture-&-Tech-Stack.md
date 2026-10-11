# Architecture & Tech Stack

## 📐 Architecture Overview

**ShareLoc** follows standard modern Android architecture principles (**MVVM** – Model-View-ViewModel) paired with declarative UI using **Jetpack Compose**.

```
+-------------------------------------------------------------+
|                       UI Layer                              |
|   (MainActivity, MainScreen, WorkerControlCard, Dialogs)    |
+------------------------------+------------------------------+
                               | Collects StateFlows
                               v
+-------------------------------------------------------------+
|                     ViewModel Layer                         |
|             (MainViewModel, BplacedViewModel)               |
+------------------------------+------------------------------+
                               | Coroutines / WorkManager
                               v
+-------------------------------------------------------------+
|                 Data & Background Layer                     |
|  (NetworkMonitor, PreferenceManager, MobileDataCheckWorker, |
|           NetworkClient, BplacedApiService)                 |
+-------------------------------------------------------------+
```

---

## 🛠️ Tech Stack & Components

| Category | Library / Tool | Usage |
| :--- | :--- | :--- |
| **Language** | Kotlin 2.x | Core programming language |
| **UI Toolkit** | Jetpack Compose (Material 3) | Declarative UI, state management, dialogs |
| **Asynchronous** | Kotlin Coroutines & `StateFlow` | Reactive UI updates and asynchronous tasks |
| **Background Work** | AndroidX WorkManager | Periodic background worker execution (`MobileDataCheckWorker`) |
| **Networking** | Retrofit 2 & Gson | REST API client for remote location logging |
| **Location** | Google Play Services (`FusedLocationProviderClient`) + `LocationManager` | GPS & fallback location retrieval |
| **Telephony & SMS** | `TelephonyManager`, `SmsManager` | Mobile data detection and SMS sending |
| **Logging** | Timber | Lightweight structured logging |

---

## 📂 Key Source Code Structure

- `MainActivity.kt`: Contains the primary Jetpack Compose UI (`MainScreen`, `WorkerControlCard`, `StatusCard`, `SendSmsDialog`, `LocationListDialog`, `SettingsDialog`).
- `MainViewModel.kt`: Exposes `StateFlow` streams for network status, worker lifecycle, preferences, and location lists.
- `MobileDataCheckWorker.kt`: `CoroutineWorker` handling background GPS acquisition, temperature collection, SMS dispatch, and API updates.
- `NetworkMonitor.kt`: Listens to network connectivity state changes via `ConnectivityManager`.
- `BplacedApiService.kt`: Retrofit interface defining REST endpoints for location persistence and retrieval.
- `PreferenceManager.kt`: Encapsulates `SharedPreferences` for phone numbers and worker intervals.
