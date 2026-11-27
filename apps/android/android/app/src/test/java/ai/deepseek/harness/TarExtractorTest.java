package ai.deepseek.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tar extraction: entry types, long names, modes, and refusal of paths that escape the target. */
public class TarExtractorTest {
    private File root;
    private final List<String> skipped = new ArrayList<>();

    /** {@link TarExtractor.Links} over java.nio, for the JVM. */
    static final TarExtractor.Links NIO = new TarExtractor.Links() {
        @Override
        public void symlink(String target, File link) throws IOException {
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
        }

        @Override
        public void hardlink(File existing, File link) throws IOException {
            Files.createLink(link.toPath(), existing.toPath());
        }

        @Override
        public void chmod(File file, int mode) throws IOException {
            Set<PosixFilePermission> permissions = java.util.EnumSet.noneOf(PosixFilePermission.class);
            PosixFilePermission[] order = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ,
            };
            for (int bit = 0; bit < order.length; bit++) if ((mode & (1 << bit)) != 0) permissions.add(order[bit]);
            Files.setPosixFilePermissions(file.toPath(), permissions);
        }

        @Override
        public boolean isSymlink(File file) {
            return Files.isSymbolicLink(file.toPath());
        }

        @Override
        public String readlink(File file) throws IOException {
            return Files.readSymbolicLink(file.toPath()).toString();
        }
    };

    @Before
    public void create() throws IOException {
        root = Files.createTempDirectory("dsh-tar").toFile();
    }

    @After
    public void delete() throws IOException {
        try (var walk = Files.walk(root.toPath())) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** Minimal ustar writer for test archives. */
    static final class TarBuilder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        TarBuilder entry(String name, char type, int mode, String link, byte[] body) throws IOException {
            byte[] header = new byte[512];
            put(header, 0, name, 100);
            put(header, 100, String.format("%07o", mode), 8);
            put(header, 124, String.format("%011o", body.length), 12);
            put(header, 136, String.format("%011o", 0), 12);
            header[156] = (byte) type;
            put(header, 157, link, 100);
            put(header, 257, "ustar", 6);
            put(header, 263, "00", 2);
            for (int index = 148; index < 156; index++) header[index] = ' ';
            long sum = 0;
            for (byte b : header) sum += b & 0xff;
            put(header, 148, String.format("%06o", sum), 7);
            out.write(header);
            out.write(body);
            out.write(new byte[(512 - body.length % 512) % 512]);
            return this;
        }

        TarBuilder file(String name, int mode, String text) throws IOException {
            return entry(name, '0', mode, "", text.getBytes(StandardCharsets.UTF_8));
        }

        byte[] build() throws IOException {
            out.write(new byte[1024]);
            return out.toByteArray();
        }

        private static void put(byte[] header, int offset, String value, int length) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
        }
    }

    private static String read(java.nio.file.Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private int extract(byte[] archive) throws IOException {
        TarExtractor extractor = new TarExtractor(root, NIO, new TarExtractor.Listener() {
            @Override
            public void entry(String path) {
            }

            @Override
            public void skipped(String path, String reason) {
                skipped.add(path + ": " + reason);
            }
        });
        return extractor.extract(new ByteArrayInputStream(archive));
    }

    @Test
    public void extractsFilesDirectoriesLinksAndModes() throws IOException {
        String longName = "usr/share/" + "x".repeat(120) + "/file.txt";
        byte[] archive = new TarBuilder()
                .entry("usr/", '5', 0755, "", new byte[0])
                .entry("usr/bin/", '5', 0755, "", new byte[0])
                .file("usr/bin/tool", 0755, "#!/bin/sh\n")
                .entry("bin", '2', 0777, "usr/bin", new byte[0])
                .entry("usr/bin/tool2", '1', 0755, "usr/bin/tool", new byte[0])
                .entry("././@LongLink", 'L', 0, "", (longName + "\0").getBytes(StandardCharsets.UTF_8))
                .file("ignored-short-name", 0644, "long")
                .file("etc/secret", 0600, "s")
                .build();
        assertEquals(6, extract(archive) - 1);
        assertEquals("#!/bin/sh\n", read(new File(root, "usr/bin/tool").toPath()));
        assertTrue(new File(root, "usr/bin/tool").canExecute());
        assertTrue(Files.isSymbolicLink(new File(root, "bin").toPath()));
        assertEquals("usr/bin", Files.readSymbolicLink(new File(root, "bin").toPath()).toString());
        assertEquals("#!/bin/sh\n", read(new File(root, "usr/bin/tool2").toPath()));
        assertEquals("long", read(new File(root, longName).toPath()));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(new File(root, "etc/secret").toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void refusesPathsThatEscapeTheTarget() throws IOException {
        byte[] archive = new TarBuilder()
                .file("../outside", 0644, "x")
                .entry("link", '2', 0777, "/tmp", new byte[0])
                .file("link/through-symlink", 0644, "x")
                .file("/absolute-is-relative", 0644, "inside")
                .build();
        extract(archive);
        assertFalse(new File(root.getParentFile(), "outside").exists());
        assertFalse(new File("/tmp/through-symlink").exists());
        assertEquals("inside", read(new File(root, "absolute-is-relative").toPath()));
        assertEquals(2, skipped.size());
    }
}
