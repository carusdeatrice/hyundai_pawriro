# pawriro 백엔드 (Java 21)

## 1. 데이터베이스 준비

PostgreSQL과 같은 메이저 버전용 PostGIS를 설치하거나 PostGIS 지원 관리형 PostgreSQL을 사용합니다. 관리 도구에서 로그인 역할 `pawriro_app`과 데이터베이스 `pawriro`를 만들고, 해당 데이터베이스에 PostGIS 확장을 활성화합니다. 앱 계정으로 실행할 때는 필요한 테이블 권한을 부여합니다.

## 2. 빌드와 스키마 적용

프로젝트 루트에서 실행합니다. JDK 21이 필요합니다. 이 컴퓨터의 프로젝트에 준비된 JDK/SDK를 사용할 경우 다음 환경값을 설정합니다.

```sh
export JAVA_HOME="$PWD/build/toolchain/jdk"
export ANDROID_HOME="$PWD/build/toolchain/sdk"
export GRADLE_USER_HOME="$PWD/build/gradle-home"
./gradlew :backend:installDist
```

관리 계정의 환경변수로 마이그레이션을 한 번 실행합니다. `DATABASE_URL`에는 JDBC URL을 입력합니다.

```sh
export DATABASE_URL='jdbc:postgresql://localhost:5432/pawriro'
export DB_USER='postgres'
# DB_PASSWORD는 관리자 비밀번호로 설정
backend/build/install/backend/bin/backend migrate
```

이 명령은 Java에 포함된 스키마를 한 트랜잭션에서 적용합니다. 이후 앱 계정에 `public` 스키마 사용 권한과 업무 테이블의 SELECT/INSERT/UPDATE 권한을 부여합니다. 테이블은 reports, search_areas, sightings, participations, visit_events, points입니다.

## 3. API 실행

```sh
export DB_USER='pawriro_app'
# DB_PASSWORD는 앱 계정 비밀번호로 설정
export PAWRIRO_API_KEY="$(openssl rand -hex 32)"
export PAWRIRO_REVIEW_KEY="$(openssl rand -hex 32)"
backend/build/install/backend/bin/backend
```

`curl -f http://127.0.0.1:8000/health`에서 PostGIS 버전을 확인합니다. 개발 API에는 `Authorization: Bearer <PAWRIRO_API_KEY>` 헤더를 사용하고, 포인트 승인에는 검토 키를 사용합니다.

### 이 Mac에서 실행

프로젝트 루트에서 `./backend/start.sh`를 실행합니다. 최초 실행 시 ORS 키를 입력하면
Git에서 제외된 `backend/.env`에 저장합니다. 다음 실행부터 어느 터미널에서든 동일한
명령으로 설정을 불러옵니다. 기존 서버를 교체할 때는 그 서버를 먼저 종료합니다.

## API

| 메서드와 경로 | JSON 입력 |
| --- | --- |
| POST /reports | id, kind, name, coat, traits, missing_at, last_seen, 선택 walk_route·photo_url |
| GET /reports | 없음 |
| PATCH /reports/{id}/status/{status} | 없음. active, found, closed |
| PUT /reports/{id}/areas | id, name, polygon 또는 center·radius_m |
| GET /reports/{id}/areas | 없음 |
| POST /search/route | remaining_route, 선택 distance_m |
| POST /reports/{id}/sightings | id, location, seen_at, 선택 photo_url |
| GET /reports/{id}/sightings | 없음 |
| POST /participations | id, area_id, device_id, mode (solo/passenger) |
| POST /participations/{id}/visits | id, event, location |
| GET /participations/{id} | 없음 |
| PUT /participations/{id}/result | result (no_sighting/sighting), 목격 시 sighting_id |
| PUT /participations/{id}/points/{kind} | 없음. base/photo, 검토 키 필요 |
| GET /devices/{id}/points | 없음 |

ID는 UUID, 시각은 시간대 오프셋이 포함된 ISO 8601, 좌표는 [경도, 위도] 순서입니다. 다각형은 첫 좌표와 마지막 좌표가 같아야 합니다. 경로 검색에는 현재 위치 이후의 선을 전달합니다.

