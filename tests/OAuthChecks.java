package platform.damage.business;

public final class OAuthChecks {
    public static void main(String[] args) throws Exception {
        var reader = new DisasterReader();
        reader.start();
        try {
            var token = DisasterReader.class.getDeclaredMethod("serviceToken");
            token.setAccessible(true);
            String first = (String) token.invoke(reader);
            if (!first.equals(token.invoke(reader))) throw new AssertionError("Token cache");
            Thread.sleep(6000);
            String renewed = (String) token.invoke(reader);
            if (first.equals(renewed)) throw new AssertionError("Token renewal");
            if (first.split("\\.").length != 3 || renewed.split("\\.").length != 3) throw new AssertionError("JWT format");
            System.out.println("PASS actual Keycloak Client Credentials cache and expiry renewal");
        } finally { reader.stop(); }
    }
}
