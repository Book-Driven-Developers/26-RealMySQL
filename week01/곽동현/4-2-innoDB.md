# 4.2 InnoDB 스토리지 엔진 아키텍처 정리

> **출처**: Real MySQL 8.0 - 개발자와 DBA를 위한 MySQL 실전 가이드 (Chapter 4)

---

## 📌 InnoDB 개요

InnoDB는 MySQL에서 가장 많이 사용되는 스토리지 엔진으로, **레코드 기반의 잠금(Row-level Locking)**을 제공하여 높은 동시성 처리가 가능하다. MySQL 8.0부터는 서버의 모든 기능을 InnoDB 스토리지 엔진만으로 구현할 수 있게 되었다.

**InnoDB 핵심 구성요소:**

| 구성요소 | 역할 |
|--------|------|
| InnoDB 버퍼 풀 | 디스크 데이터/인덱스를 메모리에 캐시 (읽기/쓰기 성능 향상) |
| 언두 페이지 | 트랜잭션 롤백 및 MVCC를 위한 이전 데이터 보관 |
| 데이터 페이지 버퍼 | 변경된 데이터를 메모리에 보관 |
| 로그 버퍼 | 리두 로그를 디스크에 기록하기 전 버퍼링 |
| 체인지 버퍼 | 인덱스 변경을 임시 저장하여 성능 향상 |
| 어댑티브 해시 인덱스 | 자주 접근하는 데이터에 대한 자동 해시 인덱스 |
| 백그라운드 스레드 | 체인지버퍼 머지, 데이터페이지 기록, 로그 기록 담당 |

---

## 4.2.1 프라이머리 키에 의한 클러스터링

InnoDB의 모든 테이블은 **프라이머리 키를 기준으로 클러스터링**되어 저장된다.

**핵심 포인트:**

| 항목 | InnoDB | MyISAM |
|------|--------|--------|
| 데이터 저장 순서 | PK 값 순서대로 디스크에 저장 | 입력 순서대로 저장 |
| 세컨더리 인덱스 | PK의 논리적 주소 사용 | 물리적 레코드 주소(ROWID) 사용 |
| 클러스터링 키 지원 | O | X |
| PK 레인지 스캔 성능 | 매우 빠름 | 상대적으로 느림 |

**Java 개발자 관점에서의 시사점:** 쿼리 실행 계획에서 프라이머리 키가 기본적으로 다른 보조 인덱스보다 높은 비중으로 선택될 확률이 높다. 따라서 JPA/Hibernate에서 `@Id` 설계가 매우 중요하다.

---

## 4.2.2 외래 키 지원

외래 키는 **InnoDB 스토리지 엔진 레벨**에서 지원하는 기능이다 (MyISAM, MEMORY에서는 사용 불가).

**주의사항:**
- 외래 키는 부모/자식 테이블 모두에 인덱스 생성이 필요하다
- 변경 시 부모/자식 테이블에 데이터 존재 여부를 체크하므로 **잠금이 여러 테이블로 전파** → 데드락 주의
- 긴급 상황에서 외래 키 체크를 일시적으로 비활성화 가능

```sql
-- AS-IS: 외래 키 체크가 활성화된 상태 (기본값)
-- 대량 데이터 적재 시 부가적인 체크로 인해 성능 저하 발생

-- TO-BE: 외래 키 체크를 일시적으로 비활성화하여 성능 향상
SET foreign_key_checks=OFF;
-- // 작업 실행 (대량 INSERT, 스키마 변경 등)
SET foreign_key_checks=ON;
```

> ⚠️ **중요**: `foreign_key_checks`를 비활성화해도 부모-자식 테이블 간의 관계가 깨진 상태로 유지해도 된다는 의미가 아니다. 반드시 작업 후 데이터 일관성을 맞추고 다시 활성화해야 한다.

> ⚠️ **SESSION 범위 주의**: `SET foreign_key_checks=OFF`는 GLOBAL과 SESSION 모두 설정 가능하다. 반드시 현재 작업을 실행하는 세션에서만 비활성화해야 한다.

