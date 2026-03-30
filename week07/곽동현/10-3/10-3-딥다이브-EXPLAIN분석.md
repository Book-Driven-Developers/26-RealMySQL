# EXPLAIN 분석 실습

> 실제 쿼리의 EXPLAIN 결과를 바탕으로 각 행의 의미와 성능 병목 포인트를 분석한다.

---

## 전체 구조 파악

총 **14개 행**, **2개의 단위 쿼리** (id=1, id=3)로 구성된다.

| id | 의미 |
|----|------|
| 1 | 메인 쿼리 (PRIMARY) — 다수의 테이블을 조인 |
| 3 | 상관 서브쿼리 (DEPENDENT SUBQUERY) — 외부 쿼리에 의존 |

---

## 행별 상세 분석

### Row 1 — `b` 테이블 ⚠️

| 항목 | 값 |
|------|-----|
| type | `const` |
| key | `userid` |
| ref | `const` |
| Extra | `Using temporary; Using filesort` |

- `const` 타입이므로 기본키 또는 유니크 인덱스로 단 1건을 조회하는 이상적인 접근이다.
- 그러나 **`Using temporary; Using filesort`** 가 문제다. 임시 테이블을 생성하고 정렬까지 수행하고 있어 성능 부담이 있다.
- ORDER BY나 GROUP BY 절이 인덱스로 처리되지 못하고 있다는 신호이다.

---

### Row 2~5 — `ms`, `mpi`, `prm`, `mpdi` 테이블 ✅

- 모두 `const` 타입으로 PK 또는 유니크 인덱스를 통한 단 1건 조회이다.
- `prm` 테이블은 `Using index` (커버링 인덱스)가 적용되어 테이블 접근 없이 인덱스만으로 처리된다.

---

### Row 6 — `mcui` 테이블

| 항목 | 값 |
|------|-----|
| type | `ref` |
| key | `PRIMARY` |
| Extra | `Using where` |

- PK를 사용하는데 `const`가 아닌 `ref` 타입이 나온 것은 **복합 PK(Composite PK)** 의 일부 컬럼만 조건으로 사용되고 있음을 의미한다.
- `Using where` — 인덱스 조건 외에 추가 필터링이 발생한다.

---

### Row 7 — `mci` 테이블 (`eq_ref`) ✅

| 항목 | 값 |
|------|-----|
| type | `eq_ref` |
| ref | `dreamsearch.mcui.CTGR_SEQ` |
| Extra | `Using where` |

- `eq_ref` 타입으로 이전 테이블(`mcui`)의 값을 참조해 PK 1건을 매칭하는 이상적인 조인 방식이다.

---

### Row 8 — 인접 테이블 (`ref` + 커버링 인덱스) ✅

| 항목 | 값 |
|------|-----|
| type | `ref` |
| key | `MOB_CTGR_INFO_IDX_01` |
| ref | `dreamsearch.mci.CTGR_SEQ_NEW` |
| Extra | `Using index` |

- `Using index` (커버링 인덱스)가 적용되어 테이블 데이터 접근 없이 인덱스만으로 처리된다.

---

### Row 9 — `mcma` 테이블 ✅

| 항목 | 값 |
|------|-----|
| type | `eq_ref` |
| ref | `const, dreamsearch.mci.CTGR_SEQ` |

- 상수 값과 이전 테이블 값을 복합 조건으로 사용해 PK 1건을 조회한다.

---

### Row 10 — `ast` 테이블 ⚠️⚠️

| 항목 | 값 |
|------|-----|
| type | `range` |
| key | `USED_ADSITE_MOBILE_IDX_01` |
| rows | **1499** |
| Extra | `Using index condition; Using where; Using join buffer (flat, BNL join)` |

이 쿼리에서 가장 주목해야 할 행이다.

- `range` 타입으로 인덱스 범위 스캔을 수행하며, 예측 행 수가 **1499건** 으로 이 쿼리에서 가장 많다.
- **`Using join buffer (flat, BNL join)`** — BNL(Block Nested Loop) 조인이 발생하고 있다. 조인 조건에 적절한 인덱스가 없어 버퍼를 통한 중첩 루프로 처리되는 것으로, 성능 병목의 주요 원인이 될 수 있다.
- `Using index condition` — ICP(Index Condition Pushdown) 최적화가 적용되어 그나마 스토리지 엔진 레벨에서 조건 필터링이 되고 있다.

---

### Row 11 — `kpi` 테이블 ✅

| 항목 | 값 |
|------|-----|
| type | `eq_ref` |
| ref | `dreamsearch.ast.KPI_NO` |

- `ast` 테이블의 값을 참조해 PK 1건을 조회한다.

---

### Row 12~13 — `tcs`, `tcbs` 테이블

| 항목 | 값 |
|------|-----|
| type | `eq_ref` |
| ref | `const, const, func, const` / `const, const, func, const, const` |
| Extra | `Using where` |

- `eq_ref` 타입이지만 ref 값에 **`func`** 가 포함되어 있다. 함수 결과값을 조인 조건으로 사용하고 있다는 의미로, 인덱스 효율이 완전하지 않을 수 있다.
- 가능하다면 함수 대신 컬럼 값을 직접 사용하도록 쿼리를 개선하는 것이 좋다.

---

### Row 14 — `z` 테이블 ⚠️

| 항목 | 값 |
|------|-----|
| id | **3** |
| select_type | **`DEPENDENT SUBQUERY`** |
| type | `unique_subquery` |
| key | `ADLINK_EXCLUDE_UK_01` |

- `DEPENDENT SUBQUERY` 는 **외부 쿼리가 실행될 때마다 서브쿼리가 반복 실행** 된다. 외부 쿼리 결과 행 수만큼 `z` 테이블 조회가 발생한다는 의미이다.
- 다행히 `unique_subquery` 타입으로 유니크 인덱스를 통한 1건 조회라 실제 비용은 낮지만, 구조적으로 `EXISTS` / `IN` 서브쿼리를 `JOIN`으로 변환하면 더 효율적일 수 있다.

---

## 핵심 정리

| 우선순위 | 문제 | 위치 | 개선 방향 |
|----------|------|------|-----------|
| 🔴 높음 | `Using temporary; Using filesort` | `b` 테이블 (Row 1) | ORDER BY/GROUP BY 컬럼 인덱스 추가 검토 |
| 🔴 높음 | `BNL join + 1499 rows` | `ast` 테이블 (Row 10) | 조인 조건에 인덱스 추가, 또는 드라이빙 테이블 순서 재검토 |
| 🟡 중간 | `DEPENDENT SUBQUERY` | `z` 테이블 (Row 14) | `EXISTS` → `LEFT JOIN` 변환 고려 |
| 🟡 중간 | `func` 포함 복합 ref | `tcs`, `tcbs` (Row 12~13) | 함수 대신 컬럼 값 직접 사용 가능한지 검토 |
| 🟢 양호 | 나머지 테이블들 | Row 2~9, 11 | `const` / `eq_ref` / `Using index` → 최적 |

---

`ast` 테이블의 **BNL(Block Nested Loop) 조인** 과 `b` 테이블의 **`filesort`** 가 이 쿼리의 주요 성능 병목이다.
실제 튜닝 시에는 이 두 지점을 우선적으로 개선하는 것이 효과적이다.
