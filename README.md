# SFTP 한글 파일명 인코딩 테스트

원격 서버 locale 이 **ISO8859-1** 인 환경에서 한글 파일명이 깨지는 이슈를
해결하기 위해, 기존 JSch(jcraft) 코드를 대체할 두 후보 라이브러리를
standalone Java 8 프로그램으로 비교한다.

| 라이브러리 | 한글 인코딩 API | 결론 |
|---|---|---|
| `com.jcraft:jsch:0.1.55` (원본, BEFORE) | — (UTF-8 강제) | ❌ ISO8859-1 서버에서 한글 1자 = 3바이트 가비지로 깨짐 |
| `com.github.mwiede:jsch:0.2.25` (AFTER) | `ChannelSftp.setFilenameEncoding("MS949")` | ✅ 표준 API 로 wire 인코딩 직접 지정 |
| `com.hierynomus:sshj:0.38.0` | — (표준 API 없음) | ⚠️ Buffer 에서 UTF-8 하드코딩, 이슈 [#277](https://github.com/hierynomus/sshj/issues/277) 미해결 |

> **요약**: 기존 JSch 코드를 그대로 두고 그룹 ID 만 `com.github.mwiede` 로
> 바꿔 `setFilenameEncoding()` 한 줄을 추가하는 mwiede/jsch 경로가
> 마이그레이션 비용이 가장 낮다. SSHJ 는 표준 API 만으로는 한글(MS949/EUC-KR)
> wire 송출이 불가능함을 본 테스트로 실측 가능하다.

---

## 문제 정의

원격 서버 locale 이 `ISO8859-1` (1바이트 = 1글자) 이라:

- 클라이언트가 UTF-8 로 한글 파일명을 송출 → 한글 1자가 3바이트로 저장됨
  → 서버 ls 시 3글자의 가비지로 표시됨, 다른 OS/도구에서도 깨짐
- 클라이언트가 EUC-KR/MS949 로 송출 → 한글 1자가 2바이트로 저장됨
  → 서버 자체 ls 에서는 여전히 가비지지만, 동일 인코딩을 쓰는 다른 한국어
    클라이언트와 호환됨 (국내 레거시 SFTP 환경의 표준 운용 방식)

핵심은 **wire 바이트열을 통제할 수 있는가** 이다.

## 디렉터리 구조

```
sftp/
├── test-server/         # Docker 기반 ISO8859-1 locale SFTP 서버 (재현 환경)
├── jsch-original-test/  # 원본 com.jcraft:jsch:0.1.55 (BEFORE: 깨짐 재현)
├── jsch-test/           # mwiede/jsch 0.2.25 (AFTER: 인코딩 제어)
└── sshj-test/           # SSHJ 0.38.0
```

각 클라이언트는 독립된 Maven 프로젝트. 테스트 서버는 Docker 컨테이너로 띄운다.

## 테스트 서버 (선택)

ISO8859-1 locale 환경을 로컬에서 재현:

```bash
cd test-server && docker compose up -d --build
# localhost:2222, sftpuser/sftppass, /home/sftpuser
```

자세한 내용은 [`test-server/README.md`](test-server/README.md) 참고.

## 빌드

```bash
cd jsch-test && mvn -q -DskipTests package
cd ../sshj-test && mvn -q -DskipTests package
```

`target/*-jar-with-dependencies.jar` 가 생성된다.

> Java 8 JDK 가 필요. macOS 에서 `/usr/libexec/java_home` 이 JRE 를 잡는
> 경우가 있으므로 `JAVA_HOME` 을 명시적으로 지정:
> `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_341.jdk/Contents/Home`

## 실행

### 0) 원본 jcraft jsch — 깨짐 재현 (BEFORE)

```bash
cd jsch-original-test && mvn -q -DskipTests package
java -jar target/jsch-original-test-1.0.0-jar-with-dependencies.jar \
     localhost 2222 sftpuser sftppass /home/sftpuser
```

- 0.1.55 는 파일명 인코딩 제어 API 가 없어 UTF-8 로 강제 송출.
- 서버 raw 바이트열: `ED 95 9C ...` (한글 1자 = 3바이트).
- ISO8859-1 locale 서버에서 `ls | cat -v` 로 보면 `M-mM-^UM-^\…` 처럼 깨짐.

> 컨테이너 sshd 는 OpenSSH 9.x 의 신규 `kex-strict-*` 마커와 일부 알고리즘이
> jcraft 0.1.55 와 호환되지 않으므로, `test-server/Dockerfile` 에서 KEX/HostKey
> 알고리즘을 명시(`=`) 로 좁혀 호환되게 구성했다.

### 1) mwiede/jsch (AFTER)

```bash
cd jsch-test
java -jar target/jsch-test-1.0.0-jar-with-dependencies.jar \
     <host> <port> <user> <password> <remoteDir> [encoding]
```

- `encoding` 기본값: `MS949`. `EUC-KR`, `UTF-8`, `ISO-8859-1` 도 시도 가능.
- 핵심 한 줄: `channelSftp.setFilenameEncoding("MS949");`
- 한글 파일명(`한글테스트_가나다_<ts>.txt`)을 업로드하고 `ls` 결과를 출력.
- 시작 시 wire 바이트열을 hex 로 출력 → 어떤 바이트가 송출되는지 검증 가능.

### 2) SSHJ

```bash
cd sshj-test
java -jar target/sshj-test-1.0.0-jar-with-dependencies.jar \
     <host> <port> <user> <password> <remoteDir> [--ms949]
```

- 기본 모드: UTF-8 그대로 송출 (서버가 UTF-8 파일명을 받는 환경에서만 정상).
- `--ms949` : MS949 바이트 → ISO-8859-1 1:1 매핑 트릭. 단 SSHJ Buffer 가
  UTF-8 재인코딩을 강제하므로 0x80 이상이 부풀려져 결국 깨진다
  → "표준 API 로는 MS949 송출 불가" 를 실측하는 용도.

## 호환성 매트릭스 (실측)

`test-server` 컨테이너에 ko_KR.eucKR / ko_KR.UTF-8 locale 을 generate 한 뒤,
서버 ls 출력을 각각의 인코딩으로 해석했을 때 정상 표시 여부:

| 보는 쪽 ↓ \ 올린 쪽 → | jcraft (UTF-8) | mwiede MS949 | mwiede EUC-KR | mwiede UTF-8 |
|---|---|---|---|---|
| EUC-KR / MS949 클라이언트 | ✗ 깨짐 | ✅ | ✅ | ✗ 깨짐 |
| UTF-8 클라이언트            | ✅ | ✗ 깨짐 | ✗ 깨짐 | ✅ |

→ "서버 locale 이 ISO8859-1" 인 환경에서도, 결국 **클라이언트들 사이의
인코딩 합의** 가 표시 정상 여부를 결정한다. 서버 locale 은 raw 바이트 저장소
역할만 한다.

재현:

```bash
# 컨테이너에 한국어 locale 추가 (한 번만)
docker exec sftp-iso8859 bash -c \
  'sed -i "s/^# *\(ko_KR\.EUC-KR\)/\1/" /etc/locale.gen && \
   sed -i "s/^# *\(ko_KR\.UTF-8\)/\1/" /etc/locale.gen && \
   locale-gen'

# EUC-KR 클라이언트 시뮬레이션 (호스트 터미널이 UTF-8 이므로 iconv 로 변환)
docker exec sftp-iso8859 bash -c 'cd /home/sftpuser && ls *.txt' \
  | iconv -f euc-kr -t utf-8 -c

# UTF-8 클라이언트 시뮬레이션
docker exec sftp-iso8859 bash -c 'cd /home/sftpuser && ls *.txt'
```

## 검증 시나리오

각 실행 후 서버측에서 raw 바이트열을 확인:

```bash
# test-server 컨테이너에 접속한 상태라면
docker exec sftp-iso8859 bash -c 'ls /home/sftpuser | xxd'
docker exec sftp-iso8859 bash -c 'ls -la /home/sftpuser | cat -v'

# 별도 SFTP 서버라면
ssh <host> 'ls <remoteDir> | xxd'
```

- **mwiede/jsch + MS949**: ls 바이트열이 MS949 인코딩과 일치하는지
- **mwiede/jsch + UTF-8**: 한글 1자당 3바이트(0xEx 시작)로 저장되었는지
- **SSHJ 기본**: mwiede/jsch + UTF-8 과 동일 결과
- **SSHJ --ms949**: 의도와 달리 깨진 바이트(UTF-8 재인코딩 결과)가 저장됨

## 결론 적용 가이드

- 기존 JSch 코드: dependency 의 groupId 를 `com.jcraft` → `com.github.mwiede`,
  artifactId 는 `jsch` 동일, 버전 `0.2.25` 로 교체. 코드 변경은
  `channelSftp.setFilenameEncoding("MS949")` (또는 운영 합의된 인코딩) 한 줄만
  추가.

## 보안 노트

데모용으로 호스트키 검증을 비활성화했다 (`StrictHostKeyChecking=no`,
`PromiscuousVerifier`). 실서비스 적용 전에 `known_hosts` 기반 검증으로 교체할 것.