현재 API는 로컬 개발용입니다. 차량 앱 자동 동기화, 사용자별 인증, 사진 파일 저장과 신뢰 가능한 차량 증거 검증은 별도 연동이 필요합니다.

## 보호자 테스트 계정과 차량 동기화

기존 DB에 새 기능을 적용하려면 관리자 계정으로 `migrate`를 다시 실행합니다. 앱 DB 역할을 따로 사용하는 경우 새 `guardian_accounts`, `guardian_sessions` 테이블의 조회 권한과 세션 생성·삭제 권한, 보관기간 정리용 `sightings`, `visit_events` 삭제 권한도 부여합니다. 테스트 계정은 DB 관리자 환경에서 아래 명령으로 발급합니다. 같은 계정명을 지정하면 비밀번호를 교체하고 기존 세션을 만료시킵니다.

```sh
read -rs 'PAWRIRO_GUARDIAN_PASSWORD?테스트 계정 비밀번호(12자 이상): '
export PAWRIRO_GUARDIAN_PASSWORD
backend/build/install/backend/bin/backend guardian guardian1
unset PAWRIRO_GUARDIAN_PASSWORD
```

서버를 실행하고 에뮬레이터에서 주차 상태로 **실종 신고** 또는 **내 신고**를 엽니다. 기본 서버 주소는 `http://10.0.2.2:8000`입니다. 다른 호스트는 HTTPS 주소를 사용합니다. 동일 서버를 보는 차량 앱은 약 10초마다 활성 신고의 구역과 최근 제보 시각을 갱신합니다.

보호자 API는 세션과 신고 소유권을 확인합니다. 차량 공개 피드는 신고 ID, 구역명·일반화한 영역, 최근 제보 시각만 제공합니다. 보호자 이름·연락처·상세 목격 위치·사진·산책 경로·자주 가는 장소는 공개 피드에 포함되지 않습니다. 발견 완료·신고 종료 시 공개 대상에서 제외되며, 서버가 동작하는 동안 정기 작업이 30일 경과 데이터를 삭제하고 정산 키와 적립 내역을 보존합니다.

지도 입력과 차량 추천은 성수동 시연 지도에 한정됩니다. 서버 피드의 일반화된 영역은 시연 매칭에 사용하고 실제 Pleos 경유 추천에는 사용하지 않습니다.

### 안전 경유 후보와 검토 대기

관리자 환경에서 최신 백엔드를 빌드한 뒤 `migrate`를 다시 실행합니다. 별도 DB 계정을 사용하는 경우 `verified_candidates`에 SELECT/INSERT/UPDATE/DELETE 권한을 부여합니다.

검토 키로 `PUT /review/candidates`를 호출해 후보를 등록합니다. 좌표는 [경도, 위도] 순서이며, `expires_at`은 미래 ISO 8601 시각입니다.

```json
{
  "poi_id": "실제 Pleos POI ID",
  "area": "성수동1가",
  "mode": "solo",
  "location": [127.04, 37.54],
  "legal_access": true,
  "normal_traffic": true,
  "legal_parking": true,
  "walkable": true,
  "requires_slowdown": false,
  "repeated_road": false,
  "expires_at": "2027-01-01T00:00:00+09:00"
}
```

동승자 후보는 mode를 passenger로 바꾸고 진입점과 60m 넘게 떨어진 통과 지점 `exit`를 추가합니다. 등록은 운영자의 확인을 기록합니다. 자동 지도 검증이나 안전한 경로의 증명은 별도입니다. ADB로 수동 ETA를 입력하는 시연에서는 등록된 POI·구역·모드·좌표가 일치해야 합니다. 만료된 후보는 피드에서 제외되고, 검토 키로 `POST /review/candidates/revoke`에 poi_id, area, mode를 보내 철회할 수 있습니다.

