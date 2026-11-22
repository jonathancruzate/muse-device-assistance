package com.jonathancruzate.metamusedeviceassistance;
import android.app.Activity;
import android.os.Bundle;
/** Headless export, ported from Muse Voicemail ExportActivity. Started via am start by voicemail-borrow.sh. Refuses with MODULE_DISABLED status when the module is off. */
public class VoicemailExportActivity extends Activity {
    @Override protected void onCreate(Bundle b) { super.onCreate(b); new Thread(() -> { VoicemailExporter.run(this); finish(); }).start(); }
}
