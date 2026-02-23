# 5.4 MySQL의 격리 수준 (Isolation Level)

트랜잭션의 격리 수준(Isolation Level)은  
여러 트랜잭션이 동시에 처리될 때,

> 한 트랜잭션이 다른 트랜잭션의 변경 내용을  
> 어디까지 볼 수 있는지

를 결정하는 설정이다.

격리 수준은 다음 4단계로 나뉜다.

1. READ UNCOMMITTED
2. READ COMMITTED
3. REPEATABLE READ
4. SERIALIZABLE

아래로 갈수록 격리 수준은 높아지고  
동시성은 낮아진다.

---

## 격리 수준과 이상 현상

격리 수준과 함께 반드시 알아야 할 3가지 이상 현상:

1. Dirty Read
2. Non-Repeatable Read
3. Phantom Read

| 격리 수준 | Dirty Read | Non-Repeatable Read | Phantom Read |
|------------|------------|----------------------|---------------|
| READ UNCOMMITTED | 발생 | 발생 | 발생 |
| READ COMMITTED | 없음 | 발생 | 발생 |
| REPEATABLE READ | 없음 | 없음 | (InnoDB에서는 없음) |
| SERIALIZABLE | 없음 | 없음 | 없음 |

---

## 5.4.1 READ UNCOMMITTED

가장 낮은 격리 수준.

특징:

- 다른 트랜잭션이 COMMIT하지 않은 데이터도 읽을 수 있음
- Dirty Read 발생

### Dirty Read란?

다른 트랜잭션이 수정했지만 아직 COMMIT하지 않은 데이터를 읽는 현상.

예:

1. 트랜잭션 A → UPDATE 수행 (아직 COMMIT 안함)
2. 트랜잭션 B → 해당 값 조회
3. 트랜잭션 A → ROLLBACK

이 경우,  
트랜잭션 B는 실제로 존재하지 않는 값을 읽은 것이다.

실무에서는 거의 사용하지 않는다.

---

## 5.4.2 READ COMMITTED

오래된 DBMS에서 기본으로 많이 사용되는 격리 수준.

특징:

- COMMIT된 데이터만 읽을 수 있음
- Dirty Read는 발생하지 않음
- 하지만 Non-Repeatable Read 발생 가능

### Non-Repeatable Read

한 트랜잭션 내에서 같은 SELECT를 두 번 실행했는데  
결과가 달라지는 현상.

예:

1. 트랜잭션 A → SELECT (값 = 100)
2. 트랜잭션 B → UPDATE + COMMIT (값 = 200)
3. 트랜잭션 A → 다시 SELECT (값 = 200)

같은 트랜잭션 안에서 결과가 달라짐.

READ COMMITTED는

- SELECT 시점에 COMMIT된 최신 데이터를 읽는다.
- 매번 새로운 버전을 본다.

---

## 5.4.3 REPEATABLE READ

MySQL(InnoDB)의 기본 격리 수준.

특징:

- 같은 트랜잭션 내에서는 항상 동일한 SELECT 결과 보장
- Non-Repeatable Read 방지
- InnoDB에서는 Phantom Read도 방지

---

### REPEATABLE READ의 핵심: MVCC

InnoDB는 MVCC(Multi Version Concurrency Control)를 사용한다.

원리:

- 트랜잭션 시작 시점의 스냅샷 생성
- SELECT는 해당 스냅샷 기준으로 읽음
- 다른 트랜잭션의 COMMIT 이후 변경은 보이지 않음

즉,

> 트랜잭션이 시작된 시점의 데이터를 일관되게 유지한다.

---

### Phantom Read란?

범위 검색 시  
다른 트랜잭션이 INSERT한 새로운 행이 보이는 현상.

예:

1. 트랜잭션 A → WHERE salary > 1000 조회
2. 트랜잭션 B → salary=2000 INSERT + COMMIT
3. 트랜잭션 A → 다시 조회

READ COMMITTED에서는  
새로운 행이 보인다.

하지만 InnoDB의 REPEATABLE READ는:

- 넥스트 키 락 사용
- 갭 락 사용

→ 팬텀 리드 방지

즉,

> InnoDB에서는 REPEATABLE READ에서 Phantom Read가 발생하지 않는다.

(표준 SQL과의 차이점)

---

## SELECT와 FOR UPDATE의 차이

일반 SELECT:

- MVCC 기반
- 잠금 없이 스냅샷 읽기

SELECT ... FOR UPDATE:

- 실제 레코드 잠금
- 넥스트 키 락 발생 가능
- 다른 트랜잭션의 변경 차단

---

## 5.4.4 SERIALIZABLE

가장 높은 격리 수준.

특징:

- SELECT도 잠금 대상
- 모든 트랜잭션이 순차적으로 실행되는 것처럼 동작
- 동시성 매우 낮음

InnoDB에서는:

- 일반 SELECT도 공유 잠금 설정
- 사실상 테이블 수준 제어에 가까움

하지만 InnoDB의 REPEATABLE READ가 이미 팬텀 리드를 방지하므로  
실무에서 SERIALIZABLE은 거의 사용하지 않는다.

---

## MySQL에서 격리 수준 설정

### 현재 격리 수준 확인

```sql
SELECT @@transaction_isolation;
```

### 세션 단위 변경

```sql
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
```

### 글로벌 변경

```sql
SET GLOBAL TRANSACTION ISOLATION LEVEL REPEATABLE READ;
```

---

## 5.4 핵심 정리

1. READ UNCOMMITTED
    - Dirty Read 발생
    - 거의 사용하지 않음

2. READ COMMITTED
    - Dirty Read 방지
    - Non-Repeatable Read 발생

3. REPEATABLE READ (MySQL 기본)
    - MVCC 기반
    - Non-Repeatable Read 방지
    - InnoDB에서는 Phantom Read도 방지

4. SERIALIZABLE
    - 가장 강력
    - 동시성 매우 낮음

---

## 중요한 설계 관점

- InnoDB의 기본값은 REPEATABLE READ
- MVCC + 넥스트 키 락으로 높은 정합성 보장
- 격리 수준이 높을수록 성능은 떨어질 수 있음
- 대부분의 온라인 서비스는 READ COMMITTED 또는 REPEATABLE READ 사용

격리 수준은 단순한 설정 값이 아니라  
동시성, 잠금 범위, 성능, 정합성에 직접적인 영향을 미치는 핵심 요소다.