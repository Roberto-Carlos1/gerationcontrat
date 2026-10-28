package mg.pacte.api;

import java.time.DateTimeException;
import mg.pacte.ai.AiController;
import mg.pacte.document.DocumentController;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes={RegistryController.class,AiController.class,DocumentController.class,mg.pacte.ocr.OcrController.class,mg.pacte.backup.BackupController.class,mg.pacte.reminder.ReminderController.class})
public class ApiErrors {
    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    public ResponseEntity<?> invalid(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Données invalides : vérifiez les dates, identifiants et champs."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> malformed(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Données invalides : vérifiez les champs, dates, montants et statuts."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason() == null ? "Requête refusée." : e.getReason()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(DataAccessException e) {
        return ResponseEntity.status(503).body(Map.of("error", "Base de données indisponible ; réessayez."));
    }
}
