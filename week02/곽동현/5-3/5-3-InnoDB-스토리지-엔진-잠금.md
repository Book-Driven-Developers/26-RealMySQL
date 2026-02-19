# 5.3 InnoDB 스토리지 엔진 잠금

InnoDB 스토리지 엔진은 MySQL에서 제공하는 잠금과는 별개로 스토리지 엔진 내부에서 **레코드 기반의 잠금 방식**을 탑재하고 있다. InnoDB는 레코드 기반의 잠금 방식 때문에 MyISAM보다 훨씬 뛰어난 동시성 처리를 제공할 수 있다.

하지만 이원화된 잠금 처리 탓에 InnoDB 스토리지 엔진에서 사용되는 잠금에 대한 정보는 MySQL 명령을 이용해 접근하기가 상당히 까다롭다.

### 잠금 정보 조회 방법

최근 버전에서는 InnoDB의 트랜잭션과 잠금, 그리고 잠금 대기 중인 트랜잭션의 목록을 조회할 수 있는 방법이 도입됐다.

- MySQL 서버의 `information_schema` 데이터베이스에 존재하는 `INNODB_TRX`, `INNODB_LOCKS`, `INNODB_LOCK_WAITS` 테이블을 조인해서 조회
- `Performance Schema`를 이용해 InnoDB 스토리지 엔진의 내부 잠금(세마포어)에 대한 모니터링 방법도 추가됨

---

## 5.3.1 InnoDB 스토리지 엔진의 잠금

InnoDB 스토리지 엔진은 레코드 기반의 잠금 기능을 제공하며, 잠금 정보가 상당히 작은 공간으로 관리되기 때문에 레코드 락이 페이지 락으로, 또는 테이블 락으로 레벨업되는 경우(락 에스컬레이션)는 없다.

일반 상용 DBMS와는 조금 다르게 InnoDB 스토리지 엔진에서는 **레코드 락뿐 아니라 레코드와 레코드 사이의 간격을 잠그는 갭(GAP) 락**이라는 것이 존재한다.

### 5.3.1.1 레코드 락

레코드 자체만을 잠그는 것을 **레코드 락(Record lock, Record only lock)** 이라고 하며, 다른 상용 DBMS의 레코드 락과 동일한 역할을 한다. 한 가지 중요한 차이는 InnoDB 스토리지 엔진은 레코드 자체가 아니라 **인덱스의 레코드를 잠근다**는 점이다.

인덱스가 하나도 없는 테이블이라도 내부적으로 자동 생성된 클러스터 인덱스를 이용해 잠금을 설정한다. 많은 사용자가 간과하지만, InnoDB에서 대부분의 보조 인덱스를 이용한 변경 작업은 이어서 설명할 **넥스트 키 락(Next key lock)** 또는 **갭 락(Gap lock)** 을 사용하지만 프라이머리 키 또는 유니크 인덱스에 의한 변경 작업에서는 갭(Gap, 간격)에 대해서는 잠그지 않고 레코드 자체에 대해서만 락을 건다.

### 5.3.1.2 갭 락

갭 락은 다른 DBMS와의 또 다른 차이가 바로 **갭 락(Gap lock)**이다. 갭 락은 레코드 자체가 아니라 레코드와 바로 인접한 레코드 사이의 간격만을 잠그는 것을 의미한다. 갭 락의 역할은 **레코드와 레코드 사이의 간격에 새로운 레코드가 생성(INSERT)되는 것을 제어**하는 것이다. 갭 락은 그 자체보다는 이어서 설명할 넥스트 키 락의 일부로 자주 사용된다.

### 5.3.1.3 넥스트 키 락

레코드 락과 갭 락을 합쳐 놓은 형태의 잠금을 **넥스트 키 락(Next key lock)** 이라고 한다. `STATEMENT` 포맷의 바이너리 로그를 사용하는 MySQL 서버에서는 `REPEATABLE READ` 격리 수준을 사용해야 한다. 또한 `innodb_locks_unsafe_for_binlog` 시스템 변수가 비활성화되면(0으로 설정되면) 변경을 위해 검색하는 레코드에는 넥스트 키 락 방식으로 잠금이 걸린다.

InnoDB의 갭 락이나 넥스트 키 락은 바이너리 로그에 기록되는 쿼리가 레플리카 서버에서 실행될 때 소스 서버에서 만들어 낸 결과와 동일한 결과를 만들어내도록 보장하는 것이 주목적이다. 그런데 의외로 넥스트 키 락과 갭 락으로 인해 데드락이 발생하거나 다른 트랜잭션을 기다리게 만드는 일이 자주 발생한다. 가능하다면 바이너리 로그 포맷을 **ROW 형태로 바꿔서 넥스트 키 락이나 갭 락을 줄이는 것이 좋다.**

> **참고**: MySQL 5.5 버전까지는 ROW 포맷의 바이너리 로그가 도입된 지 오래되지 않아 그다지 널리 사용되지 않았다. 하지만 MySQL 8.0에서는 ROW 포맷의 바이너리 로그가 기본 설정으로 변경되었다.

