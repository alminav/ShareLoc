# Welcome to the ShareLoc Wiki

**ShareLoc** is an Android application designed to monitor cellular data connectivity, track device location and battery temperature telemetry in the background, send SMS notifications, and log status updates to a remote PHP REST API (`bplaced.net`).

---

## 📌 Navigation

- [[Home]]
- [[Architecture & Tech Stack|Architecture-&-Tech-Stack]]
- [[Features & Usage|Features-&-Usage]]
- [[Setup & Installation|Setup-&-Installation]]
- [[API & Backend Integration|API-&-Backend-Integration]]
- [[Permissions & Security|Permissions-&-Security]]

---

## 🚀 Key Highlights

* **Real-time Connectivity Monitoring**: Live detection of mobile data status versus Wi-Fi connection state.
* **Background Telemetry Worker**: Periodic background scheduling using **AndroidX WorkManager** to gather location (GPS & Fused) and battery temperature metrics.
* **Instant SMS Alerting**: Interactive Compose dialog for user-triggered SMS messages, plus automatic background SMS logging.
* **Remote Backend Synchronization**: Synchronizes telemetry data with remote PHP endpoints (`location.php`, `get_locations.php`).
* **Google Maps Integration**: Direct integration to view recorded device coordinates in Google Maps.
* **Modern Jetpack Compose UI**: Built with Material 3 UI components, tabbed navigation, and custom filter/settings dialogs.
