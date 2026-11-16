# pawriro

## 앱 이름 및 한 줄 소개

**pawriro** — 차량 내비 경로 주변의 실종 반려동물 수색 구역을 안내하고, 참여를 수락하면 수색 경유지를 반영하는 차량용 앱입니다.

## 실행 방법

1. Android Studio에서 Pleos Connect 에뮬레이터를 켭니다.
2. 저장소 루트에서 백엔드를 실행합니다. 이미 실행 중이라면 그대로 사용합니다.

   ```sh
   ./backend/start.sh
   ```

3. 별도 터미널에서 제출용 APK를 설치하고 ORS 시연 모드로 실행합니다.

   ```sh
   ./demo.sh install-release-ors
   ```

4. 앱에서 **왕십리역**을 검색해 ORS 결과를 선택하고 **안내 시작**을 누릅니다. 가상 주행 명령과 결과 제출 순서는 아래의 에뮬레이터 시연 및 수색 결과 항목을 참고하세요.

일반 모드로 실행하려면 APK 설치 후 에뮬레이터 앱 목록에서 **pawriro**를 엽니다.

## 테스트 환경 및 필요한 설정

- Android Studio, Android SDK Platform Tools(ADB), Pleos Connect 에뮬레이터가 필요합니다. SDK가 기본 위치에 없다면 `ANDROID_SDK_ROOT`를 설정합니다.
- 목적지 검색 및 ORS 경로 계산에는 백엔드의 `ORS_API_KEY`가 필요합니다. 서버에는 JDK 21과 PostgreSQL/PostGIS가 필요합니다. DB 초기화와 계정 발급은 [백엔드 설정](backend/README.md)을 참고하세요.
- 에뮬레이터의 기본 백엔드 주소는 `http://10.0.2.2:8000`입니다. Mac에서 `curl -f http://127.0.0.1:8000/health`로 서버 상태를 확인할 수 있습니다.
- ORS 검색·경로와 OpenStreetMap 지도 타일을 불러오려면 인터넷 연결이 필요합니다. 수색 제안을 확인하려면 주행 경로 근처에 활성 실종 신고가 필요합니다.
- 소스에서 APK를 빌드하려면 JDK 21, Android SDK Platform 35, Build Tools 36.0.0, 자동차 API 스텁이 포함된 Android Platform 37.0을 준비합니다.

## Pleos Connect 에뮬레이터 버전 및 화면 규격

- **Pleos Connect v2.1.0** · Android Automotive API 34 · ARM64
- **2560 × 1440 px** · 가로 16:9 · AVD 설정 160 dpi

## APK 파일 위치

- [apk/조수인_pawriro_release.apk](apk/조수인_pawriro_release.apk)
- 소스에서 제출용 APK를 다시 생성하려면 `./build.sh release`를 실행합니다. 릴리스 빌드를 생성하고 로컬 서명 키로 서명한 뒤 설치용 APK를 저장합니다.

## 주요 파일

- `native/PawriroActivity.kt` 앱 상태, 주행 제한, 음성 안내, 개발용 이벤트
- `native/PawriroScreen.kt` 홈, 검색, 미리보기와 주행 안내
- `native/PawriroReports.kt` 실종 신고 4단계와 내 신고
- `native/PawriroMap.kt` 신고 입력과 내비 화면의 지도 오버레이

## 에뮬레이터 시연

목적지를 선택해 안내를 시작한 뒤 `./demo.sh drive`를 실행합니다. `./demo.sh reply 참여할게`로 수색 경유지를 표시합니다. `./demo.sh timeout`, `./demo.sh fail`, `./demo.sh park`, `./demo.sh stop`도 지원합니다.

ORS 시연 모드는 실제 지도와 ORS 자동차 경로를 사용합니다. 차량 이동과 수락은 터미널 명령으로 재현합니다.

## 신고·경로 매칭

신고와 작성 중 초안은 Compose의 앱 전용 `pawriro-reports` 저장소에 보관됩니다. 사진 선택은 Android 문서 선택기에서 지원되는 이미지로 시도합니다. 내 신고에서 동네를 수정하거나 발견 완료·신고 종료를 처리할 수 있습니다.

경로 매칭 시연은 활성 신고와 가상 경로의 남은 구간을 비교합니다. 추가 시간은 10분 이하이며 `./demo.sh tick`으로 가상 위치를 전진시킬 수 있습니다. 이전 Java 앱의 저장 파일은 별도로 유지됩니다.

Pleos AVD에서는 NaviHelper 목적지·경유지 조회와 FusedLocation 위치 구독을 시작합니다. 연결 및 권한 결과는 `adb logcat -s PawriroPleos`로 확인할 수 있습니다. 시연 지도 좌표와 실제 위경도는 별개이며, 실제 경로 매칭·경유지 적용에는 라우팅 데이터 및 적용 권한 검증이 필요합니다.

### 조수석 확인과 경유지 재확인

경로 미리보기 진입, 수동 동승자 모드 선택, 주행 중 수색 후보 계산 직전, 수락 직전에 Vehicle SDK로 앞 오른쪽 좌석의 착석과 벨트 체결을 조회합니다. 두 신호가 모두 확인된 경우에만 동승자 후보를 계산합니다. 수락 직전에 경유지 또는 추가 시간이 바뀌면 한 번 더 동의를 받습니다. 운전자 화면의 AAOS 주행 제한은 좌석 상태와 별도로 적용합니다.

시연 모드에서 `./demo.sh seat occupied-buckled`, `./demo.sh seat unbuckled`, `./demo.sh seat empty`로 신호를 주입할 수 있습니다. `./demo.sh seat vehicle`은 실제 SDK 조회로 돌아갑니다. 동승자 후보를 제안한 다음 `unbuckled`로 바꾸고 참여 명령을 보내면 단독 경유 후보를 다시 안내합니다. `turn` 동안에는 새 제안을 보류하고 `clear-turn` 후 다시 확인합니다. 실제 경유지 변경과 후보 ETA는 시연 데이터입니다.