`GET /review/queue`는 의심 신고와 제보를 반환합니다. 검토 키로 `PATCH /review/reports/{id}/{state}` 또는 `PATCH /review/sightings/{id}/{state}`를 호출합니다. state는 pending, rejected, clear입니다. 보호자 신고의 미래 시각·유사 중복과 제보의 미래 시각·근접 중복을 표시합니다. 검토 중인 신고는 차량 피드와 추천에서 빠지고 제보는 추천 및 사진 보너스에서 제외됩니다.

## 실제 수색 경유 자동 계산

최신 백엔드를 빌드하고 관리자 계정으로 `migrate`를 실행합니다. 백엔드 프로세스에
`ORS_API_KEY`로 openrouteservice Directions 키를 설정합니다. 앱은 기존 API 주소를 사용합니다. 자체 ORS 서버를 운영할 경우 `ORS_DIRECTIONS_URL`에 Directions 기본 URL을 지정할 수 있습니다.

지원 지역의 합법적 주차장 입구와 일반 도로 진입·통과 지점을 조사한 데이터로
`PUT /review/candidates`에 넣습니다. 위 등록 JSON 형식의 객체 또는 최대 500개 객체 배열을
검토 키로 전송합니다. 배열 전체는 한 트랜잭션으로 저장됩니다. 이후 앱 재배포 없이 등록,
갱신 또는 철회할 수 있습니다.

- `display_name`은 운전자에게 읽어 줄 후보 이름입니다.
- 단독 모드의 `location`은 차량으로 진입 가능한 주차장 입구입니다. `opening_hours`는
  서울 시간으로 월요일 `1`부터 일요일 `7`까지이며, 각 값은 자정부터의 분 단위
  구간 배열입니다. 예를 들어 `{"1":[[540,1080]],"2":[[540,1080]]}`은 월·화 09:00–18:00입니다.
  종일 운영은 해당 요일에 `[[0,1440]]`을 입력합니다. 운영시간이 없으면 자동 추천에서 빠집니다.
- 동승자 모드의 `exit`은 정상 주행 방향으로 진입점을 지난 뒤 통과할 지점입니다.
  경유 경로가 두 지점을 순서대로 지나고 그 사이가 수색 구역과 겹쳐야 합니다.

차량은 현재 좌표와 NaviHelper의 남은 목적지·경유지 정보를 `POST /vehicle/detour`에
보냅니다. 서버는 이 정보로 기준 경로를 재계산하고 기존 경유지의 순서를 보존하는 삽입
위치를 검사합니다. 단독 후보는 구역 내부 지점까지 보행 800m 및 15분 이내인지,
예상 도착부터 최소 하차 시간까지 주차장이 운영되는지 추가 확인합니다. 추가 10분 제한은
주행 ETA 차이에 적용합니다. ETA와 경로선은 ORS 추정이며 Pleos 내부 경로선 및 실시간
교통과 차이가 날 수 있습니다. 지도에는 보호자 공개 범위가 적용된 수색 구역이 표시됩니다.

동승자 실경로 추천은 등록 후보 테이블 없이 동작합니다. ORS 자동차 경로와 활성 신고 구역을
PostGIS에서 교차시켜 주행 순서대로 진입점과 통과점을 정하고 두 점을 넣은 경로를 다시
요청합니다. 안쪽 경유지 사이 연속 200m 이상·예상 12초 이상, 최대 1km, 추가 ETA 10분
이하를 확인합니다. 서버는 신고 경계를 그대로 공개하는 대신 구역 안에 완전히 포함되는
20m 격자 영역을 방문 판정용으로 반환합니다. 기존 경유지가 있는 경우 두 점을 넣을 슬롯이 없어 자동 제안을
생략합니다. GPS 방문 검증은 이 영역 안에서 연속 200m 및 12초를 확인합니다.

## 차량 목적지 검색

기존 `ORS_API_KEY`로 호스팅된 ORS Geocoding을 호출합니다. 일반 차량 모드의 검색창에서
장소명이나 주소를 두 글자 이상 입력하면 `POST /vehicle/destinations`로 검색합니다.
시연 모드는 목업 검색을 유지합니다. 자체 Pelias 또는 검증용 서버를 운영할 경우
`ORS_GEOCODE_URL`로 검색 엔드포인트를 지정할 수 있습니다.
