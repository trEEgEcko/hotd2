package com.flycast.emulator;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.flycast.emulator.emu.JNIdc;
import com.flycast.emulator.periph.InputDeviceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Java HUD drawn on top of the GL view for the single-game wrapper:
 * reload / coin / start / test / service buttons, the top-right icons
 * (play/pause, aspect ratio, menu), the Quick options dialog and the Edit HUD mode.
 */
public class HudOverlay
{
    private static final int KEY_COIN = 0;
    private static final int KEY_START = 1;
    private static final int KEY_RELOAD = 2;
    private static final int KEY_TEST = 3;
    private static final int KEY_SERVICE = 4;

    private static final int ICON_PLAY = 0;
    private static final int ICON_ASPECT = 1;
    private static final int ICON_MENU = 2;

    private final BaseGLActivity activity;
    private final SharedPreferences prefs;
    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final List<HudButton> buttons = new ArrayList<HudButton>();
    private final LinearLayout topBar;

    private HudButton reloadBtn;
    private HudButton coinBtn;
    private HudButton startBtn;
    private HudButton testBtn;
    private HudButton serviceBtn;

    private View editScrim;
    private LinearLayout editBar;
    private TextView editHint;
    private boolean editing = false;
    private int opacity;
    private boolean coinStartShown;
    private boolean testServiceShown;
    private Dialog quickDialog;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            updateVisibility();
            handler.postDelayed(this, 300);
        }
    };

    public HudOverlay(BaseGLActivity activity)
    {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("hud", 0);
        this.dp = activity.getResources().getDisplayMetrics().density;
        this.opacity = prefs.getInt("opacity", 85);
        this.coinStartShown = prefs.getBoolean("coinstart_shown", true);
        this.testServiceShown = prefs.getBoolean("testservice_shown", false);

        root = new FrameLayout(activity);

        reloadBtn = addButton("reload", KEY_RELOAD, R.drawable.btn_reload, null, 130, 130);
        coinBtn = addButton("coin", KEY_COIN, R.drawable.btn_coin, null, 96, 48);
        startBtn = addButton("start", KEY_START, R.drawable.btn_start, null, 96, 48);
        testBtn = addButton("test", KEY_TEST, 0, "TEST", 80, 40);
        serviceBtn = addButton("service", KEY_SERVICE, 0, "SERVICE", 80, 40);

        topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tlp.setMargins(0, px(6), px(6), 0);
        root.addView(topBar, tlp);

        IconView play = new IconView(ICON_PLAY);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean paused = HudOverlay.this.activity.toggleUserPause();
                Toast.makeText(HudOverlay.this.activity, paused ? "Paused" : "Resumed", Toast.LENGTH_SHORT).show();
            }
        });
        IconView aspect = new IconView(ICON_ASPECT);
        aspect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleAspect();
            }
        });
        IconView menu = new IconView(ICON_MENU);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQuickOptions();
            }
        });
        topBar.addView(play, new LinearLayout.LayoutParams(px(48), px(48)));
        topBar.addView(aspect, new LinearLayout.LayoutParams(px(48), px(48)));
        topBar.addView(menu, new LinearLayout.LayoutParams(px(48), px(48)));

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot)
                    applyPositions();
            }
        });

        applyOpacity();
        updateVisibility();
    }

    public void attach()
    {
        activity.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        handler.post(poller);
    }

    public void destroy()
    {
        handler.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ helpers

    private int px(float v)
    {
        return Math.round(v * dp);
    }

    // Opened slightly later so the window focus change (auto resume) settles first
    private void openFlycastMenu()
    {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                JNIdc.guiOpenSettings();
            }
        }, 400);
    }

    private void cycleAspect()
    {
        int mode = (JNIdc.getAspectMode() + 1) % 3;
        JNIdc.setAspectMode(mode);
        Toast.makeText(activity, aspectName(mode), Toast.LENGTH_SHORT).show();
    }

    private static String aspectName(int mode)
    {
        if (mode == 1)
            return "Widescreen";
        if (mode == 2)
            return "Super widescreen";
        return "4:3";
    }

    private void applyOpacity()
    {
        float a = opacity / 100f;
        for (HudButton b : buttons)
            b.view.setAlpha(a);
        topBar.setAlpha(Math.max(a, 0.5f));
    }

    private void updateVisibility()
    {
        boolean guiOpen = false;
        try {
            guiOpen = JNIdc.guiIsOpen();
        } catch (Throwable t) {
            // ignore
        }
        root.setVisibility(guiOpen && !editing ? View.GONE : View.VISIBLE);
        reloadBtn.view.setVisibility(View.VISIBLE);
        coinBtn.view.setVisibility(coinStartShown || editing ? View.VISIBLE : View.GONE);
        startBtn.view.setVisibility(coinStartShown || editing ? View.VISIBLE : View.GONE);
        testBtn.view.setVisibility(testServiceShown || editing ? View.VISIBLE : View.GONE);
        serviceBtn.view.setVisibility(testServiceShown || editing ? View.VISIBLE : View.GONE);
        topBar.setVisibility(editing ? View.INVISIBLE : View.VISIBLE);
    }

    private GradientDrawable roundedBox(int color, float radiusDp, int strokeColor, float strokeDp)
    {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        if (strokeDp > 0)
            g.setStroke(px(strokeDp), strokeColor);
        return g;
    }

    private TextView pill(String text)
    {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(0xFF4FC3F7);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(px(8), 0, px(8), 0);
        t.setBackground(roundedBox(Color.TRANSPARENT, 100, 0xFF8A8A8E, 1.5f));
        t.setClickable(true);
        return t;
    }

    private LinearLayout row()
    {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void addPill(LinearLayout row, TextView t)
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(42), 1f);
        lp.setMargins(px(4), px(3), px(4), px(3));
        row.addView(t, lp);
    }

    // ------------------------------------------------------------------ HUD buttons

    private class HudButton
    {
        final String name;
        final int key;
        final int wDp;
        final int hDp;
        final ImageView view;
        float dx;
        float dy;

        HudButton(String name, int key, int wDp, int hDp, ImageView view)
        {
            this.name = name;
            this.key = key;
            this.wDp = wDp;
            this.hDp = hDp;
            this.view = view;
        }
    }

    private Bitmap makePill(String text)
    {
        Bitmap bmp = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFE8E8EC);
        c.drawRoundRect(10, 20, 230, 100, 40, 40, p);
        p.setColor(0xFF2A2A2E);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(text.length() > 5 ? 34 : 44);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, 120, 72 + (text.length() > 5 ? 12 : 15), p);
        return bmp;
    }

    private HudButton addButton(String name, int key, int resId, String label, int wDp, int hDp)
    {
        ImageView iv = new ImageView(activity);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        if (resId != 0)
            iv.setImageResource(resId);
        else
            iv.setImageBitmap(makePill(label));
        final HudButton b = new HudButton(name, key, wDp, hDp, iv);
        iv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (editing)
                    return drag(b, v, ev);
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    InputDeviceManager.getInstance().virtualKeyInput(b.key, true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    InputDeviceManager.getInstance().virtualKeyInput(b.key, false);
                }
                return true;
            }
        });
        root.addView(iv, new FrameLayout.LayoutParams(px(wDp), px(hDp)));
        buttons.add(b);
        return b;
    }

    private boolean drag(HudButton b, View v, MotionEvent ev)
    {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            b.dx = v.getX() - ev.getRawX();
            b.dy = v.getY() - ev.getRawY();
        } else if (action == MotionEvent.ACTION_MOVE) {
            float x = b.dx + ev.getRawX();
            float y = b.dy + ev.getRawY();
            x = Math.max(0, Math.min(x, root.getWidth() - v.getWidth()));
            y = Math.max(0, Math.min(y, root.getHeight() - v.getHeight()));
            v.setX(x);
            v.setY(y);
        }
        return true;
    }

    private float[] defaultPos(HudButton b, int w, int h)
    {
        float bw = px(b.wDp);
        float bh = px(b.hDp);
        if (b == reloadBtn)
            return new float[] { px(16), h - bh - px(16) };
        if (b == coinBtn)
            return new float[] { w / 2f - bw - px(6), h - bh - px(10) };
        if (b == startBtn)
            return new float[] { w / 2f + px(6), h - bh - px(10) };
        if (b == testBtn)
            return new float[] { px(16), px(16) };
        return new float[] { px(16) + px(80) + px(8), px(16) };
    }

    private void applyPositions()
    {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0)
            return;
        for (HudButton b : buttons) {
            float[] pos = defaultPos(b, w, h);
            if (prefs.contains(b.name + "_x") && prefs.contains(b.name + "_y")) {
                pos[0] = prefs.getFloat(b.name + "_x", 0f) * w;
                pos[1] = prefs.getFloat(b.name + "_y", 0f) * h;
            }
            float x = Math.max(0, Math.min(pos[0], w - px(b.wDp)));
            float y = Math.max(0, Math.min(pos[1], h - px(b.hDp)));
            b.view.setX(x);
            b.view.setY(y);
        }
    }

    // ------------------------------------------------------------------ top-right icons

    private class IconView extends View
    {
        private final int type;
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconView(int type)
        {
            super(activity);
            this.type = type;
            setClickable(true);
            white.setColor(Color.WHITE);
            shadow.setColor(0x99000000);
        }

        private void style(Paint p, float extra)
        {
            if (type == ICON_PLAY) {
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeWidth(extra);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(2.5f) + extra);
            }
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = px(10);
            style(shadow, px(2.5f));
            glyph(c, shadow, cx, cy, r);
            style(white, 0);
            glyph(c, white, cx, cy, r);
        }

        private void glyph(Canvas c, Paint p, float cx, float cy, float r)
        {
            if (type == ICON_PLAY) {
                float left = cx - r;
                Path tri = new Path();
                tri.moveTo(left, cy - r * 0.8f);
                tri.lineTo(left, cy + r * 0.8f);
                tri.lineTo(left + r * 0.9f, cy);
                tri.close();
                c.drawPath(tri, p);
                c.drawRect(left + r * 1.2f, cy - r * 0.8f, left + r * 1.48f, cy + r * 0.8f, p);
                c.drawRect(left + r * 1.7f, cy - r * 0.8f, left + r * 1.98f, cy + r * 0.8f, p);
            } else if (type == ICON_ASPECT) {
                float arm = r * 0.6f;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        Path path = new Path();
                        path.moveTo(cx + sx * r, cy + sy * (r - arm));
                        path.lineTo(cx + sx * r, cy + sy * r);
                        path.lineTo(cx + sx * (r - arm), cy + sy * r);
                        c.drawPath(path, p);
                    }
                }
            } else {
                c.drawLine(cx - r, cy - r * 0.7f, cx + r, cy - r * 0.7f, p);
                c.drawLine(cx - r, cy, cx + r, cy, p);
                c.drawLine(cx - r, cy + r * 0.7f, cx + r, cy + r * 0.7f, p);
            }
        }
    }

    // ------------------------------------------------------------------ Quick options

    public void showQuickOptions()
    {
        if (editing || (quickDialog != null && quickDialog.isShowing()))
            return;
        final Dialog d = new Dialog(activity);
        quickDialog = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(22), px(16), px(22), px(10));
        card.setBackground(roundedBox(0xFF2B2B30, 26, 0, 0));

        TextView title = new TextView(activity);
        title.setText("Quick options");
        title.setTextColor(0xFFE6E6EA);
        title.setTextSize(24);
        card.addView(title);

        TextView exit = new TextView(activity);
        exit.setText("\u2192]  Exit game");
        exit.setTextColor(0xFF4FC3F7);
        exit.setTextSize(16);
        exit.setPadding(px(4), px(10), px(4), px(10));
        exit.setClickable(true);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                activity.finishAffinity();
            }
        });
        card.addView(exit);

        final TextView video = pill("Video settings");
        video.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                openFlycastMenu();
            }
        });
        TextView opacityBtn = pill("Controls opacity");
        opacityBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                showOpacityDialog();
            }
        });
        final TextView coinStart = pill("Coin / Start: " + (coinStartShown ? "Shown" : "Hidden"));
        coinStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                coinStartShown = !coinStartShown;
                prefs.edit().putBoolean("coinstart_shown", coinStartShown).apply();
                ((TextView) v).setText("Coin / Start: " + (coinStartShown ? "Shown" : "Hidden"));
                updateVisibility();
            }
        });
        final TextView testService = pill("Test / Service: " + (testServiceShown ? "Shown" : "Hidden"));
        testService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testServiceShown = !testServiceShown;
                prefs.edit().putBoolean("testservice_shown", testServiceShown).apply();
                ((TextView) v).setText("Test / Service: " + (testServiceShown ? "Shown" : "Hidden"));
                updateVisibility();
            }
        });
        TextView editHud = pill("Edit HUD");
        editHud.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                startEditing();
            }
        });
        final TextView aspect = pill("Aspect: " + aspectName(JNIdc.getAspectMode()));
        aspect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int mode = (JNIdc.getAspectMode() + 1) % 3;
                JNIdc.setAspectMode(mode);
                ((TextView) v).setText("Aspect: " + aspectName(mode));
            }
        });
        TextView saveStates = pill("Save states");
        saveStates.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                openFlycastMenu();
            }
        });
        TextView restart = pill("Restart game");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                new AlertDialog.Builder(activity)
                        .setMessage("Restart the game?")
                        .setPositiveButton("Restart", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface di, int which) {
                                JNIdc.restartGame();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        LinearLayout r1 = row();
        addPill(r1, video);
        addPill(r1, opacityBtn);
        LinearLayout r2 = row();
        addPill(r2, coinStart);
        addPill(r2, testService);
        LinearLayout r3 = row();
        addPill(r3, editHud);
        addPill(r3, aspect);
        LinearLayout r4 = row();
        addPill(r4, saveStates);
        addPill(r4, restart);
        card.addView(r1);
        card.addView(r2);
        card.addView(r3);
        card.addView(r4);

        TextView cancel = new TextView(activity);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFF4FC3F7);
        cancel.setTextSize(16);
        cancel.setGravity(Gravity.END);
        cancel.setPadding(px(8), px(12), px(16), px(8));
        cancel.setClickable(true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        card.addView(cancel);

        ScrollView sv = new ScrollView(activity);
        sv.addView(card);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int width = Math.min((int) (dm.widthPixels * 0.8f), px(620));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private void showOpacityDialog()
    {
        final Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(22), px(16), px(22), px(10));
        card.setBackground(roundedBox(0xFF2B2B30, 26, 0, 0));

        TextView title = new TextView(activity);
        title.setText("Controls opacity");
        title.setTextColor(0xFFE6E6EA);
        title.setTextSize(22);
        card.addView(title);

        SeekBar bar = new SeekBar(activity);
        bar.setMax(80);
        bar.setProgress(opacity - 20);
        bar.setPadding(px(8), px(20), px(8), px(20));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                opacity = 20 + progress;
                applyOpacity();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("opacity", opacity).apply();
            }
        });
        card.addView(bar);

        TextView ok = new TextView(activity);
        ok.setText("OK");
        ok.setTextColor(0xFF4FC3F7);
        ok.setTextSize(16);
        ok.setGravity(Gravity.END);
        ok.setPadding(px(8), px(8), px(16), px(8));
        ok.setClickable(true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putInt("opacity", opacity).apply();
                d.dismiss();
            }
        });
        card.addView(ok);

        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            w.setLayout(Math.min((int) (dm.widthPixels * 0.6f), px(420)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    // ------------------------------------------------------------------ Edit HUD

    private void startEditing()
    {
        editing = true;
        editScrim = new View(activity);
        editScrim.setBackgroundColor(0x99000000);
        editScrim.setClickable(true);
        root.addView(editScrim, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editHint = new TextView(activity);
        editHint.setText("Drag the buttons to reposition them");
        editHint.setTextColor(Color.WHITE);
        editHint.setTextSize(16);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.setMargins(0, px(40), 0, 0);
        root.addView(editHint, hlp);

        editBar = new LinearLayout(activity);
        editBar.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = pill("Reset");
        reset.setBackground(roundedBox(0xFF2B2B30, 100, 0xFF8A8A8E, 1.5f));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor e = prefs.edit();
                for (HudButton b : buttons) {
                    e.remove(b.name + "_x");
                    e.remove(b.name + "_y");
                }
                e.apply();
                applyPositions();
            }
        });
        TextView done = pill("Done");
        done.setBackground(roundedBox(0xFF2B2B30, 100, 0xFF8A8A8E, 1.5f));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishEditing();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(110), px(44));
        blp.setMargins(px(8), 0, px(8), 0);
        editBar.addView(reset, blp);
        editBar.addView(done, new LinearLayout.LayoutParams(blp));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        elp.setMargins(0, px(70), 0, 0);
        root.addView(editBar, elp);

        updateVisibility();
    }

    private void finishEditing()
    {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w > 0 && h > 0) {
            SharedPreferences.Editor e = prefs.edit();
            for (HudButton b : buttons) {
                e.putFloat(b.name + "_x", b.view.getX() / w);
                e.putFloat(b.name + "_y", b.view.getY() / h);
            }
            e.apply();
        }
        editing = false;
        root.removeView(editScrim);
        root.removeView(editBar);
        root.removeView(editHint);
        editScrim = null;
        editBar = null;
        editHint = null;
        updateVisibility();
    }
}
