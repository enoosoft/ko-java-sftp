package sample.sftp;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * SSHJ SFTP 한글 파일명 업로드 테스트.
 *
 * 배경:
 *   원격 서버 locale 이 ISO8859-1 환경에서, 클라이언트가 wire 에 송출하는
 *   파일명 바이트열을 어떻게 통제할 수 있는지 검증한다.
 *
 * SSHJ 의 한계:
 *   - 0.38.0 표준 API 에는 setFilenameCharset() 같은 메서드가 없다.
 *   - net.schmizz.sshj.common.Buffer.putString(String) 이 UTF-8 로 하드코딩.
 *   - 관련 이슈: https://github.com/hierynomus/sshj/issues/277 (미해결)
 *
 * 두 가지 모드:
 *   1) 기본 모드           : 한글 파일명을 그대로 put() → UTF-8 로 wire 송출
 *   2) --euckr wire trick  : EUC-KR 바이트열을 ISO-8859-1 1:1 매핑 String 으로
 *                            만들어서 전달. 이론적으로 wire 에 그 바이트열이
 *                            그대로 흘러야 하나, Buffer 가 UTF-8 재인코딩하므로
 *                            0x80 이상은 부풀려져 실제로는 깨진다.
 *                            → SSHJ 표준 API 로는 EUC-KR 송출 불가능을
 *                               실측하는 용도.
 *
 * 사용법:
 *   mvn -q -DskipTests package
 *   java -jar target/sshj-test-1.0.0-jar-with-dependencies.jar \
 *        <host> <port> <user> <password> <remoteDir> [--euckr]
 */
public class SshjKoreanUploadTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: <host> <port> <user> <password> <remoteDir> [--euckr]");
            System.exit(1);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String user = args[2];
        String password = args[3];
        String remoteDir = args[4];
        boolean euckrTrick = args.length >= 6 && "--euckr".equals(args[5]);

        String koreanName = "한_[sshj-0.38.0" + (euckrTrick ? "-euckrtrick" : "-utf8") + "]_"
                + System.currentTimeMillis() + ".txt";
        String wireName = euckrTrick
                ? new String(koreanName.getBytes(Charset.forName("EUC-KR")), StandardCharsets.ISO_8859_1)
                : koreanName;

        String content = "안녕하세요. SSHJ 한글 인코딩 테스트입니다.\n"
                + "mode: " + (euckrTrick ? "EUC-KR wire trick" : "UTF-8 default") + "\n";

        System.out.println("=== SSHJ SFTP Korean Upload Test ===");
        System.out.println("host        : " + host + ":" + port);
        System.out.println("user        : " + user);
        System.out.println("remoteDir   : " + remoteDir);
        System.out.println("mode        : " + (euckrTrick ? "EUC-KR wire trick" : "UTF-8 (default)"));
        System.out.println("korean name : " + koreanName);
        System.out.println("wire name   : " + wireName);
        System.out.println("file.encoding(JVM): " + System.getProperty("file.encoding"));
        System.out.println();
        System.out.println("[ref bytes] '" + koreanName + "' as UTF-8  = "
                + toHex(koreanName.getBytes(StandardCharsets.UTF_8)));
        System.out.println("[ref bytes] '" + koreanName + "' as EUC-KR = "
                + toHex(koreanName.getBytes(Charset.forName("EUC-KR"))));
        System.out.println("[ref bytes] wireName.getBytes(UTF-8)      = "
                + toHex(wireName.getBytes(StandardCharsets.UTF_8)) + "  ← SSHJ 실제 송출 바이트열");
        System.out.println();

        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.setConnectTimeout(15_000);
            ssh.setTimeout(15_000);

            ssh.connect(host, port);
            System.out.println("[OK] SSH connected");

            ssh.authPassword(user, password);
            System.out.println("[OK] auth password");

            try (SFTPClient sftp = ssh.newSFTPClient()) {
                System.out.println("[OK] SFTP client opened");
                System.out.println("[INFO] SSHJ has NO public API to change filename charset.");
                System.out.println("       SFTP strings are encoded as UTF-8 by Buffer (hardcoded).");

                String remotePath = remoteDir.endsWith("/")
                        ? remoteDir + wireName
                        : remoteDir + "/" + wireName;

                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                sftp.put(new InMemoryBytesSource(wireName, bytes), remotePath);
                System.out.println("[OK] PUT " + remotePath + " (" + bytes.length + " bytes)");

                System.out.println();
                System.out.println("[ls] " + remoteDir);
                for (RemoteResourceInfo info : sftp.ls(remoteDir)) {
                    System.out.println("  - " + info.getName());
                }

                System.out.println();
                System.out.println("[DONE] Upload finished");
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    private static class InMemoryBytesSource extends InMemorySourceFile {
        private final String name;
        private final byte[] bytes;

        InMemoryBytesSource(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
        @Override public String getName() { return name; }
        @Override public long getLength() { return bytes.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
    }
}
