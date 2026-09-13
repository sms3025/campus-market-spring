# 로컬 성능 실험실 사용법

이정태(sms3025)의 상품·찜 조회·키워드·FCM 기여를 검증하는 환경이다. 로그인 및 채팅 고도화는 포함하지 않는다. 실제 SQL/서비스 흐름을 호출하며 FCM과 S3는 기본적으로 로컬 대체 동작을 사용한다. 개선 전 baseline과 개선 후 수치를 같은 조건으로 비교하며, 두 결과 모두 [결과 문서](result/로컬_기능_테스트_페이지.md)에 남긴다.

## 1. 처음 실행

Java 17의 JAVA_HOME, 실행 중인 Docker Desktop의 Linux 컨테이너 환경이 필요하다. 스크립트는 저장소 루트에서 PowerShell로 실행한다. Node.js는 선택 사항이며 HTTP 검증 및 비교 HTML 생성에 사용한다.

```powershell
# 본인 설치 경로에 맞게 설정
$env:JAVA_HOME='C:/Users/SSAFY/.jdks/ms-17.0.20.1'
$env:GRADLE_USER_HOME="$PWD/.gradle-local"
./scripts/local-test/run.ps1 -Action Build
./scripts/local-test/run.ps1 -Action Seed -Scale smoke
./scripts/local-test/run.ps1 -Action Start
```

| 화면/연결 | 주소 또는 설정 |
| --- | --- |
| 기능 테스트 페이지 | http://localhost:8080/local-test/ |
| Grafana | http://localhost:3000/d/campus-local |
| Grafana 기본 계정 | admin / local-grafana-only |
| Prometheus | http://localhost:9090/targets |
| MariaDB | localhost:13306 / campus_local_test / campus / local-only-password |
| Redis | localhost:16379 |

포트와 로컬 계정을 바꾸려면 `docker/local-test/.env.example`을 `.env`로 복사해 수정한다. 스크립트는 이 파일을 자동 적용한다. Compose를 직접 호출할 때는 `--env-file docker/local-test/.env`를 지정해야 동일 설정이 된다. 컨테이너 내부 연결은 서비스 이름과 기본 포트를 사용한다.

기능 페이지에서 사용자 1(판매자), 사용자 2(찜/수신), 관리자 3을 선택한다. seed가 없으면 계정 선택은 409를 반환한다. 토큰은 메모리에만 보관되며 새로고침 후 다시 선택한다. 생성된 상품 ID는 자동으로 대상 ID에 채워진다.

전체 이미지 빌드가 아닌 인프라만 켜는 명령:

```powershell
docker compose -f compose.local-test.yml up -d --wait mariadb redis prometheus grafana
```

## 2. 데이터 규모와 재생성

| Scale | 사용자 | 상품 | 찜 | 기본 키워드 |
| --- | ---: | ---: | ---: | ---: |
| smoke | 100 | 1,000 | 5,000 | 200 |
| small | 1,000 | 10,000 | 50,000 | 2,000 |
| medium | 10,000 | 100,000 | 500,000 | 20,000 |
| large | 100,000 | 1,000,000 | 5,000,000 | 200,000 |

기본 키워드 외 FCM-BENCH 키워드가 Fanout 수만큼 추가된다. 캠퍼스 10개, 약관 1개, 조회 집계용 채팅방 fixture 100개를 함께 만든다. 채팅 API를 실행하는 데이터는 아니다.

```powershell
# 이전 테스트 데이터 삭제: 이 Compose의 campus_local_test DB/Redis만 초기화
./scripts/local-test/run.ps1 -Action Reset -ConfirmReset
./scripts/local-test/run.ps1 -Action Seed -Scale medium -Distribution uniform
./scripts/local-test/run.ps1 -Action Start
```

`-Distribution skewed`는 인기 상품에 찜이 집중되는 데이터다. 사용자 2는 항상 앞의 500개 상품을 찜한다. 5% 상품은 삭제 상태이므로 사용자 2의 실제 반환 목록은 그보다 작다. uniform/skewed, 페이지 크기, 데이터 규모를 결과에 함께 남긴다.

seed는 빈 전용 DB만 허용한다. 완료 또는 중단된 seed가 있으면 중복 적재를 거부한다. 실패 시 원인을 확인하고 Reset 후 다시 실행한다. 서버 시작 시 자동 적재하지 않는다. 원본 ID/시각을 결정적으로 생성하고 JDBC 청크 커밋을 사용하며 FK 검사를 유지한다.

