# InnoDB Buffer Pool 내부 구조 분석

InnoDB Buffer Pool은 MySQL 성능의 핵심이다.
디스크 I/O를 최소화하기 위해 데이터 페이지와 인덱스 페이지를 메모리에 캐싱하는 영역이며,
InnoDB의 모든 읽기,쓰기 경로는 버퍼 풀을 중심으로 동작한다.

MySQL에서 성능 문제의 대부분은 결국 다음 질문으로 수렴한다:

- 버퍼 풀에 페이지가 존재하는가?
- Dirty Page가 얼마나 쌓였는가?
- Flush가 병목을 만들고 있는가?

Buffer Pool의 내부 메모리 구조와 동작 방식을 아키텍처 관점에서 분석한다.

---

## 메모리 구조

<details>
<summary>Buffer Pool 메모리 내부 구조</summary>

Buffer Pool은 단순한 캐시 배열이 아니다.
내부적으로는 다음과 같은 구조로 구성된다.

---

### 1. Page Frame 배열

버퍼 풀은 "페이지 프레임(Page Frame)"의 집합이다.

- 각 프레임은 16KB 크기
- 하나의 프레임에 하나의 디스크 페이지 매핑
- 버퍼 풀 크기 = page_frame_count × 16KB

예:

```
innodb_buffer_pool_size = 1GB
→ 약 65,536개 페이지 프레임 존재
```

---

### 2. Control Block (Buffer Descriptor)

각 페이지 프레임에는 메타데이터가 붙는다.

이 메타데이터는 다음 정보를 포함한다:

- page_id (테이블스페이스 + 페이지 번호)
- dirty 여부
- LRU 리스트 위치
- pin 여부 (사용 중인지)
- hash table 연결 정보

즉, 실제 데이터(16KB) + 관리 정보가 함께 존재한다.

---

### 3. Buffer Pool 내부 주요 구성 영역

Buffer Pool 내부는 크게 다음으로 나뉜다:

1. Free List
   - 아직 사용되지 않은 페이지 프레임 목록

2. LRU List
   - 사용 중인 페이지를 관리하는 리스트

3. Flush List
   - Dirty Page만 모아둔 리스트

이 세 리스트는 서로 독립적으로 존재하며
동일한 페이지가 여러 리스트에 연결될 수 있다.

---

### 4. Page Hash Table

버퍼 풀은 해시 테이블을 통해 페이지를 빠르게 찾는다.

동작 과정:

1. 특정 page_id 요청
2. 해시 테이블 조회
3. 존재하면 즉시 반환
4. 없으면 디스크 I/O 후 적재

이 구조 덕분에 O(1)에 가까운 조회 성능을 가진다.

---

### 5. 메모리 할당 방식

Buffer Pool은 MySQL 서버 시작 시 한 번에 큰 메모리를 할당한다.

특징:

- 동적 확장은 제한적
- 운영 중 크기 변경은 부담이 큼
- 서버 시작 시 선할당(pre-allocation)

따라서 초기 설계가 중요하다.

---

### 6. 내부 동작 흐름 예시 (읽기)

1. 클라이언트가 특정 레코드 요청
2. InnoDB는 해당 레코드가 속한 페이지 계산
3. Page Hash Table 조회
   - 있으면 → 메모리에서 반환
   - 없으면 → 디스크에서 읽어와 Free Frame에 적재
4. LRU 리스트 갱신

---

### 7. 내부 동작 흐름 예시 (쓰기)

1. UPDATE 실행
2. 해당 페이지가 버퍼 풀에 존재
3. 메모리 상에서 데이터 변경
4. Dirty Flag 설정
5. Flush List에 추가
6. 나중에 Page Cleaner가 디스크 반영

즉, 쓰기 작업도 디스크에 즉시 반영되지 않는다.
이 구조가 WAL + Checkpoint와 연결된다.

---

### 8. 중요한 점

Buffer Pool은 단순 캐시가 아니라

- 트랜잭션
- Redo Log
- Checkpoint
- Flush 정책

과 긴밀하게 연결된 핵심 아키텍처 구성요소이다.

</details>

## LRU Old/New 리스트

<details>
<summary>LRU 내부 구조 및 동작 방식</summary>

