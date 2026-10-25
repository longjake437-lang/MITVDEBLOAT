package com.cleantv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple TV style home screen: black background, a dock row of the apps you pick,
 * the remaining apps in a grid below it, one glass highlight that glides between tiles.
 * No blur, no video, no polling: it only reacts to key presses and package changes.
 */
public class MainActivity extends Activity {

    private static final float SCALE = 1.08f;

    // ---- layout numbers (all derived from the screen size) ----
    private int W, H, cols;
    private int sidePad, cellW, gap, bannerW, bannerH, labelH, bandPad, rowVPad, rowH,
            dockTop, bottomMargin, hp;

    private Typeface font;
    private RecyclerView grid;
    private TileAdapter adapter;
    private View highlight, glow, focusedTile;
    private TextView dockName, greeting, clock, date;
    private final TextView[] pills = new TextView[3]; // 0 Inputs, 1 Wi-Fi, 2 Settings
    private LinearLayout dropdown;
    private int lastPos = 0;
    private int loadToken = 0;

    private static class AppEntry {
        String pkg;
        String label;
        Drawable banner; // may be null
        Drawable icon;
        int glow = Color.WHITE;
        Intent intent;
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
        }
    };

    // =====================================================================
    // lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        cols = Math.max(1, getResources().getInteger(R.integer.columns));
        loadFont();
        computeDimensions();

        // optional wallpaper: drop a file called wallpaper.jpg/png into res/drawable-nodpi
        int wp = getResources().getIdentifier("wallpaper", "drawable", getPackageName());
        if (wp != 0) {
            findViewById(R.id.root).setBackgroundResource(wp);
        }

        setupHeader();
        setupPills();
        setupGrid();
        setupGlowAndHighlight();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        reloadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGreeting();
        pills[1].setText(wifiLabel());
        if (getCurrentFocus() == null && adapter.getItemCount() > 0) {
            focusPosition(Math.min(lastPos, adapter.getItemCount() - 1), 0);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we are already on top: go back to the dock
        closeDropdown(false);
        if (adapter != null && adapter.getItemCount() > 0) {
            grid.scrollToPosition(0);
            focusPosition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (dropdown.getVisibility() == View.VISIBLE) {
            closeDropdown(true);
        }
        // a home screen has nowhere to go back to
    }

    // =====================================================================
    // setup
    // =====================================================================

    private void loadFont() {
        try {
            font = Typeface.createFromAsset(getAssets(), "fonts/Outfit.ttf");
        } catch (Exception e) {
            font = null;
        }
    }

    private void style(TextView t, int weight) {
        if (font != null) {
            t.setTypeface(font);
        }
        try {
            t.setFontVariationSettings("'wght' " + weight);
        } catch (Throwable ignored) {
        }
    }

    private void computeDimensions() {
        sidePad = (int) (W * 0.12f);
        cellW = (W - 2 * sidePad) / cols;
        gap = (int) (W * 0.022f);
        bannerW = cellW - gap;
        bannerH = bannerW * 9 / 16;
        labelH = (int) (W * 0.03f);
        bandPad = (int) (W * 0.012f);
        rowVPad = (int) (W * 0.008f);
        rowH = bannerH + labelH + 2 * rowVPad;
        bottomMargin = (int) (H * 0.08f);
        dockTop = H - rowH - bottomMargin;
        hp = (int) (W * 0.005f);
    }

    private void setupHeader() {
        LinearLayout header = findViewById(R.id.header);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) header.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = (int) (W * 0.06f);
        lp.topMargin = (int) (H * 0.06f);
        header.setLayoutParams(lp);

        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.013f);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.058f);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.0135f);
        style(greeting, 300);
        style(clock, 100);
        style(date, 300);

        dockName = findViewById(R.id.dock_name);
        dockName.setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.022f);
        style(dockName, 200);
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dockName.getLayoutParams();
        dl.gravity = Gravity.TOP | Gravity.START;
        int nameH = (int) (W * 0.022f * 1.4f);
        dl.leftMargin = sidePad + gap / 2;
        dl.topMargin = dockTop - bandPad - nameH - (int) (H * 0.012f);
        dockName.setLayoutParams(dl);
    }

    private void setupPills() {
        pills[0] = findViewById(R.id.pill_inputs);
        pills[1] = findViewById(R.id.pill_wifi);
        pills[2] = findViewById(R.id.pill_settings);

        LinearLayout bar = findViewById(R.id.pill_bar);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.rightMargin = (int) (W * 0.05f);
        lp.topMargin = (int) (H * 0.06f);
        bar.setLayoutParams(lp);

        for (int i = 0; i < pills.length; i++) {
            final int index = i;
            TextView p = pills[i];
            style(p, 400);
            p.setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.0125f);
            int ph = (int) (W * 0.014f);
            int pv = (int) (W * 0.0065f);
            p.setPadding(ph, pv, ph, pv);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                pl.leftMargin = (int) (W * 0.008f);
            }
            p.setLayoutParams(pl);

            p.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    hideHighlight();
                    hideDockInfo();
                }
            });
            p.setOnKeyListener((v, keyCode, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (index > 0) {
                            closeDropdown(false);
                            pills[index - 1].requestFocus();
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (index < pills.length - 1) {
                            closeDropdown(false);
                            pills[index + 1].requestFocus();
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true; // top bar is the top: stay put
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (index == 0 && dropdown.getVisibility() == View.VISIBLE
                                && dropdown.getChildCount() > 0) {
                            dropdown.getChildAt(0).requestFocus();
                        } else {
                            closeDropdown(false);
                            focusPosition(lastPos, 0);
                        }
                        return true;
                    default:
                        return false;
                }
            });
        }

        pills[0].setOnClickListener(v -> {
            if (dropdown.getVisibility() == View.VISIBLE) {
                closeDropdown(true);
            } else {
                openDropdown();
            }
        });
        pills[1].setOnClickListener(v -> startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        pills[2].setOnClickListener(v -> startSafe(new Intent(Settings.ACTION_SETTINGS)));

        dropdown = findViewById(R.id.dropdown);
        dropdown.setPadding((int) (W * 0.006f), (int) (W * 0.006f), (int) (W * 0.006f), (int) (W * 0.006f));
    }

    private void setupGrid() {
        grid = findViewById(R.id.grid);
        GridLayoutManager lm = new GridLayoutManager(this, cols);
        grid.setLayoutManager(lm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        // the dock row rests near the bottom of the screen; the grid sits below it and scrolls up
        grid.setPadding(sidePad, dockTop, sidePad, bottomMargin);
        grid.addItemDecoration(new DockBand());
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (focusedTile != null && focusedTile.isFocused() && highlight.getAlpha() > 0.5f) {
                    moveHighlight(focusedTile, false);
                }
            }
        });
    }

    private void setupGlowAndHighlight() {
        highlight = findViewById(R.id.highlight);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(
                (int) (bannerW * SCALE + 2 * hp), (int) (bannerH * SCALE + 2 * hp));
        hl.gravity = Gravity.TOP | Gravity.START;
        highlight.setLayoutParams(hl);

        glow = findViewById(R.id.glow);
        int size = (int) (W * 0.40f);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(size, size);
        gl.gravity = Gravity.TOP | Gravity.START;
        int nameH = (int) (W * 0.022f * 1.4f);
        int nameCenterY = dockTop - bandPad - nameH / 2 - (int) (H * 0.012f);
        gl.leftMargin = sidePad - size / 5;
        gl.topMargin = nameCenterY - size / 2;
        glow.setLayoutParams(gl);
        GradientDrawable gd = new GradientDrawable();
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientRadius(size / 2f);
        gd.setColors(new int[]{0x00FFFFFF, 0x00FFFFFF});
        glow.setBackground(gd);
    }

    // =====================================================================
    // header texts
    // =====================================================================

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : h < 22 ? "Good evening" : "Good night";
        String name = getString(R.string.greeting_name);
        if (name != null && !name.trim().isEmpty()) {
            g = g + ", " + name.trim();
        }
        greeting.setText(g);
    }

    private String wifiLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) {
                return "Offline";
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                String s = wi == null ? null : wi.getSSID();
                if (s != null) {
                    s = s.replace("\"", "");
                }
                if (s == null || s.isEmpty() || s.contains("unknown ssid")) {
                    return "Wi-Fi";
                }
                return s;
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "Ethernet";
            }
            return "Online";
        } catch (Exception e) {
            return "Wi-Fi";
        }
    }

    // =====================================================================
    // loading apps (background thread)
    // =====================================================================

    private void reloadApps() {
        final int token = ++loadToken;
        new Thread(() -> {
            final List<AppEntry> list = loadApps();
            runOnUiThread(() -> {
                if (token != loadToken || isFinishing()) {
                    return;
                }
                boolean needFocus = getCurrentFocus() == null || grid.hasFocus();
                adapter.items = list;
                adapter.notifyDataSetChanged();
                if (needFocus && !list.isEmpty()) {
                    focusPosition(Math.min(lastPos, list.size() - 1), 0);
                }
            });
        }).start();
    }

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> found = new HashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : categories) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || found.containsKey(pkg)) {
                    continue;
                }
                boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (cat.equals(Intent.CATEGORY_LAUNCHER) && system) {
                    continue; // phone-style system apps would only clutter the grid
                }
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                Drawable b = ri.activityInfo.loadBanner(pm);
                if (b == null) {
                    b = ri.activityInfo.applicationInfo.loadBanner(pm);
                }
                e.banner = b;
                e.icon = ri.loadIcon(pm);
                e.glow = sampleColor(b != null ? b : e.icon);
                e.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                found.put(pkg, e);
            }
        }

        List<AppEntry> out = new ArrayList<>();
        Map<String, Boolean> used = new LinkedHashMap<>();
        for (String p : getResources().getStringArray(R.array.dock_apps)) {
            AppEntry e = found.get(p);
            if (e != null && used.put(p, true) == null) {
                out.add(e);
            }
        }
        for (String p : getResources().getStringArray(R.array.grid_apps)) {
            AppEntry e = found.get(p);
            if (e != null && used.put(p, true) == null) {
                out.add(e);
            }
        }
        List<AppEntry> rest = new ArrayList<>();
        for (AppEntry e : found.values()) {
            if (!used.containsKey(e.pkg)) {
                rest.add(e);
            }
        }
        Collections.sort(rest, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        out.addAll(rest);
        return out;
    }

    private static int sampleColor(Drawable d) {
        if (d == null) {
            return Color.WHITE;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 8, 8);
            d.draw(c);
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < 128) {
                        continue;
                    }
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
            bmp.recycle();
            if (n == 0) {
                return Color.WHITE;
            }
            int cr = (int) (r / n), cg = (int) (g / n), cb = (int) (b / n);
            // very dark banners would give an invisible glow: lift them a little
            int max = Math.max(cr, Math.max(cg, cb));
            if (max < 90) {
                cr += 70;
                cg += 70;
                cb += 70;
            }
            return Color.rgb(Math.min(255, cr), Math.min(255, cg), Math.min(255, cb));
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    // =====================================================================
    // focus, highlight, dock info
    // =====================================================================

    /** Focus the tile at adapter position p, scrolling to it first if it is not attached yet. */
    private void focusPosition(final int p, final int attempt) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm == null || p < 0) {
            return;
        }
        View v = lm.findViewByPosition(p);
        if (v != null) {
            v.requestFocus();
            return;
        }
        if (attempt == 0) {
            grid.scrollToPosition(p);
        }
        if (attempt < 6) {
            grid.postOnAnimation(() -> focusPosition(p, attempt + 1));
        }
    }

    private void moveHighlight(View tile, boolean animate) {
        View banner = tile.findViewById(R.id.banner);
        float cx = tile.getLeft() + tile.getWidth() / 2f;
        float cy = tile.getTop() + tile.getHeight() / 2f;
        float bl = tile.getLeft() + banner.getLeft();
        float bt = tile.getTop() + banner.getTop();
        float left = cx + (bl - cx) * SCALE - hp;
        float top = cy + (bt - cy) * SCALE - hp;
        if (!animate || highlight.getAlpha() < 0.5f) {
            highlight.animate().cancel();
            highlight.setX(left);
            highlight.setY(top);
            highlight.setAlpha(1f);
        } else {
            highlight.animate().x(left).y(top).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
        }
    }

    private void hideHighlight() {
        highlight.animate().cancel();
        highlight.animate().alpha(0f).setDuration(120).start();
    }

    private void showDockInfo(AppEntry e) {
        dockName.setText(e.label);
        int c = e.glow & 0x00FFFFFF;
        GradientDrawable gd = (GradientDrawable) glow.getBackground();
        gd.setColors(new int[]{c | (0x66 << 24), c});
        dockName.animate().alpha(1f).setDuration(150).start();
        glow.animate().alpha(1f).setDuration(200).start();
    }

    private void hideDockInfo() {
        dockName.animate().alpha(0f).setDuration(120).start();
        glow.animate().alpha(0f).setDuration(120).start();
    }

    // =====================================================================
    // launching
    // =====================================================================

    private void launch(AppEntry e) {
        try {
            startActivity(e.intent);
        } catch (Exception ex) {
            Toast.makeText(this, "Could not open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================================
    // inputs dropdown
    // =====================================================================

    private void openDropdown() {
        dropdown.removeAllViews();
        List<TvInputInfo> inputs = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                inputs = tim.getTvInputList();
            }
        } catch (Throwable ignored) {
        }

        final List<String> hdmiLabels = new ArrayList<>();
        final Map<String, TvInputInfo> hdmi = new LinkedHashMap<>();
        final Map<String, TvInputInfo> av = new LinkedHashMap<>();
        TvInputInfo tuner = null;
        for (TvInputInfo info : inputs) {
            if (info.getType() == TvInputInfo.TYPE_TUNER) {
                if (tuner == null) {
                    tuner = info;
                }
            } else if (info.isPassthroughInput()) {
                CharSequence cs = info.loadLabel(this);
                String label = (cs == null || cs.length() == 0) ? typeName(info.getType()) : cs.toString();
                if (info.getType() == TvInputInfo.TYPE_HDMI) {
                    hdmi.put(label + "\u0000" + info.getId(), info);
                    hdmiLabels.add(label + "\u0000" + info.getId());
                } else {
                    av.put(label + "\u0000" + info.getId(), info);
                }
            }
        }
        Collections.sort(hdmiLabels);
        final List<Runnable> rows = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (String k : hdmiLabels) {
            final TvInputInfo i = hdmi.get(k);
            texts.add(k.substring(0, k.indexOf('\u0000')));
            rows.add(() -> switchInput(i));
        }
        for (Map.Entry<String, TvInputInfo> en : av.entrySet()) {
            final TvInputInfo i = en.getValue();
            texts.add(en.getKey().substring(0, en.getKey().indexOf('\u0000')));
            rows.add(() -> switchInput(i));
        }
        if (tuner != null) {
            final TvInputInfo i = tuner;
            texts.add("Live TV");
            rows.add(() -> switchInput(i));
        }
        if (texts.isEmpty()) {
            texts.add("No inputs found");
            rows.add(() -> {
            });
        }

        for (int idx = 0; idx < texts.size(); idx++) {
            final int index = idx;
            TextView t = new TextView(this);
            style(t, 400);
            t.setText(texts.get(idx));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.0135f);
            t.setSingleLine(true);
            t.setBackgroundResource(R.drawable.item_bg);
            t.setFocusable(true);
            t.setClickable(true);
            t.setDefaultFocusHighlightEnabled(false);
            t.setMinWidth((int) (W * 0.17f));
            t.setPadding((int) (W * 0.014f), (int) (W * 0.008f), (int) (W * 0.014f), (int) (W * 0.008f));
            t.setOnClickListener(v -> {
                closeDropdown(false);
                rows.get(index).run();
            });
            final int count = texts.size();
            t.setOnKeyListener((v, keyCode, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (index == 0) {
                            closeDropdown(true);
                        } else {
                            dropdown.getChildAt(index - 1).requestFocus();
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (index < count - 1) {
                            dropdown.getChildAt(index + 1).requestFocus();
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return true;
                    default:
                        return false;
                }
            });
            dropdown.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        int[] loc = new int[2];
        pills[0].getLocationInWindow(loc);
        dropdown.setVisibility(View.VISIBLE);
        dropdown.setX(loc[0]);
        dropdown.setY(loc[1] + pills[0].getHeight() + hp * 2);
        dropdown.getChildAt(0).requestFocus();
    }

    private void closeDropdown(boolean refocusPill) {
        if (dropdown.getVisibility() != View.VISIBLE) {
            return;
        }
        dropdown.setVisibility(View.GONE);
        dropdown.removeAllViews();
        if (refocusPill) {
            pills[0].requestFocus();
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case TvInputInfo.TYPE_HDMI:
                return "HDMI";
            case TvInputInfo.TYPE_COMPOSITE:
                return "AV";
            case TvInputInfo.TYPE_COMPONENT:
                return "Component";
            case TvInputInfo.TYPE_VGA:
                return "VGA";
            case TvInputInfo.TYPE_SCART:
                return "SCART";
            default:
                return "Input";
        }
    }

    private void switchInput(TvInputInfo info) {
        try {
            Uri uri = info.isPassthroughInput()
                    ? TvContract.buildChannelUriForPassthroughInput(info.getId())
                    : TvContract.Channels.CONTENT_URI;
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            if (!openTvApp()) {
                Toast.makeText(this, "No TV app can open this input", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not switch input", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean openTvApp() {
        String[] candidates = {"com.android.tv", "com.droidlogic.droidlivetv"};
        for (String p : candidates) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // dock band behind the first row
    // =====================================================================

    private class DockBand extends RecyclerView.ItemDecoration {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        DockBand() {
            paint.setColor(0x24FFFFFF);
        }

        @Override
        public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            boolean any = false;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View v = parent.getChildAt(i);
                int p = parent.getChildAdapterPosition(v);
                if (p >= 0 && p < cols) {
                    any = true;
                    l = Math.min(l, v.getLeft());
                    t = Math.min(t, v.getTop());
                    r = Math.max(r, v.getRight());
                    b = Math.max(b, v.getBottom());
                }
            }
            if (!any) {
                return;
            }
            rect.set(l + gap / 2f - bandPad, t, r - gap / 2f + bandPad, b);
            float rad = W * 0.02f;
            c.drawRoundRect(rect, rad, rad, paint);
        }
    }

    // =====================================================================
    // adapter
    // =====================================================================

    private class TileHolder extends RecyclerView.ViewHolder {
        final ImageView banner;
        final TextView label;

        TileHolder(View v) {
            super(v);
            banner = v.findViewById(R.id.banner);
            label = v.findViewById(R.id.label);
        }
    }

    private class TileAdapter extends RecyclerView.Adapter<TileHolder> {
        List<AppEntry> items = new ArrayList<>();

        @NonNull
        @Override
        public TileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
            v.setLayoutParams(new RecyclerView.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT));
            v.setPadding(gap / 2, rowVPad, gap / 2, rowVPad);

            final TileHolder holder = new TileHolder(v);
            holder.banner.setLayoutParams(new LinearLayout.LayoutParams(bannerW, bannerH));
            holder.label.setLayoutParams(new LinearLayout.LayoutParams(bannerW, labelH));
            holder.label.setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.0125f);
            style(holder.label, 300);
            final float radius = W * 0.008f;
            holder.banner.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            holder.banner.setClipToOutline(true);

            v.setOnClickListener(view -> {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && pos < items.size()) {
                    launch(items.get(pos));
                }
            });

            v.setOnFocusChangeListener((view, has) -> {
                view.animate().scaleX(has ? SCALE : 1f).scaleY(has ? SCALE : 1f).setDuration(150).start();
                holder.label.animate().alpha(has ? 1f : 0f).setDuration(150).start();
                if (has) {
                    int pos = holder.getAdapterPosition();
                    if (pos >= 0 && pos < items.size()) {
                        lastPos = pos;
                        focusedTile = view;
                        moveHighlight(view, true);
                        if (pos < cols) {
                            showDockInfo(items.get(pos));
                        } else {
                            hideDockInfo();
                        }
                    }
                }
            });

            // deterministic D-pad: exactly one cell per press, no surprises at the edges
            v.setOnKeyListener((view, keyCode, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                int pos = holder.getAdapterPosition();
                if (pos < 0) {
                    return false;
                }
                int last = items.size() - 1;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (pos % cols != 0) {
                            focusPosition(pos - 1, 0);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (pos % cols != cols - 1 && pos < last) {
                            focusPosition(pos + 1, 0);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: {
                        int t = pos + cols;
                        if (t > last) {
                            if (pos / cols < last / cols) {
                                t = last; // short last row: land on its last tile
                            } else {
                                return true;
                            }
                        }
                        focusPosition(t, 0);
                        return true;
                    }
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (pos < cols) {
                            pills[0].requestFocus();
                        } else {
                            focusPosition(pos - cols, 0);
                        }
                        return true;
                    default:
                        return false;
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull TileHolder h, int position) {
            AppEntry e = items.get(position);
            h.itemView.setScaleX(1f);
            h.itemView.setScaleY(1f);
            h.label.setAlpha(0f);
            h.label.setText(e.label);
            if (e.banner != null) {
                h.banner.setBackgroundColor(Color.TRANSPARENT);
                h.banner.setScaleType(ImageView.ScaleType.FIT_XY);
                h.banner.setPadding(0, 0, 0, 0);
                h.banner.setImageDrawable(e.banner);
            } else {
                int pad = bannerH / 5;
                h.banner.setBackgroundColor(0xFF2A2A2E);
                h.banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.banner.setPadding(pad, pad, pad, pad);
                h.banner.setImageDrawable(e.icon);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