```sql
-- 안전한 사용법: SESSION 범위 명시
SET SESSION foreign_key_checks=OFF;
```

---

## 4.2.3 MVCC (Multi Version Concurrency Control)

MVCC는 **잠금을 사용하지 않는 일관된 읽기**를 제공하는 핵심 기능이다. InnoDB는 **언두 로그(Undo Log)**를 이용해 구현한다.

**동작 원리 (UPDATE 예시):**

```
[1단계] INSERT 실행 → InnoDB 버퍼 풀 + 데이터 파일에 데이터 저장

[2단계] UPDATE 실행 시:
  ┌─────────────────┐     ┌──────────────┐
  │  InnoDB 버퍼 풀   │     │   언두 로그    │
  │  m_id=12        │     │  m_id=12     │
  │  m_name=홍길동    │ ──→ │  m_area=서울  │  ← 변경 전 값 백업
  │  m_area=경기 (신) │     │              │
  └─────────────────┘     └──────────────┘
```

**격리 수준별 읽기 동작:**

| 격리 수준 | 읽는 위치 | 설명 |
|---------|---------|------|
| `READ_UNCOMMITTED` | InnoDB 버퍼 풀 | 커밋 여부 무관, 현재 변경된 데이터 반환 (Dirty Read) |
| `READ_COMMITTED` | 언두 영역 | 커밋되지 않았으면 변경 전 데이터 반환 |
| `REPEATABLE_READ` | 언두 영역 | 트랜잭션 시작 시점의 데이터 반환 |
| `SERIALIZABLE` | - | 읽기에도 잠금 사용 |

**Java 개발자 관점:** Spring의 `@Transactional(isolation = Isolation.READ_COMMITTED)`과 같은 설정이 실제 InnoDB에서 어떻게 동작하는지 이해하는 것이 중요하다. MVCC 덕분에 `SELECT`가 다른 트랜잭션의 `UPDATE`를 기다리지 않는다.

---

## 4.2.4 잠금 없는 일관된 읽기 (Non-Locking Consistent Read)

InnoDB는 MVCC를 이용해 **잠금을 걸지 않고 읽기 작업을 수행**한다.

- 격리 수준이 `SERIALIZABLE`이 아닌 경우, 순수한 `SELECT` 작업은 다른 트랜잭션의 변경 작업과 관계없이 **항상 잠금을 대기하지 않고 바로 실행**된다.
- 변경되기 전의 데이터를 읽기 위해 **언두 로그를 사용**한다.

> ⚠️ **주의**: 오랜 시간 활성 상태인 트랜잭션은 언두 로그를 삭제하지 못하게 하여 MySQL 서버 성능 저하의 원인이 된다. 따라서 트랜잭션이 시작되면 **가능한 한 빨리 롤백이나 커밋을 통해 완료**하는 것이 좋다.

---

## 4.2.5 자동 데드락 감지

InnoDB는 내부적으로 잠금 교착 상태를 체크하기 위해 **잠금 대기 목록을 Wait-for List 그래프**로 관리한다.

**데드락 감지 스레드 동작:**
1. 주기적으로 잠금 대기 그래프를 검사
2. 교착 상태에 빠진 트랜잭션들을 찾음
3. **언두 로그 레코드가 적은 트랜잭션**(롤백 비용이 적은 쪽)을 강제 종료

**성능 이슈와 대안:**

| 상황 | 해결책 |
|------|--------|
| 동시 처리 스레드가 매우 많아 데드락 감지 스레드가 느려짐 | `innodb_deadlock_detect = OFF` 설정 |
| 데드락 감지 비활성화 시 무한 대기 방지 | `innodb_lock_wait_timeout`을 50초보다 훨씬 낮게 설정 |

