package ideabridge;

import com.intellij.ide.AppLifecycleListener;

/**
 * Starts the bridge as soon as the IDE is up.
 *
 * {@code appStarted()} fires before any project is opened, so the bridge is already listening
 * while the user is still on the welcome screen -- which matters because the first thing an
 * agent usually needs is to find out what is open.
 */
public final class BridgeAppListener implements AppLifecycleListener {

    @Override
    public void appStarted() {
        BridgeServer.startIfNeeded("appStarted");
    }
}