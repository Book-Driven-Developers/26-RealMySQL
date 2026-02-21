# 선착순 이벤트 시스템 실험 (DB 트랜잭션/락/격리수준 기반)

> 제약: Redis/분산락/네임드락 미사용  
> 목표: InnoDB의 트랜잭션/락/격리수준만으로 선착순 N명 정합성을 확보하고, 조건을 바꿔가며 실험/기록한다.

---

## 실행 방법

<details>
<summary>1) Docker MySQL 실행</summary>

- `docker-compose.yml` 실행 후 init SQL로 스키마/시드 자동 생성
- MySQL 8.0 + performance_schema ON

</details>

```bash
docker compose up -d
docker compose logs -f mysql
docker exec -it firstcome-mysql mysql -uroot -prootpw -e "SHOW DATABASES;"
docker exec -it firstcome-mysql mysql -uapp -papppw -D firstcome -e "SELECT * FROM event;"
```
<details>
<summary>2) Spring API 실행</summary>

- 애플리케이션 실행
  ```bash
  ./gradlew bootRun
  ```

- 기본 포트: `http://localhost:8080`

- 주요 API

    - 신청
      ```
      POST /events/{eventId}/apply?userId=...&mode=...&iso=...
      ```

      예시:
      ```
      POST http://localhost:8080/events/1/apply?userId=10&mode=FOR_UPDATE&iso=RR
      ```

      파라미터 설명:
        - `mode`
            - `FOR_UPDATE`
            - `ATOMIC_UPDATE`
            - `COUNT_BASED`
        - `iso`
            - `RR` (REPEATABLE_READ)
            - `RC` (READ_COMMITTED)

    - 통계 조회
      ```
      GET /events/{eventId}/stats
      ```

    - 실험 초기화
      ```
      POST /events/{eventId}/reset
      ```

</details>

---

## 문제 정의

<details>
<summary>선착순 이벤트 요구사항</summary>

- 이벤트 ID=1
- 정원(capacity)=100명
- 동시에 수백~수천 요청이 들어와도 정확히 100명만 성공해야 한다.
- 같은 유저는 1번만 성공해야 한다.
- Redis/분산락 없이 DB 트랜잭션과 InnoDB 락만 사용한다.

</details>

---

## 데이터 모델 설계 의도

<details>
<summary>설계 핵심</summary>

- `event.remaining`을 감소시키는 방식으로 재고 관리
- `event_apply(event_id, user_id)`에 UNIQUE 인덱스로 중복 신청 방지
- remaining 감소 + 신청 insert를 하나의 트랜잭션으로 묶어 부분 반영 방지

</details>

### 핵심 인덱스

```sql
UNIQUE KEY uk_event_user (event_id, user_id)
```

- 동일 사용자의 중복 신청을 DB 레벨에서 차단
- DuplicateKeyException 발생 시 트랜잭션 롤백 → remaining 원복

---

## 구현 전략 3종

<details>
<summary>Mode A: FOR_UPDATE</summary>

- `SELECT ... FOR UPDATE`로 이벤트 1행을 선점
- remaining 확인 → 감소 → 신청 insert
- 정합성은 가장 안정적
- 단점: 락 경합 시 대기 증가

</details>

<details>
<summary>Mode B: ATOMIC_UPDATE</summary>

- `UPDATE ... WHERE remaining > 0` 조건부 감소
- 성공 여부는 update count로 판단
- 이후 신청 insert
- FOR_UPDATE보다 경합 감소 가능

</details>

<details>
<summary>Mode C: COUNT_BASED</summary>

- `COUNT(*) < capacity`면 insert
- 동시성 상황에서 초과 당첨 가능
- 격리수준 차이를 체감하기 위한 실험용 방식

</details>

---

## 실험 설계

### 실험 변수

- Mode
    - FOR_UPDATE
    - ATOMIC_UPDATE
    - COUNT_BASED

- Isolation
    - RR
    - RC

- Concurrency
    - 50 / 200 / 1000

---

## 동시성 테스트 실행

테스트 클래스 실행:

```bash
./gradlew test --tests *ConcurrencyApplyTest
```

테스트 내부 설정:

```java
String mode = "COUNT_BASED"; // FOR_UPDATE | ATOMIC_UPDATE | COUNT_BASED
String iso = "RC";           // RR | RC
int concurrency = 200;
```

---

## 실험 결과 기록

| Case | Mode | Isolation | Concurrency | Success | Fail | applyCount | remaining | 초과당첨 |
|------|------|-----------|------------|---------|------|-----------|-----------|----------|
| 1 | FOR_UPDATE | RR | 200 |  |  |  |  |  |
| 2 | ATOMIC_UPDATE | RR | 200 |  |  |  |  |  |
| 3 | COUNT_BASED | RC | 200 |  |  |  |  |  |
| 4 | COUNT_BASED | RR | 200 |  |  |  |  |  |

<img width="652" height="287" alt="Image" src="https://github.com/user-attachments/assets/8759ede2-a075-4410-b236-647f5a4f300a" />
<img width="1008" height="100" alt="Image" src="https://github.com/user-attachments/assets/2cee4da9-02ae-4ff2-ba91-76aeae23200b" />

확인 SQL:

```sql
SELECT remaining FROM event WHERE event_id=1;
SELECT COUNT(*) FROM event_apply WHERE event_id=1;
```
<img width="598" height="339" alt="Image" src="https://github.com/user-attachments/assets/fd7e2ab9-2ed7-4c1c-88a5-1f4ba8701d1e" />

---

## 락 관측

```sql
SHOW PROCESSLIST;

SELECT * FROM performance_schema.data_locks;
SELECT * FROM performance_schema.data_lock_waits;
```

---

## 실험 초기화 SQL

```sql
DELETE FROM event_apply WHERE event_id=1;
UPDATE event SET remaining=capacity WHERE event_id=1;
```