```sql
-- AS-IS: 기본 설정 (데드락 감지 활성화, 타임아웃 50초)
-- 동시 처리 스레드가 매우 많은 환경에서 성능 저하 발생

-- TO-BE: PK 또는 세컨더리 인덱스 기반 높은 동시성 서비스
SET GLOBAL innodb_deadlock_detect = OFF;
SET GLOBAL innodb_lock_wait_timeout = 5;  -- 50초 → 5초로 단축
```

---

## 4.2.6 자동화된 장애 복구

InnoDB는 MySQL 서버가 시작될 때 **데이터 파일에 대해 항상 자동 복구를 수행**한다. 자동 복구가 불가능한 경우 `innodb_force_recovery` 시스템 변수를 설정하여 수동 복구한다.

**innodb_force_recovery 옵션 요약:**

| 값 | 이름 | 설명 |
|---|------|------|
| 0 | 기본값 | 정상 시작, SELECT 외 DML 가능 |
| 1 | SRV_FORCE_IGNORE_CORRUPT | 손상된 데이터/인덱스 페이지 무시하고 시작 |
| 2 | SRV_FORCE_NO_BACKGROUND | 메인 백그라운드 스레드 미시작 (Undo purge 중단) |
| 3 | SRV_FORCE_NO_TRX_UNDO | 커밋되지 않은 트랜잭션 롤백 안 함 |
| 4 | SRV_FORCE_NO_IBUF_MERGE | 인서트 버퍼 병합 안 함 |
| 5 | SRV_FORCE_NO_UNDO_LOG_SCAN | 언두 로그 무시, 미완료 트랜잭션 모두 커밋 처리 |
| 6 | SRV_FORCE_NO_LOG_REDO | 리두 로그 모두 무시, 마지막 체크포인트 시점 데이터만 유지 |

> **복구 전략**: 값을 1부터 시작하여 MySQL이 시작되지 않으면 점진적으로 올린다. 값이 커질수록 데이터 손실 가능성도 커진다. 서버 시작 후 반드시 `mysqldump`로 백업 → 재구축하는 것이 권장된다.

---

## 4.2.7 InnoDB 버퍼 풀

InnoDB 스토리지 엔진의 **가장 핵심적인 부분**으로, 디스크의 데이터/인덱스 정보를 **메모리에 캐시**해 둔다. 쓰기 작업을 지연시켜 일괄 처리할 수 있게 하는 **버퍼 역할**도 한다.

### 4.2.7.1 버퍼 풀의 크기 설정

**권장 설정 가이드:**

| 전체 메모리 | InnoDB 버퍼 풀 권장 크기 |
|-----------|---------------------|
| 8GB 미만 | 전체의 50% |
| 8GB 이상 | 전체의 50%에서 시작, 점진적 증가 |
| 50GB 이상 | 15GB~30GB는 OS/다른 프로그램용으로 남기고 나머지 할당 |

```sql
-- innodb_buffer_pool_size: 동적으로 크기 조절 가능 (MySQL 5.7+)
-- 주의: 128MB 청크 단위로 처리됨
-- 주의: 버퍼 풀 줄이기는 서비스 영향도가 매우 크므로 가능하면 하지 않을 것
```

**버퍼 풀 인스턴스:**

```sql
-- AS-IS: 단일 버퍼 풀 → 잠금(세마포어) 경합 발생
-- TO-BE: 여러 개의 버퍼 풀 인스턴스로 경합 분산
-- innodb_buffer_pool_instances 시스템 변수로 설정

-- 권장: 메모리 40GB 이하 → 기본값 8 유지
-- 권장: 메모리가 크다면 → 버퍼 풀 인스턴스당 5GB 정도가 되게 설정
```

### 4.2.7.2 버퍼 풀의 구조

InnoDB 버퍼 풀은 **페이지 크기(innodb_page_size)** 조각으로 나뉘며, 3개의 자료 구조로 관리된다:

| 리스트 | 역할 |
|--------|------|
| **LRU 리스트** | 디스크에서 읽어온 페이지를 최대한 오래 메모리에 유지하여 디스크 읽기 최소화 |
| **플러시 리스트** | 디스크로 동기화되지 않은 데이터(더티 페이지)의 변경 시점 기준 관리 |
| **프리 리스트** | 비어 있는 페이지 목록, 새로운 데이터 페이지를 읽어올 때 사용 |

