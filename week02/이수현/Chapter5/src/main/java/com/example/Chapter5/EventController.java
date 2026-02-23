package com.example.Chapter5;

import com.example.Chapter5.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class EventController {

  private final EventService eventService;

  /**
   * 예:
   * POST /events/1/apply?userId=10&mode=FOR_UPDATE&iso=RR
   */
  @PostMapping("/events/{eventId}/apply")
  public ResponseEntity<Map<String, Object>> apply(
          @PathVariable long eventId,
          @RequestParam long userId,
          @RequestParam(defaultValue = "FOR_UPDATE") String mode,
          @RequestParam(defaultValue = "RR") String iso
  ) {
    return ResponseEntity.ok(eventService.apply(eventId, userId, mode, iso));
  }

  @GetMapping("/events/{eventId}/stats")
  public ResponseEntity<Map<String, Object>> stats(@PathVariable long eventId) {
    return ResponseEntity.ok(eventService.stats(eventId));
  }

  @PostMapping("/events/{eventId}/reset")
  public ResponseEntity<Map<String, Object>> reset(@PathVariable long eventId) {
    return ResponseEntity.ok(eventService.reset(eventId));
  }
}