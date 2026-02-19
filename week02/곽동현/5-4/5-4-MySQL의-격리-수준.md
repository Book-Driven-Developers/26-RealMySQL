# 5.4 MySQL의 격리 수준

트랜잭션의 격리 수준(isolation level)이란 **여러 트랜잭션이 동시에 처리될 때 특정 트랜잭션이 다른 트랜잭션에서 변경하거나 조회하는 데이터를 볼 수 있게 허용할지 말지를 결정**하는 것이다.

격리 수준은 크게 4가지로 나뉜다:

1. **READ UNCOMMITTED**
2. **READ COMMITTED**
3. **REPEATABLE READ**
4. **SERIALIZABLE**

"DIRTY READ"라고도 하는 READ UNCOMMITTED는 일반적인 데이터베이스에서 거의 사용하지 않고, SERIALIZABLE 또한 동시성이 중요한 데이터베이스에서는 거의 사용되지 않는다. 4개의 격리 수준에서 순서대로 뒤로 갈수록 각 트랜잭션 간의 데이터 격리(고립) 정도가 높아지며, 동시 처리 성능도 떨어지는 것이 일반적이다.

> 격리 수준이 높아질수록 MySQL 서버의 처리 성능이 많이 떨어질 것으로 생각하는 사용자가 많은데, 사실 SERIALIZABLE 격리 수준이 아니라면 크게 성능의 개선이나 저하는 발생하지 않는다.

---

## 격리 수준별 부정합 문제 정리

| | DIRTY READ | NON-REPEATABLE READ | PHANTOM READ |
|---|:---:|:---:|:---:|
| **READ UNCOMMITTED** | 발생 | 발생 | 발생 |
| **READ COMMITTED** | 없음 | 발생 | 발생 |
| **REPEATABLE READ** | 없음 | 없음 | 발생 (InnoDB는 없음) |
| **SERIALIZABLE** | 없음 | 없음 | 없음 |

SQL-92 또는 SQL-99 표준에 따르면 REPEATABLE READ 격리 수준에서는 PHANTOM READ가 발생할 수 있지만, **InnoDB에서는 독특한 특성 때문에 REPEATABLE READ 격리 수준에서도 PHANTOM READ가 발생하지 않는다.**

일반적인 온라인 서비스 용도의 데이터베이스는 READ COMMITTED와 REPEATABLE READ 중 하나를 사용한다. 오라클 같은 DBMS에서는 주로 READ COMMITTED 수준을 많이 사용하며, **MySQL에서는 REPEATABLE READ를 주로 사용**한다.

> 여기서 설명하는 SQL 예제는 모두 AUTOCOMMIT이 OFF인 상태(`SET autocommit=OFF`)에서만 테스트할 수 있다.

---

## 5.4.1 READ UNCOMMITTED

READ UNCOMMITTED 격리 수준에서는 각 트랜잭션에서의 변경 내용이 **COMMIT이나 ROLLBACK 여부에 상관없이** 다른 트랜잭션에서 보인다.

### 동작 예시

1. 사용자 A는 `emp_no`가 500000이고 `first_name`이 "Lara"인 새로운 사원을 INSERT한다.
2. 사용자 B가 변경된 내용을 커밋하기도 전에 `emp_no=500000`인 사원을 검색하고 있다.
3. 하지만 사용자 B는 사용자 A가 INSERT한 사원의 정보를 **커밋되지 않은 상태에서도 조회**할 수 있다.

문제는 사용자 A가 처리 도중 알 수 없는 문제가 발생해 INSERT된 내용을 롤백한다고 하더라도 여전히 사용자 B는 "Lara"가 정상적인 사원이라고 생각하고 계속 처리할 것이라는 점이다.

이처럼 어떤 트랜잭션에서 처리한 작업이 완료되지 않았는데도 다른 트랜잭션에서 볼 수 있는 현상을 **더티 리드(Dirty read)** 라 하고, 더티 리드가 허용되는 격리 수준이 READ UNCOMMITTED이다.

> RDBMS 표준에서는 트랜잭션의 격리 수준으로 인정하지 않을 정도로 정합성에 문제가 많은 격리 수준이다. MySQL을 사용한다면 최소한 READ COMMITTED 이상의 격리 수준을 사용할 것을 권장한다.

---

## 5.4.2 READ COMMITTED

READ COMMITTED는 **오라클 DBMS에서 기본으로 사용되는 격리 수준**이며, 온라인 서비스에서 가장 많이 선택되는 격리 수준이다. 이 레벨에서는 더티 리드(Dirty read) 같은 현상은 발생하지 않는다. **어떤 트랜잭션에서 데이터를 변경했더라도 COMMIT이 완료된 데이터만 다른 트랜잭션에서 조회할 수 있기 때문**이다.