**LRU 리스트 동작 과정:**
1. 필요한 레코드의 데이터 페이지가 버퍼 풀에 있는지 검사
2. 없으면 디스크에서 읽어와 LRU 헤더 부분에 추가
3. 실제로 읽히면 MRU 헤더 부분으로 이동
4. 오래 사용되지 않으면 나이(Age)가 오래되어 버퍼 풀에서 제거(Eviction)
5. 자주 접근되는 데이터는 어댑티브 해시 인덱스에 추가

### 4.2.7.3 버퍼 풀과 리두 로그

버퍼 풀과 리두 로그는 **매우 밀접한 관계**를 맺고 있다.

- **클린 페이지 (Clean Page)**: 디스크에서 읽은 상태 그대로, 변경 없음
- **더티 페이지 (Dirty Page)**: INSERT, UPDATE, DELETE로 변경된 데이터를 가진 페이지

**리두 로그 공간과 버퍼 풀 크기의 관계:**

| 시나리오 | 버퍼 풀 | 리두 로그 | 문제점 |
|---------|--------|---------|--------|
| 1번 | 100GB | 100MB | 리두 로그가 너무 작아 쓰기 버퍼링 효과 거의 없음 |
| 2번 | 100MB | 100GB | 버퍼 풀이 너무 작아 허용 가능한 더티 페이지 100MB뿐 |

> **권장**: 버퍼 풀 100GB 이하 → 리두 로그 전체 크기 5~10GB에서 시작하여 점진적으로 최적값을 찾는다.

### 4.2.7.4 버퍼 풀 플러시 (Buffer Pool Flush)

InnoDB는 더티 페이지를 디스크에 동기화하기 위해 **2가지 플러시**를 백그라운드로 실행한다:

**① 플러시 리스트(Flush_list) 플러시:**
- 리두 로그 공간 재활용을 위해 오래된 리두 로그 엔트리부터 비움
- 관련 시스템 변수: `innodb_page_cleaners`, `innodb_max_dirty_pages_pct`, `innodb_io_capacity` 등

**② LRU 리스트(LRU_list) 플러시:**
- LRU 리스트 끝에서 사용 빈도가 낮은 페이지를 제거하여 새 페이지를 위한 공간 확보
- 관련 시스템 변수: `innodb_lru_scan_depth`

**주요 시스템 변수:**

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `innodb_page_cleaners` | 8 | 더티 페이지 동기화 클리너 스레드 수 |
| `innodb_max_dirty_pages_pct` | 90% | 버퍼 풀 내 최대 더티 페이지 비율 |
| `innodb_max_dirty_pages_pct_lwm` | 10% | 일정 수준 이상이면 조금씩 디스크 기록 시작 |
| `innodb_io_capacity` | - | 일반 상황의 디스크 I/O 기준값 |
| `innodb_io_capacity_max` | - | 최대 성능 발휘 시 디스크 I/O 한계값 |
| `innodb_adaptive_flushing` | ON | 리두 로그 증가 속도 분석 기반 플러시 |
| `innodb_flush_neighbors` | 0 | SSD는 비활성(0), HDD는 1~2 설정 권장 |

### 4.2.7.5 버퍼 풀 상태 백업 및 복구

서버 재시작 시 버퍼 풀이 비어 있으면 쿼리 성능이 평상시의 **1/10 이하**로 떨어질 수 있다 (Warming Up 필요).

```sql
-- AS-IS: 서버 재시작 후 버퍼 풀이 비어 있어 쿼리 성능 저하
-- TO-BE: 셧다운 전 버퍼 풀 상태 백업 → 재시작 후 자동 복구

-- 수동 백업/복구
SET GLOBAL innodb_buffer_pool_dump_now=ON;    -- 셧다운 전
SET GLOBAL innodb_buffer_pool_load_now=ON;    -- 재시작 후

-- 자동 백업/복구 설정 (my.cnf에 추가 권장)
-- innodb_buffer_pool_dump_at_shutdown = ON
-- innodb_buffer_pool_load_at_startup = ON
```

