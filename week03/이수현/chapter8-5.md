# 8.5 전문 검색 인덱스 (Full-Text Index)

전문 검색 인덱스는 일반적인 B-Tree 인덱스로 처리하기 어려운
문자열 전체 검색을 위한 특수한 인덱스이다.

예를 들어 다음과 같은 검색은 B-Tree 인덱스로 효율적으로 처리할 수 없다.

```sql
SELECT * FROM articles WHERE content LIKE '%database%';
```

LIKE '%문자열%' 형태의 검색은 인덱스를 사용할 수 없기 때문에
테이블 풀 스캔이 발생한다.

이를 해결하기 위해 등장한 것이 전문 검색 인덱스(Full-Text Index)이다.

--------------------------------------------------

## 8.5.1 인덱스 알고리즘

전문 검색 인덱스는 문서를 작은 단위로 분해한 뒤
그 단위를 기반으로 인덱스를 구성한다.

대표적인 방식은 다음 두 가지다.

1. 어근 분석 알고리즘 (Stemming 기반)
2. n-gram 알고리즘

--------------------------------------------------

### 8.5.1.1 어근 분석 알고리즘

어근 분석(Stemming)은 단어의 원형을 추출하는 방식이다.

예:
- running → run
- studies → study

MySQL 전문 검색 인덱스는 다음 과정을 거쳐 동작한다.

1) 불용어(Stop Word) 제거
2) 어근 분석(Stemming)
3) 인덱스 엔트리 생성

#### 불용어(Stop Word)

검색에서 의미 없는 단어는 제거된다.

예:
a, the, is, at, on, about 등

MySQL에서는 기본 불용어 목록을 제공한다.

```sql
SELECT * 
FROM information_schema.INNODB_FT_DEFAULT_STOPWORD;
```

#### 특징

- 영어에 적합
- 형태소 분석 기반
- 언어 의존적
- 한국어에는 부적합

--------------------------------------------------

### 8.5.1.2 n-gram 알고리즘

MySQL 5.7부터 도입된 방식.
형태소 분석 대신 문자열을 일정 길이로 잘라 인덱싱한다.

예:

문장:
To be or not to be

2-gram 방식:

To → To
be → be
or → or
not → no, ot
...

문장을 일정 길이(n) 단위로 분해하여
모든 조각을 인덱스에 저장한다.

#### 특징

- 언어 독립적
- 한국어에 적합
- 인덱스 크기가 커짐
- 부분 문자열 검색에 유리

n-gram 인덱스 생성 예:

```sql
CREATE TABLE tb_test (
  doc_id INT,
  doc_body TEXT,
  PRIMARY KEY (doc_id),
  FULLTEXT KEY fx_docbody (doc_body) WITH PARSER ngram
) ENGINE=InnoDB;
```

--------------------------------------------------

## 8.5.1.3 불용어 변경 및 삭제

전문 검색 인덱스는 불용어 목록을 변경할 수 있다.

### 방법 1: 서버 설정 파일 사용

my.cnf 설정:

```
ft_stopword_file = '/data/my_custom_stopword.txt'
```

MySQL 재시작 필요.

---

### 방법 2: InnoDB 테이블 기반 설정

```sql
CREATE TABLE my_stopword(value VARCHAR(30)) ENGINE=InnoDB;

INSERT INTO my_stopword(value) VALUES ('MySQL');

SET GLOBAL innodb_ft_server_stopword_table='mydb/my_stopword';
```

전문 검색 인덱스를 재생성해야 적용된다.

---

### 불용어 완전 비활성화

```sql
SET GLOBAL innodb_ft_enable_stopword=OFF;
```

--------------------------------------------------

## 8.5.2 전문 검색 인덱스의 가용성

전문 검색 인덱스를 사용하려면 다음 조건을 만족해야 한다.

1) FULLTEXT 인덱스가 생성되어 있어야 한다.
2) 검색은 MATCH() ... AGAINST() 구문으로 작성해야 한다.

예:

```sql
SELECT *
FROM tb_test
WHERE MATCH(doc_body) AGAINST('database');
```

LIKE 구문은 전문 검색 인덱스를 사용하지 않는다.

--------------------------------------------------

## 전문 검색 모드

### 1. Natural Language Mode (기본)

```sql
SELECT *
FROM tb_test
WHERE MATCH(doc_body) AGAINST('database');
```

자연어 검색 방식

---

### 2. Boolean Mode

```sql
SELECT *
FROM tb_test
WHERE MATCH(doc_body) AGAINST('+database -mysql' IN BOOLEAN MODE);
```

연산자 사용 가능:

+ : 반드시 포함
- : 제외
* : 와일드카드

---

### 3. Query Expansion

검색 결과를 기반으로 확장 검색 수행

--------------------------------------------------

## 전문 검색 인덱스의 특징

1. 대용량 텍스트 검색에 적합
2. LIKE '%문자열%' 문제 해결
3. 일반 B-Tree 인덱스와 완전히 다른 구조
4. INSERT/UPDATE 비용 증가
5. 인덱스 크기 큼 (특히 n-gram)

--------------------------------------------------

## 성능 특성

- 검색 대상이 많을수록 효과적
- 선택도가 낮을 경우 성능 저하
- 불용어가 많으면 인덱스 효율 감소
- n-gram은 인덱스 크기 급증 가능

--------------------------------------------------

## 핵심 정리

1. 전문 검색 인덱스는 텍스트 검색 전용 인덱스이다.
2. B-Tree는 LIKE '%문자열%' 검색을 처리하지 못한다.
3. 어근 분석은 영어 중심이다.
4. n-gram은 한국어에 적합하다.
5. 반드시 MATCH() AGAINST() 구문을 사용해야 한다.
6. 불용어 설정이 성능에 영향을 준다.