# ISO8859-1 SFTP 테스트 서버

원격 서버 locale 이 `ISO8859-1` 인 환경을 로컬에서 재현하기 위한 Docker 컨테이너.

## 기동

```bash
cd test-server
docker compose up -d --build

# 또는 compose 없이:
docker build -t sftp-iso8859 .
docker run -d --name sftp-iso8859 -p 2222:22 sftp-iso8859
```

기본 접속 정보:

| key | value |
|---|---|
| host | `localhost` |
| port | `2222` |
| user | `sftpuser` |
| pass | `sftppass` |
| home | `/home/sftpuser` |

## locale 확인

```bash
docker exec sftp-iso8859 locale
# LANG=en_US.ISO-8859-1
# LC_ALL=en_US.ISO-8859-1
```

## 클라이언트 테스트 실행

루트 프로젝트로 돌아가 `jsch-test` / `sshj-test` 를 실행:

```bash
# mwiede/jsch + MS949
java -jar ../jsch-test/target/jsch-test-1.0.0-jar-with-dependencies.jar \
     localhost 2222 sftpuser sftppass /home/sftpuser MS949

# mwiede/jsch + EUC-KR
java -jar ../jsch-test/target/jsch-test-1.0.0-jar-with-dependencies.jar \
     localhost 2222 sftpuser sftppass /home/sftpuser EUC-KR

# mwiede/jsch + UTF-8 (대조군)
java -jar ../jsch-test/target/jsch-test-1.0.0-jar-with-dependencies.jar \
     localhost 2222 sftpuser sftppass /home/sftpuser UTF-8

# SSHJ (UTF-8 강제됨)
java -jar ../sshj-test/target/sshj-test-1.0.0-jar-with-dependencies.jar \
     localhost 2222 sftpuser sftppass /home/sftpuser
```

## 서버측 검증

업로드된 파일이 실제로 어떤 바이트로 저장되었는지 확인:

```bash
# ASCII 외 문자를 \M-… 로 가시화
docker exec sftp-iso8859 bash -c 'ls -la /home/sftpuser | cat -v'

# 파일명 raw 바이트열 hex 확인 (한글 1자가 MS949=2바이트, UTF-8=3바이트)
docker exec sftp-iso8859 bash -c 'ls /home/sftpuser | xxd'

# 정확한 인코딩별 비교: 한 문자씩 hex 로 풀어 보기
docker exec sftp-iso8859 bash -c 'ls /home/sftpuser | od -c | head -20'
```

기대 결과:
- `MS949` 로 송출한 파일 → 한글 1자 = 2바이트 (예: `한` = `C7 D1`)
- `EUC-KR` 로 송출한 파일 → 동일
- `UTF-8` 로 송출한 파일 → 한글 1자 = 3바이트 (예: `한` = `ED 95 9C`)
- SSHJ → UTF-8 과 동일

## 종료/정리

```bash
docker compose down -v          # 볼륨까지 제거
# 또는
docker rm -f sftp-iso8859
```

## 주의

- 데모 비번이므로 외부 노출 금지. 포트 `2222` 는 로컬에만 바인딩됨.
- 실제 운영 서버는 locale 변경이 어렵기 때문에 클라이언트(이 프로젝트) 측에서
  wire 인코딩을 통제하는 것이 현실적 해법이다.