> 백업 파일(`ib_buffer_pool`)은 LRU 리스트의 **메타 정보만** 저장하므로 수십 MB 이하로 매우 빠르게 완료된다. 복구는 실제 데이터 페이지를 디스크에서 다시 읽어야 하므로 시간이 걸릴 수 있다.

### 4.2.7.6 버퍼 풀의 적재 내용 확인

```sql
-- MySQL 8.0: innodb_cached_indexes 테이블로 인덱스별 캐시 상태 확인
SELECT it.name table_name, ii.name index_name, ici.n_cached_pages
FROM information_schema.innodb_tables it
  INNER JOIN information_schema.innodb_indexes ii ON ii.table_id = it.table_id
  INNER JOIN information_schema.innodb_cached_indexes ici ON ici.index_id = ii.index_id
WHERE it.name=CONCAT('employees','/','employees');
```

---

## 4.2.8 Double Write Buffer

InnoDB는 더티 페이지를 디스크에 플러시할 때 **일부만 기록되는 현상(Partial-page/Torn-page)**을 방지하기 위해 **Double-Write 기법**을 사용한다.

**동작 방식:**
1. 더티 페이지를 먼저 **DoubleWrite 버퍼(시스템 테이블스페이스)**에 한번 묶어서 순차 쓰기
2. 그 후 각 더티 페이지를 **실제 데이터 파일의 적당한 위치**에 랜덤 쓰기

**장애 복구 시:** DoubleWrite 버퍼 내용과 데이터 파일을 비교하여, 불일치가 있으면 DoubleWrite 버퍼의 내용으로 복사한다.

| 스토리지 | DoubleWrite 부담 | 권장 |
|---------|----------------|------|
| HDD | 낮음 (순차 쓰기) | 활성화 |
| SSD | 높음 (랜덤 IO 비용 비슷) | 데이터 무결성이 중요하면 활성화 유지 |

> ⚠️ `innodb_flush_log_at_trx_commit`이 1이 아닌 값이면서 DoubleWrite만 활성화하는 것은 **잘못된 설정**이다.

---

## 4.2.9 언두 로그 (Undo Log)

InnoDB는 트랜잭션과 격리 수준을 보장하기 위해 **DML(INSERT, UPDATE, DELETE)로 변경되기 이전 버전의 데이터**를 별도로 백업한다.

**언두 로그의 2가지 용도:**

| 용도 | 설명 |
|------|------|
| 트랜잭션 롤백 대비 | 롤백 시 언두 로그에 백업해 둔 이전 데이터로 복구 |
| 격리 수준 보장 (MVCC) | 다른 커넥션에서 트랜잭션 격리 수준에 맞게 변경 전 데이터를 반환 |

### 4.2.9.1 언두 로그 레코드 모니터링

```sql
-- MySQL 서버의 언두 로그 레코드 건수 확인 (모든 버전)
SHOW ENGINE INNODB STATUS \G
-- History list length 값 확인

-- MySQL 8.0 전용
SELECT count FROM information_schema.innodb_metrics
WHERE SUBSYSTEM='transaction' AND NAME='trx_rseg_history_len';
```

> 서비스 중인 MySQL 서버에서 **활성 트랜잭션이 장시간 유지되면 언두 로그가 계속 증가**하여 디스크 사용량 증가 + 쿼리 성능 저하가 발생한다. 항상 모니터링하는 것이 좋다.

### 4.2.9.2 언두 테이블스페이스 관리

**버전별 언두 테이블스페이스 변화:**

| 버전 | 저장 위치 | 특징 |
|------|---------|------|
| MySQL 5.6 이전 | 시스템 테이블스페이스(ibdata.ibd) | 확장의 한계 |
| MySQL 5.6+ | 별도 파일 가능 (`innodb_undo_tablespaces` ≥ 2) | 선택적 분리 |
| MySQL 8.0.14+ | 항상 외부 별도 로그 파일 | `innodb_undo_tablespaces` Deprecated |

