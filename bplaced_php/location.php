<?php
// 1. Datenbank-Zugangsdaten von bplaced einfügen
$host     = 'localhost'; // Bei bplaced meist 'localhost' oder '127.0.0.1'
$dbname   = 'almica_db';
$username = 'almica_db';
$password = 'Edc4#rfv';

// Verbindung zur Datenbank herstellen
try {
    $pdo = new PDO("mysql:host=$host;dbname=$dbname;charset=utf8mb4", $username, $password, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(["status" => "error", "message" => "Datenbankverbindung fehlgeschlagen."]);
    exit;
}

// 2. POST-Parameter empfangen und validieren
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    
    // Werte auslesen und filtern
    $title     = isset($_POST['title']) ? trim($_POST['title']) : 'Unbekannter Ort';
	$image_url = isset($_POST['image_url']) ? trim($_POST['image_url']) : '';
    $latitude  = isset($_POST['latitude']) ? filter_var($_POST['latitude'], FILTER_VALIDATE_FLOAT) : false;
    $longitude = isset($_POST['longitude']) ? filter_var($_POST['longitude'], FILTER_VALIDATE_FLOAT) : false;
	$altitude = isset($_POST['altitude']) ? filter_var($_POST['altitude'], FILTER_VALIDATE_FLOAT) : false;
	$temperature = isset($_POST['temperature']) ? filter_var($_POST['temperature'], FILTER_VALIDATE_FLOAT) : false;
	$battery_level = isset($_POST['battery_level']) ? filter_var($_POST['battery_level'], FILTER_VALIDATE_INT ) : false;
	$sos_flag = isset($_POST['sos_flag']) ? filter_var($_POST['sos_flag'], FILTER_VALIDATE_INT ) : false;

    // Plausibilitätsprüfung (Lat: -90 bis 90, Lon: -180 bis 180)
    if ($latitude === false || $longitude === false || 
        $latitude < -90 || $latitude > 90 || 
        $longitude < -180 || $longitude > 180) {
        
        http_response_code(400);
        echo json_encode(["status" => "error", "message" => "Ungültige Koordinaten übermittelt."]);
        exit;
    }

    // 3. Daten mit Prepared Statements einfügen
    try {
        $sql = "INSERT INTO locations (title, image_url, latitude, longitude, altitude, temperature, battery_level, sos_flag) VALUES (:title, :image_url, :latitude, :longitude, :altitude, :temperature, :battery_level, :sos_flag)";
        $stmt = $pdo->prepare($sql);
        
        $stmt->execute([
            ':title'     => $title,
			':image_url' => $image_url,
            ':latitude'  => $latitude,
            ':longitude' => $longitude, 
			':altitude' => $altitude,
			':temperature' => $temperature,
			':battery_level' => $battery_level,
			':sos_flag' => $sos_flag,
        ]);

        echo json_encode(["status" => "success", "message" => "Koordinaten erfolgreich gespeichert."]);
        
    } catch (PDOException $e) {
        http_response_code(500);
        echo json_encode(["status" => "error", "message" => "Fehler beim Speichern: " . $e->getMessage()]);
    }
} else {
    http_response_code(405);
    echo json_encode(["status" => "error", "message" => "Nur POST-Anfragen sind erlaubt."]);
}
?>
