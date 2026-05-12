package sample.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Vector;

/**
 * mwiede/jsch SFTP 한글 파일명 업로드 테스트.
 *
 * 배경:
 *   원격 서버 locale 이 ISO8859-1 (1바이트 = 1글자) 라서, 클라이언트가 어떤
 *   인코딩으로 파일명 바이트를 송출하느냐에 따라 서버측 ls 결과가 달라진다.
 *   - UTF-8 송출   : 한글 1자 = 3바이트 → ISO8859-1 locale 에서 3글자 가비지
 *   - EUC-KR/MS949: 한글 1자 = 2바이트 → ISO8859-1 locale 에서 2글자 가비지
 *                    그러나 같은 인코딩을 쓰는 클라이언트끼리는 정상 표시됨
 *   본 테스트는 setFilenameEncoding 으로 wire 바이트열을 통제할 수 있는지
 *   검증한다.
 *
 * 사용법:
 *   mvn -q -DskipTests package
 *   java -jar target/jsch-test-1.0.0-jar-with-dependencies.jar \
 *        <host> <port> <user> <password> <remoteDir> [encoding]
 *
 *   encoding 기본값: MS949
 *   추천 비교: MS949, EUC-KR, UTF-8 을 각각 돌려 서버 ls 결과를 비교
 */
public class JschKoreanUploadTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: <host> <port> <user> <password> <remoteDir> [encoding]");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String user = args[2];
        String password = args[3];
        String remoteDir = args[4];
        String encoding = args.length >= 6 ? args[5] : "MS949";

        String remoteFilename = "[mwiede-jsch-0.2.25-" + encoding + "]한글_가나다_" + System.currentTimeMillis() + ".txt";
        String content = "안녕하세요. mwiede/jsch 한글 인코딩 테스트입니다.\n인코딩: " + encoding + "\n";

        System.out.println("=== mwiede/jsch SFTP Korean Upload Test ===");
        System.out.println("host        : " + host + ":" + port);
        System.out.println("user        : " + user);
        System.out.println("remoteDir   : " + remoteDir);
        System.out.println("encoding    : " + encoding);
        System.out.println("file name   : " + remoteFilename);
        System.out.println("file.encoding(JVM): " + System.getProperty("file.encoding"));
        System.out.println();
        System.out.println("[wire bytes] '" + remoteFilename + "' as " + encoding + " = "
                + toHex(remoteFilename.getBytes(encoding)));
        System.out.println("[ref bytes]  '" + remoteFilename + "' as UTF-8  = "
                + toHex(remoteFilename.getBytes(StandardCharsets.UTF_8)));
        System.out.println();

        Session session = null;
        ChannelSftp sftp = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(user, host, port);
            session.setPassword(password);

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);

            session.connect(15_000);
            System.out.println("[OK] SSH session connected");

            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15_000);
            System.out.println("[OK] SFTP channel opened");

            // 핵심: 파일명 인코딩 지정 (mwiede fork 전용 API)
            sftp.setFilenameEncoding(encoding);
            System.out.println("[OK] setFilenameEncoding(\"" + encoding + "\") called");

            sftp.cd(remoteDir);
            System.out.println("[OK] cd " + remoteDir + " (pwd=" + sftp.pwd() + ")");

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            sftp.put(new ByteArrayInputStream(bytes), remoteFilename);
            System.out.println("[OK] PUT " + remoteFilename + " (" + bytes.length + " bytes)");

            System.out.println();
            System.out.println("[ls] " + remoteDir);
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");
            for (ChannelSftp.LsEntry e : entries) {
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                System.out.println("  - " + name);
            }

            System.out.println();
            System.out.println("[DONE] Upload finished");
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