**MySQL 8.0에서 언두 테이블스페이스 동적 관리:**

```sql
-- 새로운 언두 테이블스페이스 추가
CREATE UNDO TABLESPACE extra_undo_003 ADD DATAFILE '/data/undo_dir/undo_003';

-- 언두 테이블스페이스 비활성화
ALTER UNDO TABLESPACE extra_undo_003 SET INACTIVE;

-- 비활성화된 테이블스페이스 삭제
DROP UNDO TABLESPACE extra_undo_003;
```

**Undo Tablespace Truncate (불필요한 공간 반납):**

| 모드 | 시스템 변수 | 설명 |
|------|----------|------|
| 자동 모드 | `innodb_undo_log_truncate = ON` | 퍼지 스레드가 주기적으로 사용하지 않는 공간을 잘라내고 반납 |
| 수동 모드 | `innodb_undo_log_truncate = OFF` | 직접 비활성화 → 퍼지 → 반납 → 재활성화 (최소 3개 이상 필요) |

---

## 4.2.10 체인지 버퍼 (Change Buffer)

RDBMS에서 레코드가 INSERT/UPDATE될 때 **데이터 파일 변경 + 해당 테이블의 인덱스 업데이트**가 모두 필요하다. 인덱스 업데이트는 **랜덤한 디스크 읽기**가 필요하므로 자원 소모가 크다.

**체인지 버퍼 동작 원리:**
- 인덱스 페이지가 **버퍼 풀에 있으면** → 바로 업데이트
- 인덱스 페이지가 **버퍼 풀에 없으면** → 임시 공간(체인지 버퍼)에 저장 → 나중에 백그라운드 머지 스레드가 병합

> ⚠️ **유니크 인덱스는 체인지 버퍼를 사용할 수 없다** (중복 여부를 반드시 체크해야 하므로).

**innodb_change_buffering 설정값:**

| 값 | 설명 |
|---|------|
| `all` | 모든 인덱스 관련 작업(inserts + deletes + purges) 버퍼링 |
| `none` | 버퍼링 안 함 |
| `inserts` | 인덱스에 새 아이템 추가하는 작업만 버퍼링 |
| `deletes` | 인덱스에서 삭제하는 작업(마킹 작업)만 버퍼링 |
| `changes` | inserts + deletes 버퍼링 |
| `purges` | 인덱스 아이템 영구 삭제만 버퍼링 (백그라운드 작업) |

```sql
-- 체인지 버퍼가 사용 중인 메모리 공간 확인
SELECT EVENT_NAME, CURRENT_NUMBER_OF_BYTES_USED
FROM performance_schema.memory_summary_global_by_event_name
WHERE EVENT_NAME='memory/innodb/ibuf0ibuf';
```

---

## 4.2.11 리두 로그 및 로그 버퍼

리두 로그(Redo Log)는 ACID 중 **D(Durable, 영속성)**와 가장 밀접하게 연관돼 있다.

**리두 로그가 필요한 이유:**
- 데이터 파일은 쓰기보다 읽기 성능을 고려한 자료 구조 → 랜덤 액세스 필요 → 쓰기 비용이 큼
- 리두 로그는 **쓰기 비용이 낮은 자료 구조** → 변경 내용을 먼저 기록 → 비정상 종료 시 복구에 사용

**비정상 종료 시 2가지 데이터 불일치:**

| 유형 | 복구 방법 |
|------|---------|
| 커밋됐지만 데이터 파일에 미기록 | 리두 로그의 내용을 데이터 파일에 복사 |
| 롤백됐지만 데이터 파일에 이미 기록 | 언두 로그의 내용을 데이터 파일에 복사 + 리두 로그로 트랜잭션 상태 확인 |

### innodb_flush_log_at_trx_commit 설정

