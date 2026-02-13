# 4.2 InnoDB 스토리지 엔진 아키텍처

InnoDB는 MySQL의 기본 스토리지 엔진으로,
- 트랜잭션 지원
- MVCC
- Row-Level Lock
- Crash Recovery
  를 제공하는 ACID 기반 엔진이다.

---

## 4.2.1 프라이머리 키에 의한 클러스터링

<details>
<summary>자세히 보기</summary>

### 클러스터형 인덱스 구조

- InnoDB는 **프라이머리 키(PK)를 기준으로 데이터 파일을 정렬 저장**
- 테이블 자체가 PK 기준 B-Tree 구조
- PK = 클러스터 인덱스

### 특징

- PK 검색은 매우 빠름 (추가 lookup 없음)
- 보조 인덱스는 PK 값을 함께 저장
- PK가 크면 모든 보조 인덱스도 커짐

### PK가 없는 경우

1. 명시적 PK 사용
2. UNIQUE + NOT NULL 사용
3. 내부 hidden row id 자동 생성

### 단점

- PK 변경 시 데이터 재정렬 필요
- 랜덤 PK(UUID)는 성능 저하 유발

👉 권장: AUTO_INCREMENT 정수 PK

</details>

---

## 4.2.2 외래 키 지원

<details>
<summary>자세히 보기</summary>

InnoDB는 외래 키(Foreign Key)를 지원한다.

### 제약 조건

- 부모 테이블에 인덱스 존재 필수
- 참조 컬럼은 인덱스 필요
- 동일 스토리지 엔진 사용해야 함

### 옵션

- ON DELETE CASCADE
- ON UPDATE CASCADE
- SET NULL
- RESTRICT

### 특징

- 무결성 보장
- 내부적으로 참조 무결성 검사 수행
- 성능 저하 가능성 존재

</details>

---

## 4.2.3 MVCC (Multi Version Concurrency Control)

<details>
<summary>자세히 보기</summary>

### 목적
읽기와 쓰기의 충돌 최소화

### 핵심 개념

- 하나의 레코드에 여러 버전 유지
- Undo 로그 활용
- 트랜잭션 ID 기반 버전 관리

### 동작 원리

- UPDATE 발생 → 기존 데이터는 Undo 영역에 저장
- SELECT는 자신의 트랜잭션 ID 기준으로 적절한 버전 읽기

### 장점

- 읽기 시 락 불필요
- 높은 동시성 제공

</details>

---

## 4.2.4 잠금 없는 일관된 읽기 (Non-Locking Consistent Read)

<details>
<summary>자세히 보기</summary>

### Consistent Read

- SELECT 시 락을 걸지 않음
- MVCC 기반 과거 버전 읽기

### Read View 생성

- 트랜잭션 시작 시 snapshot 생성
- Undo 로그를 통해 과거 데이터 조회

### 적용 범위

- 일반 SELECT
- REPEATABLE READ 기본 격리 수준에서 동작

</details>

---

## 4.2.5 자동 데드락 감지

<details>
<summary>자세히 보기</summary>

### 데드락이란?

두 개 이상의 트랜잭션이 서로의 락을 기다리는 상태

### InnoDB 동작

- Wait-for Graph 구성
- 주기적으로 데드락 탐지
- 하나의 트랜잭션 강제 롤백

### 특징

- 자동 해결
- 가장 작은 작업량 트랜잭션 롤백

</details>

---

## 4.2.6 자동화된 장애 복구

<details>
<summary>자세히 보기</summary>

### Crash Recovery 과정

1. Redo 로그 적용 (roll-forward)
2. Undo 로그 적용 (roll-back)

### 체크포인트

- 일정 시점 디스크 반영
- 복구 시간 단축

### WAL (Write Ahead Logging)

- 로그 먼저 기록
- 이후 데이터 페이지 기록

</details>

---

## 4.2.7 InnoDB 버퍼 풀

<details>
<summary>자세히 보기</summary>

### 역할

디스크 I/O 최소화

### 저장 대상

- 데이터 페이지
- 인덱스 페이지
- Undo 페이지

### LRU 알고리즘

- Old 영역
- Young 영역

### 특징

- 변경 페이지는 Dirty Page로 표시
- Flush 스레드가 디스크 반영

### 설정

```sql
SHOW VARIABLES LIKE 'innodb_buffer_pool_size';
```

👉 메모리의 50~70% 권장

</details>

---

## 4.2.8 Double Write Buffer

<details>
<summary>자세히 보기</summary>

### 목적

부분 페이지 쓰기(Partial Page Write) 방지

### 동작

1. 먼저 Double Write 영역에 기록
2. 이후 실제 데이터 파일에 기록

### 장점

- Crash 시 데이터 손상 방지

</details>

---

## 4.2.9 언두 로그 (Undo Log)

<details>
<summary>자세히 보기</summary>

### 역할

- MVCC 지원
- 롤백 지원

### 저장 내용

- 이전 데이터 값
- 트랜잭션 정보

### 특징

- 트랜잭션 종료 후 purge 과정에서 정리

</details>

---

## 4.2.10 체인지 버퍼 (Change Buffer)

<details>
<summary>자세히 보기</summary>

### 대상

보조 인덱스 변경 작업

### 목적

디스크 랜덤 I/O 감소

### 동작

- 즉시 인덱스 반영하지 않음
- 버퍼에 저장 후 나중에 병합

👉 쓰기 성능 향상

</details>

---

## 4.2.11 리두 로그 및 로그 버퍼

<details>
<summary>자세히 보기</summary>

### Redo Log

- 변경 사항 기록
- Crash Recovery 핵심

### Log Buffer

- 메모리 영역
- 커밋 시 디스크 flush

### 관련 설정

```sql
SHOW VARIABLES LIKE 'innodb_log_file_size';
SHOW VARIABLES LIKE 'innodb_flush_log_at_trx_commit';
```

### innodb_flush_log_at_trx_commit 값

- 1 : 가장 안전 (매 커밋 flush)
- 2 : 1초마다 flush
- 0 : 성능 우선

</details>

---

## 4.2.12 어댑티브 해시 인덱스

<details>
<summary>자세히 보기</summary>

### 개념

- B-Tree 기반 인덱스를 Hash 형태로 자동 변환

### 목적

자주 조회되는 패턴 가속

### 특징

- 자동 생성
- 메모리 기반
- 워크로드 의존적

</details>

---

## 4.2.13 InnoDB vs MyISAM vs MEMORY 비교

<details>
<summary>자세히 보기</summary>

| 항목 | InnoDB | MyISAM | MEMORY |
|------|--------|--------|--------|
| 트랜잭션 | O | X | X |
| 외래 키 | O | X | X |
| 락 단위 | Row | Table | Table |
| Crash Recovery | O | X | X |
| 기본 엔진 | O | X | X |

### 결론

- 일반 서비스 → InnoDB
- 읽기 전용 로그성 데이터 → MyISAM
- 임시 테이블 → MEMORY

</details>
