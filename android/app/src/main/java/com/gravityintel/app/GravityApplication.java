package com.gravityintel.app;

import android.app.Application;
import android.os.Process;

/** Installs a process-wide uncaught-exception handler before any Activity or
  * Service runs. An Application subclass, not something wired from
  * MainActivity.onCreate(), on purpose: MotionMonitorService is START_STICKY, so
  * the OS can restart it directly in a fresh process without MainActivity ever
  * running first - installing the handler only from the Activity would leave
  * exactly that path uncovered. */
public class GravityApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                DiagnosticLog.log(getApplicationContext(), "CRASH",
                        "Uncaught exception on thread \"" + thread.getName() + "\": "
                                + android.util.Log.getStackTraceString(throwable));
            } catch (Throwable loggingFailure) {
                // Never let a failure in the crash handler itself mask the real
                // crash - DiagnosticLog.log() already has its own Logcat fallback,
                // but this exists in case something more fundamental goes wrong.
            }
            // Still crash normally afterward - chain to whatever handler Android
            // (or a crash-reporting library, if one's ever added) already
            // installed, so this never silently swallows a crash the OS or Play
            // Console would otherwise have reported.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }
}
