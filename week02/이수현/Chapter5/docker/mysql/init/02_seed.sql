USE chapter5

INSERT INTO event(event_id, title, capacity, remaining, starts_at, ends_at)
VALUES (1, 'first-come', 100, 100, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY));