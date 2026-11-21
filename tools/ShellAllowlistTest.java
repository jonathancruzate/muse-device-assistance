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

    // ---- v1.13: wear_install_face (dedicated validator) ----
    // Its slots are typed values (label / https URL / sha256), never
    // shell text, so the URL legitimately carries characters the
    // shared hygiene() bans for shell args ('?', '&', '=').
    expect(ShellAllowlist.isNativeTemplate("wear_install_face"), "wear_install_face is native template");
    String wSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    ShellAllowlist.NativeGate wOk = ShellAllowlist.checkNative("wear_install_face",
            List.of("swiss-army-v6.2", "https://muse.example.com/faces/swiss.apk?sig=abc&x=1", wSha));
    expect(wOk.allowed && "wear_install".equals(wOk.op)
            && wOk.args != null && wOk.args.length == 3
            && "swiss-army-v6.2".equals(wOk.args[0])
            && wSha.equals(wOk.args[2]), "wear_install_face valid job allowed (op+args)");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "https://h.example/a.apk", "xyz")).allowed,
            "wear_install_face bad sha refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "https://h.example/a.apk", wSha.toUpperCase())).allowed,
            "wear_install_face uppercase sha refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "http://h.example/a.apk", wSha)).allowed,
            "wear_install_face http refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("Swiss Army", "https://h.example/a.apk", wSha)).allowed,
            "wear_install_face label with space refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "https://h.example/a.apk")).allowed,
            "wear_install_face 2 args refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "https://h.example/a.apk", wSha, "extra")).allowed,
            "wear_install_face 4 args refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "https://user@h.example/a.apk", wSha)).allowed,
            "wear_install_face userinfo refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "https://h.example/a b.apk", wSha)).allowed,
            "wear_install_face url with space refused");
    expect(!ShellAllowlist.checkNative("wear_install_face",
            List.of("label", "https://h.example/a.apk\";reboot", wSha)).allowed,
            "wear_install_face url injection refused");

    // v1.21 self_update gate: <url> <sha256>; https anywhere, http
    // ONLY to the box tailnet host; args ride NativeGate.args.
    ShellAllowlist.NativeGate uOk = ShellAllowlist.checkNative("self_update",
            List.of("https://h.example/da.apk", wSha));
    expect(uOk.allowed && "self_update".equals(uOk.op)
            && uOk.args != null && uOk.args.length == 2
            && "https://h.example/da.apk".equals(uOk.args[0])
            && wSha.equals(uOk.args[1]), "self_update https allowed (op+args)");
    ShellAllowlist.NativeGate uBox = ShellAllowlist.checkNative("self_update",
            List.of("http://100.85.53.70:8795/da.apk", wSha));
    expect(uBox.allowed && "self_update".equals(uBox.op),
            "self_update http to box allowed");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("http://h.example/da.apk", wSha)).allowed,
            "self_update http elsewhere refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("http://100.85.53.71/da.apk", wSha)).allowed,
            "self_update http to box-lookalike refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("https://h.example/da.apk", "xyz")).allowed,
            "self_update bad sha refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("https://h.example/da.apk", wSha.toUpperCase())).allowed,
            "self_update uppercase sha refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("https://h.example/da.apk")).allowed,
            "self_update 1 arg refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("https://h.example/da.apk", wSha, "extra")).allowed,
            "self_update 3 args refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("https://user@h.example/da.apk", wSha)).allowed,
            "self_update userinfo refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("https://h.example/a b.apk", wSha)).allowed,
            "self_update url with space refused");
    expect(!ShellAllowlist.checkNative("self_update",
            List.of("https://h.example/da.apk\";reboot", wSha)).allowed,
            "self_update url injection refused");
    expect(ShellAllowlist.isNativeTemplate("self_update"),
            "self_update is a native template");

    // ---- v1.24: files_* gates (dedicated validator, FilePlan tables) ----
    expect(ShellAllowlist.isNativeTemplate("files_list"), "files_list is native template");
    expect(ShellAllowlist.isNativeTemplate("files_organize"), "files_organize is native template");
    ShellAllowlist.NativeGate fAcc = ShellAllowlist.checkNative("files_access", List.of());
    expect(fAcc.allowed && "files_access".equals(fAcc.op), "files_access no args");
    expect(!ShellAllowlist.checkNative("files_access", List.of("x")).allowed, "files_access arg refused");
    expect(ShellAllowlist.checkNative("files_summary", List.of()).allowed, "files_summary no args");
    ShellAllowlist.NativeGate fList = ShellAllowlist.checkNative("files_list", List.of("download"));
    expect(fList.allowed && fList.args != null && fList.args.length == 2
            && "download".equals(fList.args[0]) && "".equals(fList.args[1]), "files_list folder only");
    ShellAllowlist.NativeGate fList2 = ShellAllowlist.checkNative("files_list", List.of("download", "40"));
    expect(fList2.allowed && "40".equals(fList2.args[1]), "files_list with offset");
    expect(!ShellAllowlist.checkNative("files_list", List.of("download", "-1")).allowed, "files_list negative offset refused");
    expect(!ShellAllowlist.checkNative("files_list", List.of("download", "x")).allowed, "files_list bad offset refused");
    expect(!ShellAllowlist.checkNative("files_list", List.of("/storage/emulated/0/Download")).allowed, "files_list path refused (token only)");
    expect(!ShellAllowlist.checkNative("files_list", List.of("secrets")).allowed, "files_list bad folder refused");
    expect(!ShellAllowlist.checkNative("files_list", List.of()).allowed, "files_list no args refused");
    // Names ride as word args joined with single spaces (typed values).
    ShellAllowlist.NativeGate fHash = ShellAllowlist.checkNative("files_hash",
            Arrays.asList("download", "My", "Report", "2026.pdf"));
    expect(fHash.allowed && fHash.args != null && "My Report 2026.pdf".equals(fHash.args[1]), "files_hash name joined");
    expect(ShellAllowlist.checkNative("files_hash",
            List.of("download", "plain.pdf")).allowed, "files_hash one-word name");
    expect(!ShellAllowlist.checkNative("files_hash",
            List.of("download", "../secret.txt")).allowed, "files_hash parent ref refused");
    expect(!ShellAllowlist.checkNative("files_hash",
            Arrays.asList("download", "a/b.txt")).allowed, "files_hash separator refused");
    expect(!ShellAllowlist.checkNative("files_hash",
            List.of("download", ".hidden")).allowed, "files_hash dotfile refused");
    expect(!ShellAllowlist.checkNative("files_hash",
            List.of("download")).allowed, "files_hash no name refused");
    ShellAllowlist.NativeGate fTrash = ShellAllowlist.checkNative("files_trash",
            Arrays.asList("download", "old", "installer.apk"));
    expect(fTrash.allowed && "old installer.apk".equals(fTrash.args[1]), "files_trash name joined");
    ShellAllowlist.NativeGate fMove = ShellAllowlist.checkNative("files_move",
            Arrays.asList("download", "documents", "My", "File.pdf"));
    expect(fMove.allowed && fMove.args != null && fMove.args.length == 3
            && "documents".equals(fMove.args[1]) && "My File.pdf".equals(fMove.args[2]), "files_move slots");
    expect(!ShellAllowlist.checkNative("files_move",
            Arrays.asList("download", "download", "x.pdf")).allowed, "files_move same folder refused");
    expect(!ShellAllowlist.checkNative("files_move",
            Arrays.asList("download", "trash", "x.pdf")).allowed, "files_move to trash refused (use files_trash)");
    expect(!ShellAllowlist.checkNative("files_move",
            Arrays.asList("download", "documents")).allowed, "files_move no name refused");
    expect(ShellAllowlist.checkNative("files_organize", List.of("download")).allowed, "files_organize download");
    expect(!ShellAllowlist.checkNative("files_organize", List.of()).allowed, "files_organize no args refused");
    expect(!ShellAllowlist.checkNative("files_rm", List.of("download", "x.pdf")).allowed, "files_rm unknown refused (no delete template exists)");
    // FilePlan filing rules, exercised directly.
    expect("Images".equals(com.jonathancruzate.metamusedeviceassistance.FilePlan.categoryFor("photo.JPG")), "category jpg -> Images");
    expect("Documents".equals(com.jonathancruzate.metamusedeviceassistance.FilePlan.categoryFor("report.pdf")), "category pdf -> Documents");
    expect("APKs".equals(com.jonathancruzate.metamusedeviceassistance.FilePlan.categoryFor("app.apk")), "category apk -> APKs");
    expect(com.jonathancruzate.metamusedeviceassistance.FilePlan.categoryFor("movie.crdownload") == null, "category crdownload -> none");
    expect(com.jonathancruzate.metamusedeviceassistance.FilePlan.categoryFor("mystery.xyz") == null, "category unknown -> none");
    expect(com.jonathancruzate.metamusedeviceassistance.FilePlan.categoryFor(".hidden.pdf") == null, "category dotfile -> none");
    expect(com.jonathancruzate.metamusedeviceassistance.FilePlan.folderOk("download"), "FilePlan download token");
    expect(!com.jonathancruzate.metamusedeviceassistance.FilePlan.folderOk("android"), "FilePlan android token refused");
    expect(com.jonathancruzate.metamusedeviceassistance.FilePlan.nameOk("a b.pdf"), "FilePlan name with space ok");
    expect(!com.jonathancruzate.metamusedeviceassistance.FilePlan.nameOk(".."), "FilePlan dotdot refused");

    // ---- v1.25: screen_* gates ----
    expect(ShellAllowlist.isNativeTemplate("screen_shot"), "screen_shot is native template");
    expect(ShellAllowlist.isNativeTemplate("screen_find_tap"), "screen_find_tap is native template");
    expect(!ShellAllowlist.isNativeTemplate("screen_record"), "screen_record is NOT a template");
    expect(ShellAllowlist.checkNative("screen_status", List.of()).allowed, "screen_status no args");
    expect(!ShellAllowlist.checkNative("screen_status", List.of("x")).allowed, "screen_status arg refused");
    expect(ShellAllowlist.checkNative("screen_shot", List.of()).allowed, "screen_shot no args");
    expect(ShellAllowlist.checkNative("screen_start", List.of()).allowed, "screen_start no args");
    expect(ShellAllowlist.checkNative("screen_stop", List.of()).allowed, "screen_stop no args");
    expect(!ShellAllowlist.checkNative("screen_shot", List.of("now")).allowed, "screen_shot arg refused");

    ShellAllowlist.NativeGate tap = ShellAllowlist.checkNative("screen_tap", List.of("500", "250"));
    expect(tap.allowed && tap.args != null && tap.args.length == 2
            && "500".equals(tap.args[0]) && "250".equals(tap.args[1]), "screen_tap coords");
    expect(ShellAllowlist.checkNative("screen_tap", List.of("0", "1000")).allowed, "screen_tap corners ok");
    expect(!ShellAllowlist.checkNative("screen_tap", List.of("1001", "0")).allowed, "screen_tap >1000 refused");
    expect(!ShellAllowlist.checkNative("screen_tap", List.of("-1", "0")).allowed, "screen_tap negative refused");
    expect(!ShellAllowlist.checkNative("screen_tap", List.of("50.5", "0")).allowed, "screen_tap decimal refused");
    expect(!ShellAllowlist.checkNative("screen_tap", List.of("500")).allowed, "screen_tap one arg refused");
    expect(!ShellAllowlist.checkNative("screen_tap", List.of("500", "250", "9")).allowed, "screen_tap extra arg refused");

    ShellAllowlist.NativeGate sw = ShellAllowlist.checkNative("screen_swipe",
            List.of("500", "800", "500", "200", "300"));
    expect(sw.allowed && sw.args != null && sw.args.length == 5 && "300".equals(sw.args[4]), "screen_swipe full");
    expect(ShellAllowlist.checkNative("screen_swipe", List.of("0", "0", "1000", "1000", "2000")).allowed, "screen_swipe max ms ok");
    expect(!ShellAllowlist.checkNative("screen_swipe", List.of("0", "0", "1000", "1000", "2001")).allowed, "screen_swipe ms>2000 refused");
    expect(!ShellAllowlist.checkNative("screen_swipe", List.of("0", "0", "1000", "1000", "49")).allowed, "screen_swipe ms<50 refused");
    expect(!ShellAllowlist.checkNative("screen_swipe", List.of("0", "0", "1000", "1000")).allowed, "screen_swipe missing ms refused");

    expect(ShellAllowlist.checkNative("screen_key", List.of("back")).allowed, "screen_key back");
    expect(ShellAllowlist.checkNative("screen_key", List.of("quick_settings")).allowed, "screen_key quick_settings");
    expect(ShellAllowlist.checkNative("screen_key", List.of("lock")).allowed, "screen_key lock");
    expect(!ShellAllowlist.checkNative("screen_key", List.of("power")).allowed, "screen_key power refused");
    expect(!ShellAllowlist.checkNative("screen_key", List.of("BACK")).allowed, "screen_key case refused");
    expect(!ShellAllowlist.checkNative("screen_key", List.of()).allowed, "screen_key no arg refused");

    ShellAllowlist.NativeGate txt = ShellAllowlist.checkNative("screen_text",
            Arrays.asList("hello", "there", "world"));
    expect(txt.allowed && "hello there world".equals(txt.arg), "screen_text words joined");
    expect(ShellAllowlist.checkNative("screen_text",
            List.of("it's", "5:30!")).allowed, "screen_text punctuation ok");
    expect(!ShellAllowlist.checkNative("screen_text", List.of()).allowed, "screen_text empty refused");
    char[] bigChars = new char[257];
    java.util.Arrays.fill(bigChars, 'a');
    expect(!ShellAllowlist.checkNative("screen_text",
            List.of(new String(bigChars))).allowed, "screen_text arg over 256 refused");
    List<String> manyWords = new java.util.ArrayList<>();
    for (int i = 0; i < 30; i++) {
      StringBuilder w = new StringBuilder();
      for (int j = 0; j < 40; j++) w.append('b');
      manyWords.add(w.toString());
    }
    expect(!ShellAllowlist.checkNative("screen_text", manyWords).allowed, "screen_text joined over 1000 refused");

    ShellAllowlist.NativeGate find = ShellAllowlist.checkNative("screen_find_tap",
            Arrays.asList("Add", "to", "cart"));
    expect(find.allowed && "Add to cart".equals(find.arg), "screen_find_tap label joined");
    expect(!ShellAllowlist.checkNative("screen_find_tap", List.of()).allowed, "screen_find_tap empty refused");

    // ---- v1.26 Duo companion templates ----
    ShellAllowlist.Gate dpOn = ShellAllowlist.check("duo_panels_on", List.of());
    expect(dpOn.allowed && "setprop persist.debug.phh.duo.panels true".equals(ShellAllowlist.describe(dpOn)), "duo_panels_on argv");
    ShellAllowlist.Gate dpGet = ShellAllowlist.check("duo_panels_get", List.of());
    expect(dpGet.allowed && "getprop persist.debug.phh.duo.panels".equals(ShellAllowlist.describe(dpGet)), "duo_panels_get argv");
    expect(!ShellAllowlist.check("duo_panels_on", List.of("false")).allowed, "duo_panels_on arg refused (fixed template)");
    expect(!ShellAllowlist.check("duo_panels_get", List.of("ro.build.id")).allowed, "duo_panels_get arg refused (no property smuggling)");

    // ---- v1.29 Duo debug mode templates ----
    // Native (on-device) fixed ops, same discipline as the panels
    // pair: the template name is the whole instruction, zero args,
    // and they have NO shell form at all.
    expect(ShellAllowlist.isNativeTemplate("duo_debug_on"), "duo_debug_on is native (v1.29)");
    expect(ShellAllowlist.isNativeTemplate("duo_debug_off"), "duo_debug_off is native (v1.29)");
    expect(ShellAllowlist.isNativeTemplate("duo_debug_get"), "duo_debug_get is native (v1.29)");
    ShellAllowlist.NativeGate ddOn = ShellAllowlist.checkNative("duo_debug_on", List.of());
    expect(ddOn.allowed && "duo_debug_on".equals(ddOn.op), "duo_debug_on gate");
    ShellAllowlist.NativeGate ddOff = ShellAllowlist.checkNative("duo_debug_off", List.of());
    expect(ddOff.allowed && "duo_debug_off".equals(ddOff.op), "duo_debug_off gate");
    ShellAllowlist.NativeGate ddGet = ShellAllowlist.checkNative("duo_debug_get", List.of());
    expect(ddGet.allowed && "duo_debug_get".equals(ddGet.op), "duo_debug_get gate");
    expect(!ShellAllowlist.checkNative("duo_debug_on", List.of("true")).allowed, "duo_debug_on arg refused (fixed template)");
    expect(!ShellAllowlist.checkNative("duo_debug_off", List.of("now")).allowed, "duo_debug_off arg refused (fixed template)");
    expect(!ShellAllowlist.checkNative("duo_debug_get", List.of("duo_debug")).allowed, "duo_debug_get arg refused (no key smuggling)");
    expect(!ShellAllowlist.check("duo_debug_on", List.of()).allowed, "duo_debug_on has no shell form");
    expect(!ShellAllowlist.check("duo_debug_get", List.of()).allowed, "duo_debug_get has no shell form");

    // ---- v1.27 nativized intent-shaped templates ----
    // The templates are native now (isNativeTemplate routes them),
    // gated by checkNative with the SAME validators as the shell
    // forms — which remain in check() as the BAL fallback.
    expect(ShellAllowlist.isNativeTemplate("app_launch"), "app_launch is native (v1.27)");
    expect(ShellAllowlist.isNativeTemplate("nav_open"), "nav_open is native (v1.27)");
    expect(ShellAllowlist.isNativeTemplate("media_play_pause"), "media_play_pause is native (v1.27)");
    expect(ShellAllowlist.isNativeTemplate("media_volume"), "media_volume is native (v1.27)");
    expect(ShellAllowlist.isNativeTemplate("media_volume_set"), "media_volume_set is native (v1.27)");
    expect(ShellAllowlist.isNativeTemplate("media_volume_get"), "media_volume_get is native (v1.27)");
    expect(ShellAllowlist.isNativeTemplate("screen_foreground"), "screen_foreground is native (v1.27)");
    expect(ShellAllowlist.isNativeTemplate("dnd_on"), "dnd_on native-first v1.27 (grant-gated at runtime)");
    expect(!ShellAllowlist.isNativeTemplate("battery"), "battery stays shell (diagnostics)");

    ShellAllowlist.NativeGate nApp = ShellAllowlist.checkNative("app_launch",
            List.of("com.google.android.apps.maps"));
    expect(nApp.allowed && "app_launch".equals(nApp.op)
            && "com.google.android.apps.maps".equals(nApp.arg), "native app_launch gate");
    expect(!ShellAllowlist.checkNative("app_launch", List.of("maps")).allowed, "native app_launch non-pkg refused");
    expect(!ShellAllowlist.checkNative("app_launch", List.of("com.x; rm -rf /")).allowed, "native app_launch injection refused");
    expect(!ShellAllowlist.checkNative("app_launch", List.of()).allowed, "native app_launch no-arg refused");

    ShellAllowlist.NativeGate nNav = ShellAllowlist.checkNative("nav_open", List.of("Astoria,Queens"));
    expect(nNav.allowed && "nav_open".equals(nNav.op)
            && "Astoria,Queens".equals(nNav.arg), "native nav_open gate");
    expect(!ShellAllowlist.checkNative("nav_open", List.of("New York")).allowed, "native nav_open space refused");
    expect(!ShellAllowlist.checkNative("nav_open", List.of("A&B")).allowed, "native nav_open amp refused");
    expect(!ShellAllowlist.checkNative("nav_open", List.of()).allowed, "native nav_open no-arg refused");

    expect(ShellAllowlist.checkNative("media_play_pause", List.of()).allowed, "native media_play_pause allowed");
    expect(!ShellAllowlist.checkNative("media_play_pause", List.of("x")).allowed, "native media_play_pause arg refused");
    expect("media_vol_raise".equals(ShellAllowlist.checkNative("media_volume", List.of("raise")).op), "native media_volume raise op");
    expect("media_vol_lower".equals(ShellAllowlist.checkNative("media_volume", List.of("lower")).op), "native media_volume lower op");
    expect(!ShellAllowlist.checkNative("media_volume", List.of("up")).allowed, "native media_volume bad dir refused");
    ShellAllowlist.NativeGate nVolSet = ShellAllowlist.checkNative("media_volume_set", List.of("7"));
    expect(nVolSet.allowed && "media_vol_set".equals(nVolSet.op) && "7".equals(nVolSet.arg), "native media_volume_set gate");
    expect(!ShellAllowlist.checkNative("media_volume_set", List.of("16")).allowed, "native media_volume_set 16 refused");
    expect(ShellAllowlist.checkNative("media_volume_get", List.of()).allowed, "native media_volume_get allowed");
    expect(ShellAllowlist.checkNative("screen_foreground", List.of()).allowed, "native screen_foreground allowed");
    expect(!ShellAllowlist.checkNative("screen_foreground", List.of("x")).allowed, "native screen_foreground arg refused");
    // v1.27 dnd: native-first (NotificationManager); the shell
    // form stays as the policy-access fallback.
    expect(ShellAllowlist.isNativeTemplate("dnd_on"), "dnd_on is native template");
    expect(ShellAllowlist.isNativeTemplate("dnd_off"), "dnd_off is native template");
    expect(ShellAllowlist.isNativeTemplate("nav_open"), "nav_open is native template");
    expect(ShellAllowlist.isNativeTemplate("app_launch"), "app_launch is native template");
    ShellAllowlist.NativeGate nDndOn = ShellAllowlist.checkNative("dnd_on", List.of());
    expect(nDndOn.allowed && "dnd_on".equals(nDndOn.op), "native dnd_on gate");
    ShellAllowlist.NativeGate nDndOff = ShellAllowlist.checkNative("dnd_off", List.of());
    expect(nDndOff.allowed && "dnd_off".equals(nDndOff.op), "native dnd_off gate");
    expect(!ShellAllowlist.checkNative("dnd_on", List.of("x")).allowed, "native dnd_on arg refused");
    expect(!ShellAllowlist.checkNative("dnd_off", List.of("x")).allowed, "native dnd_off arg refused");
    // The shell fallbacks still gate exactly as before.
    expect(ShellAllowlist.check("app_launch", List.of("com.google.android.apps.maps")).allowed, "shell fallback app_launch still gated");
    expect(ShellAllowlist.check("nav_open", List.of("Astoria")).allowed, "shell fallback nav_open still gated");
    expect(ShellAllowlist.check("screen_foreground", List.of()).allowed, "shell fallback screen_foreground still gated");

    // ---- v1.28: link_open (dedicated validator, chunked URL) ----
    // Slots are chunks of ONE https meeting URL, reassembled by
    // concatenation (the relay slices each arg at 256 chars, so a
    // >256-char URL must arrive split). The URL never becomes
    // shell text; the gate re-implements hygiene per chunk plus
    // the meeting-host allowlist.
    expect(ShellAllowlist.isNativeTemplate("link_open"), "link_open is native template");
    String zoomUrl = "https://zoom.us/j/91561286454?pwd=d444bk2267E66lq8lTHaG4Yabw1tvq.1";
    ShellAllowlist.NativeGate lZoom = ShellAllowlist.checkNative("link_open", List.of(zoomUrl));
    expect(lZoom.allowed && "link_open".equals(lZoom.op) && zoomUrl.equals(lZoom.arg),
            "link_open zoom proof url allowed (op+arg)");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://us02web.zoom.us/j/123456789?pwd=abc")).allowed,
            "link_open zoom subdomain allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://meet.google.com/abc-defg-hij")).allowed,
            "link_open meet allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://teams.microsoft.com/l/meetup-join/19%3ameeting")).allowed,
            "link_open teams microsoft allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://teams.live.com/l/invite/abc")).allowed,
            "link_open teams live allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://acme.webex.com/meet/jonathan")).allowed,
            "link_open webex subdomain allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://secure.gethealthie.com/appointments")).allowed,
            "link_open secure healthie allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://gethealthie.com/")).allowed,
            "link_open healthie root allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://clinic.doxy.me/room")).allowed,
            "link_open doxy subdomain allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://member.teladoc.com/visit")).allowed,
            "link_open teladoc subdomain allowed");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://amwell.com/visit")).allowed,
            "link_open amwell allowed");
    // Chunked reassembly: split at an arbitrary point, no separator.
    int cut = 37;
    ShellAllowlist.NativeGate lChunk = ShellAllowlist.checkNative("link_open",
            List.of(zoomUrl.substring(0, cut), zoomUrl.substring(cut)));
    expect(lChunk.allowed && zoomUrl.equals(lChunk.arg), "link_open 2-chunk reassembly");
    String longTeams = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_"
            + "abcdefghijklmnopqrstuvwxyz0123456789".repeat(4);
    ShellAllowlist.NativeGate lChunk3 = ShellAllowlist.checkNative("link_open",
            List.of(longTeams.substring(0, 80), longTeams.substring(80, 160),
                    longTeams.substring(160)));
    expect(lChunk3.allowed && longTeams.equals(lChunk3.arg), "link_open 3-chunk reassembly");
    // Refusals.
    expect(!ShellAllowlist.checkNative("link_open", List.of()).allowed,
            "link_open no args refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://zoom.us/", "a", "b", "c")).allowed,
            "link_open 4 chunks refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("http://zoom.us/j/123")).allowed,
            "link_open http refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("javascript://zoom.us/%0aalert(1)")).allowed,
            "link_open non-https scheme refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://example.com/j/123")).allowed,
            "link_open off-allowlist host refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://zoom.us.evil.com/j/123")).allowed,
            "link_open lookalike suffix host refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://evilzoom.us/j/123")).allowed,
            "link_open lookalike prefix host refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://google.com/")).allowed,
            "link_open bare google refused (meet subdomain only)");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://user@zoom.us/j/123")).allowed,
            "link_open userinfo refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://user:pass@meet.google.com/abc")).allowed,
            "link_open userinfo+password refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://zoom.us/j/12 3")).allowed,
            "link_open space refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://zoom.us/j/123\";reboot")).allowed,
            "link_open quote injection refused");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://zoom.us/j/123`reboot`")).allowed,
            "link_open backtick refused");
    expect(ShellAllowlist.checkNative("link_open",
            List.of("https://zoom.us")).allowed,
            "link_open bare allowlisted host allowed");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of("https://z")).allowed,
            "link_open too-short url refused");
    StringBuilder over = new StringBuilder("https://zoom.us/j/");
    while (over.length() <= 512) over.append("a");
    expect(!ShellAllowlist.checkNative("link_open",
            List.of(over.substring(0, 256), over.substring(256, 512),
                    over.substring(512))).allowed,
            "link_open over 512 total refused");
    expect(!ShellAllowlist.check("link_open", List.of(zoomUrl)).allowed,
            "link_open has no shell form");

    System.out.println(fails==0?"ALL_PASS":"FAILURES:"+fails);
  }
}
