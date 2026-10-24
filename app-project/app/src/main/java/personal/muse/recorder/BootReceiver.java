package personal.muse.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Optionally resumes recording after a reboot, if the user enabled it. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean shouldResume = Prefs.autoResume(context)
                && Prefs.get(context).getBoolean(Prefs.KEY_WAS_RECORDING, false);
        boolean hasMic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (shouldResume && hasMic && Build.VERSION.SDK_INT < Build.VERSION_CODES.S + 2) {
            // Note: Android 12+ restricts starting microphone foreground services
            // from the background; if start fails the user can reopen the app.
            try {
                RecordingService.start(context);
            } catch (Exception ignored) {
                // User can resume manually from the app.
            }
        }
    }
}
