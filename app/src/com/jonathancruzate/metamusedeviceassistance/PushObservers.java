package com.jonathancruzate.metamusedeviceassistance;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Push observers (v1.7) — the real on-device observers behind the
 * v1.6 push-channel toggles for Calendar, Contacts, Messages, Call
 * Log, and Phone dialer (design §2/§5 step 6).
 *
 * Shape: BridgeService owns one instance. ContentObservers registered
 * on the service's handler thread only mark their channel dirty; the
 * 60-second BridgeService cycle then calls syncDirty(), which — for
 * each dirty channel that is still ON and still holds its runtime
 * permission — reads only the rows changed since the channel's
 * cursor, pushes them through PushClient, and advances the cursor
 * ONLY on a 2xx push (verify-then-mark; PushClient also queues the
 * payload on failure, so a re-read duplicate is possible and is
 * deduped downstream by the row id carried in every item).
 *
 * Toggle discipline (design §4): OFF = observer unregistered, not
 * read, not pushed. Registration is re-evaluated every cycle, so a
 * toggle flipped in MainActivity takes effect within one cycle.
 * First ON: the row cursor is seeded to the current maximum (no
 * historical dump). A missing permission at registration sets the
 * channel's last_error (loud in the bridge report) instead of
 * crashing. TV profile: start() is a no-op.
 */
public final class PushObservers {
    private static final int MAX_ITEMS = 50;

    private final Context app;
    private final Handler handler;
    private final AtomicBoolean calendarDirty = new AtomicBoolean(false);
    private final AtomicBoolean contactsDirty = new AtomicBoolean(false);
    private final AtomicBoolean messagesDirty = new AtomicBoolean(false);
    private final AtomicBoolean callLogDirty = new AtomicBoolean(false);

    private ContentObserver calendarObs, contactsObs, smsObs, mmsObs, callLogObs;
    private boolean dialerRegistered = false;
    private TelephonyManager tm;
    private TelephonyCallback callCb;
    private ExecutorService dialerExec;

    public PushObservers(Context ctx, Handler handler) {
        this.app = ctx.getApplicationContext();
        this.handler = handler;
    }

    // ---- lifecycle ------------------------------------------------------

    public void start() {
        if (DeviceProfile.isTv(app)) return;
        ensureRegistered();
    }

    public void stop() {
        try { if (calendarObs != null) app.getContentResolver().unregisterContentObserver(calendarObs); } catch (Exception ignored) {}
        try { if (contactsObs != null) app.getContentResolver().unregisterContentObserver(contactsObs); } catch (Exception ignored) {}
        try { if (smsObs != null) app.getContentResolver().unregisterContentObserver(smsObs); } catch (Exception ignored) {}
        try { if (mmsObs != null) app.getContentResolver().unregisterContentObserver(mmsObs); } catch (Exception ignored) {}
        try { if (callLogObs != null) app.getContentResolver().unregisterContentObserver(callLogObs); } catch (Exception ignored) {}
        calendarObs = contactsObs = smsObs = mmsObs = callLogObs = null;
        unregisterDialer();
    }

    /** Called every BridgeService cycle: sync registrations with toggles, then push dirty diffs. */
    public void syncDirty() {
        if (DeviceProfile.isTv(app)) return;
        ensureRegistered();
        if (calendarDirty.get()) { calendarDirty.set(false); syncCalendar(); }
        if (contactsDirty.get()) { contactsDirty.set(false); syncContacts(); }
        if (messagesDirty.get()) { messagesDirty.set(false); syncMessages(); }
        if (callLogDirty.get()) { callLogDirty.set(false); syncCallLog(); }
    }

