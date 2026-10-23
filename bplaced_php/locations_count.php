<?php
header('Content-Type: application/json; charset=utf-8');

$host     = 'localhost'; // Bei bplaced meist 'localhost' oder '127.0.0.1'
$dbname   = 'almica_db';
$username = 'almica_db';
$password = 'Edc4#rfv';

try {
    $pdo = new PDO("mysql:host=$host;dbname=$dbname;charset=utf8mb4", $username, $password, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);

    // 1. Parameter 'count' aus der URL auslesen (z.B. ?count=5)
    // Wenn kein Parameter übergeben wurde oder er ungültig ist, wird standardmäßig kein Limit gesetzt (bzw. ein hohes Limit)
    $count = isset($_GET['count']) ? filter_var($_GET['count'], FILTER_VALIDATE_INT) : false;

    // 2. SQL-Query dynamisch aufbauen
    if ($count !== false && $count > 0) {
        // Bei LIMIT mit Prepared Statements müssen wir den Wert explizit als Integer binden
        $sql = "SELECT id, title, image_url, latitude, longitude, altitude, temperature, created_at, sos_flag FROM locations ORDER BY id DESC LIMIT :count";
        $stmt = $pdo->prepare($sql);
        $stmt->bindValue(':count', $count, PDO::PARAM_INT);
        $stmt->execute();
    } else {
        // Ohne Limit alle Einträge laden
        $sql = "SELECT id, title, image_url, latitude, longitude, altitude, temperature, created_at, sos_flag FROM locations ORDER BY id DESC";
        $stmt = $pdo->query($sql);
    }

    $locations = $stmt->fetchAll();

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