`artifacts/performance/seed-manifest.json`에 현재 적재 조건과 실제 건수를 기록하며 seed별 복사본도 남긴다. DB 용량은 information_schema의 통계 추정치다. manifest 시간은 데이터 적재·검사 구간이며 Spring 시작 시간이나 이미지 다운로드 시간은 포함하지 않는다.

## 3. 기능 및 SQL 검증

페이지에서 상품 등록·검색·상세·수정·삭제, 찜 등록·목록·해제, 키워드 등록·삭제, 알림 이력, 담당 관리자 상품/학교 조회를 실행한다. 요청 JSON은 실제 DTO 형식을 사용한다. 무효 입력과 타 사용자 수정 요청은 기존 백엔드의 오류 응답을 그대로 보여준다.

```powershell
node scripts/local-test/verify-api.mjs
```

이 스크립트는 모의 FCM 모드만 허용하며 소수의 상품·키워드를 생성해 검증한 뒤 키워드 삭제/상품 소프트 삭제를 수행한다. 이후 순수 성능 비교에는 Reset/Seed로 초기 상태를 복구한다.

키워드 알림은 상품 등록 트랜잭션이 커밋된 뒤 별도 스레드에서 전송한다. 따라서 등록 응답이 200을 반환한 시점에 전송이 끝나 있지 않다. 검증 스크립트는 전송 내역에 해당 알림이 나타날 때까지 최대 10초 기다린 뒤 판정한다. 화면에서 확인할 때도 `서버 전송 내역 조회`를 잠시 뒤에 다시 눌러야 한다.

`X-Local-SQL-Count`는 인증·서비스·DTO 구성까지 현재 요청 스레드가 실행한 JDBC 문장 수다. SQL 시간을 별도 헤더 `X-Local-SQL-Ms`로 반환한다. Redis 명령과 별도 스레드는 포함하지 않는다. seed나 동시에 실행한 다른 요청의 카운트가 합쳐지지 않도록 ThreadLocal을 요청 전후 초기화한다.

## 4. k6 부하와 보고서

```powershell
# 기본: 1분 워밍업 + 3분 측정, 초당 10개 요청
./scripts/local-test/run.ps1 -Action Measure -Scenario likes -PageSize 10 -Rate 10
./scripts/local-test/run.ps1 -Action Measure -Scenario likes -PageSize 50 -Rate 10
./scripts/local-test/run.ps1 -Action Measure -Scenario likes -PageSize 100 -Rate 10

# 상품 검색 / 관리자 조회
./scripts/local-test/run.ps1 -Action Measure -Scenario items -PageSize 10 -Rate 10
./scripts/local-test/run.ps1 -Action Measure -Scenario admin -PageSize 10 -Rate 10

# 결과 목록과 막대 그래프
node scripts/local-test/report.mjs
```

전후 비교용 묶음 실행은 `baseline-suite.ps1`을 쓴다. 한 번에 하나씩 순차 실행하므로 측정끼리 자원을 다투지 않는다.

```powershell
./scripts/local-test/baseline-suite.ps1 -Part Likes -Repeats 3   # 페이지 크기 10/50/100 × 3회
./scripts/local-test/baseline-suite.ps1 -Part Search             # 상품 검색 10/30/100 RPS, 관리자 1/10 RPS
$env:FCM_DELAY_MS='50'; ./scripts/local-test/run.ps1 -Action Start
./scripts/local-test/baseline-suite.ps1 -Part Fcm -Repeats 3     # 상품 등록(알림 발생) 1 RPS
./scripts/local-test/baseline-suite.ps1 -Part Register -Label medium-20k-keywords  # 단건 등록 비용
```

`Register`는 부하 도구 없이 상품 등록을 20회 호출해 단건 응답 시간과 SQL 실행 수를 기록한다(`artifacts/performance/register-<Label>.json`). 키워드 수가 다른 두 데이터셋에서 같은 라벨 규칙으로 실행하면 키워드 조회 비용을 비교할 수 있다.

k6 threshold는 서버 오류율 5% 초과일 때만 실행을 중단한다. 목표 요청률 미달(`dropped_iterations`)은 중단 사유가 아니라 기록 대상이다. 워밍업이 threshold를 넘겨도 본 측정은 그대로 진행한다.

`artifacts/performance/index.html`을 열면 실제 실행별 지연·SQL 횟수·오류율을 비교한다. 각 행에서 k6 HTML 보고서로 이동한다. k6 자체 대시보드는 짧은 실행에서 표본 부족으로 HTML 내보내기를 생략할 수 있으므로 본 측정은 기본 3분을 사용한다. summary.json은 짧은 실행에서도 생성된다.