### 동작 예시

1. 사용자 A는 `emp_no=500000`인 사원의 `first_name`을 "Lara"에서 "Toto"로 변경한다.
2. 이때 새로운 값인 "Toto"는 employees 테이블에 즉시 기록되고, 이전 값인 "Lara"는 **언두 영역으로 백업**된다.
3. 사용자 A가 커밋을 수행하기 전에 사용자 B가 `emp_no=500000`인 사원을 SELECT하면, 조회된 결과의 `first_name` 칼럼의 값은 "Toto"가 아니라 **"Lara"로 조회**된다.
4. 여기서 사용자 B의 SELECT 쿼리 결과는 employees 테이블이 아니라 **언두 영역에 백업된 레코드에서 가져온 것**이다.
5. 최종적으로 사용자 A가 변경된 내용을 커밋하면 그때부터는 다른 트랜잭션에서도 백업된 언두 레코드("Lara")가 아니라 새롭게 변경된 "Toto"라는 값을 참조할 수 있게 된다.

### NON-REPEATABLE READ 문제

READ COMMITTED 격리 수준에서도 **"NON-REPEATABLE READ"("REPEATABLE READ"가 불가능하다)** 라는 부정합의 문제가 있다.

1. 사용자 B가 BEGIN 명령으로 트랜잭션을 시작하고 `first_name`이 "Toto"인 사용자를 검색했는데, 일치하는 결과가 없었다.
2. 하지만 사용자 A가 사원 번호가 500000인 사원의 이름을 "Toto"로 변경하고 커밋을 실행한 후, 사용자 B가 똑같은 SELECT 쿼리로 다시 조회하면 이번에는 결과가 1건이 조회된다.
3. 이는 별다른 문제가 없어 보이지만, 사실 사용자 B가 **하나의 트랜잭션 내에서 똑같은 SELECT 쿼리를 실행했을 때는 항상 같은 결과를 가져와야 한다**는 "REPEATABLE READ" 정합성에 어긋나는 것이다.

> 이러한 부정합 현상은 일반적인 웹 프로그램에서는 크게 문제되지 않을 수 있지만, 하나의 트랜잭션에서 입금과 출금 처리를 계속 반복할 때 다른 트랜잭션에서 오늘 입금된 금액의 총합을 조회한다고 가정하면, "REPEATABLE READ"가 보장되지 않기 때문에 총합을 계산하는 SELECT 쿼리는 실행될 때마다 다른 결과를 가져오게 될 것이다.

---

## 5.4.3 REPEATABLE READ

REPEATABLE READ는 **MySQL의 InnoDB 스토리지 엔진에서 기본으로 사용되는 격리 수준**이다. 바이너리 로그를 가진 MySQL 서버에서는 최소 REPEATABLE READ 격리 수준 이상을 사용해야 한다. 이 격리 수준에서는 READ COMMITTED 격리 수준에서 발생하는 "NON-REPEATABLE READ" 부정합이 발생하지 않는다.

### MVCC (Multi Version Concurrency Control)

InnoDB 스토리지 엔진은 트랜잭션이 ROLLBACK될 가능성에 대비해 변경되기 전 레코드를 언두(Undo) 공간에 백업해두고 실제 레코드 값을 변경한다. 이러한 변경 방식을 MVCC라고 한다(4.2.3절 참조).

- REPEATABLE READ와 READ COMMITTED 모두 MVCC를 이용해 트랜잭션 커밋 전의 데이터를 보여주지만, 두 격리 수준의 차이는 **언두 영역에 백업된 레코드의 여러 버전 가운데 몇 번째 이전 버전까지 찾아 들어가야 하느냐**에 있다.
- 모든 InnoDB의 트랜잭션은 **고유한 트랜잭션 번호(순차적으로 증가하는 값)** 를 가지며, 언두 영역에 백업된 모든 레코드에는 변경을 발생시킨 트랜잭션의 번호가 포함돼 있다.

### 동작 예시

1. employees 테이블의 초기 두 레코드는 트랜잭션 번호 6인 트랜잭션에 의해 INSERT됐다고 가정한다.
2. 사용자 B가 `BEGIN` 명령으로 트랜잭션을 시작하면서 트랜잭션 번호 10을 부여받는다.
3. 사용자 A(트랜잭션 번호 12)가 `emp_no=500000`인 사원의 이름을 "Toto"로 변경하고 커밋을 수행한다.
4. 사용자 B가 `emp_no=500000`인 사원을 A 트랜잭션의 변경 전후 각각 한 번씩 SELECT했는데, 결과는 항상 "Lara"라는 값을 가져온다.

