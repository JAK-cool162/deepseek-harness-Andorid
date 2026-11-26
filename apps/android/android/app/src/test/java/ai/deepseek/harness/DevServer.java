package ai.deepseek.harness;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Desktop launcher for {@link LocalServer}: serves the assembled `www/` assets
 * from disk with the same routes and stored names the APK serves, for browser checks off-device.
 * Arguments: asset directory, state file, token, port. Prints the boot URL.
 */
public final class DevServer {
    private DevServer() {
    }

    /**
     * @param args asset directory, state file, token, port
     * @throws IOException when the port cannot be bound
     * @throws InterruptedException when interrupted while serving
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        File root = new File(args[0]);
        LocalServer server = new LocalServer(path -> {
            File file = new File(root, LocalServer.storedAssetName(path));
            try {
                return file.isFile() ? new FileInputStream(file) : null;
            } catch (FileNotFoundException missing) {
                return null;
            }
        }, new File(args[1]), args[2], LocalServer.NO_HOST);
        server.start(Integer.parseInt(args[3]));
        System.out.println(server.bootUrl(false));
        Thread.currentThread().join();
    }
}