시나리오는 items, likes, admin, fcm을 제공한다. 부하는 10→30→100 RPS 순서로 올리고 같은 조건을 3회 반복한다. 페이지 크기는 1~100, 깊은 페이지는 `-Page`로 조절한다. 검색어는 `SEARCH` 환경변수로 지정한다. 고정 도착률 1 iteration당 API 요청 1개이며 VU 상한은 200이다. `dropped_iterations` 발생은 목표 부하를 시작하지 못한 상태로 기록한다.

`api_duration`, `api_requests`, `api_failed`는 계정 준비 요청을 제외한 본 시나리오 지표다. k6 기본 `http_req_*`에는 준비 요청이 포함될 수 있다. p95/p99를 서로 다른 반복에서 평균 내어 전체 p95/p99로 쓰지 않는다.

실행 폴더에는 seed manifest, 조건/커밋/dirty 상태, app 이미지와 자원 제한, metrics 전후, 실제 데이터 건수, k6 summary/HTML/로그가 저장된다. 비밀값을 포함한 전체 Compose 설정은 보고서에 저장하지 않는다. 실패한 실행도 원본 로그를 보존한다.

Grafana는 API 처리 지연·요청률, JVM 메모리/GC, 프로세스 CPU, DB 연결 풀, 알림 완료량/지연, 알림 executor의 큐·활성 스레드·거절 수를 보여준다. Histogram 기반 서버 지연과 k6 클라이언트 지연은 관측 위치가 다르다.

DB 조회 결과와 SQL 실행 수 회귀 검증은 부하 실행과 별도로 수행한다. 테스트/빌드/seed를 부하 실행과 동시에 돌리지 않는다. 단일 PC 결과를 운영 최대 성능으로 일반화하지 않는다.

## 5. 키워드 알림 전송 부하

```powershell
./scripts/local-test/run.ps1 -Action Reset -ConfirmReset
./scripts/local-test/run.ps1 -Action Seed -Scale medium -Fanout 10
$env:FCM_DELAY_MS='50'
./scripts/local-test/run.ps1 -Action Start
./scripts/local-test/run.ps1 -Action Measure -Scenario fcm -Rate 1
```

동일 학교의 FCM-BENCH 매칭 사용자 10명에게 50ms씩 모의 전송한다. 이는 제어 가능한 실험 조건이며 Firebase의 실제 지연을 의미하지 않는다. 기본 smoke는 사용자 수 때문에 Fanout 최대 97이며 100명 검증은 small 이상에서 수행한다.

FCM 부하는 상품을 계속 등록한다. 워밍업 후 실제 상품 수와 종료 후 상품 수를 기록하며, 비교 반복마다 Reset/Seed로 초기 상태를 맞춘다. API 응답과 전송 완료/실패를 함께 비교한다. 실제 모드에서는 k6 fcm 시나리오가 실행을 거부한다.

키워드 알림은 등록 트랜잭션 커밋 이후 `fcmExecutor`에서 전송한다. 그래서 이 시나리오의 API 응답 시간에는 전송 대기가 들어 있지 않다. 전송이 실제로 끝났는지는 `/local-test/api/fcm` 내역과 `fcm_dispatch_total`·`executor_queued_tasks{name="fcmExecutor"}` 지표로 따로 확인한다. 부하 종료 후 큐 잔량이 0인지 확인하지 않으면 응답 시간 개선을 전송 지연으로 옮긴 것과 구분할 수 없다.

모의 모드는 메시지 1건마다 지정한 지연을 그대로 적용한다. 실제 Firebase 전송은 배치 1회 호출이므로 모의 모드가 개선 폭을 과장하지 않는 보수적인 조건이다. 서버 성공은 SDK 전송 완료이며 사용자 브라우저 수신 성공과 구분한다.

## 6. Firebase 웹 실제 수신

1. Firebase 콘솔에서 테스트 프로젝트의 웹 앱을 등록한다.
2. 프로젝트 설정의 웹 앱 firebaseConfig를 복사한다.
3. Cloud Messaging의 Web Push 인증서에서 VAPID 키를 생성하고 공개 키를 복사한다. 필요한 FCM Registration API가 활성화되어 있어야 한다.
4. 같은 프로젝트의 Admin SDK 서비스 계정 JSON을 `docker/local-test/secrets/firebase.json`에 배치한다. 이 폴더는 Git/Docker 빌드 컨텍스트에서 제외된다.
5. 다음과 같이 실제 모드로 app을 다시 만든다.

