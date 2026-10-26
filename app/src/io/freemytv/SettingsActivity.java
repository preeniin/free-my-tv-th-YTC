package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the lantern look: a brand
 * rail on the left and one scrolling list on the right (name, greeting, home screen, theme, weather and cards, shelf and
 * remote, system). Home screen switches run in the main process (HomeMode); this screen asks for them and follows the
 * state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    /** The name after the greeting: the TV's own keyboard in a small dialog. */
    void askName() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(Prefs.name());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("ชื่อที่แสดง").setView(input)
                .setPositiveButton("บันทึก", (d, which) -> {
                    Prefs.setName(input.getText().toString());
                    view.changed("บันทึกชื่อ “" + Prefs.name() + "” แล้ว");
                })
                .setNegativeButton("ยกเลิก", null).show();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, REMOTE_KEYS = 1, PICKER = 2, SHELF_EDIT = 3;
        private static final int SECTION = 0, INPUT = 1, SEG = 2, SW = 3, VAL = 4, DANGER = 5, CHECK = 6, SHELF = 7, NOTE = 8;
        private static final float RAIL_W = 360, BODY_X = 432, BODY_R = 1848, LIST_TOP = 178, LIST_BOTTOM = 996;
        private static final String[] UNITS = {"C", "F", "auto"}, THEMES = {"auto", "light", "dark"};

        private static final class Row {
            int type, section;
            String key, label, desc, value;
            String[] opts;
            int sel;
            boolean on, warn, chevron;
            float y, h;
            boolean focusable() { return type != SECTION && type != NOTE; }
        }

        private final SettingsActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<Row> rows = new ArrayList<>();
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        private final List<String> pickLabels = new ArrayList<>();
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent;
        private JSONObject state = new JSONObject();
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy, dirty = true;
        private long pendingAt, toastAt;
        private String toastText = "", signature = "";
        private float scrollY, scrollTarget, contentH;
        private final Spoken spoken;
        private int w, h;
        private float k = 1;
        private Shader sky, markShader;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), box2 = new RectF();
        private Paint title, subtitle, label, desc, sec, val, segOff, segOn, inputText, badge, rail, railOn, brandTitle, brandSub,
                railFoot, foot, kbd, toast, warn;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                boolean nowBusy = !state.optString("busy").isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                String now = state.optString("mode") + "|" + state.optString("busy") + "|" + state.optString("error") + "|" + Prefs.version;
                if (!now.equals(signature)) {
                    signature = now;
                    dirty = true;
                }
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            setFocusable(true);
            setContentDescription("Free My TV");
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i).focusable()) out.add(i + 1);
                }
                @Override public String text(int id) { return id >= 1 && id <= rows.size() ? rowText(rows.get(id - 1)) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rows.size()) return false;
                    Row r = rows.get(id - 1);
                    out.set(Math.round(BODY_X * k), Math.round((LIST_TOP + r.y - scrollY) * k), Math.round(BODY_R * k), Math.round((LIST_TOP + r.y - scrollY + r.h) * k));
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    scrollToFocus();
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        private String rowText(Row r) {
            switch (r.type) {
                case SEG: return r.label + ", " + r.opts[r.sel];
                case SW: return r.label + ", " + (r.on ? "เปิด" : "ปิด");
                case VAL: case INPUT: case SHELF: return r.label + (r.value == null || r.value.isEmpty() ? "" : ", " + r.value);
                case CHECK: return r.label + (r.on ? ", เลือกอยู่" : "");
                default: return r.label;
            }
        }

        /** Called after something outside this view changed a setting (the name dialog). */
        void changed(String message) {
            dirty = true;
            toast(message);
            invalidate();
        }

        void toast(String text) {
            toastText = text;
            toastAt = SystemClock.uptimeMillis();
            invalidate();
            announceForAccessibility(text);
        }

        void resume() {
            if (running) return;
            running = true;
            lastFrame = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        private long lastFrame;

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = Math.abs(scrollTarget - scrollY) > 0.5f || SystemClock.uptimeMillis() - toastAt < 2600 || busy();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            sky = Lantern.pageSky(w, h);
            markShader = null;
            title = Ui.text(Ui.displayBold, 44 * k, Lantern.WHITE);
            subtitle = Ui.text(Ui.regular, 19 * k, 0x80FFFFFF);
            label = Ui.text(Ui.medium, 23 * k, Lantern.WHITE);
            desc = Ui.text(Ui.regular, 15 * k, 0x73FFFFFF);
            sec = Ui.text(Ui.semibold, 16 * k, Lantern.LANTERN);
            sec.setLetterSpacing(0.02f);
            val = Ui.text(Ui.regular, 20 * k, 0xB3FFFFFF);
            val.setTextAlign(Paint.Align.RIGHT);
            segOff = Ui.text(Ui.medium, 18 * k, 0x99FFFFFF);
            segOn = Ui.text(Ui.displayBold, 18 * k, Lantern.ON_LANTERN);
            inputText = Ui.text(Ui.regular, 21 * k, Lantern.WHITE);
            inputText.setTextAlign(Paint.Align.RIGHT);
            badge = Ui.numbers(Ui.medium, 16 * k, Lantern.DOWN);
            rail = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
            railOn = Ui.text(Ui.medium, 21 * k, Lantern.WHITE);
            brandTitle = Ui.text(Ui.displayBold, 26 * k, Lantern.WHITE);
            brandSub = Ui.text(Ui.regular, 14 * k, 0x73FFFFFF);
            railFoot = Ui.text(Ui.regular, 14 * k, 0x59FFFFFF);
            foot = Ui.text(Ui.regular, 17 * k, 0x66FFFFFF);
            kbd = Ui.numbers(Ui.regular, 14 * k, 0xB3FFFFFF);
            toast = Ui.text(Ui.medium, 23 * k, Lantern.WHITE);
            warn = Ui.text(Ui.regular, 15 * k, 0xFFFFB4AE);
            line.setStyle(Paint.Style.STROKE);
            dirty = true;
        }

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        // MARK: - Rows

        private Row row(int type, String key, String text, String description) {
            Row r = new Row();
            r.type = type;
            r.key = key;
            r.label = text;
            r.desc = description;
            rows.add(r);
            return r;
        }

        private int sectionNo;

        private void section(String name) {
            Row r = row(SECTION, null, name, null);
            r.section = sectionNo++;
        }

        private Row seg(String key, String text, String description, String[] opts, int sel) {
            Row r = row(SEG, key, text, description);
            r.opts = opts;
            r.sel = Math.max(0, Math.min(opts.length - 1, sel));
            return r;
        }

        private Row sw(String key, String text, String description, boolean on) {
            Row r = row(SW, key, text, description);
            r.on = on;
            return r;
        }

        private Row value(String key, String text, String description, String value, boolean chevron) {
            Row r = row(VAL, key, text, description);
            r.value = value;
            r.chevron = chevron;
            return r;
        }

        /** Rebuilds the list for the current screen (when something it shows changed). */
        private void build() {
            Row focused = focus >= 0 && focus < rows.size() ? rows.get(focus) : null;
            String focusKey = focused == null ? null : focused.key;
            rows.clear();
            sectionNo = 0;
            switch (screen) {
                case REMOTE_KEYS: buildKeys(); break;
                case PICKER: buildPicker(); break;
                case SHELF_EDIT: buildShelf(); break;
                default: buildMain(); break;
            }
            layout();
            if (focusKey != null) {
                for (int i = 0; i < rows.size(); i++) if (focusKey.equals(rows.get(i).key)) { focus = i; break; }
            }
            if (focus >= rows.size() || focus < 0 || !rows.get(focus).focusable()) focus = firstFocusable();
            dirty = false;
        }

        private int firstFocusable() {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).focusable()) return i;
            return 0;
        }

        private void buildMain() {
            Context c = getContext();
            String busyText = state.optString("busy"), error = state.optString("error");
            section("ทั่วไป");
            Row name = row(INPUT, "name", "ชื่อที่แสดง", "ใช้ขึ้นต้นในคำทักทายบนหน้าโฮมและพักหน้าจอ");
            name.value = Prefs.name();
            seg("greet", "คำทักทาย", "ใช้กับหน้าโฮม พักหน้าจอ และ All Apps", Thai.GREETINGS, Prefs.greet());
            Row home = seg("home", "หน้าโฮม", "สลับไปมาได้อย่างปลอดภัย ไม่ถอนการติดตั้งใดๆ",
                    new String[] {"Free My TV", "ดั้งเดิม (Original)"}, freeActive() ? 0 : 1);
            if (busy()) home.desc = busyText.isEmpty() ? "กำลังสลับ…" : busyText;
            else if (!error.isEmpty()) {
                home.desc = error;
                home.warn = true;
            }
            seg("theme", "ธีม", "อัตโนมัติเปลี่ยนตามพระอาทิตย์ตกจริง", new String[] {"อัตโนมัติ", "กลางวัน", "กลางคืน"},
                    java.util.Arrays.asList(THEMES).indexOf(Prefs.theme()));
            section("สภาพอากาศ & ราคา");
            value("city", "เมือง", "ตั้งใน config.properties (latitude, longitude, city)", Prefs.city().isEmpty() ? "—" : Prefs.city(), false);
            seg("temp", "หน่วยอุณหภูมิ", null, new String[] {"°C", "°F", "อัตโนมัติ"}, java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature()));
            sw("crypto", "แสดงราคาคริปโต", "BTC / ETH / SOL บนหน้าโฮมและพักหน้าจอ", Prefs.quotes());
            section("การ์ดบนหน้าโฮม");
            sw("cweather", "การ์ดสภาพอากาศ + AQI", null, Prefs.cardWeather());
            sw("ccal", "การ์ด Google Calendar", "ใส่ลิงก์ .ics ใน config.properties (ical_url)", Prefs.cardCalendar());
            sw("csys", "การ์ดสถานะเครื่อง (RAM / CPU)", "กด OK ที่การ์ดบนหน้าโฮมเพื่อล้าง RAM", Prefs.cardSystem());
            section("ชั้นแอป & รีโมท");
            value("shelf", "จัดการชั้นแอป", null, HomeApps.shelfPackages(c).size() + " แอป", true);
            value("remote", "ปุ่มรีโมท TCL", "Netflix · YouTube · Prime · TCL Channel", RemoteKeys.supported() ? "" : "ไม่ใช้กับทีวีรุ่นนี้", true);
            section("ระบบ");
            value("saver", "พักหน้าจอ", "ดูตัวอย่างตอนนี้", "", true);
            value("tv", "ตั้งค่าทีวี", "ภาพ เสียง เครือข่าย ของระบบ", "", true);
            row(DANGER, "reset", "กลับสู่หน้าโฮมเดิม (Original)", "คืนโฮม/พักหน้าจอ/แอปทุกอย่างกลับเหมือนโรงงาน");
            value("version", "เวอร์ชัน", null, versionName(c), false);
        }

        private void buildKeys() {
            section("ปุ่มรีโมท TCL");
            String[] labels = keyLabels(), values = keyValues();
            for (int i = 0; i < labels.length; i++) value("key" + i, labels[i], null, values[i], true);
            row(NOTE, null, RemoteKeys.supported() ? "Disney และ Globoplay: ทีวีควบคุมสองปุ่มนั้นเอง"
                    : "เหล่านี้คือปุ่มรีโมทของ TCL: บนทีวีเครื่องนี้ปุ่มทำงานตามปกติ", null);
        }

        private void buildPicker() {
            section(keyLabels()[pickFor - 1]);
            for (int i = 0; i < pickLabels.size(); i++) {
                Row r = row(CHECK, "pick" + i, pickLabels.get(i), null);
                r.on = i == pickCurrent;
            }
        }

        private void buildShelf() {
            section("จัดการชั้นแอป");
            for (String[] app : shelfRows) {
                int place = shelfOrder.indexOf(app[0]);
                Row r = row(SHELF, app[0], app[1], null);
                r.on = place >= 0;
                r.value = place >= 0 ? "#" + (place + 1) : "นอกชั้น";
            }
            row(NOTE, null, "OK เพิ่ม/เอาออก (สูงสุด " + HomeApps.MAX_SHELF + " แอป) · ← → เปลี่ยนลำดับ", null);
        }

        /** Positions of the rows in the list's own coordinates (1920 grid, before scrolling). */
        private void layout() {
            float y = 0;
            for (Row r : rows) {
                if (r.type == SECTION) {
                    y += y == 0 ? 0 : 30;
                    r.y = y;
                    r.h = 24;
                    y += 24 + 12;
                } else if (r.type == NOTE) {
                    r.y = y + 6;
                    r.h = 30;
                    y += 46;
                } else {
                    boolean twoLines = r.desc != null && !r.desc.isEmpty();
                    r.h = twoLines ? 94 : 76;
                    r.y = y;
                    y += r.h + 8;
                }
            }
            contentH = y + 24;
            scrollToFocus();
        }

        private void scrollToFocus() {
            if (focus < 0 || focus >= rows.size()) return;
            Row r = rows.get(focus);
            float view = LIST_BOTTOM - LIST_TOP, pad = 20;
            float top = r.y - pad, bottom = r.y + r.h + pad;
            if (top < scrollTarget) scrollTarget = top;
            else if (bottom > scrollTarget + view) scrollTarget = bottom - view;
            // The first rows keep their section title in view.
            if (focus <= 2) scrollTarget = 0;
            scrollTarget = Math.max(0, Math.min(Math.max(0, contentH - view), scrollTarget));
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            if (dirty) build();
            scrollY += (scrollTarget - scrollY) * 0.28f;
            if (Math.abs(scrollTarget - scrollY) < 0.5f) scrollY = scrollTarget;

            fill.setColor(0xFF000000);   // a shader is painted through the paint's alpha: reset it first
            fill.setShader(sky);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            drawRail(c);
            drawHeader(c);

            c.save();
            c.clipRect(BODY_X * k - 30 * k, LIST_TOP * k, w, LIST_BOTTOM * k);
            c.translate(0, (LIST_TOP - scrollY) * k);
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                float top = (LIST_TOP + r.y - scrollY) * k;
                if (top > LIST_BOTTOM * k || top + r.h * k < LIST_TOP * k - 20 * k) continue;
                drawRow(c, r, i == focus);
            }
            c.restore();
            drawFoot(c);
            drawToast(c);
        }

        private void drawRail(Canvas c) {
            fill.setColor(0x38000000);
            c.drawRect(0, 0, RAIL_W * k, h, fill);
            fill.setColor(0x14FFFFFF);
            c.drawRect(RAIL_W * k - k, 0, RAIL_W * k, h, fill);
            // Brand: the lantern tile and the names.
            float bx = 40 * k, by = 56 * k, bs = 40 * k;
            if (markShader == null) {
                SweepGradient g = new SweepGradient(bx + bs / 2, by + bs / 2,
                        new int[] {Lantern.LANTERN, 0xFFCC9944, 0xFF7A1E2A, Lantern.LANTERN}, new float[] {0f, .3f, .7f, 1f});
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setRotate(120, bx + bs / 2, by + bs / 2);
                g.setLocalMatrix(m);
                markShader = g;
            }
            fill.setColor(0xFF000000);
            fill.setShader(markShader);
            box.set(bx, by, bx + bs, by + bs);
            c.drawRoundRect(box, 12 * k, 12 * k, fill);
            fill.setShader(null);
            c.drawText("Free My TV", Math.round(bx + bs + 14 * k), Math.round(by + 13 * k + Ui.halfCap(brandTitle)), brandTitle);
            c.drawText("การตั้งค่า · " + versionName(getContext()).replace("Free My TV ", "v") + " (th-YTC)", Math.round(bx + bs + 14 * k), Math.round(by + 44 * k), brandSub);
            // Navigation: the section that holds the focused row is lit.
            String[] names = {"ทั่วไป", "อากาศ & ราคา", "การ์ด", "ชั้นแอป & รีโมท", "ระบบ"};
            String[] icons = {"home", "cloud", "cards", "remote", "gear"};
            int active = screen == MAIN && focus >= 0 && focus < rows.size() ? sectionOf(focus) : 3;
            float ny = 56 * k + 40 * k + 48 * k;
            for (int i = 0; i < names.length; i++) {
                boolean on = i == active;
                float rowH = 58 * k;
                box.set(40 * k - 16 * k + 16 * k, ny, RAIL_W * k - 40 * k, ny + rowH);
                if (on) {
                    fill.setColor(0x12FFFFFF);
                    c.drawRoundRect(box, 14 * k, 14 * k, fill);
                }
                Icons.draw(c, icons[i], box.left + 16 * k + 12 * k, box.centerY(), 24 * k, on ? Lantern.LANTERN : 0x99FFFFFF, true);
                Paint p = on ? railOn : rail;
                c.drawText(names[i], Math.round(box.left + 16 * k + 24 * k + 14 * k), Math.round(box.centerY() + Ui.halfCap(p)), p);
                ny += rowH + 6 * k;
            }
            String[] lines = {"ค่าที่เปลี่ยนบนทีวีจะมีผล", "เหนือ config.properties"};
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], Math.round(40 * k), Math.round(h - 56 * k - (lines.length - 1 - i) * 22 * k), railFoot);
        }

        private int sectionOf(int index) {
            int s = 0;
            for (int i = 0; i <= index && i < rows.size(); i++) if (rows.get(i).type == SECTION) s = rows.get(i).section;
            return s;
        }

        private void drawHeader(Canvas c) {
            String t = screen == MAIN ? "การตั้งค่า" : screen == SHELF_EDIT ? "จัดการชั้นแอป" : screen == PICKER ? keyLabels()[pickFor - 1] : "ปุ่มรีโมท TCL";
            String s = screen == MAIN ? "ตั้งค่าทั้งหมดได้บนจอทีวีนี้อันเดียว ไม่ต้องแตะคอมพิวเตอร์"
                    : screen == PICKER ? "เลือกแอปที่ปุ่มนี้จะเปิด" : screen == SHELF_EDIT ? "เลือกแอปบนชั้นหน้าโฮมและจัดลำดับ" : "เลือกแอปที่แต่ละปุ่มบนรีโมทจะเปิด";
            c.drawText(t, Math.round(BODY_X * k), Math.round(52 * k + 44 * k * 0.82f), title);
            c.drawText(s, Math.round(BODY_X * k), Math.round(52 * k + 44 * k + 30 * k), subtitle);
        }

        private void drawFoot(Canvas c) {
            float x = BODY_X * k, y = 1042 * k;
            String[][] parts = {{"↑↓", " เลื่อน"}, {"←→", " เปลี่ยนค่า"}, {"OK", " สลับ"}, {"Back", " กลับ"}};
            for (int i = 0; i < parts.length; i++) {
                float kw = kbd.measureText(parts[i][0]) + 18 * k;
                box.set(x, y - 22 * k, x + kw, y + 6 * k);
                fill.setColor(0x14FFFFFF);
                c.drawRoundRect(box, 7 * k, 7 * k, fill);
                line.setStrokeWidth(k);
                line.setColor(0x24FFFFFF);
                c.drawRoundRect(box, 7 * k, 7 * k, line);
                c.drawText(parts[i][0], Math.round(x + 9 * k), Math.round(y - 8 * k + Ui.halfCap(kbd)), kbd);
                x += kw;
                String after = parts[i][1] + (i < parts.length - 1 ? "  ·  " : "");
                c.drawText(after, Math.round(x), Math.round(y - 8 * k + Ui.halfCap(foot)), foot);
                x += foot.measureText(after);
            }
        }

        private void drawToast(Canvas c) {
            long age = SystemClock.uptimeMillis() - toastAt;
            if (toastText.isEmpty() || age > 2400) return;
            float a = age < 300 ? age / 300f : age > 2000 ? Math.max(0, (2400 - age) / 400f) : 1;
            float tw = toast.measureText(toastText), bw = tw + 52 * k, bh = 62 * k;
            float x = (w - bw) / 2f, y = h - 120 * k - bh;
            box.set(x, y, x + bw, y + bh);
            fill.setColor(Ui.alpha(0xEB0C0E18, a));
            c.drawRoundRect(box, 18 * k, 18 * k, fill);
            line.setStrokeWidth(k);
            line.setColor(Ui.alpha(0x29FFFFFF, a));
            c.drawRoundRect(box, 18 * k, 18 * k, line);
            toast.setAlpha(Math.round(255 * a));
            c.drawText(toastText, Math.round(x + 26 * k), Math.round(box.centerY() + Ui.halfCap(toast)), toast);
            toast.setAlpha(255);
        }

        private void drawRow(Canvas c, Row r, boolean focused) {
            float x = BODY_X * k, right = BODY_R * k, top = r.y * k, hh = r.h * k;
            if (r.type == SECTION) {
                c.drawText(r.label, Math.round(x + 2 * k), Math.round(top + 12 * k + Ui.halfCap(sec)), sec);
                return;
            }
            if (r.type == NOTE) {
                desc.setAlpha(255);
                c.drawText(r.label, Math.round(x + 4 * k), Math.round(top + 15 * k + Ui.halfCap(desc)), foot);
                return;
            }
            box.set(x, top, right, top + hh);
            boolean danger = r.type == DANGER;
            fill.setColor(focused ? 0x12FFFFFF : 0x09FFFFFF);
            c.drawRoundRect(box, 16 * k, 16 * k, fill);
            line.setStrokeWidth(k);
            line.setColor(0x0FFFFFFF);
            box2.set(box);
            box2.inset(k / 2f, k / 2f);
            c.drawRoundRect(box2, 16 * k, 16 * k, line);
            if (focused) {
                line.setStrokeWidth(4 * k);
                line.setColor(danger ? Lantern.DOWN : Lantern.WHITE);
                box2.set(box);
                box2.inset(-2 * k, -2 * k);
                c.drawRoundRect(box2, 18 * k, 18 * k, line);
            }
            // The words.
            label.setColor(danger ? 0xFFFFB4AE : Lantern.WHITE);
            boolean two = r.desc != null && !r.desc.isEmpty();
            float cy = box.centerY(), reserve = controlWidth(r) + 60 * k;
            float textW = right - x - 44 * k - reserve;
            float labelBase = two ? top + 34 * k + Ui.halfCap(label) - 8 * k : cy + Ui.halfCap(label);
            c.drawText(TextUtils.ellipsize(r.label, new TextPaint(label), textW, TextUtils.TruncateAt.END).toString(),
                    Math.round(x + 22 * k), Math.round(labelBase), label);
            if (two) {
                Paint p = r.warn ? warn : desc;
                c.drawText(TextUtils.ellipsize(r.desc, new TextPaint(p), textW, TextUtils.TruncateAt.END).toString(),
                        Math.round(x + 22 * k), Math.round(top + 64 * k + Ui.halfCap(p)), p);
            }
            drawControl(c, r, right - 22 * k, cy);
        }

        private float segWidth(Row r) {
            float total = 8 * k;
            for (int i = 0; i < r.opts.length; i++) total += segOn.measureText(r.opts[i]) + 32 * k + (i > 0 ? 4 * k : 0);
            return total;
        }

        private float controlWidth(Row r) {
            switch (r.type) {
                case SEG: return segWidth(r);
                case SW: return 74 * k;
                case INPUT: return 280 * k;
                case VAL: return (r.value == null ? 0 : val.measureText(r.value)) + (r.chevron ? 34 * k : 0);
                case DANGER: return badge.measureText("กลับหน้าเดิม") + 28 * k;
                case CHECK: return 28 * k;
                case SHELF: return val.measureText(r.value == null ? "" : r.value);
                default: return 0;
            }
        }

        private void drawControl(Canvas c, Row r, float right, float cy) {
            switch (r.type) {
                case SW: {
                    box.set(right - 74 * k, cy - 20 * k, right, cy + 20 * k);
                    fill.setColor(r.on ? Lantern.UP : 0x24FFFFFF);
                    c.drawRoundRect(box, 20 * k, 20 * k, fill);
                    fill.setColor(Lantern.WHITE);
                    float kx = box.left + 5 * k + (r.on ? 34 * k : 0);
                    c.drawCircle(kx + 15 * k, cy, 15 * k, fill);
                    break;
                }
                case SEG: {
                    float sw = segWidth(r);
                    box.set(right - sw, cy - 26 * k, right, cy + 26 * k);
                    fill.setColor(0x14FFFFFF);
                    c.drawRoundRect(box, 14 * k, 14 * k, fill);
                    line.setStrokeWidth(k);
                    line.setColor(0x1AFFFFFF);
                    box2.set(box);
                    box2.inset(k / 2f, k / 2f);
                    c.drawRoundRect(box2, 14 * k, 14 * k, line);
                    float x = box.left + 4 * k;
                    for (int i = 0; i < r.opts.length; i++) {
                        float bw = segOn.measureText(r.opts[i]) + 32 * k;
                        if (i == r.sel) {
                            box2.set(x, cy - 22 * k, x + bw, cy + 22 * k);
                            fill.setColor(Lantern.LANTERN);
                            c.drawRoundRect(box2, 10 * k, 10 * k, fill);
                        }
                        Paint p = i == r.sel ? segOn : segOff;
                        c.drawText(r.opts[i], Math.round(x + 16 * k), Math.round(cy + Ui.halfCap(p)), p);
                        x += bw + 4 * k;
                    }
                    break;
                }
                case INPUT: {
                    box.set(right - 280 * k, cy - 26 * k, right, cy + 26 * k);
                    fill.setColor(0x0FFFFFFF);
                    c.drawRoundRect(box, 12 * k, 12 * k, fill);
                    line.setStrokeWidth(k);
                    line.setColor(0x24FFFFFF);
                    box2.set(box);
                    box2.inset(k / 2f, k / 2f);
                    c.drawRoundRect(box2, 12 * k, 12 * k, line);
                    c.drawText(TextUtils.ellipsize(r.value == null ? "" : r.value, new TextPaint(inputText), 250 * k, TextUtils.TruncateAt.END).toString(),
                            Math.round(right - 14 * k), Math.round(cy + Ui.halfCap(inputText)), inputText);
                    break;
                }
                case DANGER: {
                    String t = "กลับหน้าเดิม";
                    float bw = badge.measureText(t) + 28 * k;
                    box.set(right - bw, cy - 18 * k, right, cy + 18 * k);
                    line.setStrokeWidth(k);
                    line.setColor(0x80FF453A);
                    c.drawRoundRect(box, 12 * k, 12 * k, line);
                    c.drawText(t, Math.round(box.left + 14 * k), Math.round(cy + Ui.halfCap(badge)), badge);
                    break;
                }
                case CHECK:
                    if (r.on) Icons.draw(c, "check", right - 12 * k, cy, 26 * k, Lantern.LANTERN, true);
                    break;
                case SHELF: {
                    val.setColor(r.on ? Lantern.LANTERN : 0x80FFFFFF);
                    c.drawText(r.value, Math.round(right), Math.round(cy + Ui.halfCap(val)), val);
                    val.setColor(0xB3FFFFFF);
                    break;
                }
                default: { // VAL
                    float x = right;
                    if (r.chevron) {
                        Icons.draw(c, "chevron", right - 10 * k, cy, 22 * k, 0x66FFFFFF, true);
                        x -= 34 * k;
                    }
                    if (r.value != null && !r.value.isEmpty()) c.drawText(r.value, Math.round(x), Math.round(cy + Ui.halfCap(val)), val);
                    break;
                }
            }
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    if (down) move(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) step(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) goBack();
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void goBack() {
            String back = screen == PICKER ? "key" + (pickFor - 1) : screen == SHELF_EDIT ? "shelf" : "remote";
            screen = screen == PICKER ? REMOTE_KEYS : MAIN;
            focus = -1;
            dirty = true;
            build();
            for (int i = 0; i < rows.size(); i++) if (back.equals(rows.get(i).key)) { focus = i; break; }
            if (focus < 0) focus = firstFocusable();
            scrollToFocus();
            invalidate();
            spoken.focusMoved();
        }

        private void move(int dir) {
            int i = focus + dir;
            while (i >= 0 && i < rows.size() && !rows.get(i).focusable()) i += dir;
            if (i < 0 || i >= rows.size()) return;
            focus = i;
            scrollToFocus();
            invalidate();
            spoken.focusMoved();
        }

        // MARK: - Actions

        private String[] keyLabels() {
            return new String[] {"ปุ่ม Netflix", "ปุ่ม YouTube", "ปุ่ม Prime Video", "ปุ่ม TCL Channel"};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : "ไม่ได้ติดตั้ง";
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            PackageManager pm = getContext().getPackageManager();
            List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            appLabels.add("ไม่ทำอะไร");
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            List<String[]> candidates = HomeApps.shelfCandidates(getContext());
            shelfRows.clear();
            for (String pkg : shelfOrder) for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
            screen = SHELF_EDIT;
            focus = -1;
            dirty = true;
            build();
            focus = firstFocusable();
            scrollTarget = 0;
        }

        /** After a shelf change: selected apps first (in order), the focus follows the app. */
        private void refreshShelf(String pkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            shelfRows.clear();
            for (String p : shelfOrder) for (String[] cand : all) if (cand[0].equals(p)) { shelfRows.add(cand); break; }
            for (String[] cand : all) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
            Prefs.setShelf(shelfOrder);
            dirty = true;
            build();
            for (int i = 0; i < rows.size(); i++) if (pkg.equals(rows.get(i).key)) { focus = i; break; }
            scrollToFocus();
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            pickLabels.addAll(appLabels);
            pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            screen = PICKER;
            focus = -1;
            dirty = true;
            build();
            focus = Math.max(firstFocusable(), pickCurrent >= 0 ? 1 + pickCurrent : firstFocusable());
            scrollToFocus();
        }

        /** ← → : seg, switch and the shelf's order. */
        private void step(int dir) {
            if (focus < 0 || focus >= rows.size()) return;
            Row r = rows.get(focus);
            if (screen == SHELF_EDIT && r.type == SHELF) {
                int at = shelfOrder.indexOf(r.key), to = at + dir;
                if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
                shelfOrder.remove(at);
                shelfOrder.add(to, r.key);
                refreshShelf(r.key);
                invalidate();
            } else if (r.type == SEG) {
                choose(r, (r.sel + dir + r.opts.length) % r.opts.length);
            } else if (r.type == SW) {
                toggle(r);
            }
        }

        private void choose(Row r, int sel) {
            Context c = getContext();
            switch (r.key) {
                case "greet": Prefs.setGreet(sel); toast("คำทักทาย: " + Thai.GREETINGS[sel]); break;
                case "theme": Prefs.setTheme(THEMES[sel]); toast("ธีม: " + r.opts[sel]); break;
                case "temp": Prefs.setTemperature(UNITS[sel]); HomeData.get(c).settingsChanged(); toast("หน่วยอุณหภูมิ: " + r.opts[sel]); break;
                case "home": {
                    boolean wantFree = sel == 0;
                    if (busy()) { toast("กำลังสลับอยู่ รอสักครู่"); return; }
                    if (wantFree == freeActive()) return;
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                    toast(wantFree ? "กำลังสลับไป Free My TV…" : "กำลังกลับสู่หน้าโฮมเดิม…");
                    break;
                }
                default: return;
            }
            dirty = true;
            invalidate();
            spoken.focusMoved();
        }

        private void toggle(Row r) {
            boolean on = !r.on;
            switch (r.key) {
                case "crypto": Prefs.setQuotes(on); HomeData.get(getContext()).settingsChanged(); break;
                case "cweather": Prefs.setCardWeather(on); break;
                case "ccal": Prefs.setCardCalendar(on); break;
                case "csys": Prefs.setCardSystem(on); break;
                default: return;
            }
            toast(r.label + ": " + (on ? "เปิด" : "ปิด"));
            dirty = true;
            invalidate();
            spoken.focusMoved();
        }

        private void activate() {
            if (focus < 0 || focus >= rows.size()) return;
            Row r = rows.get(focus);
            Context c = getContext();
            if (r.type == SEG) {
                choose(r, (r.sel + 1) % r.opts.length);
            } else if (r.type == SW) {
                toggle(r);
            } else if (r.type == INPUT) {
                activity.askName();
            } else if (r.type == CHECK) {
                int at = Integer.parseInt(r.key.substring(4));
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                goBack();
            } else if (r.type == SHELF) {
                boolean on = shelfOrder.contains(r.key);
                if (on) shelfOrder.remove(r.key);
                else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(r.key);
                else { toast("ชั้นแอปเต็ม (สูงสุด " + HomeApps.MAX_SHELF + " แอป)"); return; }
                refreshShelf(r.key);
                invalidate();
            } else if (r.type == DANGER) {
                if (busy()) { toast("กำลังสลับอยู่ รอสักครู่"); return; }
                if (!freeActive()) { toast("ใช้หน้าโฮมเดิมอยู่แล้ว"); return; }
                HomeMode.request(c, HomeMode.ORIGINAL);
                pendingAt = SystemClock.uptimeMillis();
                toast("กำลังกลับสู่หน้าโฮมเดิม (Original)…");
            } else if (r.type == VAL) {
                switch (r.key) {
                    case "shelf": openShelfEditor(); break;
                    case "remote":
                        HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                        loadApps();
                        screen = REMOTE_KEYS;
                        focus = -1;
                        dirty = true;
                        build();
                        focus = firstFocusable();
                        scrollTarget = 0;
                        break;
                    case "saver": pause(); activity.showPreview(); return;
                    case "tv": activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return;
                    default:
                        if (r.key != null && r.key.startsWith("key")) {
                            openPicker(Integer.parseInt(r.key.substring(3)) + 1);
                            break;
                        }
                        return;
                }
            }
            invalidate();
            spoken.focusMoved();
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }
    }
}