InnoDB의 LRU는 일반적인 LRU(Least Recently Used)와 다르다.  
단순히 “가장 오래 사용되지 않은 페이지 제거” 방식이 아니다.

InnoDB는 버퍼 풀을 다음 두 영역으로 나눈다:

- Young Sublist
- Old Sublist

이를 Midpoint Insertion 전략이라고 한다.

---

### 1. 왜 단순 LRU를 쓰지 않는가?

문제 상황:

- 대용량 테이블 Full Scan 실행
- 수많은 페이지가 한 번씩만 읽힘
- 기존 Hot Page들이 밀려나게 됨

이 경우 단순 LRU는 성능을 크게 악화시킨다.

이를 방지하기 위해 Old 영역을 둔다.

---

### 2. LRU 구조 개념

LRU List는 하나의 Doubly Linked List다.

구조는 다음과 같다:

```
[MRU] → Young 영역 → 경계점 → Old 영역 → [LRU]
```

- Young: 자주 사용되는 페이지
- Old: 최근에 들어왔지만 아직 검증되지 않은 페이지

기본적으로 Old 영역은 전체 LRU의 약 37%를 차지한다.
(innodb_old_blocks_pct 설정 가능)

---

### 3. 페이지가 처음 적재될 때

새로운 페이지가 디스크에서 읽혀 오면

→ Young이 아니라 Old 영역의 경계 지점(midpoint)에 삽입된다.

이게 핵심이다.

즉, "읽혔다고 바로 Hot Page로 취급하지 않는다."

---

### 4. 페이지 승격 조건

Old 영역에 있던 페이지가 다시 접근되면

→ Young 영역으로 승격된다.

이때:

- 해당 페이지는 LRU의 MRU 쪽으로 이동
- 실제 Hot Page로 간주

이 과정을 통해 일회성 스캔과 실제 빈번 접근을 구분한다.

---

### 5. Full Scan이 발생하면?

Full Scan 시 발생하는 현상:

1. 수천 개 페이지가 버퍼 풀에 적재
2. 모두 Old 영역에 위치
3. 재접근이 없으면
   → LRU tail에서 제거됨
4. 기존 Young 영역 Hot Page는 보호됨

이 구조 덕분에 OLTP 환경에서 안정적인 성능을 유지한다.

---

### 6. innodb_old_blocks_time

Old 영역에 들어온 페이지는 일정 시간 동안 Young 승격이 제한된다.

설정 변수:

```
innodb_old_blocks_time
```

이 시간 내 재접근은 승격되지 않는다.

목적:

- Scan 작업이 Hot Page로 오인되는 것 방지

---

### 7. 페이지 제거(Eviction) 과정

버퍼 풀이 가득 찬 경우:

1. LRU tail 쪽에서 제거 후보 탐색
2. Dirty Page라면 즉시 제거 불가
3. Flush 후 제거
4. Clean Page면 즉시 Free List로 이동

즉, LRU는 Dirty 상태와 강하게 연결된다.

---

### 8. 내부 자료구조 관점

각 페이지는 다음 포인터를 가진다:

- LRU prev/next
- Flush list prev/next
- Hash chain pointer

즉, 하나의 페이지가 여러 리스트에 동시에 연결될 수 있다.

---

### 9. 왜 이 구조가 중요한가?

LRU Old/New 구조는

- OLTP 환경 안정성
- 대용량 스캔 보호
- 캐시 오염 방지
- 예측 가능한 메모리 동작

을 위해 설계되었다.

InnoDB는 단순 캐시가 아니라  
워크로드를 구분하는 전략적 캐시 정책을 사용한다.

---

### 10. 실무에서 발생하는 문제

- Full Table Scan이 반복되면 Old 영역이 계속 교체됨
- innodb_old_blocks_pct 튜닝 필요
- 버퍼 풀이 너무 작으면 Young 보호 효과 감소
- 핫 페이지가 Flush 압박을 받을 수 있음

이 부분은 성능 튜닝과 직결된다.

</details>

## Dirty Page 관리 및 Flush 정책

<details>
<summary>Dirty Page, Flush List, Flush 동작 메커니즘</summary>

Buffer Pool에서 가장 중요한 개념 중 하나는 Dirty Page이다.

Dirty Page란:

- 메모리 상에서 수정되었지만
- 아직 디스크에 반영되지 않은 페이지

InnoDB는 모든 쓰기 작업을 먼저 메모리에서 수행한다.
이때 페이지는 Dirty 상태로 표시된다.

---

### 1. Dirty Page 생성 과정

1. UPDATE / INSERT / DELETE 실행
2. 해당 데이터 페이지가 버퍼 풀에 존재
3. 메모리에서 데이터 수정
4. Redo Log 기록
5. 페이지에 Dirty Flag 설정
6. Flush List에 추가

중요한 점은:
데이터 변경은 디스크보다 Redo Log에 먼저 기록된다.
(Write Ahead Logging 원칙)

---

### 2. Flush List란?

LRU List와 별도로
Dirty Page만 모아둔 연결 리스트가 존재한다.

특징:

- Dirty Page만 포함
- Redo Log LSN 순서대로 정렬
- Checkpoint 계산에 사용

즉, Flush List는 "복구 기준"을 위한 리스트다.

---

### 3. Dirty Page는 언제 디스크로 반영되는가?

Flush는 다음 상황에서 발생한다.

#### (1) LRU 기반 Flush

- 버퍼 풀이 부족할 때
- LRU tail에서 페이지 제거 필요
- Dirty Page라면 먼저 디스크 반영 후 제거

#### (2) Checkpoint 기반 Flush

- Redo Log 공간이 부족해질 때
- 오래된 Dirty Page를 디스크에 반영
- Checkpoint LSN을 전진시킴

#### (3) Background Flush

- Page Cleaner Thread가 주기적으로 수행
- Dirty 비율이 높아질 때 적극적으로 flush

---

### 4. Checkpoint와의 관계

Redo Log는 계속 쌓인다.
하지만 디스크 데이터가 따라오지 않으면 복구 시간이 길어진다.

Checkpoint의 목적:

- 특정 LSN까지의 변경 내용을
- 디스크에 안전하게 반영했다는 지점 기록

Checkpoint LSN 이전의 Redo Log는 더 이상 필요하지 않다.

따라서:

Dirty Page가 많으면
→ Checkpoint가 느려짐
→ Redo Log 공간 압박 발생
→ 성능 저하 발생

이 구조는 매우 중요하다.

---

### 5. Page Cleaner Thread

InnoDB는 별도의 백그라운드 스레드를 사용한다.

역할:

- Dirty Page 주기적 Flush
- LRU 리스트 정리
- Checkpoint 진행 보조

이 스레드는 급격한 Flush 폭증을 방지한다.
즉, 성능을 부드럽게 유지하기 위한 완충 장치다.

---

### 6. Flush 정책 세부 동작

Flush는 단순히 한 페이지씩 하지 않는다.

- Batch Flush 수행
- 인접 페이지 묶어서 쓰기 (I/O 효율 증가)
- Adaptive Flush 적용

Adaptive Flush는 다음을 고려한다:

- Redo Log 생성 속도
- Dirty Page 비율
- Checkpoint 거리

즉, 시스템 상태에 따라 Flush 강도를 조절한다.

---

### 7. Dirty Page 비율

설정 변수:

```
innodb_max_dirty_pages_pct
```

의미:

- 전체 버퍼 풀 대비 Dirty Page 허용 비율

비율이 너무 높으면:

- Crash Recovery 시간 증가
- Checkpoint 지연
- 급격한 Flush 발생

너무 낮으면:

- 쓰기 성능 감소

균형이 중요하다.

---

### 8. 내부 동작 흐름 예시 (쓰기 폭증 상황)

1. 대량 UPDATE 실행
2. Dirty Page 급증
3. Redo Log LSN 빠르게 증가
4. Checkpoint 거리 확대
5. Page Cleaner 적극 Flush
6. 디스크 I/O 급증
7. 성능 저하 가능성 발생

이게 흔히 말하는 "Flush Storm"이다.

---

### 9. 왜 이 구조가 중요한가?

InnoDB 성능 문제의 대부분은

- Dirty Page 과다
- Checkpoint 지연
- Redo Log 압박
- 디스크 I/O 병목

에서 발생한다.

Buffer Pool을 이해하지 못하면
MySQL 성능 튜닝은 불가능하다.