```powershell
$env:FCM_REAL='true'
./scripts/local-test/run.ps1 -Action Start
```

6. localhost 테스트 페이지에서 사용자 2를 적용하고, 웹 설정 JSON·VAPID 공개 키를 입력한 뒤 알림 권한/토큰 등록 버튼을 누른다.
7. 사용자 2에 `웹푸시` 키워드를 등록한다. 사용자 1로 전환해 제목에 `웹푸시`를 포함한 상품을 등록한다.
8. 페이지가 보이는 상태에서는 foreground 수신 로그, 다른 탭을 보고 있을 때는 OS/브라우저 알림을 확인한다. 백그라운드 메시지는 열려 있는 로컬 페이지에도 전달한다.
9. 알림 클릭은 같은 origin의 `/local-test/`로만 이동하며 itemId가 있으면 상세 조회 대상으로 채운다. 알림 이중 표시를 막기 위해 notification payload를 서비스 워커에서 재표시하지 않는다.

SDK는 10.14.1로 고정했으며 실제 연동 시 gstatic/Firebase 네트워크가 필요하다. 웹 앱 설정은 서비스 워커 URL에도 전달되는 공개 설정이다. 서비스 계정 private_key는 브라우저에 넣지 않는다. Firebase 프로젝트를 바꿀 때는 페이지를 새로고침하고 필요하면 해당 로컬 서비스 워커와 사이트 알림 권한을 정리한 뒤 다시 등록한다.

서비스 워커 범위는 `/local-test/`다. 브라우저 알림 차단/OS 방해 금지/지원 여부에 따라 실제 표시가 달라질 수 있으므로 서버 완료 로그와 실제 수신을 별도로 기록한다. 모의 seed 토큰은 실제 전송에서 건너뛴다.

공식 안내: [웹 클라이언트 설정](https://firebase.google.com/docs/cloud-messaging/web/get-started), [메시지 수신](https://firebase.google.com/docs/cloud-messaging/web/receive-messages).

## 7. 자동 테스트와 커버리지

```powershell
# 전용 DB seed 완료 후 실행. 부하 테스트와는 별도 실행한다.
./gradlew.bat localVerification
```

- 테스트 결과: `build/reports/tests/localVerification/index.html`
- 담당 범위 커버리지: `build/reports/jacoco/localCoverage/html/index.html`
- XML: `build/reports/jacoco/localCoverage/localCoverage.xml`

리포지토리 테스트는 기존 H2 테스트 구성을 사용하고, LocalApiIntegrationTest는 전용 MariaDB/Redis를 사용한다. 담당 상품/키워드/찜 컨트롤러·서비스·리포지토리와 FCM baseline·로컬 격리를 검증한다. 로그인·채팅 테스트를 실행 범위로 확장하지 않는다. 커버리지 대상 클래스는 build.gradle의 localCoverage에 명시되어 있으며 전체 프로젝트 비율과 구분한다.

seed main/실제 Firebase처럼 자동 테스트로 실행하지 않은 경로는 미커버로 남긴다. 숫자를 높이기 위해 숨기지 않는다. 기존 FcmServiceTest는 실행 가능한 @Test가 없어 실제 테스트 수에 포함되지 않는다.

## 8. IntelliJ 실행과 종료

IntelliJ에서는 local-test 프로필로 실행한다. Docker 쪽 app은 먼저 중지하고 DB/Redis는 유지한다. Prometheus/k6가 호스트 app에 접근해야 하므로 IntelliJ 환경변수 APP_BIND=0.0.0.0을 사용하며 로컬 방화벽 정책을 확인한다.

```powershell
$env:PROMETHEUS_CONFIG='./docker/local-test/prometheus/host.yml'
$env:BASE_URL='http://host.docker.internal:8080'
docker compose -f compose.local-test.yml up -d prometheus
```

IntelliJ 모드 수치는 컨테이너 CPU/메모리 제한이 적용된 기본 측정 모드와 섞지 않는다. 기본으로 돌아갈 때 해당 환경변수를 제거하고 Start를 실행한다.

```powershell
./scripts/local-test/run.ps1 -Action Stop
```

일반 Stop은 DB/Grafana/Prometheus 볼륨을 보존한다. Redis는 테스트용 비영속 설정이므로 재생성 후 상태가 사라진다. Reset은 전용 테스트 데이터만 삭제하며 다른 Docker 프로젝트의 볼륨을 정리하지 않는다.
