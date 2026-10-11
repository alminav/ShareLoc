# API & Backend Integration

**ShareLoc** communicates with a remote PHP REST API hosted on `bplaced.net` via **Retrofit 2** and **Gson**.

---

## 📡 REST API Endpoints

### 1. Save Location Telemetry
* **Endpoint**: `POST location.php`
* **Content-Type**: `application/x-www-form-urlencoded`
* **Parameters**:
  - `title` (String): Device name or custom title.
  - `latitude` (Double): Latitude coordinate.
  - `longitude` (Double): Longitude coordinate.
  - `altitude` (Double): Altitude in meters.
  - `temperature` (Float?): Battery temperature in °C.
* **Response**:
  ```json
  {
    "status": "success",
    "message": "Location saved successfully"
  }
  ```

---

### 2. Fetch Locations
* **Endpoint**: `GET get_locations.php`
* **Response**:
  ```json
  {
    "status": "success",
    "data": [
      {
        "id": 101,
        "title": "Pixel_8_Pro",
        "latitude": 51.1657,
        "longitude": 10.4515,
        "altitude": 180.0,
        "temperature": 28.5,
        "created_at": "2026-09-29 14:30:00"
      }
    ],
    "message": null
  }
  ```

---

### 3. Cleanup All Locations
* **Endpoint**: `POST location_cleanup.php`
* **Response**:
  ```json
  {
    "status": "success",
    "message": "All entries deleted"
  }
  ```

---

### 4. Delete Locations by Filter
* **Endpoint**: `POST location_delete_filter.php`
* **Content-Type**: `application/x-www-form-urlencoded`
* **Parameters**:
  - `title` (String): Device title or search query to match for deletion.
* **Response**:
  ```json
  {
    "status": "success",
    "message": "Filtered entries deleted"
  }
  ```
