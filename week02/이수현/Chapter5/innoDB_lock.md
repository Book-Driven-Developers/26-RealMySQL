# 5.3 InnoDB 스토리지 엔진 잠금

InnoDB는 MySQL 엔진 레벨 잠금과는 별도로  
스토리지 엔진 내부에서 레코드 기반 잠금을 제공한다.

MyISAM과 가장 큰 차이점은:

- MyISAM → 테이블 단위 잠금
- InnoDB → 레코드 단위 잠금

하지만 중요한 사실은,

> InnoDB는 “레코드 자체”를 잠그는 것이 아니라  
> 인덱스 레코드를 잠근다.

이 차이가 InnoDB 잠금 이해의 핵심이다.

---

## 5.3.1 InnoDB 잠금의 종류

InnoDB의 잠금은 크게 다음과 같이 나뉜다.

1. 레코드 락 (Record Lock)
2. 갭 락 (Gap Lock)
3. 넥스트 키 락 (Next-Key Lock)
4. 자동 증가 락 (AUTO_INCREMENT Lock)
5. 인덱스 기반 잠금

---

## 5.3.1 레코드 락 (Record Lock)

가장 기본적인 잠금.

특정 인덱스 레코드 하나를 잠근다.

중요한 특징:

- 레코드 자체가 아니라 인덱스 레코드를 잠금
- 인덱스가 없으면 내부적으로 숨겨진 PK를 사용

예:

```sql
UPDATE employees
SET hire_date = NOW()
WHERE emp_no = 10001;
```

이 경우:

- PRIMARY KEY 인덱스의 해당 레코드가 잠김

---

## 5.3.2 갭 락 (Gap Lock)

갭(Gap)은 인덱스 레코드와 레코드 사이의 공간을 의미한다.

갭 락은:

> 특정 레코드 사이의 “공간”을 잠근다.

목적:

- 새로운 레코드 INSERT 방지
- 팬텀 리드 방지

예를 들어:

```
10 ----- 20
```

10과 20 사이의 공간에 대해 갭 락이 걸리면  
11~19 값은 INSERT할 수 없다.

갭 락은 주로:

- REPEATABLE READ 격리 수준에서
- 범위 검색 조건에서 발생

---

## 5.3.3 넥스트 키 락 (Next-Key Lock)

넥스트 키 락은

> 레코드 락 + 갭 락

의 조합이다.

즉,

- 특정 인덱스 레코드
- 그 앞의 갭

을 함께 잠근다.

InnoDB의 기본 격리 수준(REPEATABLE READ)에서  
범위 검색 시 기본적으로 사용된다.

예:

```sql
SELECT * FROM employees
WHERE emp_no BETWEEN 100 AND 200
FOR UPDATE;
```

이 경우:

- 100~200 범위의 레코드
- 그 사이의 갭
- 경계 값 포함

모두 잠금 대상이 된다.

---

### 왜 넥스트 키 락을 사용하는가?

목적은:

> 팬텀 리드(Phantom Read) 방지

다른 트랜잭션이  
범위 내에 새로운 레코드를 INSERT하지 못하도록 막는다.

---

## 5.3.4 AUTO_INCREMENT 락

AUTO_INCREMENT 컬럼이 있는 테이블에서  
INSERT 시 자동 증가 값을 보호하기 위해 사용된다.

InnoDB는 내부적으로 AUTO_INCREMENT 락을 사용한다.

특징:

- INSERT 또는 REPLACE 시 사용
- 테이블 단위 잠금
- 짧은 시간 유지

---

### innodb_autoinc_lock_mode

자동 증가 락 동작 방식은 시스템 변수로 제어된다.

1. 0 (traditional)
2. 1 (consecutive)
3. 2 (interleaved)

MySQL 8.0 기본값은 2 (interleaved)

- 대량 INSERT 시 성능 향상
- 대신 연속성 보장은 약함

---

## 5.3.2 인덱스와 잠금의 관계

InnoDB는 레코드가 아니라 인덱스를 잠근다.

따라서:

- 인덱스가 있으면 → 해당 인덱스 범위만 잠금
- 인덱스가 없으면 → 테이블 전체 스캔
- 결과적으로 거의 테이블 전체가 잠길 수 있음

예시:

```sql
UPDATE employees
SET hire_date = NOW()
WHERE first_name = 'Georg';
```

만약 first_name에 인덱스가 없다면:

- 모든 레코드 스캔
- 많은 레코드에 잠금 발생
- 동시성 급격히 저하

따라서

> InnoDB에서는 인덱스 설계가 잠금 범위를 결정한다.

---

## 5.3.3 레코드 수준 잠금 확인 및 해제

MySQL 8.0에서는 performance_schema를 통해  
잠금 정보를 조회할 수 있다.

### 잠금 대기 확인

```sql
SELECT
    r.trx_id waiting_trx_id,
    r.trx_query waiting_query,
    b.trx_id blocking_trx_id,
    b.trx_query blocking_query
FROM performance_schema.data_lock_waits w
JOIN information_schema.innodb_trx b
  ON b.trx_id = w.blocking_engine_transaction_id
JOIN information_schema.innodb_trx r
  ON r.trx_id = w.requesting_engine_transaction_id;
```

이를 통해:

- 어떤 트랜잭션이 대기 중인지
- 어떤 트랜잭션이 잠금을 보유 중인지

확인 가능하다.

---

### 특정 트랜잭션 종료

```sql
KILL 17;
```

잠금을 오래 점유하는 세션을 종료하면  
대기 중인 트랜잭션이 실행된다.

---

## 5.3 핵심 정리

InnoDB 잠금의 핵심은 다음이다.

1. 레코드 단위 잠금
2. 인덱스 기반 잠금
3. 갭 락으로 INSERT 방지
4. 넥스트 키 락으로 팬텀 리드 방지
5. AUTO_INCREMENT 전용 잠금 존재

중요한 설계 원칙:

- 인덱스가 잠금 범위를 결정한다.
- 범위 검색은 넥스트 키 락을 유발한다.
- 장시간 트랜잭션은 동시성을 급격히 저하시킨다.

InnoDB 잠금은 단순한 동시성 제어가 아니라  
격리 수준과 직접적으로 연결된 동작이다.