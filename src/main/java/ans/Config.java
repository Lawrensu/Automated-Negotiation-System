package ans;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {
    private static final Properties props = new Properties();

    static {
        try (InputStream in = Config.class
                .getClassLoader()
                .getResourceAsStream("config.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("config.properties not found", e);
        }
    }

    public static double getDouble(String key) {
        return Double.parseDouble(props.getProperty(key));
    }

    public static int getInt(String key) {
        return Integer.parseInt(props.getProperty(key));
    }

    public static String get(String key) {
        return props.getProperty(key);
    }
}
