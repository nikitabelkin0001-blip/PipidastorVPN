import com.pipidastr.vpn.SubscriptionParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SubscriptionParserSmokeTest {
    public static void main(String[] args) {
        String vless = "vless://bf000d23-0752-40b4-affe-68f7707a9661@example.com:443?security=reality&type=tcp&sni=www.example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA#Sample";
        String hysteria = "hysteria2://password@example.org:8443?sni=example.org#Second";
        String encoded = Base64.getEncoder().encodeToString((vless + "\n" + hysteria).getBytes(StandardCharsets.UTF_8));
        SubscriptionParser.Result result = SubscriptionParser.parse(encoded);
        if (result.servers.size() != 2 || !result.servers.get(0).name.equals("Sample") ||
                !result.servers.get(1).protocol.equals("Hysteria2"))
            throw new AssertionError("Server import failed");
        result = SubscriptionParser.parse(vless + "\ntrojan://x@example.com:443");
        if (result.servers.size() != 1 || result.skipped != 1)
            throw new AssertionError("Unsupported protocol count failed");
        try {
            SubscriptionParser.parse("vless://bf000d23-0752-40b4-affe-68f7707a9661@0.0.0.0:1?security=none#" +
                    "%D0%92%D0%BA%D0%BB%D1%8E%D1%87%D0%B8%D1%82%D0%B5%20HWID");
            throw new AssertionError("HWID decoy was accepted");
        } catch (SubscriptionParser.DeviceIdRequiredException expected) {
            System.out.println("Android subscription parser: OK");
        }
    }
}
