package se.vidstige.jadb.managers;

import se.vidstige.jadb.JadbDevice;
import se.vidstige.jadb.JadbException;
import se.vidstige.jadb.RemoteFile;
import se.vidstige.jadb.StreamHelper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.*;

/**
 * Java interface to package manager. Launches package manager through jadb
 */
public class PackageManager {
    private final JadbDevice device;

    public PackageManager(JadbDevice device) {
        this.device = device;
    }

    public List<Package> getPackages() throws IOException, JadbException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(device.executeShell("pm", "list", "packages"), StandardCharsets.UTF_8))) {
            ArrayList<Package> result = new ArrayList<>();
            String line;
            while ((line = input.readLine()) != null) {
                final String prefix = "package:";
                if (line.startsWith(prefix)) {
                    result.add(new Package(line.substring(prefix.length())));
                }
            }
            return result;
        }
    }

    private String getErrorMessage(String operation, String errorMessage) {
        return "pm " + operation + ": " + errorMessage;
    }

    private void verifyOperation(String operation, String result) throws JadbException {
        if (!result.contains("Success")) throw new JadbException(getErrorMessage(operation, result));
    }

    private void remove(RemoteFile file) throws IOException, JadbException {
        try (InputStream s = device.executeShell("rm", "-f", file.getPath())) {
            StreamHelper.readAll(s, StandardCharsets.UTF_8);
        }
    }

    private String runPmAndVerify(String operation, List<String> arguments) throws IOException, JadbException {
        String[] args = Stream.concat(Stream.of(operation), arguments.stream()).toArray(String[]::new);

        try (InputStream s = device.executeShell("pm", args)) {
            String result = StreamHelper.readAll(s, StandardCharsets.UTF_8);
            verifyOperation(operation, result);
            return result;
        }
    }

    private void install(File apkFile, List<String> extraArguments) throws IOException, JadbException {
        RemoteFile remote = new RemoteFile(getTempDir() + apkFile.getName());
        device.push(apkFile, remote);

        try {
            List<String> args = Stream.concat(extraArguments.stream(), Stream.of(remote.getPath())).toList();
            runPmAndVerify("install", args);
        } finally {
            remove(remote);
        }
    }

    public void install(File apkFile) throws IOException, JadbException {
        install(apkFile, new ArrayList<String>(0));
    }

    private void install(List<File> apkFiles, List<String> extraArguments) throws IOException, JadbException {
        List<RemoteFile> remotes = new ArrayList<>(apkFiles.size());

        // push all apk files to device
        String dir = getTempDir();
        for (File apk : apkFiles) {
            RemoteFile remote = new RemoteFile(dir + apk.getName());
            remotes.add(remote);
            device.push(apk, remote);
        }

        String sessionId = null;
        try {
            // create a new install session
            sessionId = installCreate(extraArguments);

            // write all apk files
            for (RemoteFile remote : remotes) {
                installWrite(sessionId, remote);
            }

            // commit install session
            installCommit(sessionId);

        } catch (Exception e) {
            if (sessionId != null)
                installAbandon(sessionId);

            throw e;
        } finally {
            // remove remote files
            for (RemoteFile remote : remotes) {
                remove(remote);
            }
        }
    }

    public void install(List<File> apkFiles) throws IOException, JadbException {
        install(apkFiles, new ArrayList<String>(0));
    }

    private String installCreate(List<String> extraArguments) throws JadbException, IOException {
        String result = runPmAndVerify("install-create", extraArguments);

        // return session id
        int start = result.indexOf('[') + 1;
        int end = result.indexOf(']');
        return result.substring(start, end);
    }

    private void installWrite(String sessionId, RemoteFile remote) throws IOException, JadbException {
        List<String> args = List.of(sessionId, remote.getName(), remote.getPath());
        runPmAndVerify("install-write", args);
    }

    private void installCommit(String sessionId) throws IOException, JadbException {
        runPmAndVerify("install-commit", List.of(sessionId));
    }

    private void installAbandon(String sessionId) throws IOException, JadbException {
        runPmAndVerify("install-abandon", List.of(sessionId));
    }

    public void installWithOptions(File apkFile, List<? extends InstallOption> options) throws IOException, JadbException {
        install(apkFile, optionsAsStrings(options));
    }

    public void installWithOptions(List<File> apkFiles, List<? extends InstallOption> options) throws IOException, JadbException {
        install(apkFiles, optionsAsStrings(options));
    }

    public void forceInstall(File apkFile) throws IOException, JadbException {
        installWithOptions(apkFile, Collections.singletonList(REINSTALL_KEEPING_DATA));
    }

    public void uninstall(Package pkg) throws IOException, JadbException {
        runPmAndVerify("uninstall", List.of(pkg.toString()));
    }

    public void launch(Package pkg) throws IOException, JadbException {
        InputStream s = device.executeShell("monkey", "-p", pkg.toString(), "-c", "android.intent.category.LAUNCHER", "1");
        s.close();
    }

    private static String getTempDir() {
        return "/data/local/tmp/" + UUID.randomUUID() + "/";
    }

    //<editor-fold desc="InstallOption">
    public static class InstallOption {
        private final StringBuilder stringBuilder = new StringBuilder();

        InstallOption(String ... varargs) {
            String suffix = "";
            for(String str: varargs) {
                stringBuilder.append(suffix).append(str);
                suffix = " ";
            }
        }

        private String getStringRepresentation() {
            return stringBuilder.toString();
        }
    }

    private static List<String> optionsAsStrings(List<? extends InstallOption> options) {
        List<String> optionsAsStr = new ArrayList<>(options.size());

        for(InstallOption installOption : options) {
            optionsAsStr.add(installOption.getStringRepresentation());
        }

        return optionsAsStr;
    }

    public static final InstallOption WITH_FORWARD_LOCK = new InstallOption("-l");

    public static final InstallOption REINSTALL_KEEPING_DATA =
            new InstallOption("-r");

    public static final InstallOption ALLOW_TEST_APK =
            new InstallOption("-t");

    @SuppressWarnings("squid:S00100")
    public static InstallOption WITH_INSTALLER_PACKAGE_NAME(String name)
    {
        return new InstallOption("-t", name);
    }

    @SuppressWarnings("squid:S00100")
    public static InstallOption ON_SHARED_MASS_STORAGE(String name) {
        return new InstallOption("-s", name);
    }

    @SuppressWarnings("squid:S00100")
    public static InstallOption ON_INTERNAL_SYSTEM_MEMORY(String name) {
        return new InstallOption("-f", name);
    }

    public static final InstallOption ALLOW_VERSION_DOWNGRADE =
            new InstallOption("-d");

    /**
     * This option is supported only from Android 6.X+
     */
    public static final InstallOption GRANT_ALL_PERMISSIONS = new InstallOption("-g");

    /**
     * This option is supported only from Android 14.X+
     */
    public static final InstallOption UPDATE_OWNERSHIP = new InstallOption("--update-ownership");
    //</editor-fold>
}
