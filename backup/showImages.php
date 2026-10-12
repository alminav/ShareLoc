<!DOCTYPE html>
<html lang="de">
<head>
    <meta charset="UTF-8">
    <title>Bildergalerie mit Download und Lösch-Button</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 0;
            padding: 20px;
            background-color: #f4f4f9;
        }
        .sort-controls {
            margin-bottom: 20px;
            text-align: center;
        }
        .sort-btn {
            background-color: #ffffff;
            color: #333333;
            border: 2px solid #007bff;
            padding: 8px 16px;
            margin: 0 5px;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
            font-weight: bold;
            transition: all 0.2s ease;
            text-decoration: none;
            display: inline-block;
        }
        .sort-btn:hover {
            background-color: #007bff;
            color: white;
        }
        .sort-btn.active {
            background-color: #007bff;
            color: white;
            cursor: default;
        }
        .gallery {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
            gap: 20px;
        }
        .image-card {
            background: #ffffff;
            border-radius: 8px;
            box-shadow: 0 4px 6px rgba(0,0,0,0.1);
            overflow: hidden;
            display: flex;
            flex-direction: column;
            position: relative;
        }
        .image-card img {
            width: 100%;
            height: 320px;
            object-fit: cover;
        }
        .image-info {
            padding: 10px;
            font-size: 13px;
            color: #555555;
            background-color: #fafafa;
            border-top: 1px solid #eeeeee;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .date-text {
            flex-grow: 1;
            text-align: left;
            font-size: 12px;
        }
        .action-buttons {
            display: flex;
            gap: 8px;
        }
        .action-btn {
            text-decoration: none;
            font-size: 16px;
            padding: 4px 6px;
            border-radius: 4px;
            transition: background-color 0.2s;
            border: none;
            background: transparent;
            cursor: pointer;
        }
        /* Styling für den Download-Button */
        .download-btn:hover {
            background-color: #e2e6ea;
        }
        /* Styling für den Lösch-Button */
        .delete-btn:hover {
            background-color: #f8d7da;
        }
        .alert {
            padding: 12px;
            margin-bottom: 20px;
            border-radius: 4px;
            text-align: center;
            font-weight: bold;
        }
        .alert-success { background-color: #d4edda; color: #155724; border: 1px solid #c3e6cb; }
        .alert-danger { background-color: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
		.maps-button {
			display: inline-block;
			background-color: #1a73e8; /* Typisches Google-Blau */
			color: white;
			padding: 10px 20px;
			text-decoration: none;
			font-family: Arial, sans-serif;
			font-size: 14px;
			font-weight: bold;
			border-radius: 4px;
			box-shadow: 0 1px 3px rgba(0,0,0,0.12);
			transition: background-color 0.2s;
		}

		.maps-button:hover {
			background-color: #1557b0; /* Dunkleres Blau beim Drüberfahren */
		}
    </style>
</head>
<body>

<?php
$dir = "uploads/"; 
$msg = "";

// 1. LÖSCH-LOGIK
if (isset($_GET['delete'])) {
    $fileToDelete = $_GET['delete'];
    if (strpos($fileToDelete, $dir) === 0 && file_exists($fileToDelete)) {
        if (unlink($fileToDelete)) {
            $msg = "<div class='alert alert-success'>Das Bild wurde erfolgreich gelöscht!</div>";
        } else {
            $msg = "<div class='alert alert-danger'>Fehler: Das Bild konnte nicht gelöscht werden.</div>";
        }
    }
}

// 2. Sortierung auslesen
$sortOrder = isset($_GET['sort']) && $_GET['sort'] === 'oldest' ? 'oldest' : 'newest';

// 3. Bilder einlesen
$pattern = $dir . "*.{jpg,jpeg,png,gif,webp,JPG,JPEG,PNG,GIF,WEBP}";
$images = glob($pattern, GLOB_BRACE);

echo $msg;
?>

<!-- Sortier-Buttons -->
<div class="sort-controls">
    <a href="?sort=newest" class="sort-btn <?php echo $sortOrder === 'newest' ? 'active' : ''; ?>">
        📅 Neueste zuerst
    </a>
    <a href="?sort=oldest" class="sort-btn <?php echo $sortOrder === 'oldest' ? 'active' : ''; ?>">
        ⏳ Älteste zuerst
    </a>

	<!-- Formular mit einer ID für JavaScript, !!! problem: variable (auch global) kann nicht an php übergeben werden !!!-->
    <form id="emailForm">
        
        <?php
        // 1. Datei auslesen und vorbereiten
        $dateiName = "emails.txt";
        $standardPlaceholder = "alt.micha@gmail.com"; // Standard-Text, falls Datei leer ist

        if (file_exists($dateiName) && filesize($dateiName) > 0) {
            // Holt den Inhalt und entfernt eventuelle Leerzeichen oder Zeilenumbrüche am Rand
            $dateiInhalt = trim(file_get_contents($dateiName));
            
            // Falls die Datei nicht leer ist, wird der Inhalt zum Placeholder
            if (!empty($dateiInhalt)) {
                $standardPlaceholder = htmlspecialchars($dateiInhalt);
            }
        }
        ?>

        <!-- 2. Die PHP-Variable direkt in das placeholder-Attribut einfügen -->
        <input 
            type="email" 
            id="email" 
            placeholder="<?php echo $standardPlaceholder; ?>" 
            required
        >
        
        <button type="submit">Speichern</button>
    </form>
	
</div>
<div class="gallery">
    <?php
    if ($images) {
        // Sortier-Logik
        usort($images, function($a, $b) use ($sortOrder) {
            $timeA = filemtime($a);
            $timeB = filemtime($b);
            if ($timeA == $timeB) return 0;
            return ($sortOrder === 'newest') ? (($timeA < $timeB) ? 1 : -1) : (($timeA > $timeB) ? 1 : -1);
        });
		$mailto = "alt.micha@gmail.com";
		$dateiName = "emails.txt";
		if (file_exists($dateiName)) {
			// Liest die gesamte Datei als einen einzigen Text-String aus
			$mailto = file_get_contents($dateiName);
			php_console_log($dateiName . " " . $mailto);
		} else {
			php_console_log('Datei nicht gefunden ' . $dateiName);
		}
        // Bilder ausgeben
        foreach ($images as $image) {
            $fileTime = filemtime($image);
            $formattedDate = date("d.m.Y H:i", $fileTime);
            $filename = basename($image);
			// GPS aus Bild extrahieren
			$coordinates = getGPSFromImage($image);

			if ($coordinates) {
				//echo "Breitengrad (Latitude): " . $coordinates['lat'] . "<br>";
				//echo "Längengrad (Longitude): " . $coordinates['lng'] . "<br>";
				
				// KORREKTUR: Zuweisung korrigiert (Es heißt 'lng', nicht 'lon')
				$latitude = $coordinates['lat'];
				$longitude = $coordinates['lng']; 
				php_console_log("GPS: " . $latitude . $longitude);
			} else {
				php_console_log($image . " Keine GPS-Daten im Bild vorhanden. Nutze URL-Parameter falls vorhanden.");
			}
            $deleteUrl = "?sort=" . $sortOrder . "&delete=" . urlencode($image);
			// Die URL für das Sende-Skript mit dem Parameter "datei" aufbauen
			//$latitude=52.1; 
			//$longitude=10.4;
			//$sendEmailUrl = "send_email.php?datei=" . urlencode($image) . "&mailto=" . urlencode($mailto) . "&latitude=" . urlencode($latitude) . "&longitude=" . urlencode($longitude);
			$sendEmailUrl = "send_email.php?datei=" . urlencode($image) . "&mailto=" . urlencode($mailto);

            echo '<div class="image-card">';
            echo '  <img src="' . htmlspecialchars($image) . '" alt="Galeriebild">';
            echo '  <div class="image-info">';
            echo '      <span class="date-text">' . $formattedDate . ' Uhr</span>';
            echo '      <div class="action-buttons">';
			// 2. NEUER E-Mail-Button (leitet an send_email.php weiter)
			echo '          <a href="' . htmlspecialchars($sendEmailUrl) . '" class="action-btn email-btn" title="Als E-Mail-Anhang senden">✉️</a>';

            // Download-Button
            echo '          <a href="' . htmlspecialchars($image) . '" download="' . htmlspecialchars($filename) . '" class="action-btn download-btn" title="Bild herunterladen">💾</a>';
            // Neuer Lösch-Button (ruft weiterhin die Sicherheitsabfrage auf)
            echo '          <button class="action-btn delete-btn" title="Bild löschen" onclick="confirmDelete(\'' . jsone(htmlspecialchars($deleteUrl)) . '\')">🗑️</button>';
            echo '      </div>';
            echo '  </div>';
			if ($coordinates) {
				// Sicherstellen, dass die Werte für die URL korrekt codiert sind
				$url_latitude = urlencode($latitude);
				$url_longitude = urlencode($longitude);

				// Google Maps URL generieren (funktioniert auf Desktop und in der mobilen App)
				//$maps_url = "http://google.com{$url_latitude},{$url_longitude}";
				$maps_url = "https://maps.google.com/?q=${url_latitude},${url_longitude}";

				// Das gesamte Layout in einem einzigen echo-Befehl ausgeben
				echo '<div class="maps-button">
					<a href="' . $maps_url . '" target="_blank" rel="noopener noreferrer" class="maps-button">
						📍 Auf Google Maps anzeigen
					</a>
				</div>';
				//echo "<div>" . "📍 " . $latitude . " " . $longitude . "</div>";
			}

            echo '</div>';
        }
    } else {
        echo '<p style="text-align:center; grid-column: 1/-1;">Keine Bilder im Verzeichnis gefunden.</p>';
    }

    function jsone($str) {
        return str_replace("'", "\'", $str);
    }
	function php_console_log($daten) {
		// Wandelt PHP-Arrays oder Objekte in gültiges JavaScript-Format (JSON) um
		$json_daten = json_encode($daten);
		
		// Gibt den JavaScript-Code direkt im Browser aus
		echo "<script>console.log('PHP-Log: ' + " . $json_daten . ");</script>";
	}
	/**
	 * Reine mathematische Hilfsfunktion zur DMS-Umrechnung.
	 * Getrennt deklariert, um Parser-Abbrüche auf dem Server zu verhindern.
	 */
	function convertExifToDecimal($coordinateArray) {
		if (!is_array($coordinateArray) || count($coordinateArray) < 3) {
			return 0.0;
		}
		
		$parts = array_map(function($part) {
			$division = explode('/', $part);
			if (count($division) === 2 && (float)$division[1] != 0) {
				return (float)$division[0] / (float)$division[1];
			}
			return (float)$part;
		}, $coordinateArray);

		// Formel: Grad + (Minuten / 60) + (Sekunden / 3600)
		return (float)$parts[0] + ((float)$parts[1] / 60) + ((float)$parts[2] / 3600);
	}

	/**
	 * Extrahiert die GPS-Koordinaten aus einem JPG-Bild.
	 * Prüft vorab, ob EXIF auf dem Server aktiviert ist.
	 */
	function getGPSFromImage($imagePath) {
		// Sicherheitscheck: Unterstützt der Webspace überhaupt EXIF?
		if (!function_exists('exif_read_data')) {
			php_console_log("WARNUNG: exif_read_data ist auf diesem Server deaktiviert!");
			return false;
		}

		$exif = @exif_read_data($imagePath, 'GPS');
		
		if (!$exif || !isset($exif['GPSLatitude'], $exif['GPSLongitude'], $exif['GPSLatitudeRef'], $exif['GPSLongitudeRef'])) {
			return false; // Keine GPS-Daten im Bild
		}

		// Berechnung über die externe Hilfsfunktion
		$lat = convertExifToDecimal($exif['GPSLatitude']);
		$lng = convertExifToDecimal($exif['GPSLongitude']);

		if (strtoupper($exif['GPSLatitudeRef']) === 'S') {
			$lat = -$lat;
		}
		if (strtoupper($exif['GPSLongitudeRef']) === 'W') {
			$lng = -$lng;
		}

		return [
			'lat' => round($lat, 6),
			'lng' => round($lng, 6)
		];
	}

?>
</div>

<script>
	function confirmDelete(deleteUrl) {
		if (confirm("Möchtest du dieses Bild wirklich unwiderruflich löschen?")) {
			window.location.href = deleteUrl;
		}
	}

	document.getElementById('emailForm').addEventListener('submit', function(event) {
		event.preventDefault(); 
		
		// Die JavaScript-Variable mit der E-Mail-Adresse
		let userEmail = document.getElementById('email').value;
		
		// Formulardaten für PHP vorbereiten
		let formData = new FormData();
		formData.set('email', userEmail);

		console.log("userEmail", userEmail);
		// Der PHP-Aufruf via Fetch (AJAX) im Hintergrund
		fetch('save_email.php', {
			method: 'POST',
			body: formData
		})
		.then(response => response.text()) // Antwort vom PHP-Skript als Text lesen
		.then(data => {
			// Hier kommt die Antwort von PHP an (z.B. "Erfolgreich gespeichert")
			console.log("Antwort vom Server:", data);
			alert(data); 
			location.reload();
		})
		.catch(error => {
			console.error("Fehler beim PHP-Aufruf:", error);
		});
    });
</script>

</body>
</html>
