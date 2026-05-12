# ISO8859-1 locale 의 SFTP 테스트 서버
#
# 목적: 원격 서버 locale 이 ISO8859-1 인 환경에서, 클라이언트가 어떤 인코딩으로
#       파일명 바이트를 송출하는지에 따라 ls 결과가 어떻게 달라지는지 검증.
#
# 빌드/실행:
#   docker build -t sftp-iso8859 .
#   docker run -d --name sftp-iso8859 -p 2222:22 sftp-iso8859
#
# 접속:
#   user / pass : sftpuser / sftppass
#   port        : 2222
#   homeDir     : /home/sftpuser
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        openssh-server \
        locales \
        xxd \
        less \
    && rm -rf /var/lib/apt/lists/*

# ISO8859-1 locale 생성 및 기본화
RUN sed -i 's/^# *\(en_US.ISO-8859-1\)/\1/' /etc/locale.gen \
    && locale-gen \
    && update-locale LANG=en_US.ISO-8859-1 LC_ALL=en_US.ISO-8859-1

ENV LANG=en_US.ISO-8859-1 \
    LANGUAGE=en_US.ISO-8859-1 \
    LC_ALL=en_US.ISO-8859-1

# sshd 준비
RUN mkdir -p /var/run/sshd \
    && useradd -m -s /bin/bash sftpuser \
    && echo 'sftpuser:sftppass' | chpasswd \
    && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config

# sshd 환경에도 ISO8859-1 강제. ssh 접속 시 client locale 을 forward 받지 않도록 한다.
RUN echo 'AcceptEnv LANG LC_*' > /etc/ssh/sshd_config.d/00-locale.conf \
    && echo 'export LANG=en_US.ISO-8859-1' >> /home/sftpuser/.bashrc \
    && echo 'export LC_ALL=en_US.ISO-8859-1' >> /home/sftpuser/.bashrc

# 레거시 호환: jcraft jsch 0.1.55 등 오래된 클라이언트와 핸드셰이크 가능하도록
# 알고리즘을 명시(=) 로 좁혀, OpenSSH 9.x 의 신규 확장(kex-strict-*, ext-info-*)이
# 광고되어 jcraft 의 알고리즘 파서가 NPE 를 내는 것을 막는다.
RUN printf '%s\n' \
        'KexAlgorithms diffie-hellman-group-exchange-sha256,diffie-hellman-group-exchange-sha1,diffie-hellman-group14-sha1' \
        'HostKeyAlgorithms ssh-rsa,ssh-ed25519,ecdsa-sha2-nistp256' \
        'PubkeyAcceptedAlgorithms ssh-rsa,ssh-ed25519,ecdsa-sha2-nistp256' \
        'Ciphers aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc' \
        'MACs hmac-sha2-256,hmac-sha2-512,hmac-sha1' \
        > /etc/ssh/sshd_config.d/10-legacy.conf

EXPOSE 22

# sshd 는 환경변수 forward 가 제한적이라 wrapper 로 LANG 을 명시 주입한다.
CMD ["/usr/sbin/sshd", "-D", "-e"]
