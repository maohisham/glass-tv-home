package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;

public class LauncherSettingsActivity extends Activity {

    private File cfgDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        cfgDir = Config.dir(this);
        Config cfg = Config.load(this);

        TextView btnSys = findViewById(R.id.btnSystemSettings);
        btnSys.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        LinearLayout content = findViewById(R.id.settingsContent);

        // ── Icons ──────────────────────────────────────────────────────────
        addSection(content, "Icons");
        addIconPackPicker(content, cfg.iconPack);
        LinearLayout[] wallpaperInfoRowRef = {null};
        wallpaperInfoRowRef[0] = addToggle(content, "Show Wallpaper Info",
                "Show location and description when the wallpaper changes",
                cfg.showWallpaperInfo,
                v -> Config.save(cfgDir, "show_wallpaper_info", v));

        // ── Wallpaper ──────────────────────────────────────────────────────
        addSection(content, "Wallpaper");
        LinearLayout[] aerialSceneRowRef = {null};
        addToggle(content, "Aerial Views Video Wallpaper",
                "Use AerialViews app as animated background",
                cfg.aerialWallpaper,
                v -> {
                    Config.save(cfgDir, "aerial_wallpaper", v);
                    setRowEnabled(wallpaperInfoRowRef[0], !v);
                    setRowEnabled(aerialSceneRowRef[0], v);
                });
        aerialSceneRowRef[0] = addAerialScenePicker(content, cfg.aerialScenes);

        // Apply initial enabled/disabled state based on current aerial wallpaper setting
        setRowEnabled(wallpaperInfoRowRef[0], !cfg.aerialWallpaper);
        setRowEnabled(aerialSceneRowRef[0], cfg.aerialWallpaper);

        // ── Sleep Timer ────────────────────────────────────────────────────
        addSection(content, "Sleep Timer");
        addListSetting(content, "Default Sleep Timer",
                new String[]{"Off", "15 min", "30 min", "60 min", "2 hours"},
                new int[]{0, 15, 30, 60, 120},
                cfg.sleepTimerMinutes,
                v -> Config.save(cfgDir, "sleep_timer_minutes", v));

        // ── Widgets ────────────────────────────────────────────────────────
        addSection(content, "Widgets");
        addToggle(content, "Clock",
                "Show clock on the home screen",
                cfg.widgetClock,
                v -> Config.save(cfgDir, "widget_clock", v));
        addToggle(content, "Weather",
                "Show weather and air quality pill",
                cfg.widgetWeather,
                v -> Config.save(cfgDir, "widget_weather", v));
        addToggle(content, "Internet",
                "Show Wi-Fi / network pill",
                cfg.widgetInternet,
                v -> Config.save(cfgDir, "widget_internet", v));
        addToggle(content, "CPU & Memory",
                "Show CPU and memory usage indicator",
                cfg.widgetStats,
                v -> Config.save(cfgDir, "widget_stats", v));
        addToggle(content, "Waktu Solat",
                "Show prayer times table",
                cfg.widgetSolat,
                v -> Config.save(cfgDir, "widget_solat", v));
        addToggle(content, "Quran Verse",
                "Show daily Quran verse",
                cfg.widgetQuran,
                v -> Config.save(cfgDir, "widget_quran", v));

        // ── Sound ──────────────────────────────────────────────────────────
        addSection(content, "Sound");
        addToggle(content, "Navigation Sound",
                "Play sound when navigating tiles",
                cfg.soundNavEnabled,
                v -> Config.save(cfgDir, "sound_nav_enabled", v));
        addToggle(content, "Selection Sound",
                "Play sound when selecting an app",
                cfg.soundSelectEnabled,
                v -> Config.save(cfgDir, "sound_select_enabled", v));
        addSeekBar(content, "Sound Volume",
                cfg.soundVolume, 0, 100,
                v -> Config.save(cfgDir, "sound_volume", v));

        // ── Location ───────────────────────────────────────────────────────
        addSection(content, "Location");
        addLocationSetting(content);

