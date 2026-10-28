package mg.pacte.reminder;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {
    private final ReminderService service;
    public ReminderController(ReminderService service){this.service=service;}
    @GetMapping("/status")public Map<String,Object> status(){return service.status();}
    @GetMapping("/preview")public List<ReminderService.Reminder> preview(){return service.preview();}
}