---

### 10. 운영 관점 핵심 포인트

- 버퍼 풀은 충분히 크게
- Redo Log도 충분히 크게
- Dirty 비율 모니터링 필수
- Checkpoint LSN 추적
- I/O 대역폭 고려

이 구조는 단순 캐시 정책이 아니라
트랜잭션 안정성과 직결된 아키텍처 설계다.

</details>

## Buffer Pool Instance 분할 및 NUMA 이슈

<details>
<summary>동시성 병목, Instance 분할 구조, NUMA 환경 고려</summary>

InnoDB Buffer Pool은 단일 거대한 메모리 덩어리가 아니다.  
고동시성 환경에서 락 경합을 줄이기 위해 여러 Instance로 분할된다.

---

### 1. 왜 Instance 분할이 필요한가?

초기 InnoDB는 단일 Buffer Pool을 사용했다.

문제:

- 수백~수천 개 스레드가 동시에 접근
- LRU 리스트 접근
- Hash Table 접근
- Flush List 접근

→ 내부 latch(뮤텍스) 경합 발생
→ CPU 코어 수 증가 시 오히려 성능 저하

이를 해결하기 위해 Buffer Pool을 여러 Instance로 분할했다.

---

### 2. Buffer Pool Instance 구조

설정 변수:

```
innodb_buffer_pool_instances
```

예:

- Buffer Pool 8GB
- Instance 8개
  → 각 Instance 1GB씩 분리

각 Instance는 독립적으로 다음을 가진다:

- LRU List
- Flush List
- Free List
- Page Hash Table
- 내부 latch

즉, 하나의 Instance는 "작은 독립 Buffer Pool"처럼 동작한다.

---

### 3. 페이지는 어떤 Instance에 속하는가?

페이지는 해시 기반으로 특정 Instance에 배정된다.

계산 기준:

- page_id
- tablespace_id

한 번 배정되면 해당 Instance에서만 관리된다.

이 구조 덕분에 서로 다른 페이지 접근은
다른 Instance에서 병렬로 처리된다.

---

### 4. Instance 분할의 효과

장점:

- LRU latch 경합 감소
- Hash table 경합 감소
- Flush 동시성 증가
- CPU 코어 활용도 향상

특히 16코어 이상 환경에서 효과가 크다.

---

### 5. Instance 수는 어떻게 결정하는가?

권장 기준:

- 1GB당 1 Instance (과거 가이드)
- 너무 많으면 오버헤드 증가
- 너무 적으면 락 경합 발생

MySQL 8.0에서는 자동 조정이 많이 개선되었다.

---

### 6. NUMA 이슈

NUMA(Non-Uniform Memory Access) 환경에서는
CPU 소켓마다 메모리 접근 속도가 다르다.

문제:

- Buffer Pool이 특정 NUMA 노드에 집중 배치될 수 있음
- 다른 CPU가 원격 메모리 접근
- 메모리 지연 증가

이 경우 성능 저하가 발생할 수 있다.

---

### 7. NUMA에서의 해결 전략

- numactl을 사용한 메모리 인터리빙
- MySQL 실행 시 interleave 옵션 사용
- 충분한 Buffer Pool Instance 분할
- OS 레벨 Huge Page 설정

NUMA 환경에서는 메모리 지역성(Locality)이 중요하다.

---

### 8. Instance 분할의 한계

Instance를 분할해도 해결되지 않는 문제:

- 동일 페이지에 대한 경합
- Hot Page 집중 접근
- 특정 테이블 집중 트래픽

이 경우는 Instance 분할이 아닌
쿼리 설계 및 인덱스 설계 문제일 가능성이 높다.

---

### 9. 성능 튜닝과 직결

Buffer Pool 튜닝은 다음과 직접 연결된다:

- innodb_buffer_pool_size
- innodb_buffer_pool_instances
- innodb_max_dirty_pages_pct
- innodb_io_capacity
- Redo Log 크기

특히 다음 현상이 보이면 점검 필요:

- CPU는 여유 있는데 TPS 낮음
- mutex wait 증가
- LRU flush 폭증
- Checkpoint 지연

이 경우 Instance 수와 Dirty 비율을 점검해야 한다.

</details>
