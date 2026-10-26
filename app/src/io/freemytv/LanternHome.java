package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen in the lantern look (the redesign's mockup): a greeting and big clock, four pills (quick actions,
 * inputs, network, settings), the story, the weather card with a photo of the sky, the status and calendar cards, and
 * the shelf of apps. Overlays: quick actions, inputs, and the All Apps page. Everything is drawn by hand on the 1920 ×
 * 1080 grid (times k); it only redraws when something moves or changes.
 */
final class LanternHome extends View implements Choreographer.FrameCallback, HomeData.Listener, Feed.Listener,
        Status.Listener, Recs.Listener {
    // Focus zones of the home page, and the overlay on top of it.
    private static final int Z_TOP = 0, Z_STORY = 1, Z_SYS = 2, Z_CAL = 3, Z_SHELF = 4;
    private static final int NONE = 0, QUICK = 1, INPUTS = 2, APPS = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final float M = 96;      // side margin of the page

    private final Activity activity;
    private final HomeData data;
    private final Feed feed;
    private final Status status;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Spoken spoken;
    private int w, h;
    private float k = 1;
    private boolean running;
    private long lastMinute = -1;
    private int zone = Z_SHELF, topIndex, shelfIndex, lastContent = Z_STORY, overlay = NONE;
    private boolean okDown, backDown;
    private long lastNanos;

    // The lists (loaded in the background, so coming back home is not delayed).
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private List<HomeApps.Item> inputs = new ArrayList<>();
    private List<HomeApps.Item> everything = new ArrayList<>();
    private final Spring[] tilePop = new Spring[16];

    private static final class Story {
        String kicker, title, app, meta, pkg;
    }

    private final List<Story> stories = new ArrayList<>();
    private int storyIndex;
    private long storyAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            feed.refreshIfStale();
            status.refreshIfStale();
            recs.refreshIfStale();
            rebuildStories();
            dirty();
            handler.postDelayed(this, 10_000);
        }
    };

    LanternHome(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        feed = Feed.get(a);
        status = Status.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        setFocusable(true);
        setContentDescription("Free My TV");
        for (int i = 0; i < tilePop.length; i++) tilePop[i] = new Spring(0.34f, 0.80f, 0);
        spoken = new Spoken(this, new Spoken.Source() {
            @Override public void items(List<Integer> out) { a11yItems(out); }
            @Override public String text(int id) { return a11yText(id); }
            @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
            @Override public int focused() { return a11yFocused(); }
            @Override public void focus(int id) { a11yFocus(id); }
            @Override public void click(int id) { activate(); }
        });
        reload();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        data.addListener(this);
        feed.addListener(this);
        status.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        feed.refreshIfStale();
        status.refreshIfStale();
        recs.refreshIfStale();
        reload();
        rebuildStories();
        handler.postDelayed(tick, 10_000);
        Choreographer.getInstance().postFrameCallback(this);
        dirty();
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        Choreographer.getInstance().removeFrameCallback(this);
        data.removeListener(this);
        feed.removeListener(this);
        status.removeListener(this);
        recs.removeListener(this);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Home pressed while the home screen is showing: back to the start (focus on the first app, overlays closed). */
    void reset(boolean animated) {
        overlay = NONE;
        zone = Z_SHELF;
        shelfIndex = 0;
        dirty();
        spoken.focusMoved();
    }

    /** Something the recorded page shows has changed: record it again on the next draw. */
    private void dirty() {
        layerDirty = true;
        invalidate();
    }

    @Override public void onData() { dirty(); }
    @Override public void onFeed() { rebuildStories(); dirty(); }
    @Override public void onStatus() { dirty(); }
    @Override public void onRecs() { rebuildStories(); dirty(); }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.max(0.001f, Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f));
        lastNanos = frameTimeNanos;
        boolean moving = false;
        for (Spring s : tilePop) moving |= s.step(dt);
        if (moving || toastAt != 0) invalidate();
        long now = System.currentTimeMillis();
        if (now / 60_000 != lastMinute) {
            lastMinute = now / 60_000;
            dirty();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    // MARK: - Lists

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(new Runnable() {
            @Override public void run() {
                final List<HomeApps.Item> s = HomeApps.shelf(c, true);
                final List<HomeApps.Item> in = HomeApps.allInputs(c);
                final List<HomeApps.Item> all = HomeApps.everything(c);
                handler.post(new Runnable() {
                    @Override public void run() {
                        shelf = s;
                        inputs = in;
                        everything = all;
                        shelfIndex = Math.min(shelfIndex, shelf.size());
                        buildApps();
                        dirty();
                    }
                });
            }
        });
    }

    /** The story: TMDB picks when there is a key, else the latest headlines, else a short welcome. */
    private void rebuildStories() {
        List<Story> list = new ArrayList<>();
        List<Recs.Pick> picks = recs.now(null);
        for (int i = 0; i < Math.min(3, picks.size()); i++) {
            Recs.Pick p = picks.get(i);
            Story s = new Story();
            s.kicker = "กำลังเป็นที่นิยมในสัปดาห์นี้";
            s.title = p.title == null ? "" : p.title;
            s.app = Recs.serviceName(p);
            s.meta = p.meta == null ? "" : p.meta;
            s.pkg = p.pkg;
            list.add(s);
        }
        if (list.isEmpty() && !feed.headlines().isEmpty()) {
            List<String> lines = feed.headlines();
            long now = System.currentTimeMillis();
            int start = (int) ((now / 600_000L) % Math.max(1, lines.size()));
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                Story s = new Story();
                String source = feed.newsSource();
                s.kicker = source.isEmpty() ? "ข่าวล่าสุด" : "ข่าวล่าสุด · " + source;
                s.title = lines.get((start + i) % lines.size());
                s.app = "";
                s.meta = Feed.ago(now, feed.newsAt());
                s.pkg = Config.STORY_APP;
                list.add(s);
            }
        }
        if (list.isEmpty()) {
            Story s = new Story();
            s.kicker = "ยินดีต้อนรับ";
            s.title = "Free My TV";
            s.app = "";
            s.meta = Config.NEWS_URL.isEmpty() ? "ใส่ลิงก์ข่าว RSS (news_url) ใน config.properties เพื่อดูข่าวที่นี่" : "กำลังโหลดข่าว…";
            s.pkg = Config.STORY_APP;
            list.add(s);
        }
        boolean same = list.size() == stories.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).title.equals(stories.get(i).title);
        if (!same) {
            stories.clear();
            stories.addAll(list);
            storyIndex = 0;
            storyAt = SystemClock.uptimeMillis();
        } else if (stories.size() > 1 && SystemClock.uptimeMillis() - storyAt > 10_000) {
            storyIndex = (storyIndex + 1) % stories.size();
            storyAt = SystemClock.uptimeMillis();
        }
    }

    // MARK: - Weather photo

    private final java.util.Map<String, Bitmap> photos = new java.util.HashMap<>();
    private String photoKey = "";
    private String photoPrev = "";
    private long photoAt;

    private static String photoFor(int code, boolean day) {
        if (code >= 95) return "storm";
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "rain";
        if (code == 45 || code == 48) return "fog";
        if (code <= 1) return day ? "clear" : "night";
        return "cloudy";
    }

    /** The photo for the weather now (decoded once, in the background; the old one fades out). */
    private Bitmap photo() {
        HomeData.Weather wx = data.weather;
        String want = Double.isNaN(wx.temp) ? "night" : photoFor(wx.code, wx.isDay);
        if (!want.equals(photoKey)) {
            photoPrev = photoKey;
            photoKey = want;
            photoAt = SystemClock.uptimeMillis();
            if (!photos.containsKey(want)) {
                photos.put(want, null);
                final String name = want;
                final Context c = getContext().getApplicationContext();
                LOADER.execute(new Runnable() {
                    @Override public void run() {
                        final Bitmap b = decode(c, name);
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (b != null) photos.put(name, b);
                                dirty();
                            }
                        });
                    }
                });
            }
        }
        return photos.get(photoKey);
    }

    private static Bitmap decode(Context c, String name) {
        try (InputStream in = c.getAssets().open("weather/" + name + ".jpg")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // MARK: - Toast

    private String toastText = "";
    private long toastAt;

    void showToast(String text) {
        toastText = text;
        toastAt = SystemClock.uptimeMillis();
        announceForAccessibility(text);
        invalidate();
    }

    // MARK: - Size, paints and layout

    private final Shadows shadows = new Shadows();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), box2 = new RectF(), tmp = new RectF();
    private final Matrix matrix = new Matrix();
    private final java.util.WeakHashMap<Bitmap, BitmapShader> shaders = new java.util.WeakHashMap<>();
    private Shader skyDay, skyNight, veilSide, veilTop;

    private Paint greetP, nameP, clockP, ampmP, dateP, cityP, pillP, msP, kickerP, metaP, metaAppP, btnP, updP, aqiNumP, aqiLblP,
            tempP, hlP, pmP, hrTimeP, hrValP, hrRainP, footP, footBoldP, headP, faintP, labelP, valP, hintP, calTimeP, calDayP, calNameP,
            calNowP, calEmptyP, toastP, wxCityP, condP, tileLabelP;
    private TextPaint storyP;
    private final RectF[] pill = new RectF[4];
    private final RectF storyBtn = new RectF(), wxRect = new RectF(), sysRect = new RectF(), calRect = new RectF(), platter = new RectF();
    private RectF[] tileRect = new RectF[0];
    private StaticLayout storyLayout;
    private String storyLayoutFor = "";

    private Paint P(android.graphics.Typeface face, float size, int color) {
        return Ui.text(face, size * k, color);
    }

    private Paint N(android.graphics.Typeface face, float size, int color) {
        return Ui.numbers(face, size * k, color);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        skyDay = Lantern.homeSky(w, h, false);
        skyNight = Lantern.homeSky(w, h, true);
        veilSide = new LinearGradient(0, 0, w * .72f, 0, new int[] {0x9E060912, 0x47060912, 0x00060912}, new float[] {0f, .64f, 1f}, Shader.TileMode.CLAMP);
        veilTop = new LinearGradient(0, 0, 0, h * .3f, new int[] {0x6B060912, 0x00060912}, null, Shader.TileMode.CLAMP);
        for (int i = 0; i < pill.length; i++) pill[i] = new RectF();
        stroke.setStyle(Paint.Style.STROKE);
        shadowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(30 * k, android.graphics.BlurMaskFilter.Blur.NORMAL));
        greetP = P(Ui.medium, 23, 0x99FFFFFF);
        nameP = P(Ui.semibold, 23, Lantern.WHITE);
        clockP = N(Ui.light, 96, Lantern.WHITE);
        clockP.setLetterSpacing(-0.01f);
        ampmP = P(Ui.regular, 30, 0xB3FFFFFF);
        dateP = P(Ui.regular, 24, 0xB8FFFFFF);
        cityP = P(Ui.regular, 20, 0x8CFFFFFF);
        pillP = P(Ui.medium, 23, Lantern.WHITE);
        msP = N(Ui.regular, 18, 0x8CFFFFFF);
        kickerP = P(Ui.medium, 23, 0xE6FFFFFF);
        storyP = new TextPaint(P(Ui.displayBold, 84, Lantern.WHITE));
        storyP.setShadowLayer(48 * k, 0, 10 * k, 0x99000000);
        metaP = P(Ui.regular, 25, 0x9EFFFFFF);
        metaAppP = P(Ui.semibold, 25, 0xEBFFFFFF);
        btnP = P(Ui.semibold, 26, 0xFF111111);
        wxCityP = P(Ui.displayBold, 30, Lantern.WHITE);
        updP = P(Ui.regular, 15, 0x9EFFFFFF);
        aqiNumP = N(Ui.semibold, 24, Lantern.WHITE);
        aqiLblP = P(Ui.regular, 12, 0x99FFFFFF);
        tempP = N(Ui.medium, 128, Lantern.WHITE);
        condP = P(Ui.displayBold, 30, Lantern.WHITE);
        hlP = P(Ui.regular, 19, 0xADFFFFFF);
        pmP = P(Ui.medium, 18, 0xD1FFFFFF);
        hrTimeP = N(Ui.regular, 15, 0x99FFFFFF);
        hrValP = N(Ui.semibold, 20, Lantern.WHITE);
        hrRainP = N(Ui.semibold, 12, 0xFF7EC8FF);
        footP = P(Ui.regular, 16, 0xB3FFFFFF);
        footBoldP = N(Ui.semibold, 16, Lantern.LANTERN);
        headP = P(Ui.semibold, 16, Lantern.DIM);
        faintP = N(Ui.regular, 14, Lantern.FAINT);
        labelP = P(Ui.regular, 15, Lantern.DIM);
        valP = N(Ui.semibold, 16, Lantern.TEXT);
        hintP = P(Ui.regular, 14, Lantern.FAINT);
        calTimeP = N(Ui.medium, 16, Lantern.DIM);
        calDayP = P(Ui.semibold, 16, Lantern.TEXT);
        calNameP = P(Ui.medium, 19, Lantern.TEXT);
        calNowP = P(Ui.semibold, 14, Lantern.DOWN);
        calEmptyP = P(Ui.regular, 17, Lantern.FAINT);
        toastP = P(Ui.medium, 23, Lantern.WHITE);
        tileLabelP = P(Ui.semibold, 20, Lantern.WHITE);
        makeOverlayPaints();
        layoutAll();
    }

    private void layoutAll() {
        if (w == 0) return;
        // Feature row.
        float top = 186 * k;
        wxRect.set((M + 420 + 32) * k, top, (M + 420 + 32 + 748) * k, top + 606 * k);
        float colX = wxRect.right + 32 * k, colW = 1920 * k - M * k - colX;
        sysRect.set(colX, top, colX + colW, top + 287 * k);
        calRect.set(colX, top + 319 * k, colX + colW, top + 606 * k);
        layoutShelf();
    }

    private void layoutShelf() {
        int n = shelf.size() + 1;   // the apps, then All Apps
        float tw = 224 * k, th = 126 * k, gap = 24 * k, pad = 32 * k;
        float total = n * tw + (n - 1) * gap;
        float left = (w - total) / 2f, top = 832 * k;
        platter.set(left - pad, top, left + total + pad, top + th + 2 * pad);
        tileRect = new RectF[n];
        for (int i = 0; i < n; i++) {
            float x = left + i * (tw + gap);
            tileRect[i] = new RectF(x, top + pad, x + tw, top + pad + th);
        }
    }

    // MARK: - Drawing helpers

    private boolean night() {
        return data.isNight(System.currentTimeMillis());
    }

    private void textL(Canvas c, String s, float x, float cy, Paint p) {
        c.drawText(s, Math.round(x), Math.round(cy + Ui.halfCap(p)), p);
    }

    private void textR(Canvas c, String s, float right, float cy, Paint p) {
        c.drawText(s, Math.round(right - p.measureText(s)), Math.round(cy + Ui.halfCap(p)), p);
    }

    private void textC(Canvas c, String s, float cx, float cy, Paint p) {
        c.drawText(s, Math.round(cx - p.measureText(s) / 2f), Math.round(cy + Ui.halfCap(p)), p);
    }

    private String fit(String s, Paint p, float width) {
        return TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
    }

    private void panel(Canvas c, RectF r, float radius, int color, int border) {
        fill.setShader(null);
        fill.setColor(color);
        c.drawRoundRect(r, radius, radius, fill);
        if (border != 0) {
            stroke.setStrokeWidth(k);
            stroke.setColor(border);
            box2.set(r);
            box2.inset(k / 2f, k / 2f);
            c.drawRoundRect(box2, radius, radius, stroke);
        }
    }

    private void dropShadow(Canvas c, RectF r, float radius, float dy, int color) {
        tmp.set(r);
        tmp.offset(0, dy);
        shadowPaint.setColor(color);
        shadows.draw(c, tmp, radius, 30 * k, shadowPaint);
    }

    /** The gold focus mark of the home page: a 3 px ring set off the item, with a soft glow. */
    private void focusRing(Canvas c, RectF r, float radius, float offset) {
        stroke.setStrokeWidth(7 * k);
        stroke.setColor(0x38F2A93B);
        box2.set(r);
        box2.inset(-(offset + 2) * k, -(offset + 2) * k);
        c.drawRoundRect(box2, radius + (offset + 2) * k, radius + (offset + 2) * k, stroke);
        stroke.setStrokeWidth(3 * k);
        stroke.setColor(Lantern.LANTERN);
        box2.set(r);
        box2.inset(-(offset + 1.5f) * k, -(offset + 1.5f) * k);
        c.drawRoundRect(box2, radius + (offset + 1.5f) * k, radius + (offset + 1.5f) * k, stroke);
    }

    private BitmapShader shaderOf(Bitmap b) {
        BitmapShader s = shaders.get(b);
        if (s == null) {
            s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(b, s);
        }
        return s;
    }

    /** `image` covering `r` (cropped, not stretched), with rounded corners. */
    private void coverImage(Canvas c, Bitmap b, RectF r, float radius, int alpha) {
        BitmapShader s = shaderOf(b);
        float scale = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate(r.left + (r.width() - b.getWidth() * scale) / 2f, r.top + (r.height() - b.getHeight() * scale) / 2f);
        s.setLocalMatrix(matrix);
        imagePaint.setShader(s);
        imagePaint.setAlpha(alpha);
        c.drawRoundRect(r, radius, radius, imagePaint);
        imagePaint.setShader(null);
        imagePaint.setAlpha(255);
    }

    /** `image` stretched over `r` with rounded corners (the apps' 16:9 art). */
    private void stretchImage(Canvas c, Bitmap b, RectF r, float radius) {
        BitmapShader s = shaderOf(b);
        matrix.setScale(r.width() / b.getWidth(), r.height() / b.getHeight());
        matrix.postTranslate(r.left, r.top);
        s.setLocalMatrix(matrix);
        imagePaint.setShader(s);
        c.drawRoundRect(r, radius, radius, imagePaint);
        imagePaint.setShader(null);
    }

    // MARK: - Drawing: the home page

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        long now = System.currentTimeMillis();
        boolean night = night();
        if (android.os.Build.VERSION.SDK_INT >= 29 && c.isHardwareAccelerated()) {
            // Everything that holds still is recorded once into a GPU layer and only replayed while the tiles move.
            long sig = (zone * 31L + topIndex) * 7 + overlay;
            boolean fading = SystemClock.uptimeMillis() - photoAt < 700;
            if (layer == null || layerW != w || layerH != h) {
                layer = new android.graphics.RenderNode("home");
                layer.setPosition(0, 0, w, h);
                layer.setUseCompositingLayer(true, null);
                layerW = w;
                layerH = h;
                layerDirty = true;
            }
            if (layerDirty || sig != layerSig || fading) {
                Canvas rc = layer.beginRecording(w, h);
                pending.clear();
                drawStatic(rc, now, night);
                layer.endRecording();
                cachedNodes.clear();
                cachedNodes.addAll(pending);
                layerSig = sig;
                layerDirty = false;
            }
            pending.clear();
            pending.addAll(cachedNodes);
            c.drawRenderNode(layer);
        } else {
            drawStatic(c, now, night);
        }
        drawLiveShelf(c);
        drawToast(c);
        if (overlay != NONE) drawOverlay(c, now, night);
    }

    private void drawSky(Canvas c, boolean night) {
        fill.setColor(0xFF000000);
        fill.setShader(night ? skyNight : skyDay);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(veilSide);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(veilTop);
        c.drawRect(0, 0, w, h * .3f, fill);
        fill.setShader(null);
    }

    private void drawHeader(Canvas c, long now) {
        float x = M * k;
        Lantern.mark(c, x + 13 * k, 63 * k, 13 * k);
        String hello = Thai.greeting(now), who = Prefs.name();
        textL(c, hello, x + 38 * k, 63 * k, greetP);
        textL(c, who, x + 38 * k + greetP.measureText(hello) + 12 * k, 63 * k, nameP);
        String clock = new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(now));
        float cy = 130 * k;
        float base = Math.round(cy + Ui.cap(clockP) / 2f);
        c.drawText(clock, Math.round(x), base, clockP);
        float cw = clockP.measureText(clock);
        c.drawText("น.", Math.round(x + cw + 8 * k), base, ampmP);
        float dx = x + cw + 8 * k + ampmP.measureText("น.") + 22 * k;
        fill.setColor(0x38FFFFFF);
        c.drawRect(Math.round(dx), cy - 32 * k, Math.round(dx) + Math.max(1, k), cy + 32 * k, fill);
        dx += 22 * k;
        textL(c, Thai.shortDate(now), dx, cy - 17 * k, dateP);
        String city = Prefs.city().isEmpty() ? "" : Prefs.city();
        if (!city.isEmpty()) textL(c, city, dx, cy + 17 * k, cityP);
    }

    private String[] pillLabels() {
        int ms = status.pingMs;
        return new String[] {"ด่วน", "Inputs", status.link.isEmpty() ? "ออนไลน์" : status.link, "Settings"};
    }

    private static final String[] PILL_ICONS = {"bolt", "hdmi", "wifi", "settings"};

    private void drawPills(Canvas c) {
        String[] labels = pillLabels();
        int ms = status.pingMs;
        String msText = ms == -2 ? " · ออฟไลน์" : ms < 0 ? " · – ms" : " · " + ms + " ms";
        float right = (1920 - M) * k, top = 58 * k, ph = 60 * k, gap = 16 * k;
        for (int i = 3; i >= 0; i--) {
            float tw = pillP.measureText(labels[i]) + (i == 2 ? msP.measureText(msText) : 0);
            float pw = 24 * k + 26 * k + 12 * k + tw + 24 * k;
            pill[i].set(right - pw, top, right, top + ph);
            right -= pw + gap;
        }
        for (int i = 0; i < 4; i++) {
            RectF r = pill[i];
            boolean focused = overlay == NONE && zone == Z_TOP && topIndex == i;
            panel(c, r, 30 * k, focused ? 0xB31E1218 : 0x80140C10, 0x1FFFFFFF);
            String icon = i == 2 && status.link.equals("Ethernet") ? "ethernet" : PILL_ICONS[i];
            Icons.draw(c, icon, r.left + 24 * k + 13 * k, r.centerY(), 26 * k, Lantern.WHITE, true);
            float tx = r.left + 24 * k + 26 * k + 12 * k;
            textL(c, labels[i], tx, r.centerY(), pillP);
            if (i == 2) {
                int col = ms == -2 || ms > 180 ? 0xFFFF8A80 : ms > 90 ? 0xFFFFB84D : 0x8CFFFFFF;
                msP.setColor(col);
                textL(c, msText, tx + pillP.measureText(labels[i]), r.centerY(), msP);
            }
            if (focused) focusRing(c, r, 30 * k, 3);
            node(labels[i], r, focused, i);
        }
    }

    private void drawStory(Canvas c) {
        if (stories.isEmpty()) return;
        Story s = stories.get(Math.min(storyIndex, stories.size() - 1));
        float x = M * k, width = 420 * k, top = 186 * k, height = 606 * k;
        // The title shrinks until it fits in four lines (headlines are long; titles are short).
        float[] sizes = {84, 66, 52, 44, 38};
        int use = 0;
        String key = s.title + "|" + k;
        if (storyLayout == null || !key.equals(storyLayoutFor)) {
            for (int i = 0; i < sizes.length; i++) {
                storyP.setTextSize(sizes[i] * k);
                int lines = 4;
                StaticLayout l = StaticLayout.Builder.obtain(s.title, 0, s.title.length(), storyP, (int) width)
                        .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false)
                        .setLineSpacing(0, sizes[i] <= 52 ? 1.22f : 1.12f).build();
                use = i;
                storyLayout = l;
                if (l.getLineCount() <= lines && (l.getEllipsisCount(Math.max(0, l.getLineCount() - 1)) == 0)) break;
            }
            storyLayoutFor = key;
        }
        float dotsH = 8 * k + 22 * k, kickerH = 34 * k + 16 * k, metaH = 22 * k + 34 * k, btnH = 36 * k + 62 * k;
        float block = dotsH + kickerH + storyLayout.getHeight() + metaH + btnH;
        float y = top + Math.max(40 * k, (height - block) / 2f);
        // Dots.
        float dx = x;
        for (int i = 0; i < Math.min(3, stories.size()); i++) {
            boolean on = i == Math.min(storyIndex, stories.size() - 1);
            float dw = on ? 26 * k : 8 * k;
            fill.setColor(on ? 0xE6FFFFFF : 0x59FFFFFF);
            box.set(dx, y, dx + dw, y + 8 * k);
            c.drawRoundRect(box, 4 * k, 4 * k, fill);
            dx += dw + 9 * k;
        }
        y += dotsH;
        textL(c, fit(s.kicker, kickerP, width), x, y + 17 * k, kickerP);
        y += kickerH;
        c.save();
        c.translate(x, y);
        storyLayout.draw(c);
        c.restore();
        y += storyLayout.getHeight();
        String line = s.app.isEmpty() ? s.meta : s.app + "  " + s.meta;
        float my = y + 22 * k + 17 * k;
        float mx = x;
        if (!s.app.isEmpty()) {
            textL(c, s.app, mx, my, metaAppP);
            mx += metaAppP.measureText(s.app) + 16 * k;
        }
        textL(c, fit(s.meta, metaP, x + width - mx), mx, my, metaP);
        y += metaH + 36 * k - 22 * k;
        // The button.
        String name = buttonLabel(s);
        float bw = 26 * k + 14 * k + btnP.measureText(name) + 34 * k, bh = 62 * k;
        storyBtn.set(x, y, x + bw, y + bh);
        dropShadow(c, storyBtn, 14 * k, 14 * k, 0x6B000000);
        panel(c, storyBtn, 14 * k, 0xF7FFFFFF, 0);
        playMark.rewind();
        playMark.moveTo(storyBtn.left + 26 * k, storyBtn.centerY() - 13 * k);
        playMark.lineTo(storyBtn.left + 26 * k, storyBtn.centerY() + 13 * k);
        playMark.lineTo(storyBtn.left + 26 * k + 22 * k, storyBtn.centerY());
        playMark.close();
        fill.setColor(0xFF111111);
        c.drawPath(playMark, fill);
        textL(c, name, storyBtn.left + 26 * k + 26 * k + 14 * k - 12 * k, storyBtn.centerY(), btnP);
        if (overlay == NONE && zone == Z_STORY) focusRing(c, storyBtn, 14 * k, 4);
        node(name + ", " + s.title, storyBtn, overlay == NONE && zone == Z_STORY, 10);
    }

    private String buttonLabel(Story s) {
        String pkg = s.pkg == null || s.pkg.isEmpty() ? Config.STORY_APP : s.pkg;
        try {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "เปิดแอป";
        }
    }

    private static String aqiWords(double a) {
        return a <= 50 ? "คุณภาพดี" : a <= 100 ? "ปานกลาง" : a <= 150 ? "เริ่มมีผลต่อสุขภาพ" : "มีผลต่อสุขภาพ";
    }

    private void drawWeather(Canvas c, long now) {
        RectF r = wxRect;
        float rad = 24 * k;
        dropShadow(c, r, rad, 24 * k, 0x80000000);
        panel(c, r, rad, 0xFF0A0D18, 0);
        Bitmap prev = photoPrev.isEmpty() ? null : photos.get(photoPrev), cur = photo();
        float fade = Math.min(1f, (SystemClock.uptimeMillis() - photoAt) / 1100f);
        if (prev != null && fade < 1f) coverImage(c, prev, r, rad, 255);
        if (cur != null) coverImage(c, cur, r, rad, Math.round(255 * (prev != null ? fade : 1f)));
        fill.setColor(0xFF000000);
        fill.setShader(new LinearGradient(0, r.bottom, 0, r.top, new int[] {0xCC03050C, 0x0A03050C, 0x0003050C}, new float[] {0f, .5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);

        HomeData.Weather wx = data.weather;
        float pad = 36 * k, x = r.left + pad, top = r.top + 32 * k;
        textL(c, fit(Prefs.city().isEmpty() ? "สภาพอากาศ" : Prefs.city(), wxCityP, 440 * k), x, top + 18 * k, wxCityP);
        String upd = data.weatherAt == 0 ? "กำลังโหลดข้อมูล…" : "อัปเดตล่าสุด " + new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(data.weatherAt));
        textL(c, upd, x, top + 54 * k, updP);
        double aqi = data.aqi;
        if (!Double.isNaN(aqi)) {
            String v = String.valueOf(Math.round(aqi));
            float pw = 14 * k + 14 * k + 10 * k + Math.max(aqiNumP.measureText(v), aqiLblP.measureText("US AQI")) + 14 * k, ph = 54 * k;
            box.set(r.right - pad - pw, top - 2 * k, r.right - pad, top - 2 * k + ph);
            panel(c, box, 14 * k, 0x66090B14, 0x24FFFFFF);
            int col = Lantern.aqiColor(aqi);
            fill.setColor(Ui.alpha(col, 0.28f));
            c.drawCircle(box.left + 14 * k + 7 * k, box.centerY(), 12 * k, fill);
            fill.setColor(col);
            c.drawCircle(box.left + 14 * k + 7 * k, box.centerY(), 7 * k, fill);
            float tx = box.left + 14 * k + 14 * k + 10 * k;
            textL(c, v, tx, box.centerY() - 8 * k, aqiNumP);
            textL(c, "US AQI", tx, box.centerY() + 16 * k, aqiLblP);
        }
        if (Double.isNaN(wx.temp)) return;

        // From the bottom up: the foot, the hours, then the temperature.
        float bottom = r.bottom - 32 * k;
        float footY = bottom - 11 * k;
        float fx = x;
        String sunrise = "พระอาทิตย์ขึ้น ", sunset = "ตก ", wind = "ลม ";
        textL(c, sunrise, fx, footY, footP);
        fx += footP.measureText(sunrise);
        String sr = wx.sunrise == 0 ? "–" : new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(wx.sunrise));
        textL(c, sr, fx, footY, footBoldP);
        fx += footBoldP.measureText(sr) + 28 * k;
        textL(c, sunset, fx, footY, footP);
        fx += footP.measureText(sunset);
        String ss = wx.sunset == 0 ? "–" : new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(wx.sunset));
        textL(c, ss, fx, footY, footBoldP);
        fx += footBoldP.measureText(ss) + 28 * k;
        if (!Double.isNaN(wx.wind)) {
            textL(c, wind + Math.round(wx.wind) + " กม./ชม.", fx, footY, footP);
        }
        float hoursBottom = bottom - 22 * k - 18 * k, hourH = 110 * k;
        int n = Math.min(5, wx.hours.size());
        float tw = (r.width() - 2 * pad - 4 * 10 * k) / 5f;
        for (int i = 0; i < n; i++) {
            float hx = x + i * (tw + 10 * k), hy = hoursBottom - hourH;
            box.set(hx, hy, hx + tw, hy + hourH);
            panel(c, box, 14 * k, 0x59090B14, 0x26FFFFFF);
            textC(c, new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(wx.hours.get(i))), box.centerX(), hy + 22 * k, hrTimeP);
            Icons.draw(c, Icons.weather(wx.hourCode.get(i), wx.hourDay.get(i)), box.centerX(), hy + 52 * k, 28 * k, Lantern.WHITE, true);
            textC(c, HomeData.degrees(wx.hourTemp.get(i)), box.centerX(), hy + 80 * k, hrValP);
            int rain = i < wx.hourRain.size() ? wx.hourRain.get(i) : -1;
            if (rain >= 20) textC(c, rain + "%", box.centerX(), hy + 99 * k, hrRainP);
        }
        float mainBottom = hoursBottom - hourH - 28 * k, mainH = 128 * k, mainTop = mainBottom - mainH;
        String temp = HomeData.degrees(wx.temp);
        c.drawText(temp, Math.round(x), Math.round(mainTop + mainH / 2f + Ui.cap(tempP) / 2f), tempP);
        float dx = x + tempP.measureText(temp) + 30 * k, mid = mainTop + mainH / 2f;
        textL(c, Thai.condition(wx.code, wx.isDay), dx, mid - 34 * k, condP);
        StringBuilder hl = new StringBuilder("สูง " + HomeData.degrees(wx.max) + " ต่ำ " + HomeData.degrees(wx.min));
        if (!Double.isNaN(wx.feels)) hl.append(" · รู้สึกได้ ").append(HomeData.degrees(wx.feels));
        if (wx.humidity >= 0) hl.append(" · ความชื้น ").append(wx.humidity).append('%');
        textL(c, fit(hl.toString(), hlP, r.right - pad - dx), dx, mid + 2 * k, hlP);
        if (!Double.isNaN(data.pm25)) {
            pmP.setColor(aqi <= 50 ? 0xD1FFFFFF : aqi <= 100 ? 0xFFF2C97A : 0xFFFF8A80);
            textL(c, fit("ฝุ่น PM2.5 " + Math.round(data.pm25) + " µg/m³ · " + (Double.isNaN(aqi) ? Thai.pm(data.pm25) : aqiWords(aqi)), pmP, r.right - pad - dx), dx, mid + 34 * k, pmP);
        }
    }

    private int cardColor(boolean night) {
        return night ? 0x9E120C10 : 0x8C1E1418;
    }

    private void drawGauge(Canvas c, float x, float right, float y, String name, String value, float frac, int[] colors) {
        textL(c, name, x, y + 9 * k, labelP);
        textR(c, value, right, y + 9 * k, valP);
        box.set(x, y + 28 * k, right, y + 38 * k);
        panel(c, box, 5 * k, 0x0FFFFFFF, 0x1FFFFFFF);
        if (frac > 0.005f) {
            box2.set(box.left, box.top, box.left + Math.max(10 * k, box.width() * Math.min(1f, frac)), box.bottom);
            fill.setColor(0xFF000000);
        fill.setShader(new LinearGradient(box2.left, 0, box2.right, 0, colors, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(box2, 5 * k, 5 * k, fill);
            fill.setShader(null);
        }
    }

    private void drawSystem(Canvas c, boolean night) {
        RectF r = sysRect;
        boolean focused = overlay == NONE && zone == Z_SYS;
        dropShadow(c, r, 24 * k, 24 * k, 0x80000000);
        panel(c, r, 24 * k, cardColor(night), 0x1FFFFFFF);
        float x = r.left + 22 * k, right = r.right - 22 * k, top = r.top + 20 * k;
        Icons.draw(c, "bolt", x + 11 * k, top + 11 * k, 22 * k, Lantern.LANTERN, true);
        textL(c, "สถานะเครื่อง", x + 32 * k, top + 11 * k, headP);
        long[] mem = Memory.snapshot(getContext());
        textR(c, Memory.size(mem[1]) + " RAM", right, top + 11 * k, faintP);
        int ram = status.ramPercent(), cpu = status.cpu;
        int[] green = {0xFF7FE39A, 0xFF30D158}, amber = {0xFFF2A93B, 0xFFFF6B4A}, red = {0xFFFF6B4A, 0xFFFF453A}, mid = {0xFFF2A93B, 0xFFFF9F0A};
        drawGauge(c, x, right, top + 40 * k, "RAM", ram + "%", ram / 100f, ram < 45 ? green : amber);
        drawGauge(c, x, right, top + 40 * k + 62 * k, "CPU", cpu < 0 ? "–" : cpu + "%", Math.max(0, cpu) / 100f, cpu < 60 ? green : cpu < 85 ? mid : red);
        int ms = status.pingMs;
        drawGauge(c, x, right, top + 40 * k + 124 * k, "เครือข่าย", ms > 0 ? ms + " ms" : ms == -2 ? "ออฟไลน์" : "–", ms > 0 ? Math.min(1f, ms / 300f) : 0f,
                ms > 180 ? red : ms > 90 ? mid : green);
        float hy = r.bottom - 22 * k - 8 * k;
        Icons.draw(c, "play_circle", x + 8 * k, hy, 16 * k, Lantern.FAINT, true);
        textL(c, "กด OK ที่การ์ดนี้เพื่อล้าง RAM", x + 24 * k, hy, hintP);
        if (focused) focusRing(c, r, 24 * k, 4);
        node("สถานะเครื่อง RAM " + ram + " เปอร์เซ็นต์ กด OK เพื่อล้าง RAM", r, focused, 11);
    }

    private static final int[] DOT_COLORS = {0xFF4285F4, 0xFF34A853, 0xFFF2A93B, 0xFFEA4335};

    private void drawCalendar(Canvas c, long now, boolean night) {
        RectF r = calRect;
        boolean focused = overlay == NONE && zone == Z_CAL;
        dropShadow(c, r, 24 * k, 24 * k, 0x80000000);
        panel(c, r, 24 * k, cardColor(night), 0x1FFFFFFF);
        float x = r.left + 22 * k, right = r.right - 22 * k, top = r.top + 20 * k;
        // The calendar glyph in Google's blue.
        box.set(x + 1 * k, top + 1 * k, x + 21 * k, top + 21 * k);
        fill.setColor(0xFF4285F4);
        c.drawRoundRect(box, 4 * k, 4 * k, fill);
        fill.setColor(0xFFFFFFFF);
        c.drawRect(box.left, box.top + 6 * k, box.right, box.top + 8 * k, fill);
        c.drawCircle(box.left + 6 * k, box.top + 13 * k, 1.8f * k, fill);
        c.drawCircle(box.left + 11 * k, box.top + 13 * k, 1.8f * k, fill);
        c.drawCircle(box.left + 15 * k, box.top + 16 * k, 1.8f * k, fill);
        textL(c, "Google Calendar", x + 32 * k, top + 11 * k, headP);
        textR(c, Thai.tinyDate(now), right, top + 11 * k, faintP);
        Ical ical = Prefs.cardCalendar() ? feed.calendar() : null;
        float y = top + 40 * k;
        if (!Prefs.cardCalendar()) {
            textL(c, "ปิดการ์ดนี้อยู่ในการตั้งค่า", x, y + 8 * k, calEmptyP);
        } else if (ical == null) {
            String hint = !Feed.hasCalendar() ? "ใส่ลิงก์ .ics ใน config.properties (ical_url)" : feed.calendarFailed() ? "โหลดปฏิทินไม่ได้ ตรวจลิงก์" : "กำลังโหลดกำหนดการ…";
            textL(c, fit(hint, calEmptyP, right - x), x, y + 8 * k, calEmptyP);
        } else {
            java.util.Calendar day = new java.util.GregorianCalendar();
            day.setTimeInMillis(now);
            day.set(java.util.Calendar.HOUR_OF_DAY, 0);
            day.set(java.util.Calendar.MINUTE, 0);
            day.set(java.util.Calendar.SECOND, 0);
            long startOfDay = day.getTimeInMillis();
            List<Ical.Event> events = ical.between(now - 2 * 3_600_000L, startOfDay + 14 * 86_400_000L);
            // In calendar order: by the day it is shown on, all-day first, then by start (between() lists all-day ones first).
            final long dayStart = startOfDay;
            events = new ArrayList<>(events);
            Collections.sort(events, new java.util.Comparator<Ical.Event>() {
                @Override public int compare(Ical.Event a, Ical.Event b) {
                    long da = Math.floorDiv(Math.max(a.start, dayStart) - dayStart, 86_400_000L), db = Math.floorDiv(Math.max(b.start, dayStart) - dayStart, 86_400_000L);
                    if (da != db) return da < db ? -1 : 1;
                    if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
                    return Long.compare(a.start, b.start);
                }
            });
            int shown = 0;
            Ical.Event next = null;
            // The "next up" line at the bottom takes the room of the fifth row.
            Ical.Event soon = null;
            for (Ical.Event e : events) if (!e.allDay && (e.end > now || e.end == e.start) && soon == null) soon = e;
            boolean footer = soon != null && soon.start - now < 8 * 3_600_000L;
            int limit = footer ? 4 : 5;
            float col = 118 * k, rowH = (footer ? 44 : 42) * k;
            for (Ical.Event e : events) {
                if (!e.allDay && e.end <= now && e.end > e.start) continue;
                if (next == null && !e.allDay) next = e;
                if (shown >= limit) continue;
                long shownDay = Math.max(e.start, startOfDay);
                int away = (int) Math.round((shownDay - startOfDay) / 86_400_000.0);
                String dayText = away <= 0 ? "วันนี้" : away == 1 ? "พรุ่งนี้" : Thai.tinyDate(shownDay);
                String timeText = e.allDay ? "ทั้งวัน" : new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(e.start));
                float cy = y + rowH / 2f - 2 * k;
                calDayP.setColor(away <= 0 ? Lantern.LANTERN : away == 1 ? Lantern.TEXT : Lantern.DIM);
                textL(c, dayText, x, cy - 10 * k, calDayP);
                calTimeP.setColor(away <= 0 ? Lantern.DIM : Lantern.FAINT);
                textL(c, timeText, x, cy + 10 * k, calTimeP);
                fill.setColor(DOT_COLORS[shown % DOT_COLORS.length]);
                c.drawCircle(x + col, cy, 4 * k, fill);
                textL(c, fit(e.title.isEmpty() ? "(ไม่มีชื่อ)" : e.title, calNameP, right - (x + col + 20 * k)), x + col + 18 * k, cy, calNameP);
                y += rowH;
                shown++;
            }
            if (shown == 0) textL(c, "ไม่มีกำหนดการใน 14 วันนี้", x, y + 8 * k, calEmptyP);
            float ny = r.bottom - 22 * k - 8 * k;
            if (next != null && next.start - now < 8 * 3_600_000L) {
                float pulse = 0.55f + 0.45f * (float) Math.abs(Math.sin(SystemClock.uptimeMillis() / 1000.0 * Math.PI / 2));
                fill.setColor(Ui.alpha(Lantern.DOWN, pulse));
                c.drawCircle(x + 4 * k, ny, 4 * k, fill);
                String t = next.start <= now ? "ตอนนี้ · " : "ถัดไป " + new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(next.start)) + " · ";
                textL(c, fit(t + next.title, calNowP, right - x - 20 * k), x + 18 * k, ny, calNowP);
            }
        }
        if (focused) focusRing(c, r, 24 * k, 4);
        node("Google Calendar", r, focused, 12);
    }

    private android.graphics.RenderNode layer;
    private int layerW, layerH;
    private boolean layerDirty = true;
    private long layerSig;
    private final List<Node> cachedNodes = new ArrayList<>();

    /** The home page without the moving parts: everything but the tiles that are growing or shrinking. */
    private void drawStatic(Canvas c, long now, boolean night) {
        drawSky(c, night);
        drawHeader(c, now);
        drawPills(c);
        drawStory(c);
        drawWeather(c, now);
        drawSystem(c, night);
        drawCalendar(c, now, night);
        if (tileRect.length != shelf.size() + 1) layoutShelf();
        dropShadow(c, platter, 54 * k, 26 * k, 0x80000000);
        panel(c, platter, 54 * k, night ? 0x990E090C : 0x80180E12, 0x1FFFFFFF);
        for (int i = 0; i < tileRect.length; i++) drawTile(c, i, 0);
    }

    /** The tiles in motion (and the focused one), over the recorded page. */
    private void drawLiveShelf(Canvas c) {
        if (tileRect.length != shelf.size() + 1) { layerDirty = true; layoutShelf(); invalidate(); return; }
        int top = -1;
        for (int i = 0; i < tileRect.length; i++) {
            boolean f = overlay == NONE && zone == Z_SHELF && shelfIndex == i;
            tilePop[i].target = f ? 1 : 0;
            if (f) top = i;
            HomeApps.Item it = i < shelf.size() ? shelf.get(i) : null;
            node(it == null ? "ทั้งหมด" : it.label, tileRect[i], f, 100 + i);
        }
        boolean cached = layer != null && android.os.Build.VERSION.SDK_INT >= 29 && c.isHardwareAccelerated();
        for (int i = 0; i < tileRect.length; i++) {
            if (i == top || !(tilePop[i].value > 0.004f || !cached)) continue;
            drawTile(c, i, Math.max(0, tilePop[i].value));
        }
        if (top >= 0) drawTile(c, top, Math.max(0, tilePop[top].value));
    }

    private void drawTile(Canvas c, int i, float f) {
        RectF base = tileRect[i];
        float s = 1 + 0.16f * f;
        float cx = base.centerX(), cy = base.centerY() - 14 * k * f;
        RectF cell = tileCell;
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 22 * k * s;
        dropShadow(c, cell, r, (8 + 26 * f) * k, Ui.alpha(0xFF000000, 0.5f));
        HomeApps.Item it = i < shelf.size() ? shelf.get(i) : null;
        if (it == null) {
            fill.setColor(0xFF000000);
        if (allShader == null) allShader = new LinearGradient(0, 0, 0, 1, 0xFF3A2A31, 0xFF1C1216, Shader.TileMode.CLAMP);
            matrix.setScale(1, cell.height());
            matrix.postTranslate(0, cell.top);
            allShader.setLocalMatrix(matrix);
            fill.setShader(allShader);
            c.drawRoundRect(cell, r, r, fill);
            fill.setShader(null);
            Icons.draw(c, "apps", cell.centerX(), cell.centerY() - 14 * k * s, 34 * k * s, Lantern.WHITE, true);
            textC(c, "ทั้งหมด", cell.centerX(), cell.centerY() + 26 * k * s, tileLabelP);
        } else if ("youtube".equals(it.key)) {
            panel(c, cell, r, 0xFFFF0000, 0);
            drawPlayMark(c, cell.centerX(), cell.centerY(), cell.height() * 0.36f);
        } else if ("netflix".equals(it.key) && it.logo != null) {
            panel(c, cell, r, 0xFFFFFFFF, 0);
            float lw = Math.min(cell.width() * 0.62f, it.logo.getWidth() * (cell.height() * 0.5f / Math.max(1, it.logo.getHeight())));
            float lh = lw * it.logo.getHeight() / it.logo.getWidth();
            box.set(cell.centerX() - lw / 2, cell.centerY() - lh / 2, cell.centerX() + lw / 2, cell.centerY() + lh / 2);
            c.drawBitmap(it.logo, null, box, imagePaint);
        } else if (it.tile != null) {
            stretchImage(c, it.tile, cell, r);
        } else {
            panel(c, cell, r, 0xFF2A1F24, 0);
            textC(c, fit(it.label, tileLabelP, cell.width() - 20 * k), cell.centerX(), cell.centerY(), tileLabelP);
        }
        // Gloss at the top, like the mockup's tiles.
        fill.setColor(0xFF000000);
        if (glossShader == null) glossShader = new LinearGradient(0, 0, 0, 1, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        matrix.setScale(1, cell.height() * .42f);
        matrix.postTranslate(0, cell.top);
        glossShader.setLocalMatrix(matrix);
        fill.setShader(glossShader);
        c.drawRoundRect(cell, r, r, fill);
        fill.setShader(null);
        boolean on = overlay == NONE && zone == Z_SHELF && shelfIndex == i;
        if (f > 0.4f && on) focusRing(c, cell, r, 2);
    }

    private final RectF tileCell = new RectF();
    private LinearGradient allShader, glossShader;

    private final android.graphics.Path playMark = new android.graphics.Path();

    /** YouTube's white play triangle, `height` tall, centered on its centroid (it reads as centered). */
    private void drawPlayMark(Canvas c, float cx, float cy, float height) {
        float tw = height * 23.5f / 27f;
        float left = cx - tw / 3f;
        playMark.rewind();
        playMark.moveTo(left, cy - height / 2);
        playMark.lineTo(left, cy + height / 2);
        playMark.lineTo(left + tw, cy);
        playMark.close();
        fill.setColor(0xFFFFFFFF);
        c.drawPath(playMark, fill);
    }

    private void drawToast(Canvas c) {
        if (toastAt == 0) return;
        long age = SystemClock.uptimeMillis() - toastAt;
        if (age > 3400) {
            toastAt = 0;
            return;
        }
        float a = age < 350 ? age / 350f : age > 2900 ? Math.max(0, (3400 - age) / 500f) : 1;
        float tw = toastP.measureText(toastText), bw = 20 * k + 38 * k + 14 * k + tw + 26 * k, bh = 70 * k;
        float x = (w - bw) / 2f, y = h - 120 * k - bh + (1 - a) * 24 * k;
        box.set(x, y, x + bw, y + bh);
        int oldAlpha = 255;
        panel(c, box, 18 * k, Ui.alpha(0xD10C0E18, a), Ui.alpha(0x29FFFFFF, a));
        fill.setColor(Ui.alpha(0xFF2E8E3E, a));
        c.drawCircle(box.left + 20 * k + 19 * k, box.centerY(), 19 * k, fill);
        Icons.draw(c, "check", box.left + 20 * k + 19 * k, box.centerY(), 22 * k, Ui.alpha(0xFFFFFFFF, a), true);
        toastP.setAlpha(Math.round(255 * a));
        textL(c, toastText, box.left + 20 * k + 38 * k + 14 * k, box.centerY(), toastP);
        toastP.setAlpha(oldAlpha);
    }

    // MARK: - Overlays: quick actions, inputs, All Apps

    private Paint qaTitleP, qaLabelP, qaSubP, qaBtnP, segP, segOnP, appsTitleP, appsCountP, chipP, chipOnP, cellP, appsGreetP, kbdP, footHintP, searchP;
    private int qaIndex, qaSleepSel, inputsIndex;
    private static final int[] SLEEP_MIN = {0, 15, 30, 60};
    private static final String[] SLEEP_LABEL = {"ปิด", "15 น.", "30 น.", "60 น."};

    private static final int A_SEARCH = 0, A_CHIPS = 1, A_GRID = 2, COLS = 6;
    private int appsZone = A_GRID, appsChip, appsFocus, appsFilter;
    private float appsScroll, appsScrollTarget;
    private String appsQuery = "";
    private final List<HomeApps.Item> appsList = new ArrayList<>();

    private void makeOverlayPaints() {
        qaTitleP = P(Ui.displayBold, 34, Lantern.WHITE);
        qaLabelP = P(Ui.medium, 23, Lantern.WHITE);
        qaSubP = P(Ui.regular, 15, 0x73FFFFFF);
        qaBtnP = P(Ui.medium, 19, Lantern.WHITE);
        segP = P(Ui.medium, 18, 0x99FFFFFF);
        segOnP = P(Ui.displayBold, 18, Lantern.ON_LANTERN);
        appsTitleP = P(Ui.displayBold, 52, Lantern.WHITE);
        appsCountP = N(Ui.regular, 20, 0x80FFFFFF);
        chipP = P(Ui.medium, 19, 0x99FFFFFF);
        chipOnP = P(Ui.medium, 19, Lantern.WHITE);
        cellP = P(Ui.medium, 19, 0xD1FFFFFF);
        appsGreetP = P(Ui.medium, 26, 0x99FFFFFF);
        kbdP = N(Ui.regular, 15, 0xB3FFFFFF);
        footHintP = P(Ui.regular, 18, 0x73FFFFFF);
        searchP = P(Ui.regular, 22, 0x66FFFFFF);
    }

    private void buildApps() {
        List<HomeApps.Item> base = new ArrayList<>();
        String q = appsQuery.trim().toLowerCase(Locale.ROOT);
        for (HomeApps.Item it : everything) if (q.isEmpty() || it.label.toLowerCase(Locale.ROOT).contains(q)) base.add(it);
        if (appsFilter == 2) {
            final Collator collator = Collator.getInstance(new Locale("th", "TH"));
            Collections.sort(base, (a, b) -> collator.compare(a.label, b.label));
        } else if (appsFilter == 1) {
            final List<String> recent = Prefs.recents();
            Collections.sort(base, (a, b) -> {
                int ia = recent.indexOf(a.pkg), ib = recent.indexOf(b.pkg);
                if (ia < 0) ia = 999;
                if (ib < 0) ib = 999;
                return ia - ib;
            });
        }
        appsList.clear();
        appsList.addAll(base);
        appsFocus = Math.max(0, Math.min(appsFocus, appsList.size() - 1));
    }

    private void drawOverlay(Canvas c, long now, boolean night) {
        if (overlay == APPS) {
            drawApps(c, now);
            return;
        }
        fill.setColor(0xA6000000);
        c.drawRect(0, 0, w, h, fill);
        float pw = 760 * k, x = (w - pw) / 2f;
        boolean quick = overlay == QUICK;
        int rows = quick ? 4 : Math.max(1, Math.min(7, inputs.size()));
        float rowH = quick ? 96 * k : 74 * k;
        float ph = 34 * k + 42 * k + 8 * k + rows * rowH + (quick ? 14 * k + 22 * k : 8 * k) + 34 * k;
        float y = (h - ph) / 2f;
        box.set(x, y, x + pw, y + ph);
        dropShadow(c, box, 26 * k, 30 * k, 0x99000000);
        panel(c, box, 26 * k, 0xEE0E090C, 0x24FFFFFF);
        textL(c, quick ? "การกระทำด่วน" : "เลือกแหล่งสัญญาณ", x + 40 * k, y + 34 * k + 21 * k, qaTitleP);
        float ry = y + 34 * k + 42 * k + 8 * k;
        if (quick) {
            String[][] rowsText = {{"ตั้งเวลาปิดเครื่อง", "ให้ทีวีดับอัตโนมัติ"}, {"ล้าง RAM", "ปิดโปรเซสเบื้องหลัง คืนหน่วยความจำ"},
                    {"ปิดแอปที่เปิดค้าง", "ปิดแอปที่เปิดอยู่เบื้องหลัง (ถามยืนยันก่อน)"}, {"พักหน้าจอทันที", "เปิดภาพพักหน้าจอตอนนี้"}};
            int left = SleepTimer.minutesLeft();
            for (int i = 0; i < 4; i++) {
                float cy = ry + rowH / 2f;
                fill.setColor(0x14FFFFFF);
                c.drawRect(x + 40 * k, ry, x + pw - 40 * k, ry + Math.max(1, k), fill);
                textL(c, rowsText[i][0], x + 40 * k, cy - 14 * k, qaLabelP);
                String sub = i == 0 && left > 0 ? "เหลืออีก " + left + " นาที" : rowsText[i][1];
                textL(c, fit(sub, qaSubP, 420 * k), x + 40 * k, cy + 18 * k, qaSubP);
                RectF ctl = new RectF();
                if (i == 0) {
                    float sw = 8 * k;
                    for (int j = 0; j < 4; j++) sw += segOnP.measureText(SLEEP_LABEL[j]) + 28 * k + (j > 0 ? 4 * k : 0);
                    ctl.set(x + pw - 40 * k - sw, cy - 26 * k, x + pw - 40 * k, cy + 26 * k);
                    panel(c, ctl, 14 * k, 0x14FFFFFF, 0x1AFFFFFF);
                    float sx = ctl.left + 4 * k;
                    for (int j = 0; j < 4; j++) {
                        float bw = segOnP.measureText(SLEEP_LABEL[j]) + 28 * k;
                        if (j == qaSleepSel) {
                            box2.set(sx, cy - 22 * k, sx + bw, cy + 22 * k);
                            fill.setColor(Lantern.LANTERN);
                            c.drawRoundRect(box2, 10 * k, 10 * k, fill);
                        }
                        Paint pp = j == qaSleepSel ? segOnP : segP;
                        textC(c, SLEEP_LABEL[j], sx + bw / 2f, cy, pp);
                        sx += bw + 4 * k;
                    }
                } else {
                    String label = i == 1 ? "ล้าง" : i == 2 ? "ปิด" : "พัก";
                    float bw = qaBtnP.measureText(label) + 40 * k;
                    ctl.set(x + pw - 40 * k - bw, cy - 24 * k, x + pw - 40 * k, cy + 24 * k);
                    panel(c, ctl, 12 * k, 0x1AFFFFFF, 0x29FFFFFF);
                    textC(c, Memory.busy() && i == 1 ? "…" : label, ctl.centerX(), cy, qaBtnP);
                }
                if (qaIndex == i) focusRing(c, ctl, 12 * k, 3);
                node(rowsText[i][0] + (i == 0 ? ", " + SLEEP_LABEL[qaSleepSel] : ""), ctl, qaIndex == i, i);
                ry += rowH;
            }
            textC(c, "OK เลือก · ↑ แถวแรก/Back ปิด", w / 2f, ry + 14 * k + 11 * k, hintP);
        } else if (inputs.isEmpty()) {
            textC(c, "ไม่พบแหล่งสัญญาณ", w / 2f, ry + 37 * k, qaLabelP);
        } else {
            int first = Math.max(0, Math.min(inputsIndex - 3, inputs.size() - rows));
            for (int i = 0; i < rows; i++) {
                HomeApps.Item it = inputs.get(first + i);
                float cy = ry + rowH / 2f;
                box.set(x + 24 * k, ry + 4 * k, x + pw - 24 * k, ry + rowH - 4 * k);
                boolean f = first + i == inputsIndex;
                if (f) panel(c, box, 16 * k, 0x1FFFFFFF, 0);
                String icon = it.kind == HomeApps.LIVE_TV ? "live_tv" : "hdmi";
                Icons.draw(c, icon, box.left + 36 * k, cy, 30 * k, it.connected ? Lantern.LANTERN : 0x66FFFFFF, true);
                qaLabelP.setColor(it.connected ? Lantern.WHITE : 0x73FFFFFF);
                textL(c, fit(it.label, qaLabelP, 480 * k), box.left + 72 * k, cy, qaLabelP);
                qaLabelP.setColor(Lantern.WHITE);
                if (!it.connected) textR(c, "ไม่มีสัญญาณ", box.right - 24 * k, cy, qaSubP);
                if (f) focusRing(c, box, 16 * k, 3);
                node(it.label, box, f, first + i);
                ry += rowH;
            }
        }
    }

    private void drawApps(Canvas c, long now) {
        fill.setColor(0xFF000000);
        fill.setShader(Lantern.pageSky(w, h));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        float x = M * k;
        textL(c, Thai.greeting(now) + ", " + Prefs.name(), x, 56 * k + 13 * k + 4 * k, appsGreetP);
        // Title, count and the search pill.
        float ty = 56 * k + 26 * k + 14 * k;
        c.drawText("แอปทั้งหมด", Math.round(x), Math.round(ty + 52 * k * 0.82f), appsTitleP);
        float tw = appsTitleP.measureText("แอปทั้งหมด");
        c.drawText(appsList.size() + " แอป", Math.round(x + tw + 20 * k), Math.round(ty + 52 * k * 0.82f), appsCountP);
        RectF search = new RectF((1920 - M - 420) * k, ty + 6 * k, (1920 - M) * k, ty + 66 * k);
        panel(c, search, 30 * k, 0x0FFFFFFF, 0x1FFFFFFF);
        Icons.draw(c, "search", search.left + 20 * k + 13 * k, search.centerY(), 26 * k, 0x8CFFFFFF, true);
        textL(c, appsQuery.isEmpty() ? "ค้นหาแอป…" : fit(appsQuery, searchP, 340 * k), search.left + 20 * k + 26 * k + 12 * k, search.centerY(), searchP);
        if (appsZone == A_SEARCH) focusRing(c, search, 30 * k, 3);
        node("ค้นหาแอป", search, appsZone == A_SEARCH, -1);
        // Filter chips.
        String[] chips = {"ทั้งหมด", "ใช้ล่าสุด", "ก–ฮ / A–Z"};
        float cx = x, cy = ty + 66 * k + 26 * k;
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == appsFilter;
            float cw = chipP.measureText(chips[i]) + 40 * k, ch = 44 * k;
            box.set(cx, cy, cx + cw, cy + ch);
            panel(c, box, 22 * k, on ? 0x29F2A93B : 0x0DFFFFFF, on ? 0x80F2A93B : 0x1AFFFFFF);
            textC(c, chips[i], box.centerX(), box.centerY(), on ? chipOnP : chipP);
            if (appsZone == A_CHIPS && appsChip == i) focusRing(c, box, 22 * k, 3);
            node(chips[i], box, appsZone == A_CHIPS && appsChip == i, -2 - i);
            cx += cw + 12 * k;
        }
        // The grid.
        float gridTop = cy + 44 * k + 30 * k, gap = 30 * k, cw = ((1920 - 2 * M) * k - (COLS - 1) * gap) / COLS, ci = cw * 9f / 16f;
        float rowPitch = ci + 12 * k + 26 * k + 34 * k;
        int rowsTotal = (appsList.size() + COLS - 1) / COLS;
        float viewport = 1000 * k - gridTop;
        int fr = appsFocus / COLS;
        float want = Math.max(0, Math.min(Math.max(0, rowsTotal * rowPitch - viewport), (fr + 1) * rowPitch - viewport + 20 * k));
        if (appsZone == A_GRID) appsScrollTarget = fr * rowPitch < appsScroll ? fr * rowPitch : Math.max(appsScrollTarget < want ? want : appsScrollTarget, 0);
        appsScrollTarget = Math.max(0, Math.min(Math.max(0, rowsTotal * rowPitch - viewport), appsScrollTarget));
        appsScroll += (appsScrollTarget - appsScroll) * 0.3f;
        if (Math.abs(appsScrollTarget - appsScroll) > 0.5f) invalidate(); else appsScroll = appsScrollTarget;
        c.save();
        c.clipRect(0, gridTop - 14 * k, w, 1000 * k);
        if (appsList.isEmpty()) textC(c, "ไม่พบแอปที่ค้นหา", w / 2f, gridTop + 120 * k, qaLabelP);
        for (int i = 0; i < appsList.size(); i++) {
            int r = i / COLS, col = i % COLS;
            float y = gridTop + r * rowPitch - appsScroll, cxx = x + col * (cw + gap);
            if (y > 1000 * k || y + rowPitch < gridTop - 20 * k) continue;
            HomeApps.Item it = appsList.get(i);
            boolean f = appsZone == A_GRID && i == appsFocus;
            float s = f ? 1.06f : 1f;
            RectF cell = new RectF(cxx + cw / 2 - cw * s / 2, y + ci / 2 - ci * s / 2, cxx + cw / 2 + cw * s / 2, y + ci / 2 + ci * s / 2);
            dropShadow(c, cell, 20 * k * s, 10 * k, 0x66000000);
            if (it.tile != null) stretchImage(c, it.tile, cell, 20 * k * s);
            else panel(c, cell, 20 * k * s, 0xFF2A1F24, 0);
            fill.setColor(0xFF000000);
        fill.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * .42f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, 20 * k * s, 20 * k * s, fill);
            fill.setShader(null);
            cellP.setColor(f ? Lantern.WHITE : 0xD1FFFFFF);
            textC(c, fit(it.label, cellP, cw), cxx + cw / 2, y + ci + 12 * k + 13 * k, cellP);
            if (f) {
                stroke.setStrokeWidth(5 * k);
                stroke.setColor(Lantern.WHITE);
                box2.set(cell);
                box2.inset(-2.5f * k, -2.5f * k);
                c.drawRoundRect(box2, 22 * k * s, 22 * k * s, stroke);
            }
            if (y + ci > gridTop && y < 1000 * k) node(it.label, cell, f, i);
        }
        c.restore();
        // Footer hints.
        float fx = x, fy = 1038 * k;
        String[][] parts = {{"↑↓←→", " เลื่อน"}, {"OK", " เปิด"}, {"Back", " กลับหน้าโฮม"}};
        for (int i = 0; i < parts.length; i++) {
            float kw = kbdP.measureText(parts[i][0]) + 18 * k;
            box.set(fx, fy - 22 * k, fx + kw, fy + 8 * k);
            panel(c, box, 7 * k, 0x14FFFFFF, 0x24FFFFFF);
            textC(c, parts[i][0], box.centerX(), box.centerY(), kbdP);
            fx += kw;
            String after = parts[i][1] + (i < parts.length - 1 ? "  ·  " : "");
            textL(c, after, fx, box.centerY(), footHintP);
            fx += footHintP.measureText(after);
        }
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
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
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled() && overlay != NONE) closeOverlay();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void closeOverlay() {
        overlay = NONE;
        click.move();
        invalidate();
        spoken.focusMoved();
    }

    private void open(int which) {
        overlay = which;
        if (which == APPS) {
            appsZone = A_GRID;
            appsFocus = 0;
            appsScroll = appsScrollTarget = 0;
            appsQuery = "";
            appsFilter = 0;
            appsChip = 0;
            buildApps();
        } else if (which == QUICK) {
            qaIndex = 1;
        } else {
            inputsIndex = 0;
        }
        click.open();
        invalidate();
        spoken.focusMoved();
    }

    private int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float dist = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < dist) {
                dist = d;
                best = i;
            }
        }
        return best;
    }

    private void move(int dx, int dy) {
        boolean changed = false;
        if (overlay == QUICK) {
            if (dy != 0 && qaIndex + dy >= 0 && qaIndex + dy < 4) { qaIndex += dy; changed = true; }
            else if (dy < 0 && qaIndex == 0) { closeOverlay(); return; }
            else if (dx != 0 && qaIndex == 0) { setSleep((qaSleepSel + dx + 4) % 4); changed = true; }
        } else if (overlay == INPUTS) {
            if (dy != 0 && inputsIndex + dy >= 0 && inputsIndex + dy < inputs.size()) { inputsIndex += dy; changed = true; }
        } else if (overlay == APPS) {
            changed = moveApps(dx, dy);
        } else {
            changed = moveHome(dx, dy);
        }
        if (changed) {
            click.move();
            invalidate();
            spoken.focusMoved();
        }
    }

    private boolean moveHome(int dx, int dy) {
        switch (zone) {
            case Z_TOP:
                if (dx > 0 && topIndex < 3) { topIndex++; return true; }
                if (dx < 0 && topIndex > 0) { topIndex--; return true; }
                if (dx > 0 || dy > 0) { zone = Z_STORY; return true; }
                return false;
            case Z_STORY:
                if (dy < 0) { zone = Z_TOP; topIndex = 0; return true; }
                if (dx > 0) { zone = Z_SYS; return true; }
                if (dy > 0) { zone = Z_SHELF; shelfIndex = nearest(tileRect, tileRect.length, storyBtn.centerX()); return true; }
                return false;
            case Z_SYS:
                if (dy < 0) { zone = Z_TOP; topIndex = nearest(pill, 4, sysRect.centerX()); return true; }
                if (dy > 0) { zone = Z_CAL; return true; }
                if (dx < 0) { zone = Z_STORY; return true; }
                return false;
            case Z_CAL:
                if (dy < 0) { zone = Z_SYS; return true; }
                if (dy > 0) { zone = Z_SHELF; shelfIndex = nearest(tileRect, tileRect.length, calRect.centerX()); return true; }
                if (dx < 0) { zone = Z_STORY; return true; }
                return false;
            default: { // shelf
                int n = tileRect.length;
                if (dx != 0) {
                    int next = Math.max(0, Math.min(n - 1, shelfIndex + dx));
                    if (next == shelfIndex) return false;
                    shelfIndex = next;
                    return true;
                }
                if (dy < 0) {
                    zone = tileRect[shelfIndex].centerX() >= calRect.left - 40 * k ? Z_CAL : Z_STORY;
                    return true;
                }
                open(APPS);
                return false;
            }
        }
    }

    private boolean moveApps(int dx, int dy) {
        if (appsZone == A_SEARCH) {
            if (dy > 0) { appsZone = A_CHIPS; appsChip = appsFilter; return true; }
            return false;
        }
        if (appsZone == A_CHIPS) {
            if (dx != 0) { appsChip = Math.max(0, Math.min(2, appsChip + dx)); return true; }
            if (dy < 0) { appsZone = A_SEARCH; return true; }
            if (dy > 0 && !appsList.isEmpty()) { appsZone = A_GRID; appsFocus = 0; return true; }
            return false;
        }
        int n = appsList.size();
        if (n == 0) { if (dy < 0) { appsZone = A_CHIPS; return true; } return false; }
        int row = appsFocus / COLS, col = appsFocus % COLS;
        if (dx != 0) {
            int next = appsFocus + dx;
            if (next < 0 || next >= n || next / COLS != row) return false;
            appsFocus = next;
            return true;
        }
        if (dy < 0) {
            if (row == 0) { appsZone = A_CHIPS; appsChip = appsFilter; return true; }
            appsFocus -= COLS;
            return true;
        }
        if (dy > 0) {
            int next = Math.min(n - 1, appsFocus + COLS);
            if (next / COLS == row) return false;
            appsFocus = next;
            return true;
        }
        return false;
    }

    private void setSleep(int sel) {
        qaSleepSel = sel;
        SleepTimer.set(getContext(), SLEEP_MIN[sel]);
        showToast(sel == 0 ? "ยกเลิกการตั้งเวลาปิดเครื่อง" : "ตั้งเวลาปิดเครื่องอีก " + SLEEP_MIN[sel] + " นาที");
    }

    // MARK: - Activation

    private void activate() {
        if (overlay == QUICK) {
            if (qaIndex == 0) {
                setSleep((qaSleepSel + 1) % 4);
            } else if (qaIndex == 1) {
                Memory.clear(getContext().getApplicationContext(), new Memory.Result() {
                    @Override public void done(String text) { showToast(text); }
                });
                showToast("กำลังล้าง RAM…");
                overlay = NONE;
            } else if (qaIndex == 2) {
                Memory.closeApps(getContext().getApplicationContext(), new Memory.Result() {
                    @Override public void done(String text) { showToast(text); }
                });
                showToast("กำลังตรวจแอปที่เปิดอยู่…");
            } else {
                overlay = NONE;
                Intent i = new Intent(activity, SettingsActivity.class).putExtra("screensaver", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            }
            click.open();
            invalidate();
            return;
        }
        if (overlay == INPUTS) {
            if (inputsIndex < inputs.size()) {
                HomeApps.Item it = inputs.get(inputsIndex);
                overlay = NONE;
                if (!HomeApps.open(activity, it, this, 0, 0, w, h)) showToast("เปิด " + it.label + " ไม่ได้");
            }
            invalidate();
            return;
        }
        if (overlay == APPS) {
            activateApps();
            return;
        }
        click.open();
        switch (zone) {
            case Z_TOP:
                if (topIndex == 0) open(QUICK);
                else if (topIndex == 1) open(INPUTS);
                else if (topIndex == 2) {
                    status.refreshIfStale();
                    int ms = status.pingMs;
                    showToast("เครือข่าย " + (status.link.isEmpty() ? "ออนไลน์" : status.link) + (ms > 0 ? " · " + ms + " ms" : ms == -2 ? " · ออฟไลน์" : ""));
                } else {
                    HomeApps.Item it = HomeApps.settings();
                    if (!HomeApps.open(activity, it, this, Math.round(pill[3].left), Math.round(pill[3].top), Math.round(pill[3].width()), Math.round(pill[3].height()))) showToast("เปิดการตั้งค่าไม่ได้");
                }
                break;
            case Z_STORY: {
                Story s = stories.isEmpty() ? null : stories.get(Math.min(storyIndex, stories.size() - 1));
                String pkg = s == null || s.pkg == null || s.pkg.isEmpty() ? Config.STORY_APP : s.pkg;
                HomeApps.Item it = HomeApps.app(pkg, "");
                if (!HomeApps.open(activity, it, this, Math.round(storyBtn.left), Math.round(storyBtn.top), Math.round(storyBtn.width()), Math.round(storyBtn.height()))) {
                    showToast("เปิดแอปไม่ได้ ตรวจชื่อแอปใน config.properties (story_app)");
                }
                break;
            }
            case Z_SYS:
                Memory.clear(getContext().getApplicationContext(), new Memory.Result() {
                    @Override public void done(String text) { showToast(text); }
                });
                showToast("กำลังล้าง RAM…");
                break;
            case Z_CAL:
                feed.refreshNow();
                showToast("กำลังอัปเดตปฏิทิน…");
                break;
            default: {
                if (shelfIndex >= shelf.size()) {
                    open(APPS);
                    return;
                }
                HomeApps.Item it = shelf.get(shelfIndex);
                RectF r = tileRect[shelfIndex];
                if (it.pkg != null) Prefs.addRecent(it.pkg);
                if (!HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()))) {
                    showToast("เปิด " + it.label + " ไม่ได้");
                }
            }
        }
        invalidate();
    }

    private void activateApps() {
        if (appsZone == A_SEARCH) {
            final EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setText(appsQuery);
            input.setSelectAllOnFocus(true);
            new AlertDialog.Builder(activity).setTitle("ค้นหาแอป").setView(input)
                    .setPositiveButton("ค้นหา", (d, which) -> {
                        appsQuery = input.getText().toString();
                        appsFocus = 0;
                        buildApps();
                        invalidate();
                    })
                    .setNegativeButton("ล้าง", (d, which) -> {
                        appsQuery = "";
                        buildApps();
                        invalidate();
                    }).show();
        } else if (appsZone == A_CHIPS) {
            appsFilter = appsChip;
            appsFocus = 0;
            appsScrollTarget = 0;
            buildApps();
            click.open();
        } else if (appsFocus < appsList.size()) {
            HomeApps.Item it = appsList.get(appsFocus);
            if (it.pkg != null) Prefs.addRecent(it.pkg);
            overlay = NONE;
            if (!HomeApps.open(activity, it, this, 0, 0, w, h)) showToast("เปิด " + it.label + " ไม่ได้");
        }
        invalidate();
    }

    // MARK: - TalkBack: every reachable item is a virtual node, registered while drawing (Spoken)

    private static final class Node {
        String text;
        RectF r;
        boolean focused;
        int tag;
    }

    private final List<Node> nodes = new ArrayList<>(), pending = new ArrayList<>();

    private void node(String text, RectF r, boolean focused, int tag) {
        Node n = new Node();
        n.text = text;
        n.r = new RectF(r);
        n.focused = focused;
        n.tag = tag;
        pending.add(n);
    }

    @Override public void draw(Canvas canvas) {
        pending.clear();
        super.draw(canvas);
        nodes.clear();
        nodes.addAll(pending);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        for (int i = 0; i < nodes.size(); i++) out.add(i + 1);
    }

    private String a11yText(int id) {
        return id >= 1 && id <= nodes.size() ? nodes.get(id - 1).text : "";
    }

    private boolean a11yBounds(int id, Rect out) {
        if (id < 1 || id > nodes.size()) return false;
        nodes.get(id - 1).r.round(out);
        return true;
    }

    private int a11yFocused() {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).focused) return i + 1;
        return -1;
    }

    private void a11yFocus(int id) {
        // TalkBack moved to a node: nothing to do (the remote's own focus stays; OK activates it).
    }
}