| 값 | 동작 | 데이터 안전성 | 성능 |
|---|------|-----------|------|
| **0** | 1초마다 리두 로그를 디스크에 기록(write) + 동기화(sync) | 최대 1초 데이터 손실 가능 | 가장 빠름 |
| **1** (권장) | 매번 커밋마다 디스크에 기록 + 동기화 | 손실 없음 | 가장 느림 |
| **2** | 매번 커밋마다 디스크에 기록(write)만, 실질적 동기화는 1초마다 | OS 정상이면 안전, OS 비정상 종료 시 최근 1초 손실 | 중간 |

> **권장**: 일반적으로 `innodb_flush_log_at_trx_commit = 1`로 설정하여 서버가 비정상적으로 종료돼도 데이터가 손실되지 않게 하는 것이 좋다.

### 리두 로그 파일 크기 설정

```
전체 리두 로그 파일의 크기 = innodb_log_file_size × innodb_log_files_in_group
```

- 로그 버퍼 기본값 16MB가 적합하며, BLOB/TEXT와 같이 큰 데이터를 자주 변경하면 더 크게 설정
- 버퍼 풀 100GB 이하 서버에서는 리두 로그 전체 크기를 **5~10GB** 수준으로 시작 권장

### 4.2.11.1 리두 로그 아카이빙

MySQL 8.0부터 리두 로그를 **아카이빙**할 수 있는 기능이 추가되었다. 데이터 변경이 너무 많아 리두 로그가 빠르게 덮어써지면 백업 툴이 실패할 수 있는데, 아카이빙 기능으로 이를 방지한다.

```sql
-- 아카이빙 시작
DO innodb_redo_log_archive_start('backup','20200722');

-- 아카이빙 종료 (반드시 정상적으로 종료해야 함)
DO innodb_redo_log_archive_stop();
```

> ⚠️ 아카이빙을 시작한 세션이 끊어지면 InnoDB가 자동으로 아카이빙을 멈추고 파일을 삭제한다. 반드시 커넥션을 유지한 상태에서 `innodb_redo_log_archive_stop` UDF를 호출하여 정상 종료해야 한다.

### 4.2.11.2 리두 로그 활성화 및 비활성화

MySQL 8.0부터 수동으로 리두 로그를 비활성화할 수 있다. **대용량 데이터 적재 시** 리두 로그를 비활성화하면 적재 시간을 단축할 수 있다.

```sql
-- 대량 데이터 적재를 위한 리두 로그 비활성화 → 적재 → 재활성화
ALTER INSTANCE DISABLE INNODB REDO_LOG;
LOAD DATA ...
ALTER INSTANCE ENABLE INNODB REDO_LOG;

-- 상태 확인
SHOW GLOBAL STATUS LIKE 'Innodb_redo_log_enabled';
```

> ⚠️ **반드시 적재 완료 후 리두 로그를 다시 활성화**해야 한다. 비활성화 상태에서 비정상 종료되면 마지막 체크포인트 이후 데이터를 모두 복구할 수 없다.

---

## 4.2.12 어댑티브 해시 인덱스 (Adaptive Hash Index)

일반적으로 '인덱스'라 하면 사용자가 생성하는 **B-Tree 인덱스**를 의미한다. 어댑티브 해시 인덱스는 **InnoDB가 자주 요청하는 데이터에 대해 자동으로 생성하는 해시 인덱스**이다.

**B-Tree vs 어댑티브 해시 인덱스:**

| 항목 | B-Tree | 어댑티브 해시 인덱스 |
|------|--------|-----------------|
| 생성 주체 | 사용자 수동 생성 | InnoDB 자동 생성 |
| 검색 방식 | 루트 → 브랜치 → 리프 순회 | 해시 값으로 즉시 접근 |
| CPU 비용 | 상대적으로 높음 (다수 노드 순회) | 매우 낮음 (해시 한번) |
| 동시 쿼리 처리 | 보통 | 약 2배 향상 가능 |

