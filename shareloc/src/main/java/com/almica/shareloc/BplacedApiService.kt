package com.almica.shareloc

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

data class ApiResponse(
    val status: String,
    val message: String,
)
data class UploadResponse(val status: String, val message: String, val url: String?)
interface BplacedApiService {
    @FormUrlEncoded
    @POST("location.php")
    suspend fun saveLocation(
        @Field("title") title: String,
        @Field("image_url") imageUrl: String?,
        @Field("latitude") latitude: Double,
        @Field("longitude") longitude: Double,
        @Field("altitude") altitude: Double,
        @Field("temperature") temperature: Float?,
        @Field("battery_level") level: Int?,
        @Field("sos_flag") sos_flag: Int,
    ): Response<ApiResponse>

    // Neue Methode zum Abrufen der Standorte
    @GET("get_locations.php")
    suspend fun fetchLocations(): Response<FetchLocationsResponse>
    // Neue Methode zum Löschen aller Einträge
    @POST("location_cleanup.php")
    suspend fun deleteAllLocations(): Response<ApiResponse>
    @FormUrlEncoded
    @POST("location_delete_filter.php")
    suspend fun deleteLocationByFilter(
        @Field("title") title: String
    ): Response<ApiResponse>
    @FormUrlEncoded
    @POST("delete_old.php")
    suspend fun deleteOldRecords(
        @Field("days") days: Int
    ): Response<ApiResponse>
    // Die aktualisierte GET-Methode mit dem optionalen count-Parameter
    @GET("locations_count.php")
    suspend fun getLocationsCount(
        @Query("count") count: Int? = null
    ): Response<FetchLocationsResponse>
    @Multipart
    @POST("location_with_photo.php")
    suspend fun uploadLocationWithPhoto(
        @Part("title") title: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<UploadResponse>
    @FormUrlEncoded
    /**
     * When PHP scripts inspect $_GET (or $request) rather than
     * $_POST (similar to how locations_count.php reads $_GET['count']),
     * any parameters sent solely via POST body fields (@Field) result in null values in PHP.
     * Additionally, if the PHP script checks alternate parameter names (e.g. file, email, lat),
     * missing fields evaluate to null.
     * ◦
     * Configured sendEmail to send parameters simultaneously via URL Query Parameters (@Query)
     * and Form Fields (@Field).
     * ◦
     * Included parameter aliases (file, filename, email, to, lat, lon)
     * alongside datei, mailto, latitude, longitude.
     * ◦
     * This ensures $_GET, $_POST, and $_REQUEST in send_email.php
     * all receive non-null values regardless of how the PHP backend extracts parameters.
     */
    @POST("send_email.php")
    suspend fun sendEmail(
        @Query("datei") dateiQuery: String,
        @Query("mailto") mailtoQuery: String,
        @Query("latitude") latitudeQuery: Double,
        @Query("longitude") longitudeQuery: Double,
        @Query("file") fileQuery: String,
        @Query("filename") filenameQuery: String,
        @Query("email") emailQuery: String,
        @Query("to") toQuery: String,
        @Query("lat") latQuery: Double,
        @Query("lon") lonQuery: Double,
        @Field("datei") dateiField: String,
        @Field("mailto") mailtoField: String,
        @Field("latitude") latitudeField: Double,
        @Field("longitude") longitudeField: Double,
        @Field("file") fileField: String,
        @Field("filename") filenameField: String,
        @Field("email") emailField: String,
        @Field("to") toField: String,
        @Field("lat") latField: Double,
        @Field("lon") lonField: Double
    ): Response<ResponseBody>
}
