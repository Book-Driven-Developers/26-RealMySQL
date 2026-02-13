## 4.1 MySQL 엔진 아키텍처

MySQL 서버는 **MySQL 엔진(서버 엔진)** 과 **스토리지 엔진**으로 구성된다.  
SQL 처리는 MySQL 엔진이 담당하고, 실제 데이터 저장은 스토리지 엔진이 담당한다.

---

## 4.1.1 MySQL의 전체 구조

<details>
<summary>자세히 보기</summary>

### MySQL 엔진 (서버 엔진)

- 커넥션 핸들러
    - 클라이언트 접속 및 인증 관리
- SQL 파서 / 전처리기
    - 문법 검사 및 객체 존재 확인
- 옵티마이저
    - 실행 계획 생성
    - 인덱스 선택 및 조인 순서 결정
- 실행 엔진
    - 스토리지 엔진 호출
- 캐시 및 버퍼
    - InnoDB 버퍼 풀 등

```sql
SHOW GLOBAL STATUS LIKE 'Handler%';
```

---

### 스토리지 엔진

- 실제 디스크 저장 담당
- 테이블 단위 엔진 선택 가능

```sql
CREATE TABLE test_table (
  id INT
) ENGINE=InnoDB;
```

대표 엔진:
- InnoDB (기본)
- MyISAM
- MEMORY
- CSV

</details>

---

## 4.1.2 MySQL 스레딩 구조

<details>
<summary>자세히 보기</summary>

MySQL은 **스레드 기반 구조**로 동작한다.

### 포그라운드 스레드
- 클라이언트 요청 처리
- 접속 수만큼 생성
- SQL 실행 담당

```sql
SHOW VARIABLES LIKE 'thread_cache_size';
```

### 백그라운드 스레드
- 로그 기록
- 버퍼 플러시
- 디스크 쓰기/읽기
- InnoDB 내부 작업 처리

</details>

---

## 4.1.3 메모리 할당 및 사용 구조

<details>
<summary>자세히 보기</summary>

### 글로벌 메모리 영역
- InnoDB 버퍼 풀
- 키 캐시
- 로그 버퍼
- 테이블 캐시

### 세션(로컬) 메모리 영역
- 정렬 버퍼
- 조인 버퍼
- read buffer
- sort buffer

세션 종료 시 해제된다.

</details>

---

## 4.1.4 플러그인 스토리지 엔진 모델

<details>
<summary>자세히 보기</summary>

MySQL은 **플러그인 아키텍처**를 사용한다.

- 스토리지 엔진 교체 가능
- 인증 모듈 확장 가능
- 검색 엔진 추가 가능

구조 흐름:

```
클라이언트 → MySQL 엔진 → Handler API → 스토리지 엔진
```

</details>

---

## 4.1.5 컴포넌트

<details>
<summary>자세히 보기</summary>

MySQL 8.0부터는 **컴포넌트 아키텍처** 도입.

| 플러그인 | 컴포넌트 |
|----------|----------|
| 내부 결합도 높음 | 서비스 기반 구조 |
| 의존성 관리 제한 | 독립적 인터페이스 |

확장성과 안정성이 개선되었다.

</details>

---

## 4.1.6 쿼리 실행 구조

<details>
<summary>자세히 보기</summary>

SQL 실행 단계:

1. 클라이언트 요청
2. SQL 파싱
3. 전처리
4. 옵티마이저 실행 계획 수립
5. 실행 엔진 → 스토리지 엔진 호출
6. 결과 반환

</details>

---

## 4.1.7 복제 (Replication)

<details>
<summary>자세히 보기</summary>

Binary Log 기반 복제 구조.

구성:
- Source
- Replica

동작 흐름:

```
Source → Binary Log 기록
Replica → Relay Log 저장
Replica → SQL Thread 실행
```

목적:
- 읽기 분산
- 장애 복구
- 백업

</details>

---

## 4.1.8 쿼리 캐시

<details>
<summary>자세히 보기</summary>

MySQL 8.0에서 제거됨.

문제점:
- 테이블 변경 시 전체 무효화
- 동시성 저하
- 확장성 문제

</details>

---

## 4.1.9 스레드 풀

<details>
<summary>자세히 보기</summary>

Thread Pool 플러그인을 통해  
동시 접속 스레드 수를 제한 가능.

장점:
- 컨텍스트 스위칭 감소
- CPU 효율 향상
- 대규모 접속 안정성 확보

</details>

---

## 4.1.10 트랜잭션 지원 메타데이터

<details>
<summary>자세히 보기</summary>

MySQL 8.0부터 메타데이터를  
InnoDB 내부 테이블에 저장.

장점:
- 트랜잭션 보장
- 원자성 확보
- 충돌 감소

이전 버전은 `.frm` 파일 기반 관리.

</details>