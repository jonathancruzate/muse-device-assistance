import com.jonathancruzate.metamusephonebridge.ShellAllowlist;
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
    System.out.println(fails==0?"ALL_PASS":"FAILURES:"+fails);
  }
}
