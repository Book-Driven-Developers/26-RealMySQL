package com.example.Chapter5.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventService {

  private final JdbcTemplate jdbc;

  public Map<String, Object> apply(long eventId, long userId, String mode, String iso) {
    ApplyMode m = ApplyMode.from(mode);
    IsoLevel i = IsoLevel.from(iso);

    return switch (i) {
      case RC -> applyRC(eventId, userId, m);
      case RR -> applyRR(eventId, userId, m);
    };
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> applyRC(long eventId, long userId, ApplyMode mode) {
    return doApply(eventId, userId, mode, "RC");
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> applyRR(long eventId, long userId, ApplyMode mode) {
    return doApply(eventId, userId, mode, "RR");
  }

  private Map<String, Object> doApply(long eventId, long userId, ApplyMode mode, String iso) {
    try {
      return switch (mode) {
        case FOR_UPDATE -> applyForUpdate(eventId, userId, iso);
        case ATOMIC_UPDATE -> applyAtomicUpdate(eventId, userId, iso);
        case COUNT_BASED -> applyCountBased(eventId, userId, iso);
      };
    } catch (DuplicateKeyException e) {
      // UNIQUE(event_id, user_id) 위반
      // 트랜잭션이므로 remaining 감소가 있었다면 함께 롤백됨이 핵심
      return Map.of("ok", false, "reason", "DUPLICATE", "mode", mode.name(), "iso", iso);
    }
  }

  /** Mode A: 이벤트 1행을 FOR UPDATE로 선점 */
  private Map<String, Object> applyForUpdate(long eventId, long userId, String iso) {
    Integer remaining = jdbc.queryForObject(
            "SELECT remaining FROM event WHERE event_id=? FOR UPDATE",
            Integer.class, eventId
    );
    if (remaining == null) return Map.of("ok", false, "reason", "NO_EVENT", "mode", "FOR_UPDATE", "iso", iso);
    if (remaining <= 0) return Map.of("ok", false, "reason", "SOLD_OUT", "mode", "FOR_UPDATE", "iso", iso);

    jdbc.update("UPDATE event SET remaining = remaining - 1 WHERE event_id=?", eventId);
    jdbc.update("INSERT INTO event_apply(event_id, user_id) VALUES (?, ?)", eventId, userId);

    return Map.of("ok", true, "mode", "FOR_UPDATE", "iso", iso);
  }

  /** Mode B: remaining>0일 때만 감소 (조건부 UPDATE 1방) */
  private Map<String, Object> applyAtomicUpdate(long eventId, long userId, String iso) {
    int updated = jdbc.update(
            "UPDATE event SET remaining = remaining - 1 WHERE event_id=? AND remaining > 0",
            eventId
    );
    if (updated == 0) return Map.of("ok", false, "reason", "SOLD_OUT", "mode", "ATOMIC_UPDATE", "iso", iso);

    jdbc.update("INSERT INTO event_apply(event_id, user_id) VALUES (?, ?)", eventId, userId);
    return Map.of("ok", true, "mode", "ATOMIC_UPDATE", "iso", iso);
  }

  /** Mode C: COUNT 기반 (실험용/위험) */
  private Map<String, Object> applyCountBased(long eventId, long userId, String iso) {
    Integer capacity = jdbc.queryForObject(
            "SELECT capacity FROM event WHERE event_id=?",
            Integer.class, eventId
    );
    Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_apply WHERE event_id=?",
            Integer.class, eventId
    );

    if (capacity == null || cnt == null) {
      return Map.of("ok", false, "reason", "NO_EVENT", "mode", "COUNT_BASED", "iso", iso);
    }
    if (cnt >= capacity) {
      return Map.of("ok", false, "reason", "SOLD_OUT", "mode", "COUNT_BASED", "iso", iso);
    }

    // 경쟁 상황에서 여러 트랜잭션이 같은 cnt를 보고 insert하면 초과당첨 가능
    jdbc.update("INSERT INTO event_apply(event_id, user_id) VALUES (?, ?)", eventId, userId);
    return Map.of("ok", true, "mode", "COUNT_BASED", "iso", iso);
  }

  public Map<String, Object> stats(long eventId) {
    Integer remaining = jdbc.queryForObject(
            "SELECT remaining FROM event WHERE event_id=?",
            Integer.class, eventId
    );
    Integer capacity = jdbc.queryForObject(
            "SELECT capacity FROM event WHERE event_id=?",
            Integer.class, eventId
    );
    Integer applyCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_apply WHERE event_id=?",
            Integer.class, eventId
    );
    return Map.of(
            "eventId", eventId,
            "capacity", capacity,
            "remaining", remaining,
            "applyCount", applyCount
    );
  }

  @Transactional
  public Map<String, Object> reset(long eventId) {
    jdbc.update("DELETE FROM event_apply WHERE event_id=?", eventId);
    jdbc.update("UPDATE event SET remaining = capacity WHERE event_id=?", eventId);
    return stats(eventId);
  }
}