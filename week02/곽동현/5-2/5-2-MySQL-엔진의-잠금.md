# 5.2 MySQL 엔진의 잠금

MySQL에서 사용되는 잠금은 크게 **스토리지 엔진 레벨**과 **MySQL 엔진 레벨**로 나눌 수 있다.

- **MySQL 엔진 레벨의 잠금**: 모든 스토리지 엔진에 영향을 미친다.
- **스토리지 엔진 레벨의 잠금**: 스토리지 엔진 간 상호 영향을 미치지 않는다.

MySQL 엔진에서는 테이블 데이터 동기화를 위한 **테이블 락** 이외에도 테이블의 구조를 잠그는 **메타데이터 락(Metadata Lock)**, 그리고 사용자의 필요에 맞게 사용할 수 있는 **네임드 락(Named Lock)** 이라는 잠금 기능도 제공한다.

---

## 5.2.1 글로벌 락

글로벌 락(GLOBAL LOCK)은 `FLUSH TABLES WITH READ LOCK` 명령으로 획득할 수 있으며, MySQL에서 제공하는 잠금 가운데 **가장 범위가 크다.** 한 세션에서 글로벌 락을 획득하면 다른 세션에서 SELECT를 제외한 대부분의 DDL 문장이나 DML 문장을 실행하는 경우 글로벌 락이 해제될 때까지 대기 상태로 남는다.

글로벌 락이 영향을 미치는 범위는 **MySQL 서버 전체**이며, 대상 데이터베이스가 다르더라도 동일하게 영향을 받는다.

### 글로벌 락의 주요 용도

- 주로 MyISAM이나 MEMORY 테이블에 대해 `mysqldump`로 일관된 백업을 받아야 할 때 사용한다.

> **주의**: `FLUSH TABLES WITH READ LOCK` 명령이 실행되기 전에 테이블이나 레코드에 쓰기 잠금을 걸고 있는 SQL이 실행 중이라면, 해당 SQL과 그 이전에 실행된 SQL이 완료된 후 `FLUSH TABLES WITH READ LOCK` 명령이 실행되고 읽기 잠금이 걸린다.

### MySQL 8.0의 백업 락

InnoDB 스토리지 엔진은 트랜잭션을 지원하기 때문에 일관된 데이터 상태를 위해 모든 데이터 변경 작업을 멈출 필요는 없다. 이를 위해 MySQL 8.0에서는 좀 더 가벼운 글로벌 락의 필요성이 생겼고, Xtrabackup이나 Enterprise Backup 같은 백업 툴들의 안정적인 실행을 위해 **백업 락**이 도입되었다.

```sql
mysql> LOCK INSTANCE FOR BACKUP;
-- // 백업 실행
mysql> UNLOCK INSTANCE;
```

특정 세션에서 백업 락을 획득하면 모든 세션에서 다음과 같이 테이블의 스키마나 사용자의 인증 관련 정보를 변경할 수 없게 된다:

- 데이터베이스 및 테이블 등 모든 객체 생성 및 변경, 삭제
- REPAIR TABLE과 OPTIMIZE TABLE 명령
- 사용자 관리 및 비밀번호 변경

하지만 **일반적인 테이블의 데이터 변경은 허용**된다. 백업 락은 주로 **레플리카 서버**에서 백업 시 사용되며, 소스 서버의 데이터가 레플리카에 반영되는 것을 막지 않으면서도 백업의 안정성을 보장한다.

---

## 5.2.2 테이블 락

테이블 락(Table Lock)은 개별 테이블 단위로 설정되는 잠금이며, 명시적 또는 묵시적으로 특정 테이블의 락을 획득할 수 있다.

```sql
-- 명시적 테이블 락 획득
mysql> LOCK TABLES table_name [ READ | WRITE ];

-- 명시적 테이블 락 해제
mysql> UNLOCK TABLES;
```