### NaviHelper 실제 경유 요청 확인

시연 APK에서 경로 정보가 부족하면 화면의 진단에 원인이 표시됩니다. `./demo.sh navi-refresh`로 차량 내비를 다시 조회합니다. `adb logcat -s PawriroPleos PawriroDemo`에서 SDK 응답을 확인할 수 있습니다.

Pleos AVD의 차량 내비에서 pawriro와 동일한 목적지로 안내를 시작합니다. 외부 경로 계산으로 검증한 후보와 ETA가 있다면 개발용 명령으로 등록합니다.

```sh
./demo.sh navi-candidate '동네|solo 또는 passenger|위도|경도|경유지 이름|Pleos POI ID|기존 ETA 초|경유 ETA 초'
```

후보는 등록 시 읽은 활성 경로에 묶이며 2분 후 만료됩니다. 테스트 주행에서 수색 구역·탑승 모드가 일치하면 실제 후보를 제안합니다. 수락 후 활성 경로를 다시 읽고 실제 경유 요청을 보냅니다. 완료 콜백과 목적지·경유지 재조회가 일치해야 성공으로 표시합니다. 정보가 부족하면 AVD에서 시연 경로를 사용합니다. 실제 후보의 안전성, 지리적 매칭과 경유 전 ETA를 자동 공급하는 라우팅 연동은 별도 단계입니다.

### 수색 결과와 가상 포인트

수색 경유를 수락한 뒤 `./demo.sh tick`으로 경유지에 도착합니다. 동승자 모드는 다음 tick으로 수색 도로를 통과합니다. 단독 모드는 경유지에 도착한 뒤 아래 이벤트로 운전석 문 열림, 하차, 3분 경과, 재탑승을 시연합니다.

```sh
./demo.sh park
./demo.sh driver-door
./demo.sh driver-exit
./demo.sh walk-minutes 3
./demo.sh driver-return
./demo.sh stop
```

홈의 수색 참여 내역에서 결과를 제출합니다. 목격 제보는 지도 핀과 시각을 확인하고 사진을 첨부할 수 있습니다. 조건 충족 시 목격 여부와 관계없이 50P, 검증된 사진에는 +25P를 적용합니다. 사진 검증은 시연 모드의 `./demo.sh photo-approve`로 재현하며 보호자·관리자 검증 서버 연동은 후속 단계입니다. 참여당 한 번, 신고·구역당 한국 시간 기준 하루 한 번, 사진 보너스를 합해 하루 최대 150P입니다.

실제 차량에서 결과 제출 시에는 기어 P를 확인합니다. 실제 경유 참여에 대한 도착·운전석 문·착석 증거는 아직 수집하지 않으며, 결과 제출 시 증거 미확보로 지급을 보류합니다. 가상 주행 포인트는 시연용이며 현금 가치가 없습니다.

### 백엔드

PostgreSQL/PostGIS 생성, 스키마 적용과 API 실행은 [backend/README.md](backend/README.md)를 참조합니다.

### 차량 SDK 연동 확인

홈의 **차량 내비 경로**에서 시스템 내비의 활성 목적지, 경유지, 남은 시간·거리, 회전 안내, FusedLocation 위치 상태를 확인합니다. NaviHelper `requestTrip` 응답은 좌표 수를 진단에 표시합니다. 요약 좌표를 실제 도로 선형으로 사용하지 않습니다.

Pleos TextToSpeech 및 SpeechToText 3.3.1의 ON_DEVICE 모드를 사용합니다. Manifest에서 TTS_SERVICE와 STT_SERVICE를 선언하며, 차량에서의 권한 부여와 한국어 지원은 실행 환경에서 확인합니다. 실제 후보의 음성 안내가 끝난 뒤 최종 인식 결과를 수락 여부로 처리합니다. 최근 TBT에서 회전까지 300m 이내이거나 회전 정보가 없으면 실제 차량 경로의 수색 제안을 보류합니다. TBT는 회전 안내 오디오가 재생 중인지 알려주는 신호가 아닙니다.

현재 검증된 후보의 입력은 에뮬레이터 전용 `navi-candidate` 명령으로 제한됩니다. 이 명령에는 활성 경로 앞의 안전한 경유지와, 현재 위치·기존 경유지·목적지를 기준으로 계산한 ETA를 넣습니다. 후보 등록 뒤 **차량 내비 경로**에서 `./demo.sh drive`로 확인할 수 있습니다. 실제 변경은 유효한 후보와 최신 위치, 경로 재확인 및 수락이 필요합니다. 별도 라우팅 공급원은 아직 연결하지 않았습니다.

### 실제 방문 증거

실제 경유 적용에 성공하면 FusedLocation의 시간순 표본으로 방문을 확인합니다. 방문 반경 30m, 표본 간 최대 15초를 초기 기준으로 사용합니다. 과도한 위치 이동과 누락은 지급 보류 사유로 남습니다. 단독은 주차 후보 도착 뒤 기어 P, 운전석 문 열림, 좌석 비움과 3분 뒤 재탑승을 확인합니다. 동승자는 검증된 진입점과 통과 지점을 순서대로 확인하며 속도·체류 보너스는 없습니다.

기기 내 가상 포인트에는 기존 50P·사진 승인 +25P·하루 150P 기준이 적용됩니다. 서버 포인트는 별도 검토 키로 관리하며 차량의 로컬 증거와 서버 자동 적립은 연결되어 있지 않습니다.