**어댑티브 해시 인덱스가 효과적인 경우:**
- 디스크 데이터가 InnoDB 버퍼 풀 크기와 비슷한 경우 (디스크 읽기가 많지 않은 경우)
- 동등 조건 검색(동등 비교와 IN 연산자)이 많은 경우
- 쿼리가 데이터 중 일부 데이터에만 집중되는 경우

**어댑티브 해시 인덱스가 효과적이지 않은 경우:**
- 디스크 읽기가 많은 경우
- 특정 패턴의 쿼리가 많은 경우 (조인이나 LIKE 패턴 검색)
- 매우 큰 테이블의 레코드를 폭넓게 읽는 경우

> ⚠️ 어댑티브 해시 인덱스는 **'공짜 점심'이 아니다**. 메모리 공간을 사용하며, 테이블 삭제(DROP)/변경(ALTER) 시 해시 인덱스 제거에 상당한 CPU 자원을 소모한다. **Online DDL 포함 변경 작업에 치명적**일 수 있다.

```sql
-- 어댑티브 해시 인덱스 사용 현황 확인
SHOW ENGINE INNODB STATUS\G
-- "hash searches/s" vs "non-hash searches/s" 비율 확인
-- hash searches/s가 0이면 비활성화 상태

-- 메모리 사용량 확인
SELECT EVENT_NAME, CURRENT_NUMBER_OF_BYTES_USED
FROM performance_schema.memory_summary_global_by_event_name
WHERE EVENT_NAME='memory/innodb/adaptive hash index';
```

**MySQL 8.0 파티션 기능:** `innodb_adaptive_hash_index_parts` 시스템 변수로 파티션 개수를 변경하여 내부 잠금 경합을 줄일 수 있다 (기본값 8).

---

## 4.2.13 InnoDB와 MyISAM, MEMORY 스토리지 엔진 비교

| 항목 | InnoDB | MyISAM | MEMORY |
|------|--------|--------|--------|
| 기본 엔진 (MySQL 8.0) | ✅ | ❌ | ❌ |
| 트랜잭션 지원 | ✅ | ❌ | ❌ |
| 레코드 잠금 | ✅ | 테이블 잠금 | 테이블 잠금 |
| 클러스터링 키 | ✅ | ❌ | ❌ |
| MVCC | ✅ | ❌ | ❌ |
| 외래 키 | ✅ | ❌ | ❌ |
| 전문 검색 (MySQL 8.0) | ✅ | ✅ | ❌ |
| 가변 길이 칼럼 지원 | ✅ | ❌ | ❌ |
| 향후 전망 | 계속 발전 | 도태 예상 | TempTable로 대체 |

> **결론**: MySQL 8.0에서는 서버의 모든 기능이 InnoDB 기반으로 재편되었고, MyISAM만이 가지는 장점이 없는 상태이다. 이후 버전에서는 MyISAM 스토리지 엔진은 없어질 것으로 예상된다. MEMORY 엔진도 MySQL 8.0부터 TempTable 엔진으로 대체되고 있다.

---

## 🔑 Java 개발자를 위한 핵심 요약

1. **트랜잭션은 최대한 짧게 유지**: MVCC를 위한 언두 로그가 계속 쌓여 성능 저하를 유발한다. Spring `@Transactional` 범위를 최소화하자.

2. **PK 설계가 매우 중요**: InnoDB는 PK 기준으로 클러스터링되므로, JPA `@Id` 전략(AUTO_INCREMENT vs UUID 등)이 성능에 직접적 영향을 미친다.

3. **외래 키는 개발 환경에서 유용하지만 서비스에서는 주의**: 잠금 전파로 인한 데드락 가능성이 높아진다.

4. **대량 데이터 적재 시 성능 튜닝 포인트**: `foreign_key_checks=OFF`, 리두 로그 비활성화, 유니크 인덱스 주의 등을 활용할 수 있다.

5. **버퍼 풀 상태 백업/복구 자동화**: 서버 재시작 시 워밍업 시간을 크게 줄일 수 있다.