- **묵시적 테이블 락**: MyISAM이나 MEMORY 테이블에 데이터를 변경하는 쿼리를 실행하면 발생한다. MySQL 서버가 데이터가 변경되는 테이블에 잠금을 설정하고 데이터를 변경한 후 즉시 잠금을 해제하는 형태로 사용된다.
- **InnoDB 테이블**: 스토리지 엔진 차원에서 레코드 기반의 잠금을 제공하기 때문에 단순 데이터 변경 쿼리로 인해 묵시적인 테이블 락이 설정되지는 않는다. 더 정확히는 InnoDB 테이블에도 테이블 락이 설정되지만 대부분의 데이터 변경(DML) 쿼리에서는 무시되고 **스키마를 변경하는 쿼리(DDL)의 경우에만 영향**을 미친다.

---

## 5.2.3 네임드 락

네임드 락(Named Lock)은 `GET_LOCK()` 함수를 이용해 **임의의 문자열에 대해 잠금을 설정**할 수 있다. 이 잠금의 특징은 대상이 테이블이나 레코드 또는 `AUTO_INCREMENT`와 같은 데이터베이스 객체가 아니라, 단순히 사용자가 지정한 **문자열(String)** 에 대해 획득하고 반납(해제)하는 잠금이다.

```sql
-- // "mylock"이라는 문자열에 대해 잠금을 획득한다.
-- // 이미 잠금을 사용 중이면 2초 동안만 대기한다. (2초 이후 자동 잠금 해제됨)
mysql> SELECT GET_LOCK('mylock', 2);

-- // "mylock"이라는 문자열에 대해 잠금이 설정돼 있는지 확인한다.
mysql> SELECT IS_FREE_LOCK('mylock');

-- // "mylock"이라는 문자열에 대해 획득했던 잠금을 반납(해제)한다.
mysql> SELECT RELEASE_LOCK('mylock');

-- // 3개 함수 모두 정상적으로 락을 획득하거나 해제한 경우에는 1을,
-- // 아니면 NULL이나 0을 반환한다.
```

### 네임드 락의 활용

네임드 락은 많은 레코드에 대해서 복잡한 요건으로 레코드를 변경하는 트랜잭션에 유용하게 사용할 수 있다. 배치 프로그램처럼 한꺼번에 많은 레코드를 변경하는 쿼리는 자주 데드락의 원인이 되곤 한다. 이러한 경우에 동일 데이터를 변경하거나 참조하는 **프로그램끼리 분류해서 네임드 락을 걸고 쿼리를 실행**하면 아주 간단히 해결할 수 있다.

### MySQL 8.0의 네임드 락 개선

MySQL 8.0 버전부터는 네임드 락을 **중첩해서 사용**할 수 있게 됐으며, 현재 세션에서 획득한 네임드 락을 **한 번에 모두 해제**하는 기능도 추가됐다.

```sql
mysql> SELECT GET_LOCK('mylock_1',10);
-- // mylock_1에 대한 작업 실행
mysql> SELECT GET_LOCK('mylock_2',10);
-- // mylock_1과 mylock_2에 대한 작업 실행

mysql> SELECT RELEASE_LOCK('mylock_2');
mysql> SELECT RELEASE_LOCK('mylock_1');

-- // mylock_1과 mylock_2를 동시에 모두 해제하고자 한다면 RELEASE_ALL_LOCKS() 함수 사용
mysql> SELECT RELEASE_ALL_LOCKS();
```

---

## 5.2.4 메타데이터 락

메타데이터 락(Metadata Lock)은 데이터베이스 객체(대표적으로 테이블이나 뷰 등)의 **이름이나 구조를 변경하는 경우에 획득하는 잠금**이다.

메타데이터 락은 명시적으로 획득하거나 해제할 수 있는 것이 아니고, `RENAME TABLE tab_a TO tab_b` 같이 테이블의 이름을 변경하는 경우 **자동으로 획득**되는 잠금이다.

### RENAME TABLE의 원자적 실행

`RENAME TABLE` 명령의 경우 **원본 이름과 변경될 이름 두 개 모두 한꺼번에 잠금을 설정**한다. 실시간으로 테이블을 바꿔야 하는 요건이 배치 프로그램에서 자주 발생하는데, 다음 예제를 보자.

