# 4.4 MySQL 로그 파일

MySQL은 서버 상태, 쿼리 실행, 오류 정보를 기록하기 위해 다양한 로그 파일을 제공한다.

대표 로그 종류:

- 에러 로그 (Error Log)
- 제너럴 로그 (General Log)
- 슬로우 쿼리 로그 (Slow Query Log)
- (추가적으로 Binary Log는 복제/복구 목적)

---

## 4.4.1 에러 로그 파일 (Error Log)

<details>
<summary>자세히 보기</summary>

### 역할

MySQL 서버 실행 중 발생하는  
**오류 및 중요 이벤트 기록**

---

### 기록 내용

- 서버 시작 / 종료 로그
- 설정 오류
- InnoDB Crash Recovery 과정
- 테이블 손상 메시지
- Deadlock 정보

---

### 설정 확인

```sql
SHOW VARIABLES LIKE 'log_error';
```

---

### 특징

- 서버 문제 발생 시 가장 먼저 확인하는 로그
- 운영 환경에서 필수
- MySQL 8.0부터 JSON 형식 로그 지원

---

### 실무 포인트

- 서버가 갑자기 종료됐을 때
- InnoDB 복구 메시지 확인
- 데드락 발생 시 상세 정보 확인

</details>

---

## 4.4.2 제너럴 쿼리 로그 파일 (General Log)

<details>
<summary>자세히 보기</summary>

### 역할

MySQL 서버에 접속된 클라이언트의  
**모든 요청을 기록**

---

### 기록 내용

- 접속 정보
- 실행된 모든 SQL 문
- 접속 종료

---

### 활성화 방법

```sql
SET GLOBAL general_log = 'ON';
SHOW VARIABLES LIKE 'general_log_file';
```

---

### 특징

- 모든 쿼리 기록 → 성능 저하 발생 가능
- 디버깅 목적 사용
- 장시간 운영 환경에서는 권장하지 않음

---

### 저장 방식

- 파일로 저장
- 테이블(mysql.general_log)로 저장 가능

```sql
SET GLOBAL log_output = 'TABLE';
```

---

### 실무 사용 예

- 애플리케이션이 실제 어떤 쿼리를 보내는지 확인
- ORM 디버깅
- 보안 감사 목적

</details>

---

## 4.4.3 슬로우 쿼리 로그 (Slow Query Log)

<details>
<summary>자세히 보기</summary>

### 역할

일정 시간 이상 실행된  
**느린 쿼리만 기록**

---

### 활성화 방법

```sql
SET GLOBAL slow_query_log = 'ON';
SHOW VARIABLES LIKE 'slow_query_log_file';
```

---

### 기준 시간 설정

```sql
SHOW VARIABLES LIKE 'long_query_time';
```

예:
```
long_query_time = 2   -- 2초 이상 실행 시 기록
```

---

### 추가 옵션

```sql
SET GLOBAL log_queries_not_using_indexes = 'ON';
```

- 인덱스를 사용하지 않은 쿼리 기록

---

### 기록 내용

- 실행 시간
- Lock 시간
- Rows examined
- 실행된 SQL 문

---

### 실무 활용

- 성능 튜닝의 핵심 도구
- 인덱스 추가 여부 판단
- EXPLAIN 분석 대상 선정

---

### 분석 도구

- mysqldumpslow
- pt-query-digest (Percona Toolkit)

</details>

---

# 로그 파일 비교 정리

| 로그 종류 | 목적 | 성능 영향 | 운영 사용 |
|------------|------|------------|------------|
| 에러 로그 | 서버 상태/오류 기록 | 낮음 | 항상 사용 |
| 제너럴 로그 | 모든 쿼리 기록 | 매우 높음 | 디버깅 시만 |
| 슬로우 로그 | 느린 쿼리 기록 | 낮음 | 성능 튜닝용 |

---

# 📌 운영 관점 결론

✔ 에러 로그 → 항상 활성화  
✔ 슬로우 로그 → 운영 환경에서 필수  
❌ 제너럴 로그 → 장기 운영 시 비권장  