### 5.3.1.4 자동 증가 락

MySQL에서는 자동 증가하는 숫자 값을 추출(채번)하기 위해 `AUTO_INCREMENT` 칼럼이 사용되는데, `AUTO_INCREMENT` 값이 사용된 테이블에 동시에 여러 레코드가 INSERT되는 경우, 저장되는 각 레코드는 중복되지 않고 저장된 순서대로 증가하는 일련번호 값을 가져야 한다.

InnoDB 스토리지 엔진에서는 이를 위해 내부적으로 **AUTO_INCREMENT 락(Auto increment lock)** 이라고 하는 테이블 수준의 잠금을 사용한다.

- AUTO_INCREMENT 락은 INSERT와 REPLACE 문장과 같이 새로운 레코드를 저장하는 쿼리에서만 필요하며, UPDATE나 DELETE 등의 쿼리에서는 걸리지 않는다.
- 다른 잠금(레코드 락이나 넥스트 키 락)과 달리 AUTO_INCREMENT 값을 가져오는 **순간만 락이 걸렸다가 즉시 해제**된다. (트랜잭션과 관계없이)
- AUTO_INCREMENT 락은 테이블에 단 하나만 존재하기 때문에 두 개의 INSERT 쿼리가 동시에 실행되는 경우 하나의 쿼리가 AUTO_INCREMENT 락을 획득하면 나머지 쿼리는 대기해야 한다.

### innodb_autoinc_lock_mode 시스템 변수

MySQL 5.1 이상부터 `innodb_autoinc_lock_mode` 시스템 변수를 이용해 자동 증가 락의 작동 방식을 변경할 수 있다.

| 값 | 모드 | 설명 |
|---|------|------|
| 0 | 전통적(Traditional) | 모든 INSERT 문장에 자동 증가 락을 사용 |
| 1 | 연속(Consecutive) | INSERT되는 레코드 건수를 정확히 예측 가능할 때는 래치(뮤텍스)를 이용해 처리. 건수를 예측할 수 없을 때만 자동 증가 락 사용. **한 번에 할당받은 자동 증가 값이 남는 경우 폐기되므로 연속되지 않고 누락된 값이 발생할 수 있다.** |
| 2 | 인터리빙(Interleaved) | 절대 자동 증가 락을 걸지 않고, 경량화된 래치(뮤텍스)를 사용. 하나의 INSERT 문장으로 INSERT되는 레코드라 하더라도 연속된 자동 증가 값을 보장하지 않는다. |

> **참고**: MySQL 5.7 버전까지는 `innodb_autoinc_lock_mode`의 기본값이 1이었지만, MySQL 8.0 버전부터는 기본값이 **2**로 바뀌었다. 이는 MySQL 8.0의 바이너리 로그 포맷이 ROW 포맷이 기본값이 되면서 STATEMENT 포맷이 아닌 한 인터리빙 모드가 안전하기 때문이다.

---

## 5.3.2 인덱스와 잠금

InnoDB의 잠금과 인덱스는 상당히 중요한 연관 관계가 있기 때문에 다시 한번 더 자세히 살펴본다. InnoDB의 잠금은 레코드를 잠그는 것이 아니라 **인덱스를 잠그는 방식으로 처리**된다. 즉, 변경해야 할 레코드를 찾기 위해 검색한 인덱스의 레코드를 모두 락을 걸어야 한다.

### 예제: 인덱스와 잠금의 관계

```sql
-- // 예제 데이터베이스의 employees 테이블에는 아래와 같이 first_name 칼럼만
-- // 멤버로 담긴 ix_firstname이라는 인덱스가 준비돼 있다.
-- //   KEY ix_firstname (first_name)
-- // employees 테이블에서 first_name='Georgi'인 사원은 전체 253명이 있으며,
-- // first_name='Georgi'이고 last_name='Klassen'인 사원은 딱 1명만 있는 것을 아래 쿼리로
-- // 확인할 수 있다.
mysql> SELECT COUNT(*) FROM employees WHERE first_name='Georgi';
+----------+
|      253 |
+----------+

mysql> SELECT COUNT(*) FROM employees WHERE first_name='Georgi' AND last_name='Klassen';
+----------+
|        1 |
+----------+
```

```sql
-- // employees 테이블에서 first_name='Georgi'이고 last_name='Klassen'인 사원의
-- // 입사 일자를 오늘로 변경하는 쿼리를 실행해보자.
mysql> UPDATE employees SET hire_date=NOW() WHERE first_name='Georgi' AND last_name='Klassen';
```

UPDATE 문장이 실행되면 1건의 레코드가 업데이트될 것이다. 하지만 이 1건의 업데이트를 위해 **몇 개의 레코드에 락을 걸어야 할까?**

이 UPDATE 문장의 조건에서 인덱스를 이용할 수 있는 조건은 `first_name='Georgi'`이며, `last_name` 칼럼은 인덱스에 없기 때문에 `first_name='Georgi'`인 레코드 **253건의 레코드가 모두 잠긴다.**

