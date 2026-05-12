package sample.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Vector;

/**
 * 원본 jcraft jsch 로 한글 파일명을 업로드해 "깨지는" 현상을 재현한다.
 *
 * 원본 jsch (com.jcraft:jsch:0.1.55) 는 파일명 인코딩 제어용 공개 API 가 없고,
 * 내부적으로 UTF-8 로 강제 인코딩한다. 따라서 ISO8859-1 locale 서버에서
 * 직접 ls 하면 한글 1자가 3바이트 가비지로 보인다.
 *
 * 사용법:
 *   java -jar target/jsch-original-test-1.0.0-jar-with-dependencies.jar \
 *        <host> <port> <user> <password> <remoteDir>
 */
public class JschOriginalKoreanUploadTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: <host> <port> <user> <password> <remoteDir>");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String user = args[2];
        String password = args[3];
        String remoteDir = args[4];

        String remoteFilename = "[jcraft-jsch-0.1.55]한글_가나다_" + System.currentTimeMillis() + ".txt";
        String content = "원본 jcraft jsch (0.1.55) 로 업로드한 파일.\n"
                + "이 라이브러리는 파일명 인코딩이 UTF-8 로 강제됨.\n";

        System.out.println("=== Original jcraft jsch 0.1.55 Korean Upload Test ===");
        System.out.println("host        : " + host + ":" + port);
        System.out.println("user        : " + user);
        System.out.println("remoteDir   : " + remoteDir);
        System.out.println("file name   : " + remoteFilename);
        System.out.println("file.encoding(JVM): " + System.getProperty("file.encoding"));
        System.out.println();
        System.out.println("[wire bytes] '" + remoteFilename + "' as UTF-8 (강제) = "
                + toHex(remoteFilename.getBytes(StandardCharsets.UTF_8)));
        System.out.println("[ref bytes]  '" + remoteFilename + "' as MS949        = "
                + toHex(remoteFilename.getBytes("MS949")));
        System.out.println();

        Session session = null;
        ChannelSftp sftp = null;
        try {
            JSch.setLogger(new com.jcraft.jsch.Logger() {
                public boolean isEnabled(int level) { return true; }
                public void log(int level, String message) {
                    System.out.println("[jsch] " + message);
                }
            });
            JSch jsch = new JSch();
            session = jsch.getSession(user, host, port);
            session.setPassword(password);

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            // 최신 OpenSSH 와 핸드셰이크하기 위해 레거시 알고리즘 보강
            // jcraft 0.1.55 가 클래스 등록한 KEX 만 사용. group14-sha256 은 등록되지 않아 NPE.
            config.put("server_host_key", "ssh-rsa,ecdsa-sha2-nistp256,ssh-ed25519");
            config.put("kex", "diffie-hellman-group-exchange-sha256,"
                    + "diffie-hellman-group-exchange-sha1,diffie-hellman-group14-sha1");
            session.setConfig(config);

            session.connect(15_000);
            System.out.println("[OK] SSH session connected");

            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15_000);
            System.out.println("[OK] SFTP channel opened");
            System.out.println("[INFO] 원본 jsch 는 setFilenameEncoding() 메서드가 없음 → UTF-8 강제");

            sftp.cd(remoteDir);
            System.out.println("[OK] cd " + remoteDir + " (pwd=" + sftp.pwd() + ")");

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            sftp.put(new ByteArrayInputStream(bytes), remoteFilename);
            System.out.println("[OK] PUT " + remoteFilename + " (" + bytes.length + " bytes)");

            System.out.println();
            System.out.println("[ls] " + remoteDir + " (원본 jsch 가 UTF-8 로 decode 한 결과)");
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");
            for (ChannelSftp.LsEntry e : entries) {
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                System.out.println("  - " + name);
            }

            System.out.println();
            System.out.println("[DONE] Upload finished");
            System.out.println("  → 이제 docker exec sftp-iso8859 bash -c 'ls /home/sftpuser | xxd'");
            System.out.println("    로 raw 바이트를 확인하면 한글 1자 = 3바이트 (0xE…) 로 저장돼 있음.");
            System.out.println("    서버 ISO8859-1 locale 에서는 3글자의 가비지로 표시된다.");
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}