        // ── Hidden Apps ────────────────────────────────────────────────────
        addSection(content, "Hidden Apps");
        addHiddenAppsManager(content, new ArrayList<>(cfg.hiddenApps));

    }

    // ── Location setting ───────────────────────────────────────────────────

    private void addLocationSetting(LinearLayout parent) {
        String name = LocationSettings.getName(this);
        String mode = !LocationSettings.isCustom(this) ? "Default"
                : LocationSettings.isManual(this) ? "Manual" : "Auto-detected";
        LinearLayout row = makeRow(parent, "Weather & Prayer Location", name + "  •  " + mode);
        row.setOnClickListener(v -> showLocationMenu(row));
    }

    private void showLocationMenu(LinearLayout row) {
        new AlertDialog.Builder(this)
                .setTitle("Location")
                .setItems(new String[]{"Search city", "Use device location", "Reset to default (" + LocationSettings.DEFAULT_NAME + ")"}, (d, which) -> {
                    switch (which) {
                        case 0: showCitySearchDialog(row); break;
                        case 1: detectDeviceLocation(row); break;
                        case 2: resetToDefault(row); break;
                    }
                })
                .show();
    }

    private void showCitySearchDialog(LinearLayout row) {
        EditText et = new EditText(this);
        et.setHint("City name, e.g. Kuala Lumpur");
        et.setTextColor(0xFFEEEEF5);
        et.setHintTextColor(0xFF808090);
        et.setSingleLine(true);
        et.setPadding(48, 24, 48, 24);
        new AlertDialog.Builder(this)
                .setTitle("Search City")
                .setView(et)
                .setPositiveButton("Search", (d, w) -> {
                    String q = et.getText().toString().trim();
                    if (!q.isEmpty()) searchCityAndPick(q, row);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void searchCityAndPick(String query, LinearLayout row) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Searching…");
        progress.setCancelable(false);
        progress.show();
        LocationSettings.search(query, results -> {
            progress.dismiss();
            if (results.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("No results")
                        .setMessage("No cities found for \"" + query + "\".")
                        .setPositiveButton("OK", null).show();
                return;
            }
            String[] names = new String[results.size()];
            for (int i = 0; i < results.size(); i++) names[i] = results.get(i).toString();
            new AlertDialog.Builder(this)
                    .setTitle("Select City")
                    .setItems(names, (d, which) -> {
                        LocationSettings.Result r = results.get(which);
                        LocationSettings.save(this, r, true);
                        updateSubtitle(row, r.name + "  •  Manual");
                    })
                    .show();
        });
    }

    @SuppressWarnings("deprecation")
    private void detectDeviceLocation(LinearLayout row) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Detecting location…");
        progress.setCancelable(false);
        progress.show();
        LocationSettings.detectAuto(this, result -> {
            progress.dismiss();
            if (result == null) {
                new AlertDialog.Builder(this)
                        .setTitle("Location unavailable")
                        .setMessage("Could not get device location. Try searching for a city instead.")
                        .setPositiveButton("OK", null).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Use this location?")
                    .setMessage(result.toString())
                    .setPositiveButton("Yes", (d, w) -> {
                        LocationSettings.save(this, result, false);
                        updateSubtitle(row, result.name + "  •  Auto-detected");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void resetToDefault(LinearLayout row) {
        LocationSettings.reset(this);
        updateSubtitle(row, LocationSettings.DEFAULT_NAME + "  •  Default");
    }

    // ── Aerial scene picker ────────────────────────────────────────────────

    private static final String[] SCENE_KEYS   = {"beach","city","countryside","nature","sea","space","waterfall"};
    private static final String[] SCENE_LABELS = {"Beach","City","Countryside","Nature","Sea","Space","Waterfall"};

    private LinearLayout addAerialScenePicker(LinearLayout parent, Set<String> currentScenes) {
        boolean[] checked = new boolean[SCENE_KEYS.length];
        for (int i = 0; i < SCENE_KEYS.length; i++)
            checked[i] = currentScenes.isEmpty() || currentScenes.contains(SCENE_KEYS[i]);

        String subtitle = currentScenes.isEmpty() ? "All scenes" : currentScenes.size() + " selected";
        LinearLayout row = makeRow(parent, "Aerial Scene Types", subtitle);
        row.setOnClickListener(v -> {
            boolean[] tmp = checked.clone();
            new AlertDialog.Builder(this)
                    .setTitle("Select Scene Types")
                    .setMultiChoiceItems(SCENE_LABELS, tmp, (d, which, isChecked) -> tmp[which] = isChecked)
                    .setPositiveButton("OK", (d, w) -> {
                        System.arraycopy(tmp, 0, checked, 0, checked.length);
                        Set<String> selected = new HashSet<>();
                        for (int i = 0; i < SCENE_KEYS.length; i++)
                            if (checked[i]) selected.add(SCENE_KEYS[i]);
                        // If all selected, store empty array (= all scenes = default)
                        boolean allSelected = selected.size() == SCENE_KEYS.length;
                        JSONArray arr = new JSONArray();
                        if (!allSelected) for (String s : selected) arr.put(s);
                        Config.save(cfgDir, "aerial_scenes", arr);
                        updateSubtitle(row, allSelected ? "All scenes" : selected.size() + " selected");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        return row;
    }

    // ── Hidden apps manager ────────────────────────────────────────────────

    private void addHiddenAppsManager(LinearLayout parent, List<String> hiddenPkgs) {
        PackageManager pm = getPackageManager();
        // Only show apps that are actually installed on this device
        hiddenPkgs.removeIf(pkg -> {
            try { pm.getApplicationInfo(pkg, 0); return false; }
            catch (PackageManager.NameNotFoundException e) { return true; }
        });
        if (hiddenPkgs.isEmpty()) {
            LinearLayout row = makeRow(parent, "No hidden apps", "");
            row.setFocusable(false);
            row.setClickable(false);
            return;
        }
        for (String pkg : hiddenPkgs) {
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString();
            } catch (PackageManager.NameNotFoundException ignored) {}
            final String pkgFinal = pkg;
            final String labelFinal = label;
            LinearLayout row = makeRow(parent, labelFinal, pkg);
            TextView unhideBtn = new TextView(this);
            unhideBtn.setText("Unhide");
            unhideBtn.setTextColor(0xFF6E6EBF);
            unhideBtn.setTextSize(14f);
            unhideBtn.setPadding(24, 0, 0, 0);
            row.addView(unhideBtn);
            row.setOnClickListener(v -> {
                hiddenPkgs.remove(pkgFinal);
                JSONArray arr = new JSONArray();
                for (String p : hiddenPkgs) arr.put(p);
                Config.save(cfgDir, "hidden_apps", arr);
                parent.removeView(row);
                if (hiddenPkgs.isEmpty()) {
                    LinearLayout empty = makeRow(parent, "No hidden apps", "");
                    empty.setFocusable(false);
                    empty.setClickable(false);
                }
            });
        }
    }

    // ── Section header ─────────────────────────────────────────────────────

    private void addSection(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title.toUpperCase());
        tv.setTextColor(0xFF6E6EBF);
        tv.setTextSize(12f);
        tv.setLetterSpacing(0.12f);
        tv.setPadding(0, 40, 0, 12);
        parent.addView(tv);

        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    // ── Toggle row ─────────────────────────────────────────────────────────

    private LinearLayout addToggle(LinearLayout parent, String title, String subtitle,
                                   boolean initial, Callback<Boolean> onChange) {
        LinearLayout row = makeRow(parent, title, subtitle);
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, checked) -> onChange.accept(checked));
        row.addView(sw);
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        return row;
    }

    private void setRowEnabled(LinearLayout row, boolean enabled) {
        if (row == null) return;
        row.setAlpha(enabled ? 1f : 0.35f);
        row.setClickable(enabled);
        row.setFocusable(enabled);
    }

    // ── Icon pack picker ───────────────────────────────────────────────────

    private void addIconPackPicker(LinearLayout parent, String currentPack) {
        List<ResolveInfo> resolved = getPackageManager()
                .queryIntentActivities(new Intent("org.adw.launcher.THEMES"), 0);

        List<String> names = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        names.add("None (system icons)");
        packages.add("");
        for (ResolveInfo ri : resolved) {
            names.add(ri.loadLabel(getPackageManager()).toString());
            packages.add(ri.activityInfo.packageName);
        }

        int currentIdx = packages.indexOf(currentPack);
        if (currentIdx < 0) currentIdx = 0;
        final int[] sel = {currentIdx};
        String subtitle = names.get(currentIdx);
        LinearLayout row = makeRow(parent, "Icon Pack", subtitle);

        final String[] namesArr = names.toArray(new String[0]);
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Select Icon Pack")
                .setSingleChoiceItems(namesArr, sel[0], (d, which) -> sel[0] = which)
                .setPositiveButton("OK", (d, w) -> {
                    String pkg = packages.get(sel[0]);
                    Config.save(cfgDir, "icon_pack", pkg);
                    updateSubtitle(row, namesArr[sel[0]]);
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    // ── List-choice setting ────────────────────────────────────────────────

    private void addListSetting(LinearLayout parent, String title,
                                String[] labels, int[] values, int currentValue,
                                Callback<Integer> onChange) {
        int idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == currentValue) { idx = i; break; }
        final int[] sel = {idx};
        LinearLayout row = makeRow(parent, title, labels[idx]);
        row.setFocusable(true);
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, sel[0], (d, which) -> sel[0] = which)
                .setPositiveButton("OK", (d, w) -> {
                    onChange.accept(values[sel[0]]);
                    updateSubtitle(row, labels[sel[0]]);
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    // ── Seek-bar setting ───────────────────────────────────────────────────

    private void addSeekBar(LinearLayout parent, String title,
                            int initial, int min, int max, Callback<Integer> onChange) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 20, 0, 20);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView lbl = makeLabel(title);
        TextView val = new TextView(this);
        val.setText(String.valueOf(initial));
        val.setTextColor(0xFFB0B0C8);
        val.setTextSize(14f);
        titleRow.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(val);
        col.addView(titleRow);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(initial - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int v = p + min;
                val.setText(String.valueOf(v));
                if (user) onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(sb);
        parent.addView(col);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private LinearLayout makeRow(LinearLayout parent, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 20, 0, 20);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(getFocusableBackground());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = makeLabel(title);
        col.addView(titleTv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setText(subtitle);
            subTv.setTextColor(0xFF808090);
            subTv.setTextSize(13f);
            subTv.setTag("subtitle");
            col.addView(subTv);
        }

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return row;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFEEEEF5);
        tv.setTextSize(16f);
        return tv;
    }

    private void updateSubtitle(LinearLayout row, String text) {
        ViewGroup col = (ViewGroup) row.getChildAt(0);
        if (col == null) return;
        for (int i = 0; i < col.getChildCount(); i++) {
            View v = col.getChildAt(i);
            if ("subtitle".equals(v.getTag())) {
                ((TextView) v).setText(text);
                return;
            }
        }
    }

    private android.graphics.drawable.StateListDrawable getFocusableBackground() {
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused},
                new android.graphics.drawable.ColorDrawable(0x33FFFFFF));
        sld.addState(new int[]{},
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return sld;
    }

    interface Callback<T> {
        void accept(T value);
    }
}