이 예제에서는 몇 건 안 되는 레코드만 잠그지만 UPDATE 문장을 위해 적절히 인덱스가 준비돼 있지 않다면 각 클라이언트 간의 동시성이 상당히 떨어져서 한 세션에서 UPDATE 작업을 하는 중에는 다른 클라이언트는 그 테이블을 업데이트하지 못하고 기다려야 하는 상황이 발생할 것이다.

> **핵심**: 테이블에 인덱스가 하나도 없다면 어떻게 될까? 이러한 경우에는 테이블을 풀 스캔하면서 UPDATE 작업을 하는데, 이 과정에서 테이블에 있는 30여만 건의 **모든 레코드를 잠그게 된다.** 이것이 MySQL의 InnoDB에서 인덱스 설계가 중요한 이유 또한 이것이다.

---

## 5.3.3 레코드 수준의 잠금 확인 및 해제

InnoDB 스토리지 엔진을 사용하는 테이블의 레코드 수준 잠금은 테이블 수준의 잠금보다는 조금 더 복잡하다. 테이블 잠금에서는 잠금의 대상이 테이블 자체이므로 쉽게 문제의 원인이 발견되고 해결될 수 있다. 하지만 레코드 수준의 잠금은 테이블의 레코드 각각에 잠금이 걸리므로 그 레코드가 자주 사용되지 않는다면 오랜 시간 동안 잠겨진 상태로 남아 있어도 잘 발견되지 않는다.

### 잠금 확인 방법

MySQL 5.1부터는 레코드 잠금과 잠금 대기에 대한 조회가 가능하다. MySQL 8.0 버전부터는 `information_schema`의 정보들은 조금씩 제거(Deprecated)되고 있으며, 그 대신 `performance_schema`의 `data_locks`와 `data_lock_waits` 테이블로 대체되고 있다.

### 잠금 대기 시나리오 예제

| 커넥션 1 | 커넥션 2 | 커넥션 3 |
|---------|---------|---------|
| BEGIN; | | |
| UPDATE employees SET birth_date=NOW() WHERE emp_no=100001; | | |
| | UPDATE employees SET hire_date=NOW() WHERE emp_no=100001; | |
| | | UPDATE employees SET hire_date=NOW(), birth_date=NOW() WHERE emp_no=100001; |

위 시나리오에서 17번 스레드(커넥션 1)가 트랜잭션을 시작하고 UPDATE 명령이 실행 완료됐지만 아직 COMMIT을 실행하지는 않은 상태이므로 업데이트한 레코드의 잠금을 그대로 가지고 있다. 18번 스레드(커넥션 2)가 그다음으로 UPDATE 명령을 실행했으며, 그 이후 19번 스레드(커넥션 3)에서 UPDATE 명령을 실행했다. 18번과 19번 스레드는 잠금 대기로 인해 아직 UPDATE 명령을 실행 중인 것으로 표시된다.

### performance_schema를 이용한 잠금 대기 순서 확인

```sql
mysql> SELECT
        r.trx_id waiting_trx_id,
        r.trx_mysql_thread_id waiting_thread,
        r.trx_query waiting_query,
        b.trx_id blocking_trx_id,
        b.trx_mysql_thread_id blocking_thread,
        b.trx_query blocking_query
    FROM performance_schema.data_lock_waits w
    INNER JOIN information_schema.innodb_trx b
        ON b.trx_id = w.blocking_engine_transaction_id
    INNER JOIN information_schema.innodb_trx r
        ON r.trx_id = w.requesting_engine_transaction_id;
```

```
+-----------+---------+---------------------+-----------+---------+---------------------+
| waiting   | waiting | waiting_query       | blocking  | blocking| blocking_query      |
| _trx_id   | _thread |                     | _trx_id   | _thread |                     |
+-----------+---------+---------------------+-----------+---------+---------------------+
| 11990     |   19    | UPDATE employees..  | 11989     |   18    | UPDATE employees..  |
| 11990     |   19    | UPDATE employees..  | 11984     |   17    | NULL                |
| 11989     |   18    | UPDATE employees..  | 11984     |   17    | NULL                |
+-----------+---------+---------------------+-----------+---------+---------------------+
```

쿼리의 실행 결과를 보면 현재 대기 중인 스레드는 18번과 19번인 것을 알 수 있다. 18번 스레드는 17번 스레드를 기다리고 있고, 19번 스레드는 17번 스레드와 18번 스레드를 기다리고 있다는 것을 알 수 있다. 즉 **17번 스레드가 가지고 있는 잠금을 해제하고, 18번 스레드가 그 잠금을 획득하고 UPDATE를 완료한 후 잠금을 풀어야만 비로소 19번 스레드가 UPDATE를 실행할 수 있음**을 의미한다.

### 잠금 강제 해제

만약 17번 스레드가 잠금을 가진 상태에서 상당히 오랜 시간 멈춰 있다면 다음과 같이 17번 스레드를 강제 종료하면 나머지 UPDATE 명령들이 진행되면서 잠금 경합이 끝난다.

```sql
mysql> KILL 17;
```
