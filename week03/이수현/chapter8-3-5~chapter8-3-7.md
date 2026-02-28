# 8.3.5 ~ 8.3.7 B-Tree 인덱스 심화

--------------------------------------------------

# 8.3.5 다중 컬럼 (Multi-column) 인덱스

지금까지 살펴본 인덱스는 단일 컬럼 인덱스였다.
하지만 실제 서비스에서는 여러 컬럼을 조합해 검색하는 경우가 훨씬 많다.

이때 사용하는 것이 다중 컬럼 인덱스(복합 인덱스)이다.

예:
```sql
CREATE INDEX ix_dept_emp ON dept_emp(dept_no, emp_no);
```

## 핵심 개념

다중 컬럼 인덱스는 **컬럼 순서가 매우 중요하다.**

B-Tree는 다음과 같이 정렬된다.

1차 정렬: dept_no  
2차 정렬: emp_no

즉,
- dept_no 기준으로 먼저 정렬
- 같은 dept_no 안에서 emp_no 정렬

이를 **Left-most prefix(왼쪽 기준 규칙)** 라고 한다.

--------------------------------------------------

## 다중 컬럼 인덱스 예시

다음 두 인덱스는 완전히 다르다.

```sql
-- 케이스 A
INDEX(dept_no, emp_no)

-- 케이스 B
INDEX(emp_no, dept_no)
```

WHERE 조건이 다음과 같다면:

```sql
SELECT *
FROM dept_emp
WHERE dept_no='d002' AND emp_no > 10114;
```

→ 케이스 A가 효율적

왜냐하면 첫 번째 컬럼(dept_no)을 먼저 활용할 수 있기 때문이다.

--------------------------------------------------

# 8.3.6 B-Tree 인덱스의 정렬 및 스캔 방향

B-Tree 인덱스는 기본적으로 오름차순으로 저장된다.
하지만 MySQL 8.0부터는 내림차순 인덱스도 지원한다.

```sql
CREATE INDEX ix_firstname_asc ON employees(first_name ASC);
CREATE INDEX ix_firstname_desc ON employees(first_name DESC);
```

--------------------------------------------------

## 8.3.6.1 인덱스 정렬

MySQL은 인덱스를 생성할 때 ASC / DESC를 지정할 수 있다.

예:
```sql
CREATE INDEX ix_teamname_userscore
ON employees(team_name ASC, user_score DESC);
```

### 정렬 방향과 ORDER BY 관계

```sql
SELECT *
FROM employees
ORDER BY first_name DESC
LIMIT 1;
```

이 경우,
- DESC 인덱스가 있다면 정방향 스캔
- ASC 인덱스라면 역방향 스캔

--------------------------------------------------

## 8.3.6.2 인덱스 스캔 방향

인덱스는 두 가지 방식으로 읽을 수 있다.

1) Forward scan (앞에서 뒤로)
2) Backward scan (뒤에서 앞으로)

MySQL 옵티마이저는 필요에 따라
ASC 인덱스를 역방향으로 읽어 DESC 정렬을 처리할 수 있다.

하지만 대량 스캔 시에는 정렬 방향에 맞는 인덱스가 더 효율적일 수 있다.

--------------------------------------------------

## LIMIT + OFFSET 주의

```sql
SELECT *
FROM t1
ORDER BY id DESC
LIMIT 1261975, 1;
```

LIMIT OFFSET 방식은
OFFSET 만큼 모든 레코드를 먼저 스캔해야 한다.

→ 인덱스가 있어도 느릴 수 있다.

--------------------------------------------------

# 8.3.7 B-Tree 인덱스의 가용성과 효율성

인덱스는 항상 사용 가능한 것이 아니다.
조건의 형태에 따라 사용 여부가 달라진다.

--------------------------------------------------

## 8.3.7.1 비교 조건의 종류

다음 조건은 인덱스를 효율적으로 사용할 수 없다.

### ❌ NOT 조건

```sql
WHERE column != 'X'
WHERE column NOT IN (10,11)
WHERE column IS NOT NULL
```

### ❌ LIKE '%문자열'

```sql
WHERE column LIKE '%abc'
```

앞에 와일드카드가 있으면 인덱스를 사용할 수 없다.

### ❌ 함수 사용

```sql
WHERE SUBSTRING(column,1,1)='X'
WHERE DAYOFMONTH(column)=1
```

컬럼에 함수가 적용되면 인덱스를 사용할 수 없다.

--------------------------------------------------

## 8.3.7.2 인덱스의 가용성 (Left-most Rule)

다중 컬럼 인덱스에서 매우 중요한 규칙:

👉 왼쪽부터 연속된 컬럼까지만 인덱스를 사용할 수 있다.

예:

```sql
INDEX(column_1, column_2, column_3)
```

### ✔ 사용 가능

```sql
WHERE column_1 = 1
WHERE column_1 = 1 AND column_2 = 2
WHERE column_1 = 1 AND column_2 = 2 AND column_3 = 3
```

### ❌ 사용 불가

```sql
WHERE column_2 = 2
WHERE column_2 = 2 AND column_3 = 3
```

첫 번째 컬럼이 조건에 없으면 인덱스를 사용할 수 없다.

--------------------------------------------------

## 8.3.7.3 작업 범위 결정 조건과 체크 조건

인덱스 조건은 두 가지로 나뉜다.

1) 작업 범위 결정 조건
   → 인덱스를 사용해 검색 범위를 줄이는 조건

2) 체크 조건
   → 인덱스로 찾은 후 추가 필터링

예:

```sql
INDEX(column_1, column_2, column_3)

WHERE column_1 = 1
AND column_2 IN (2,4)
AND column_3 >= 30
AND column_4 LIKE '김%'
```

여기서:

- column_1, column_2 → 작업 범위 결정
- column_3 이후 → 체크 조건

즉, 인덱스는 column_3까지 범위 탐색에 사용되고
column_4는 인덱스 사용 불가.

--------------------------------------------------

# 핵심 정리

1. 다중 컬럼 인덱스는 컬럼 순서가 절대적으로 중요하다.
2. 왼쪽부터 연속된 조건까지만 인덱스를 사용할 수 있다.
3. ASC/DESC 인덱스는 MySQL 8.0부터 정식 지원.
4. 인덱스는 앞/뒤 양방향 스캔이 가능하다.
5. NOT, 함수, 앞 와일드카드 LIKE는 인덱스를 사용할 수 없다.
6. 인덱스 조건은 "작업 범위 결정 조건"과 "체크 조건"으로 나뉜다.