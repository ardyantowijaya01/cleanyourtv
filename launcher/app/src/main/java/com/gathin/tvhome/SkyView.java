package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * A full-screen gradient that follows the clock: night blues, a warm horizon at sunset, a soft
 * glow that travels with the sun. Off by default (config.xml, sky_intensity = 0); a theme turns
 * it on with {@code "sky_intensity": 60}.
 *
 * Like the ambient glow, the backdrop is one small bitmap (480x270) stretched over the screen:
 * a gradient repainted at full resolution every minute is exactly the CPU cost the ambient glow
 * was rewritten to avoid on weak GPUs. It is redrawn once a minute and never animated, so the
 * cost is a single full-screen draw per minute.
 */
public class SkyView extends View {

    /** Full-screen and faded with setAlpha: without this every redraw of Home goes through an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private static final int TEX_W = 480;
    private static final int TEX_H = 270;

    /** Opacity of the whole backdrop, 0 to 255, from sky_intensity. */
    private int intensity;
    private int lastMinute = -1;
    private Bitmap texture;
    private BitmapDrawable drawable;
    /** Where the sun or the moon is now, recomputed with the rest of the sky once a minute. */
    @Nullable
    private Sky.Body body;
    private float moonLit;
    private boolean moonWaxing;
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path moonPath = new Path();
    private final Path shadowPath = new Path();

    public SkyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** {@code percent} of sky_intensity: 0 hides the backdrop, 100 covers the wallpaper. */
    public void setIntensity(int percent) {
        intensity = Math.max(0, Math.min(100, percent));
        setAlpha(intensity / 100f);
        setVisibility(intensity == 0 ? GONE : VISIBLE);
        if (intensity > 0) render(true);
    }

    /** Picks up a new time of day; cheap and safe to call every minute. */
    public void update() {
        if (intensity == 0 || getVisibility() != VISIBLE) return;
        render(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (intensity > 0) render(true);
    }

    /** Today's sunrise and sunset at the configured place, or null without one (fixed reference day). */
    @Nullable
    private int[] sunTimes() {
        String lat = getContext().getString(R.string.latitude);
        String lon = getContext().getString(R.string.longitude);
        if (lat.isEmpty() || lon.isEmpty()) return null;
        try {
            return Sun.riseAndSet(LocalDate.now(), Double.parseDouble(lat), Double.parseDouble(lon),
                    ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void render(boolean force) {
        Calendar now = Calendar.getInstance();
        int minute = Sky.minuteOfDay(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        if (!force && minute == lastMinute) return;
        lastMinute = minute;

        int[] sun = sunTimes();
        Sky.Palette sky = sun == null ? Sky.at(minute) : Sky.at(minute, sun[0], sun[1]);
        body = sun == null ? Sky.bodyAt(minute, 0, 0) : Sky.bodyAt(minute, sun[0], sun[1]);
        if (body.moon) {
            // The phase comes from the day of the lunar month, which is what Lunar already computes.
            double tz = TimeZone.getDefault().getOffset(now.getTimeInMillis()) / 3600000.0;
            int lunarDay = Lunar.fromSolar(now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.YEAR), tz)[0];
            moonLit = Sky.moonLit(lunarDay);
            moonWaxing = Sky.moonWaxing(lunarDay);
        }
        if (texture == null) texture = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(texture);
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0f, 0f, 0f, TEX_H,
                new int[]{sky.top, sky.mid, sky.bottom},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, TEX_W, TEX_H, paint);

        if (sky.sun > 0) {
            // A warm core low on the horizon, where the sun (or its afterglow) sits.
            int warm = Sky.lerpColor(sky.bottom, 0xFFFFD9A0, 0.65f);
            paint.setShader(new RadialGradient(
                    sky.sunX * TEX_W, TEX_H * 0.80f, TEX_W * 0.55f,
                    new int[]{(sky.sun << 24) | (warm & 0x00FFFFFF), warm & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, TEX_W, TEX_H, paint);
        }

        if (drawable == null) {
            drawable = new BitmapDrawable(getResources(), texture);
            drawable.setGravity(Gravity.FILL);
            setBackground(drawable);
        }
        invalidate();
    }

    /**
     * The sun or the moon itself, over the gradient. Drawn at the view's own resolution rather than
     * into the 480x270 texture, where a disc this small would stretch to a blur; it is two shapes
     * once a minute, not an animation.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Sky.Body b = body;
        if (b == null || intensity == 0) return;
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        // Fade out near the horizon, so neither body pops into existence as it rises or sets.
        int alpha = Math.round(255 * Math.min(1f, b.alt * 5f));
        if (alpha <= 0) return;

        float horizon = h * 0.80f;
        float cx = b.x * w;
        float cy = horizon - b.alt * (horizon - h * 0.14f);
        float r = w * (b.moon ? 0.017f : 0.021f);
        drawHalo(canvas, cx, cy, r, alpha, b.moon ? 0xFFBFD4F2 : 0xFFFFD08A);
        if (b.moon) drawMoon(canvas, cx, cy, r, alpha);
        else {
            bodyPaint.setShader(null);
            bodyPaint.setColor(0xFFFFF4D6);
            bodyPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, r, bodyPaint);
        }
    }

    /** The soft light around a body, which is what makes it read as light rather than a sticker. */
    private void drawHalo(Canvas canvas, float cx, float cy, float r, int alpha, int colour) {
        int core = (Math.round(alpha * 0.45f) << 24) | (colour & 0x00FFFFFF);
        bodyPaint.setAlpha(255);
        bodyPaint.setShader(new RadialGradient(cx, cy, r * 4.5f,
                new int[]{core, colour & 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 4.5f, bodyPaint);
        bodyPaint.setShader(null);
    }

    /**
     * The moon with its phase: the lit disc minus a circle offset across it.
     * shortcut: a circular terminator, so gibbous phases are approximate; at this size the
     * difference is a pixel or two. Upgrade to an ellipse if the moon is ever drawn large.
     */
    private void drawMoon(Canvas canvas, float cx, float cy, float r, int alpha) {
        moonPath.reset();
        moonPath.addCircle(cx, cy, r, Path.Direction.CW);
        if (moonLit < 0.98f) {
            float offset = 2 * r * moonLit;
            shadowPath.reset();
            shadowPath.addCircle(moonWaxing ? cx - offset : cx + offset, cy, r, Path.Direction.CW);
            moonPath.op(shadowPath, Path.Op.DIFFERENCE);
        }
        bodyPaint.setColor(0xFFE9EFF8);
        bodyPaint.setAlpha(alpha);
        canvas.drawPath(moonPath, bodyPaint);
    }
}