```sql
-- // 배치 프로그램에서 별도의 임시 테이블(rank_new)에 서비스용 랭킹 데이터를 생성
-- // 랭킹 배치가 완료되면 현재 서비스용 랭킹 테이블(rank)을 rank_backup으로 백업하고
-- // 새로 만들어진 랭킹 테이블(rank_new)을 서비스용으로 대체하고자 하는 경우
mysql> RENAME TABLE rank TO rank_backup, rank_new TO rank;
```

위와 같이 하나의 `RENAME TABLE` 명령문에 두 개의 RENAME 작업을 한꺼번에 실행하면 실제 애플리케이션에서는 "Table not found 'rank'" 같은 상황을 발생시키지 않고 적용하는 것이 가능하다.

하지만 이 문장을 다음과 같이 2개로 나눠서 실행하면 아주 짧은 시간이지만 `rank` 테이블이 존재하지 않는 순간이 생기며, 그 순간에 실행되는 쿼리는 "Table not found 'rank'" 오류를 발생시킨다.

```sql
-- // 이렇게 나누면 안 된다!
mysql> RENAME TABLE rank TO rank_backup;
mysql> RENAME TABLE rank_new TO rank;
```

### 메타데이터 락과 InnoDB 트랜잭션의 조합

때로는 메타데이터 잠금과 InnoDB의 트랜잭션을 동시에 사용해야 하는 경우도 있다. 예를 들어, INSERT만 실행되는 로그 테이블의 구조를 변경해야 할 때:

```sql
-- 테이블의 압축을 적용하기 위해 KEY_BLOCK_SIZE=4 옵션을 추가해 신규 테이블을 생성
mysql> CREATE TABLE access_log_new (
        id BIGINT NOT NULL AUTO_INCREMENT,
        client_ip INT UNSIGNED,
        access_dttm TIMESTAMP,
        ...
        PRIMARY KEY(id)
       ) KEY_BLOCK_SIZE=4;

-- 4개의 스레드를 이용해 id 범위별로 레코드를 신규 테이블로 복사
mysql_thread1> INSERT INTO access_log_new SELECT * FROM access_log WHERE id>=0 AND id<10000;
mysql_thread2> INSERT INTO access_log_new SELECT * FROM access_log WHERE id>=10000 AND id<20000;
mysql_thread3> INSERT INTO access_log_new SELECT * FROM access_log WHERE id>=20000 AND id<30000;
mysql_thread4> INSERT INTO access_log_new SELECT * FROM access_log WHERE id>=30000 AND id<40000;
```

그리고 나머지 데이터는 다음과 같이 트랜잭션과 테이블 잠금, `RENAME TABLE` 명령으로 응용 프로그램의 중단 없이 실행할 수 있다.

```sql
-- // 트랜잭션을 autocommit으로 실행(BEGIN이나 START TRANSACTION으로 실행하면 안 됨)
mysql> SET autocommit=0;

-- // 작업 대상 테이블 2개에 대해 테이블 쓰기 락을 획득
mysql> LOCK TABLES access_log WRITE, access_log_new WRITE;

-- // 남은 데이터를 복사
mysql> SELECT MAX(id) as @MAX_ID FROM access_log;
mysql> INSERT INTO access_log_new SELECT * FROM access_log WHERE pk>@MAX_ID;
mysql> COMMIT;

-- // 새로운 테이블로 데이터 복사가 완료되면 RENAME 명령으로 새로운 테이블을 서비스로 투입
mysql> RENAME TABLE access_log TO access_log_old, access_log_new TO access_log;
mysql> UNLOCK TABLES;

-- // 불필요한 테이블 삭제
mysql> DROP TABLE access_log_old;
```

이때 "남은 데이터를 복사"하는 시간 동안은 테이블의 잠금으로 인해 INSERT를 할 수 없게 된다. 그래서 가능하면 미리 아주 최근 데이터까지 복사해 둬야 잠금 시간을 최소화해서 서비스에 미치는 영향을 줄일 수 있다.
