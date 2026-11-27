package ai.deepseek.harness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Package index parsing, dependency order, .deb member lookup, and binary path patching. */
public class NativeInstallerTest {
    private static final String INDEX = String.join("\n",
            "Package: npm", "Version: 12.2.3", "Depends: nodejs | nodejs-lts", "Filename: pool/n/npm.deb", "SHA256: aa", "Size: 10",
            "",
            "Package: nodejs", "Version: 26.13.1", "Depends: libc++", "Filename: pool/n/nodejs.deb", "SHA256: bb", "Size: 20",
            "",
            "Package: nodejs-lts", "Version: 24.22.3", "Depends: libc++ (>= 27), openssl", "Filename: pool/n/lts.deb", "SHA256: cc", "Size: 30",
            "",
            "Package: libc++", "Version: 28", "Filename: pool/l/libc.deb", "SHA256: dd", "Size: 1",
            "",
            "Package: openssl", "Version: 3", "Depends: termux-tools", "Filename: pool/o/openssl.deb", "SHA256: ee", "Size: 2",
            "",
            "Package: termux-tools", "Version: 1", "Filename: pool/t/tools.deb", "SHA256: ff", "Size: 3",
            "");

    private static List<String> names(List<NativeInstaller.Package> packages) {
        List<String> names = new ArrayList<>();
        for (NativeInstaller.Package pkg : packages) names.add(pkg.name);
        return names;
    }

    @Test
    public void parsesFieldsAndVersionedDependencies() {
        Map<String, NativeInstaller.Package> index = NativeInstaller.parseIndex(INDEX);
        NativeInstaller.Package lts = index.get("nodejs-lts");
        assertEquals("24.22.3", lts.version);
        assertEquals("pool/n/lts.deb", lts.filename);
        assertEquals(30, lts.size);
        assertEquals(List.of(List.of("libc++"), List.of("openssl")), lts.depends);
        assertEquals(List.of(List.of("nodejs", "nodejs-lts")), index.get("npm").depends);
    }

    @Test
    public void ordersDependenciesFirstKeepsTheRequestedNodeAndSkipsTermuxIntegration() throws IOException {
        Map<String, NativeInstaller.Package> index = NativeInstaller.parseIndex(INDEX);
        List<String> order = names(NativeInstaller.resolve(index, new String[] { "nodejs-lts", "npm" }));
        assertEquals(List.of("libc++", "openssl", "nodejs-lts", "npm"), order);
    }

    @Test(expected = IOException.class)
    public void refusesAMissingRoot() throws IOException {
        NativeInstaller.resolve(NativeInstaller.parseIndex(INDEX), new String[] { "absent" });
    }

    @Test
    public void findsTheDataMemberOfADeb() throws IOException {
        ByteArrayOutputStream deb = new ByteArrayOutputStream();
        deb.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
        member(deb, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
        member(deb, "control.tar.xz", new byte[] { 1, 2, 3 });
        member(deb, "data.tar.xz", new byte[] { 9, 8, 7, 6 });
        ByteArrayInputStream in = new ByteArrayInputStream(deb.toByteArray());
        assertEquals(4, NativeInstaller.seekArMember(in, "data.tar.xz"));
        assertArrayEquals(new byte[] { 9, 8, 7, 6 }, Io.readN(in, 4));
    }

    private static void member(ByteArrayOutputStream out, String name, byte[] body) throws IOException {
        String header = String.format("%-16s%-12s%-6s%-6s%-8s%-10s`\n", name + "/", "0", "0", "0", "100644", body.length);
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        if (body.length % 2 == 1) out.write('\n');
    }

    @Test
    public void patchesAShellPathInsideAnElfWithoutMovingBytes() throws IOException {
        File file = Files.createTempFile("dsh-git", "").toFile();
        try {
            byte[] elf = ("\u007fELF....\0" + NativeInstaller.TERMUX_PREFIX + "/bin/sh\0tail").getBytes(StandardCharsets.UTF_8);
            Files.write(file.toPath(), elf);
            assertEquals(1, NativeInstaller.patchBinaryString(file, NativeInstaller.TERMUX_PREFIX + "/bin/sh", "/system/bin/sh"));
            byte[] patched = Files.readAllBytes(file.toPath());
            assertEquals(elf.length, patched.length);
            String text = new String(patched, StandardCharsets.UTF_8);
            assertEquals(true, text.contains("\0/system/bin/sh\0"));
            assertEquals(true, text.endsWith("\0tail"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void installsEveryRootFromTheBundleWithoutTheNetwork() throws IOException {
        StringBuilder index = new StringBuilder();
        Map<String, byte[]> debs = new java.util.HashMap<>();
        for (String root : NativeInstaller.ROOTS) {
            byte[] data = new TarExtractorTest.TarBuilder()
                    .file("./data/data/com.termux/files/usr/share/" + root + "/marker", 0644, root)
                    .build();
            ByteArrayOutputStream xz = new ByteArrayOutputStream();
            try (org.tukaani.xz.XZOutputStream out = new org.tukaani.xz.XZOutputStream(xz, new org.tukaani.xz.LZMA2Options())) {
                out.write(data);
            }
            ByteArrayOutputStream deb = new ByteArrayOutputStream();
            deb.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
            member(deb, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
            member(deb, "data.tar.xz", xz.toByteArray());
            byte[] bytes = deb.toByteArray();
            debs.put("debs/" + root + ".deb", bytes);
            index.append("Package: ").append(root).append("\nVersion: 1\nFilename: pool/").append(root)
                    .append(".deb\nSize: ").append(bytes.length).append("\nSHA256: ").append(sha256(bytes)).append("\n\n");
        }
        List<String> opened = new ArrayList<>();
        File prefix = Files.createTempDirectory("dsh-bundle-prefix").toFile();
        File cache = Files.createTempDirectory("dsh-bundle-cache").toFile();
        new NativeInstaller("aarch64", prefix, prefix.getPath(), cache, TarExtractorTest.NIO, new NativeInstaller.Output() {
            @Override
            public void line(String text) {
            }

            @Override
            public void progress(String step, int percent) {
                if (step.startsWith("Downloading")) throw new AssertionError("used the network: " + step);
            }
        }, name -> {
            opened.add(name);
            if (name.equals("Packages")) return new ByteArrayInputStream(index.toString().getBytes(StandardCharsets.UTF_8));
            byte[] bytes = debs.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }).install();
        for (String root : NativeInstaller.ROOTS) {
            assertEquals(root, new String(Files.readAllBytes(new File(prefix, "share/" + root + "/marker").toPath()), StandardCharsets.UTF_8));
        }
        assertEquals("Packages", opened.get(0));
        assertEquals(NativeInstaller.ROOTS.length + 1, opened.size());
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
