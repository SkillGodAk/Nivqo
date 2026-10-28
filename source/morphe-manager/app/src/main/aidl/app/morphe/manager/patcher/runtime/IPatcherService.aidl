/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.runtime;

import app.morphe.manager.patcher.runtime.HostStarted;
import app.morphe.manager.patcher.runtime.jvm.JvmLaunch;

// Starts the host of a run, which reads HostCommands from commands and writes HostEvents to events.
// Throws IllegalStateException if it could not
interface IPatcherService {
    // In the JVM the app embeds
    HostStarted startJvm(in JvmLaunch launch, in ParcelFileDescriptor commands, in ParcelFileDescriptor events);

    // On the ART of the service's own process
    HostStarted startArt(in ParcelFileDescriptor commands, in ParcelFileDescriptor events);
}
