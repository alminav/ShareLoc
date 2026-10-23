package com.almica.shareloc

data class LocationItem(
    val id: Int,
    val title: String?,
    val image_url: String?,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val temperature: Double?,
    val created_at: String,
    val sos_flag: Int
)

// Repräsentiert die gesamte JSON-Antwort von get_locations.php
data class FetchLocationsResponse(
    val status: String,
    val data: List<LocationItem>?,
    val message: String?
)