    private void ensureRegistered() {
        // Calendar
        if (PushChannels.isOn(app, PushChannels.CALENDAR)) {
            if (calendarObs == null && hasPerm(Manifest.permission.READ_CALENDAR, PushChannels.CALENDAR)) {
                calendarObs = dirtyObs(calendarDirty, "calendar");
                register(calendarObs, CalendarContract.Events.CONTENT_URI, PushChannels.CALENDAR);
                calendarDirty.set(true); // catch changes since toggle-on
            }
        } else if (calendarObs != null) {
            unregister(calendarObs); calendarObs = null;
        }
        // Contacts
        if (PushChannels.isOn(app, PushChannels.CONTACTS)) {
            if (contactsObs == null && hasPerm(Manifest.permission.READ_CONTACTS, PushChannels.CONTACTS)) {
                contactsObs = dirtyObs(contactsDirty, "contacts");
                register(contactsObs, ContactsContract.Contacts.CONTENT_URI, PushChannels.CONTACTS);
                contactsDirty.set(true);
            }
        } else if (contactsObs != null) {
            unregister(contactsObs); contactsObs = null;
        }
        // Messages (SMS + MMS URIs)
        if (PushChannels.isOn(app, PushChannels.MESSAGES)) {
            if (smsObs == null && hasPerm(Manifest.permission.READ_SMS, PushChannels.MESSAGES)) {
                smsObs = dirtyObs(messagesDirty, "messages");
                mmsObs = dirtyObs(messagesDirty, "messages");
                register(smsObs, Telephony.Sms.CONTENT_URI, PushChannels.MESSAGES);
                register(mmsObs, Telephony.Mms.CONTENT_URI, PushChannels.MESSAGES);
                messagesDirty.set(true);
            }
        } else if (smsObs != null) {
            unregister(smsObs); unregister(mmsObs); smsObs = mmsObs = null;
        }
        // Call log
        if (PushChannels.isOn(app, PushChannels.CALL_LOG)) {
            if (callLogObs == null && hasPerm(Manifest.permission.READ_CALL_LOG, PushChannels.CALL_LOG)) {
                callLogObs = dirtyObs(callLogDirty, "call_log");
                register(callLogObs, CallLog.Calls.CONTENT_URI, PushChannels.CALL_LOG);
                callLogDirty.set(true);
            }
        } else if (callLogObs != null) {
            unregister(callLogObs); callLogObs = null;
        }
        // Dialer: telephony callback registered only while ON.
        if (PushChannels.isOn(app, PushChannels.DIALER)) {
            if (!dialerRegistered && hasPerm(Manifest.permission.READ_PHONE_STATE, PushChannels.DIALER)) {
                registerDialer();
            }
        } else if (dialerRegistered) {
            unregisterDialer();
        }
    }

