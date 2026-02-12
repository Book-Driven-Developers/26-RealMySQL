## Real MySQL 1주차 학습 내용 정리

### 4장. 아키텍처
MySQL 서버는 크게 다음과 같이 구분할 수 있다.

- 사람의 머리 역할을 담당하는 `MySQL 엔진`
- 손발 역할을 담당하는 `스토리지 엔진`

`스토리지 엔진`은 핸들러 API를 만족하면 누구든지 스토리지 엔진을 구현해서 MySQL 서버에 추가해서 사용할 수 있다.

4장에서는 MySQL 엔진과 MySQL 서버에서 기본으로 제공되는 InnoDB 스토리지 엔진, 그리고 MyISAM 스토리지 엔진을 구분해서 살펴보겠다.


[1. MySQL 엔진 아키텍처](https://succulent-bottle-ad0.notion.site/1-MySQL-301483e1832f808b8fe2e89fd32d34b6)

[2. InnoDB 스토리지 엔진 아키텍처](https://succulent-bottle-ad0.notion.site/2-InnoDB-301483e1832f80079472c84fa047e49f)

[3. MyISAM 스토리지 엔진 아키텍처](https://succulent-bottle-ad0.notion.site/3-MyISAM-301483e1832f8027b969f4e6a18d2aea)

[4. MySQL 로그 파일](https://succulent-bottle-ad0.notion.site/4-MySQL-301483e1832f8046ad14dcb7e2c534af)


---
## 추가로 찾아본 내용
[트랜잭션 격리 레벨](https://succulent-bottle-ad0.notion.site/305483e1832f800c8d31fd010799892f)

[MVCC](https://succulent-bottle-ad0.notion.site/MVCC-305483e1832f80a9b3aaf769eccb7b3a)