사용자 B가 BEGIN 명령으로 트랜잭션을 시작하면서 10번이라는 트랜잭션 번호를 부여받았는데, 그때부터 사용자 B의 10번 트랜잭션 안에서 실행되는 모든 SELECT 쿼리는 **트랜잭션 번호가 10(자신의 트랜잭션 번호)보다 작은 트랜잭션 번호에서 변경한 것만 보게 된다.**

### 언두 영역과 성능

언두 영역에 백업된 데이터가 하나만 있는 것으로 표현했지만 사실 하나의 레코드에 대해 백업이 하나 이상 얼마든지 존재할 수 있다. 한 사용자가 BEGIN으로 트랜잭션을 시작하고 장시간 트랜잭션을 종료하지 않으면 언두 영역이 백업된 데이터로 무한정 커질 수 있다. 이렇게 언두에 백업된 레코드가 많아지면 **MySQL 서버의 처리 성능이 떨어질 수 있다.**

### PHANTOM READ (PHANTOM ROWS)

REPEATABLE READ 격리 수준에서도 다음과 같은 부정합이 발생할 수 있다. 사용자 A가 employees 테이블에 INSERT를 실행하는 도중에 사용자 B가 `SELECT ... FOR UPDATE` 쿼리로 employees 테이블을 조회했을 때 어떤 결과를 가져오는지 보여준다.

1. 사용자 B가 `BEGIN`으로 트랜잭션을 시작한 후 `SELECT ... WHERE emp_no>=500000 FOR UPDATE`를 수행하면 결과 1건(Lara)이 반환된다.
2. 사용자 A가 `INSERT INTO employees (500001, 'Georgi')`를 실행하고 COMMIT한다.
3. 사용자 B가 동일한 `SELECT ... FOR UPDATE` 쿼리를 실행하면 이번에는 결과가 2건(Lara, Georgi)이 반환된다.

이렇게 다른 트랜잭션에서 수행한 변경 작업에 의해 레코드가 보였다 안 보였다 하는 현상을 **PHANTOM READ(또는 PHANTOM ROW)** 라고 한다.

- `SELECT ... FOR UPDATE` 쿼리는 SELECT하는 레코드에 **쓰기 잠금을 걸어야** 하는데, 언두 레코드에는 잠금을 걸 수 없다.
- 그래서 `SELECT ... FOR UPDATE`나 `SELECT ... LOCK IN SHARE MODE`로 조회되는 레코드는 **언두 영역의 변경 전 데이터를 가져오는 것이 아니라 현재 레코드의 값을 가져오게 되는 것**이다.

> **InnoDB의 특성**: InnoDB 스토리지 엔진에서는 갭 락과 넥스트 키 락 덕분에 REPEATABLE READ 격리 수준에서도 이미 "PHANTOM READ"가 발생하지 않기 때문에 굳이 SERIALIZABLE을 사용할 필요성은 없어 보인다.
>
> 단, 엄밀하게는 `SELECT ... FOR UPDATE` 또는 `SELECT ... FOR SHARE` 쿼리의 경우 REPEATABLE READ 격리 수준에서 PHANTOM READ 현상이 발생할 수 있다. 하지만 레코드의 변경 이력(언두 레코드)에 잠금을 걸 수는 없기 때문에, 이러한 잠금을 동반한 SELECT 쿼리는 예외적인 상황으로 볼 수 있다.

---

## 5.4.4 SERIALIZABLE

가장 단순한 격리 수준이면서 동시에 **가장 엄격한 격리 수준**이다. 그만큼 동시 처리 성능도 다른 트랜잭션 격리 수준보다 떨어진다.

- InnoDB 테이블에서 기본적으로 순수한 SELECT 작업(`INSERT ... SELECT ...` 또는 `CREATE TABLE ... AS SELECT ...`가 아닌)은 아무런 레코드 잠금도 설정하지 않고 실행된다. InnoDB 매뉴얼에서 자주 나타나는 "Non-locking consistent read(잠금이 필요 없는 일관된 읽기)"라는 말이 이를 의미하는 것이다.
- 하지만 트랜잭션의 격리 수준이 SERIALIZABLE로 설정되면 읽기 작업도 **공유 잠금(읽기 잠금)을 획득**해야만 하며, 동시에 다른 트랜잭션은 그러한 레코드를 변경하지 못하게 된다.
- 즉, **한 트랜잭션에서 읽고 쓰는 레코드를 다른 트랜잭션에서는 절대 접근할 수 없는 것**이다.

SERIALIZABLE 격리 수준에서는 일반적인 DBMS에서 일어나는 "PHANTOM READ"라는 문제가 발생하지 않는다. 하지만 InnoDB 스토리지 엔진에서는 갭 락과 넥스트 키 락 덕분에 **REPEATABLE READ 격리 수준에서도 이미 "PHANTOM READ"가 발생하지 않기 때문에** 굳이 SERIALIZABLE을 사용할 필요성은 없어 보인다.
