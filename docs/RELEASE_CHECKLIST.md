# 배포 체크리스트 (Modrinth / Hangar)

각 사이트의 규정·입력 항목은 바뀔 수 있으니 올리기 직전에 최신 안내를 한 번 확인하세요. 아래는 이 프로젝트 기준으로 정리한 목록입니다.

## A. 올리기 전에 저장소에서 (✅ = 1.0.0 준비에서 이미 확인)

- [x] 버전 `1.0.0`(`build.gradle.kts` → `plugin.yml`의 `${version}`로 들어감). 테스트가 확인한다.
- [x] 기본 `config.yml`에 `secret-seed` 값이 없고(`""`), 소스에 64자리 16진수 시드 문자열이 없다(`ReleaseDefaultsTest`).
- [x] 기본값: 섀도 모드, `confirm-rule: PAIRED`, `debug.allow-spectator: false`. 처벌 동작(킥·밴 호출) 코드 없음(테스트가 검사).
- [x] `./gradlew build` 통과(단위 테스트 + 시뮬레이터 컴파일).
- [x] GitHub Actions(`.github/workflows/build.yml`): push/PR마다 gradle wrapper 검증 → Java 25 빌드(테스트 포함) → 성공하면 jar를 artifact로 업로드. 저장소를 올린 뒤 첫 실행이 초록인지 확인한다.
- [x] 라이선스 **GPL-3.0**: `LICENSE` 추가, 저작권자 정성원, README·`plugin.yml` 표기(테스트가 확인). 사이트 입력란에도 **GPL-3.0**(-only)을 고른다. PacketEvents의 라이선스와 호환되는지 한 번 더 확인.
- [x] `plugin.yml`에 `authors: [정성원]` 추가. `website`(소스 저장소 주소)는 저장소를 공개할 때 추가(선택).
- [ ] **소스 저장소를 공개한다면** 지난 커밋 기록에 실서버 시험용 `secret-seed` 값이 한 번 들어갔는지 확인(`git log -S<값>`). 있으면 그 서버의 시드를 새로 바꾸거나 기록을 정리한 뒤 공개.
- [ ] 태그 `v1.0.0` 달기, 변경 이력(CHANGELOG) 작성.

## B. 배포 파일

- [ ] `./gradlew clean build` → `build/libs/anchor-mc-1.0.0.jar` 하나만 올린다(시뮬레이터 클래스는 안 들어간다. `unzip -l`로 확인).
- [ ] jar 안 `plugin.yml`: `name: AnchorMC`, `version: 1.0.0`, `api-version: "26.2"`, `depend: [packetevents]`, `libraries`(SQLite).
- [ ] 파일 해시(SHA-256) 기록해 두기.

## C. 깨끗한 서버에서 마지막 시험

- [ ] Paper 26.2 + Java 25 + PacketEvents 2.14.0 + 이 jar만 넣은 **새 서버**에서 시작.
- [ ] 첫 시작: SQLite 라이브러리 다운로드, `config.yml` 생성, `secret-seed` 무작위 생성 경고가 한 번, `anchor-mc 켜짐(섀도 모드 …)`.
- [ ] 두 번째 시작: 시드 경고 없음(같은 시드 유지), `확정 조건: 먼저 반응한 쪽 log10E ≥ 9.0` 로그, config 경고 없음.
- [ ] PacketEvents 없이 시작: `plugin.yml`의 `depend` 때문에 Paper가 의존성 누락으로 로드를 거부하는지(플러그인 코드의 안내 메시지는 이중 안전장치).
- [ ] `/anchor stats`에서 청크 패킷 삽입 횟수 증가, `/anchor simtest <나> xray 5`로 확정 경로, `/anchor reset`으로 기록 삭제.
- [ ] 예전 config(없는 키·`config-version` 없음)로 시작하면 경고가 뜨는지.

## D. 프로젝트 페이지 공통 입력

- [ ] 이름 `anchor-mc`(또는 표시 이름), **한 줄 요약**(예: "Paper용 엑스레이 탐지: 가짜 광석 미끼와 통계 검정, 섀도 모드(처벌 없음)").
- [ ] 설명 본문: 저장소 `README.md`를 바탕으로(필요한 것·설치·명령·설정·동작 원리·한계). **한국어 문서임**을 밝히고, 가능하면 영어 요약 한 단락 추가.
- [ ] **서버 전용 플러그인**(클라이언트 설치 불필요)이라고 표시.
- [ ] 아이콘(정사각형 PNG), 선택: 스크린샷(예: `/anchor status` 출력, 콘솔 확정 로그).
- [ ] 카테고리/태그: 관리·보안·안티치트 계열(사이트의 분류 중 가장 가까운 것).
- [ ] 링크: 소스 저장소, 이슈 트래커, 문서(`docs/DEVELOPMENT.md`).
- [ ] 라이선스 선택: **GPL-3.0**(위 LICENSE와 일치).
- [ ] **개인정보 안내**: 계정 UUID·마지막 이름·관측 통계를 서버의 SQLite에 저장한다(외부로 전송하지 않음). 설명란에 한 줄.
- [ ] 섀도 모드·통계적 판정의 한계(시뮬레이터 결과이며 사람 데이터가 아님, 오탐 상한은 이론값)를 과장 없이 적기.

## E. 버전(릴리스) 입력

- [ ] 버전 번호 `1.0.0`, 이름, 변경 이력.
- [ ] 지원 플랫폼/로더: **Paper**, 지원 게임 버전: **26.2**(Spigot·Folia 등은 시험하지 않았으므로 선택하지 않는다).
- [ ] **필수 의존성: PacketEvents(2.14.0 이상)** 를 등록(사이트의 의존성 항목으로. 없으면 설명에 다운로드 링크).
- [ ] 릴리스 채널: 처음이면 `beta`로 올려 피드백을 받은 뒤 `release`로 승격하는 것을 권장(실서버 시험 표본이 적다).
- [ ] 첫 시작 때 SQLite 라이브러리를 내려받는다는 점(인터넷 필요)을 설명에 적기.

## F. 올린 뒤

- [ ] 사이트에서 내려받은 jar의 해시가 올린 파일과 같은지, 그 jar로 C 절 시험 한 번 더.
- [ ] 이슈 템플릿(버전·Paper 빌드·PacketEvents 버전·`config.yml` 시드 제외·콘솔 로그 요청) 준비.
- [ ] 다음 버전부터: `config-version`을 올리고, 키·기본값이 바뀌면 변경 이력에 "config.yml 직접 수정 필요"를 명시(기존 파일은 덮어쓰지 않는다).
