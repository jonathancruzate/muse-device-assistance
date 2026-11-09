import com.jonathancruzate.metamusedeviceassistance.ShellAllowlist;
import java.util.*;
public class ShellAllowlistTest {
  static int fails=0;
  static void expect(boolean cond, String name){ System.out.println((cond?"PASS ":"FAIL ")+name); if(!cond) fails++; }
  public static void main(String[] a){
    expect(ShellAllowlist.check("battery", List.of()).allowed, "battery allowed");
    expect(ShellAllowlist.check("standby_get", List.of("com.arlosoft.macrodroid")).allowed, "standby_get pkg");
    expect(!ShellAllowlist.check("standby_get", List.of("com.x; rm -rf /")).allowed, "injection refused");
    expect(!ShellAllowlist.check("standby_get", List.of("com.x y")).allowed, "space arg refused");
    expect(!ShellAllowlist.check("settings_put", Arrays.asList("global","adb_wifi_enabled","1;reboot")).allowed, "settings_put injection refused");
    expect(ShellAllowlist.check("settings_put", Arrays.asList("global","adb_wifi_enabled","1")).allowed, "settings_put ok");
    expect(!ShellAllowlist.check("settings_put", Arrays.asList("bogus","k","1")).allowed, "bad ns refused");
    expect(!ShellAllowlist.check("logcat_tail", List.of("99999")).allowed, "logcat over-range refused");
    expect(ShellAllowlist.check("logcat_tail", List.of("500")).allowed, "logcat ok");
    expect(!ShellAllowlist.check("rm_rf", List.of()).allowed, "unknown template refused");
    expect(!ShellAllowlist.check("role_holders", List.of("CAMERA")).allowed, "bad role refused");
    expect(ShellAllowlist.check("voicemail_role_borrow", List.of("com.jonathancruzate.metamusevoicemail")).allowed, "borrow ok");
    ShellAllowlist.Gate g = ShellAllowlist.check("standby_set_active", List.of("com.tailscale.ipn"));
    expect(g.allowed && "am set-standby-bucket com.tailscale.ipn active".equals(ShellAllowlist.describe(g)), "describe argv");

    // ---- v1.5 extension pack templates ----
    ShellAllowlist.Gate la = ShellAllowlist.check("app_launch", List.of("com.google.android.apps.maps"));
    expect(la.allowed && "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.google.android.apps.maps".equals(ShellAllowlist.describe(la)), "app_launch maps argv");
    expect(!ShellAllowlist.check("app_launch", List.of("maps")).allowed, "app_launch non-pkg refused");
    expect(!ShellAllowlist.check("app_launch", List.of("com.x; rm -rf /")).allowed, "app_launch injection refused");
    expect(!ShellAllowlist.check("app_launch", List.of()).allowed, "app_launch no-arg refused");
    expect(!ShellAllowlist.check("app_launch", Arrays.asList("com.a.b","extra")).allowed, "app_launch 2-arg refused");

    ShellAllowlist.Gate nv = ShellAllowlist.check("nav_open", List.of("Astoria"));
    expect(nv.allowed && "am start -a android.intent.action.VIEW -d google.navigation:q=Astoria com.google.android.apps.maps".equals(ShellAllowlist.describe(nv)), "nav_open argv");
    ShellAllowlist.Gate nvc = ShellAllowlist.check("nav_open", List.of("Astoria,Queens"));
    expect(nvc.allowed && ShellAllowlist.describe(nvc).contains("google.navigation:q=Astoria,Queens"), "nav_open comma argv");
    expect(!ShellAllowlist.check("nav_open", List.of("New York")).allowed, "nav_open space refused");
    expect(!ShellAllowlist.check("nav_open", List.of("A&B")).allowed, "nav_open amp refused");
    expect(!ShellAllowlist.check("nav_open", List.of("q=evil")).allowed, "nav_open equals refused");
    expect(!ShellAllowlist.check("nav_open", List.of("a;b")).allowed, "nav_open semicolon refused");
    expect(!ShellAllowlist.check("nav_open", List.of()).allowed, "nav_open no-arg refused");

    expect(ShellAllowlist.check("media_play_pause", List.of()).allowed, "media_play_pause allowed");
    expect(ShellAllowlist.check("media_next", List.of()).allowed, "media_next allowed");
    expect(ShellAllowlist.check("media_previous", List.of()).allowed, "media_previous allowed");
    expect(!ShellAllowlist.check("media_next", List.of("2")).allowed, "media_next arg refused");
    ShellAllowlist.Gate pp = ShellAllowlist.check("media_play_pause", List.of());
    expect("input keyevent 85".equals(ShellAllowlist.describe(pp)), "media_play_pause argv");

    ShellAllowlist.Gate vr = ShellAllowlist.check("media_volume", List.of("raise"));
    expect(vr.allowed && "cmd media_session volume --stream 3 --adj raise".equals(ShellAllowlist.describe(vr)), "media_volume raise argv");
    expect(ShellAllowlist.check("media_volume", List.of("lower")).allowed, "media_volume lower allowed");
    expect(!ShellAllowlist.check("media_volume", List.of("up")).allowed, "media_volume bad direction refused");
    expect(ShellAllowlist.check("media_volume_set", List.of("0")).allowed, "volume_set 0 allowed");
    expect(ShellAllowlist.check("media_volume_set", List.of("12")).allowed, "volume_set 12 allowed");
    expect(ShellAllowlist.check("media_volume_set", List.of("15")).allowed, "volume_set 15 allowed");
    expect(!ShellAllowlist.check("media_volume_set", List.of("16")).allowed, "volume_set 16 refused");
    expect(!ShellAllowlist.check("media_volume_set", List.of("-1")).allowed, "volume_set -1 refused");
    expect(!ShellAllowlist.check("media_volume_set", List.of("7.5")).allowed, "volume_set float refused");
    expect(!ShellAllowlist.check("media_volume_set", List.of("12 && reboot")).allowed, "volume_set injection refused");
    expect(ShellAllowlist.check("media_volume_get", List.of()).allowed, "media_volume_get allowed");

    ShellAllowlist.Gate dOn = ShellAllowlist.check("dnd_on", List.of());
    expect(dOn.allowed && "cmd notification set_dnd on".equals(ShellAllowlist.describe(dOn)), "dnd_on argv");
    expect(ShellAllowlist.check("dnd_off", List.of()).allowed, "dnd_off allowed");
    expect(!ShellAllowlist.check("dnd_on", List.of("on")).allowed, "dnd_on arg refused");

    ShellAllowlist.Gate fg = ShellAllowlist.check("screen_foreground", List.of());
    expect(fg.allowed && "dumpsys window displays | grep -m 1 mCurrentFocus".equals(ShellAllowlist.describe(fg)), "screen_foreground argv");
    expect(!ShellAllowlist.check("screen_foreground", List.of("x")).allowed, "screen_foreground arg refused");

    // ---- v1.5 native gates (flashlight + voice module) ----
    expect(ShellAllowlist.isNativeTemplate("flashlight"), "flashlight is native template");
    expect(ShellAllowlist.isNativeTemplate("voice_mode"), "voice_mode is native template");
    expect(!ShellAllowlist.isNativeTemplate("battery"), "battery is not native template");
    ShellAllowlist.NativeGate tOn = ShellAllowlist.checkNative("flashlight", List.of("on"));
    expect(tOn.allowed && "torch_on".equals(tOn.op), "flashlight on -> torch_on");
    ShellAllowlist.NativeGate tOff = ShellAllowlist.checkNative("flashlight", List.of("off"));
    expect(tOff.allowed && "torch_off".equals(tOff.op), "flashlight off -> torch_off");
    expect(!ShellAllowlist.checkNative("flashlight", List.of("toggle")).allowed, "flashlight toggle refused");
    expect(!ShellAllowlist.checkNative("flashlight", List.of()).allowed, "flashlight no-arg refused");
    ShellAllowlist.NativeGate tSt = ShellAllowlist.checkNative("flashlight_status", List.of());
    expect(tSt.allowed && "torch_status".equals(tSt.op), "flashlight_status -> torch_status");
    expect(!ShellAllowlist.checkNative("flashlight_status", List.of("x")).allowed, "flashlight_status arg refused");
    ShellAllowlist.NativeGate vT = ShellAllowlist.checkNative("voice_mode", List.of("toggle"));
    expect(vT.allowed && "voice_toggle".equals(vT.op), "voice_mode toggle -> voice_toggle");
    expect(ShellAllowlist.checkNative("voice_mode", List.of("on")).allowed, "voice_mode on allowed");
    expect(ShellAllowlist.checkNative("voice_mode", List.of("off")).allowed, "voice_mode off allowed");
    expect(!ShellAllowlist.checkNative("voice_mode", List.of("now")).allowed, "voice_mode bad arg refused");

    // ---- v1.5.1 Galaxy Buds plugin routing gates ----
    expect(ShellAllowlist.isNativeTemplate("buds_status"), "buds_status is native template");
    expect(ShellAllowlist.isNativeTemplate("buds_noise"), "buds_noise is native template");
    ShellAllowlist.NativeGate bSt = ShellAllowlist.checkNative("buds_status", List.of());
    expect(bSt.allowed && "buds_status".equals(bSt.op), "buds_status -> buds_status");
    expect(!ShellAllowlist.checkNative("buds_status", List.of("x")).allowed, "buds_status arg refused");
    ShellAllowlist.NativeGate bUp = ShellAllowlist.checkNative("buds_volume", List.of("up"));
    expect(bUp.allowed && "buds_vol_up".equals(bUp.op), "buds_volume up -> buds_vol_up");
    ShellAllowlist.NativeGate bDn = ShellAllowlist.checkNative("buds_volume", List.of("down"));
    expect(bDn.allowed && "buds_vol_down".equals(bDn.op), "buds_volume down -> buds_vol_down");
    expect(!ShellAllowlist.checkNative("buds_volume", List.of("raise")).allowed, "buds_volume raise refused (vocabulary is up|down)");
    ShellAllowlist.NativeGate bSet = ShellAllowlist.checkNative("buds_volume_set", List.of("50"));
    expect(bSet.allowed && "buds_vol_set".equals(bSet.op) && "50".equals(bSet.arg), "buds_volume_set 50 op+arg");
    expect(ShellAllowlist.checkNative("buds_volume_set", List.of("0")).allowed, "buds_volume_set 0 allowed");
    expect(ShellAllowlist.checkNative("buds_volume_set", List.of("100")).allowed, "buds_volume_set 100 allowed");
    expect(!ShellAllowlist.checkNative("buds_volume_set", List.of("101")).allowed, "buds_volume_set 101 refused");
    expect(!ShellAllowlist.checkNative("buds_volume_set", List.of("-5")).allowed, "buds_volume_set -5 refused");
    ShellAllowlist.NativeGate bAnc = ShellAllowlist.checkNative("buds_noise", List.of("anc"));
    expect(bAnc.allowed && "buds_noise".equals(bAnc.op) && "anc".equals(bAnc.arg), "buds_noise anc op+arg");
    expect(ShellAllowlist.checkNative("buds_noise", List.of("ambient")).allowed, "buds_noise ambient allowed");
    expect(ShellAllowlist.checkNative("buds_noise", List.of("off")).allowed, "buds_noise off allowed");
    expect(ShellAllowlist.checkNative("buds_noise", List.of("adaptive")).allowed, "buds_noise adaptive allowed (plugin refuses per-model)");
    expect(!ShellAllowlist.checkNative("buds_noise", List.of("loud")).allowed, "buds_noise bad mode refused");
    expect(!ShellAllowlist.checkNative("buds_noise", List.of("anc; reboot")).allowed, "buds_noise injection refused");
    ShellAllowlist.NativeGate bAmb = ShellAllowlist.checkNative("buds_ambient_volume", List.of("3"));
    expect(bAmb.allowed && "buds_ambvol".equals(bAmb.op) && "3".equals(bAmb.arg), "buds_ambient_volume 3 op+arg");
    expect(!ShellAllowlist.checkNative("buds_ambient_volume", List.of("0")).allowed, "buds_ambient_volume 0 refused");
    expect(!ShellAllowlist.checkNative("buds_ambient_volume", List.of("6")).allowed, "buds_ambient_volume 6 refused");
    ShellAllowlist.NativeGate bEq = ShellAllowlist.checkNative("buds_eq", List.of("bass_boost"));
    expect(bEq.allowed && "buds_eq".equals(bEq.op) && "bass_boost".equals(bEq.arg), "buds_eq bass_boost op+arg");
    expect(ShellAllowlist.checkNative("buds_eq", List.of("off")).allowed, "buds_eq off allowed");
    expect(!ShellAllowlist.checkNative("buds_eq", List.of("bass-boost")).allowed, "buds_eq hyphen form refused (underscore vocabulary)");
    expect(!ShellAllowlist.checkNative("buds_eq", List.of("loud")).allowed, "buds_eq bad preset refused");
    ShellAllowlist.NativeGate bLock = ShellAllowlist.checkNative("buds_touch_lock", List.of("on"));
    expect(bLock.allowed && "buds_lock".equals(bLock.op) && "on".equals(bLock.arg), "buds_touch_lock on op+arg");
    expect(!ShellAllowlist.checkNative("buds_touch_lock", List.of("yes")).allowed, "buds_touch_lock yes refused");

    System.out.println(fails==0?"ALL_PASS":"FAILURES:"+fails);
  }
}
