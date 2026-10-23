<?php
header('Content-Type: application/json; charset=utf-8');

$host     = 'localhost'; 
$dbname   = 'almica_db';
$username = 'almica_db';
$password = 'Edc4#rfv';

try {
    $pdo = new PDO("mysql:host=$host;dbname=$dbname;charset=utf8mb4", $username, $password, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);

    // Alle Standorte aus der Datenbank abfragen
    $stmt = $pdo->query("SELECT id, title, image_url, latitude, longitude, altitude, temperature, battery_level, created_at, sos_flag FROM locations ORDER BY id DESC");
    $locations = $stmt->fetchAll();

    // Erfolgreiche Antwort senden
    echo json_encode([
        "status" => "success",
        "data" => $locations
    ]);

} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode([
        "status" => "error",
        "message" => "Datenbankfehler: " . $e->getMessage()
    ]);
}
?>
