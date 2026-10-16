package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

/**
 * Exercises the host device-test transport without changing settings or account data. With `-e probe
 * message_log_render` it runs MessageLogRenderProbe instead.
 */
public final class BuildTransportProbe extends Instrumentation {
    private String probe;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        probe = arguments == null ? null : arguments.getString("probe");
        start();
    }

    @Override public void onStart() {
        if ("message_log_render".equals(probe)) {
            Bundle result = new Bundle();
            try {
                result.putString("stream", "\n" + new MessageLogRenderProbe(this).run() + "\nOK (1 probe)\n");
            } catch (Exception | AssertionError error) {
                result.putString("stream", "\nFAILURES!!!\n" + error + "\n");
            }
            finish(Activity.RESULT_OK, result);
            return;
        }
        Bundle test = new Bundle();
        test.putString("id", "InstrumentationTestRunner");
        test.putString("class", getClass().getName());
        test.putString("test", "testBuildLibrariesStayOutsideTheExtension");
        test.putInt("numtests", 1);
        test.putInt("current", 1);
        sendStatus(1, test);
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            if (!"app.hushmessenger.extension".equals(target.getPackageName())) {
                throw new AssertionError("Run this probe against the UI preview only");
            }
            PackageInfo installed = target.getPackageManager().getPackageInfo(target.getPackageName(), 0);
            if (!BuildConfig.VERSION_NAME.equals(installed.versionName)) {
                throw new AssertionError("Installed preview version differs from its compiled extension");
            }
            for (String name : new String[] {
                    "io.netty.channel.Channel", "io.grpc.ServerBuilder",
                    "org.apache.http.client.HttpClient", "org.apache.commons.lang3.ClassUtils"}) {
                try {
                    Class.forName(name, false, target.getClassLoader());
                } catch (ClassNotFoundException expected) {
                    continue;
                }
                throw new AssertionError("Host test library was bundled: " + name);
            }
            sendStatus(0, test);
            result.putString("stream", "\nOK (1 test)\n");
        } catch (Exception | AssertionError error) {
            test.putString("stack", error.toString());
            sendStatus(-2, test);
            result.putString("stream", "\nFAILURES!!!\n" + error + "\n");
        }
        finish(Activity.RESULT_OK, result);
    }
}