    private ContentObserver dirtyObs(final AtomicBoolean flag, final String name) {
        return new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                flag.set(true);
                BridgeLog.log(app, "PUSH", name + " observer fired"
                        + (uri != null ? " uri=" + uri : ""));
                BridgeService.requestCycle(app, "push-" + name);
            }
        };
    }

    private void register(ContentObserver obs, Uri uri, PushChannels.Channel ch) {
        try {
            app.getContentResolver().registerContentObserver(uri, true, obs);
        } catch (Exception e) {
            fail(ch, "observer register failed: " + e.getMessage());
        }
    }

    private void unregister(ContentObserver obs) {
        try { app.getContentResolver().unregisterContentObserver(obs); } catch (Exception ignored) {}
    }

    private boolean hasPerm(String perm, PushChannels.Channel ch) {
        if (app.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true;
        fail(ch, "permission not granted: " + perm);
        return false;
    }

    private void fail(PushChannels.Channel ch, String err) {
        PushChannels.recordError(app, ch, err);
        BridgeLog.log(app, "PUSH", ch.id + " observer error: " + err);
    }

    // ---- Calendar ---------------------------------------------------------

    private void syncCalendar() {
        PushChannels.Channel ch = PushChannels.CALENDAR;
        if (!PushChannels.isOn(app, ch)) return;
        if (!hasPerm(Manifest.permission.READ_CALENDAR, ch)) return;
        long cursor = Store.getLong(app, ch.cursorKey, 0);
        long syncStart = System.currentTimeMillis();
        try (Cursor c = app.getContentResolver().query(
                CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                        CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                        CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.ALL_DAY},
                CalendarContract.Events.DTSTART + " >= ? OR " + CalendarContract.Events.DTEND + " >= ?",
                new String[]{String.valueOf(cursor), String.valueOf(cursor)},
                CalendarContract.Events.DTSTART + " ASC")) {
            if (c == null) { fail(ch, "calendar query returned null"); return; }
            JSONArray items = new JSONArray();
            while (c.moveToNext() && items.length() < MAX_ITEMS) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("title", nz(c.getString(1)));
                o.put("start", c.getLong(2));
                o.put("end", c.getLong(3));
                o.put("location", nz(c.getString(4)));
                o.put("all_day", c.getInt(5) != 0);
                items.put(o);
            }
            if (items.length() == 0) { Store.put(app, ch.cursorKey, syncStart); return; }
            if (PushClient.push(app, ch.source, items)) Store.put(app, ch.cursorKey, syncStart);
        } catch (Exception e) {
            fail(ch, "calendar sync failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ---- Contacts ---------------------------------------------------------

    private void syncContacts() {
        PushChannels.Channel ch = PushChannels.CONTACTS;
        if (!PushChannels.isOn(app, ch)) return;
        if (!hasPerm(Manifest.permission.READ_CONTACTS, ch)) return;
        long cursor = Store.getLong(app, ch.cursorKey, 0);
        long maxSeen = cursor;
        try (Cursor c = app.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP},
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP + " > ?",
                new String[]{String.valueOf(cursor)},
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP + " ASC")) {
            if (c == null) { fail(ch, "contacts query returned null"); return; }
            JSONArray items = new JSONArray();
            while (c.moveToNext() && items.length() < MAX_ITEMS) {
                long id = c.getLong(0);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("display_name", nz(c.getString(1)));
                o.put("phones", joinData(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.NUMBER, id));
                o.put("emails", joinData(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Email.ADDRESS, id));
                items.put(o);
                if (c.getLong(2) > maxSeen) maxSeen = c.getLong(2);
            }
            if (items.length() == 0) return;
            final long newCursor = maxSeen;
            if (PushClient.push(app, ch.source, items)) Store.put(app, ch.cursorKey, newCursor);
        } catch (Exception e) {
            fail(ch, "contacts sync failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private String joinData(Uri uri, String idCol, String valCol, long contactId) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = app.getContentResolver().query(uri, new String[]{valCol},
                idCol + " = ?", new String[]{String.valueOf(contactId)}, null)) {
            if (c == null) return "";
            while (c.moveToNext()) {
                String v = c.getString(0);
                if (v == null || v.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(v);
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    // ---- Messages -----------------------------------------------------------

    private void syncMessages() {
        PushChannels.Channel ch = PushChannels.MESSAGES;
        if (!PushChannels.isOn(app, ch)) return;
        if (!hasPerm(Manifest.permission.READ_SMS, ch)) return;
        syncMessageUri(ch, Telephony.Sms.CONTENT_URI, "sms", Store.K_PUSH_MESSAGES_SMS_ID);
        syncMessageUri(ch, Telephony.Mms.CONTENT_URI, "mms", Store.K_PUSH_MESSAGES_MMS_ID);
    }

    private void syncMessageUri(PushChannels.Channel ch, Uri uri, String kind, String idKey) {
        try {
            long idCursor = Store.getLong(app, idKey, -1);
            if (idCursor < 0) { // first ON: seed to current max, no historical dump
                Store.put(app, idKey, maxId(uri));
                BridgeLog.log(app, "PUSH", "messages " + kind + " cursor seeded (no historical dump)");
                return;
            }
            try (Cursor c = app.getContentResolver().query(uri,
                    new String[]{Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ},
                    Telephony.Sms._ID + " > ?", new String[]{String.valueOf(idCursor)},
                    Telephony.Sms._ID + " ASC")) {
                if (c == null) { fail(ch, "messages query returned null for " + kind); return; }
                JSONArray items = new JSONArray();
                long maxSeen = idCursor;
                while (c.moveToNext() && items.length() < MAX_ITEMS) {
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(0));
                    o.put("kind", kind);
                    o.put("address", nz(c.getString(1)));
                    o.put("body", nz(c.getString(2)));
                    o.put("date", c.getLong(3));
                    o.put("type", c.getInt(4));
                    o.put("read", c.getInt(5) != 0);
                    items.put(o);
                    if (c.getLong(0) > maxSeen) maxSeen = c.getLong(0);
                }
                if (items.length() == 0) return;
                if (PushClient.push(app, ch.source, items)) Store.put(app, idKey, maxSeen);
            }
        } catch (Exception e) {
            // MMS content URI column sets differ on some builds; a bad
            // MMS query must not take the SMS leg down with it.
            fail(ch, "messages " + kind + " sync failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ---- Call log -------------------------------------------------------------

    private void syncCallLog() {
        PushChannels.Channel ch = PushChannels.CALL_LOG;
        if (!PushChannels.isOn(app, ch)) return;
        if (!hasPerm(Manifest.permission.READ_CALL_LOG, ch)) return;
        String idKey = Store.K_PUSH_CALL_LOG_ID;
        try {
            long idCursor = Store.getLong(app, idKey, -1);
            if (idCursor < 0) {
                Store.put(app, idKey, maxId(CallLog.Calls.CONTENT_URI));
                BridgeLog.log(app, "PUSH", "call_log cursor seeded (no historical dump)");
                return;
            }
            try (Cursor c = app.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME},
                    CallLog.Calls._ID + " > ?", new String[]{String.valueOf(idCursor)},
                    CallLog.Calls._ID + " ASC")) {
                if (c == null) { fail(ch, "call log query returned null"); return; }
                JSONArray items = new JSONArray();
                long maxSeen = idCursor;
                while (c.moveToNext() && items.length() < MAX_ITEMS) {
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(0));
                    o.put("number", nz(c.getString(1)));
                    o.put("type", c.getInt(2));
                    o.put("date", c.getLong(3));
                    o.put("duration", c.getLong(4));
                    o.put("cached_name", nz(c.getString(5)));
                    items.put(o);
                    if (c.getLong(0) > maxSeen) maxSeen = c.getLong(0);
                }
                if (items.length() == 0) return;
                if (PushClient.push(app, ch.source, items)) Store.put(app, idKey, maxSeen);
            }
        } catch (Exception e) {
            fail(ch, "call_log sync failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private long maxId(Uri uri) {
        try (Cursor c = app.getContentResolver().query(uri, new String[]{"_id"}, null, null, "_id DESC LIMIT 1")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            BridgeLog.log(app, "PUSH", "maxId query failed for " + uri + ": " + e.getMessage());
        }
        return 0;
    }

    // ---- Dialer (live call state) --------------------------------------------

    private void registerDialer() {
        try {
            tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) { fail(PushChannels.DIALER, "TelephonyManager unavailable"); return; }
            dialerExec = Executors.newSingleThreadExecutor();
            CallCb cb = new CallCb(this);
            tm.registerTelephonyCallback(dialerExec, cb);
            callCb = cb;
            dialerRegistered = true;
            BridgeLog.log(app, "PUSH", "dialer call-state callback registered");
        } catch (Exception e) {
            fail(PushChannels.DIALER, "dialer register failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** TelephonyCallback + CallStateListener (API 31+ registration model). */
    static final class CallCb extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        private final PushObservers owner;
        CallCb(PushObservers owner) { this.owner = owner; }
        // Framework CallStateListener (API 35) delivers state only —
        // the number is not exposed to non-privileged apps, so the
        // pushed event carries number="" (see the v1.7 record).
        @Override public void onCallStateChanged(int state) {
            owner.pushDialerEvent(state, "");
        }
    }

    private void unregisterDialer() {
        try {
            if (tm != null && callCb != null) tm.unregisterTelephonyCallback(callCb);
        } catch (Exception ignored) {}
        if (dialerExec != null) dialerExec.shutdownNow();
        dialerExec = null; callCb = null; tm = null; dialerRegistered = false;
    }

    /** Push one live dialer event (called from the telephony callback). */
    public void pushDialerEvent(int state, String number) {
        PushChannels.Channel ch = PushChannels.DIALER;
        if (!PushChannels.isOn(app, ch)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("state", stateName(state));
            o.put("state_code", state);
            o.put("number", number == null ? "" : number);
            o.put("ts", System.currentTimeMillis());
            JSONArray items = new JSONArray();
            items.put(o);
            PushClient.pushAsync(app, ch.source, items);
        } catch (Exception e) {
            fail(ch, "dialer event push failed: " + e.getMessage());
        }
    }

    static String stateName(int s) {
        switch (s) {
            case TelephonyManager.CALL_STATE_RINGING: return "ringing";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "offhook";
            case TelephonyManager.CALL_STATE_IDLE: return "idle";
            default: return "unknown";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
