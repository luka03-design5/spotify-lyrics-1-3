package dev.axiom.lyrics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Zeichnet die Lyrics. Modus 0: 3D-Schrift in der Welt. Modus 1: weiche Anzeige unten.
 * Text-Qualitaet: mehrschichtiger Glow, soft depth, Subpixel-Passes bei grosser Skala
 * damit die Bitmap-Schrift weniger pixelig wirkt.
 */
public final class LyricsRenderer {
    private static final int MAX_CHARS = 18;
    private static final float ROW_H = 18f;
    private static final float UNIT = 0.10f;

    private record Row(List<String> words, float[] widths, float width, int firstWord, float xOff, float roll) {}
    private record Phrase(Vec3d anchor, Vector3f right, Vector3f up, Vector3f normal,
                          List<Row> rows, long start, long dur, float step) {}

    private static final Map<Integer, Phrase> PHRASES = new HashMap<>();
    private static String cacheTrack = "";
    private static String lastStatus = "";
    private static long statusSince = 0;

    public static void render(DrawContext g, RenderTickCounter tc) {
        if (!LyricsState.enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        TextRenderer tr = mc.textRenderer;
        int W = g.getScaledWindowWidth(), H = g.getScaledWindowHeight();

        String track = LyricsState.trackId;
        if (!track.equals(cacheTrack)) { PHRASES.clear(); cacheTrack = track; }

        List<LyricsState.LrcLine> lines = LyricsState.lines;
        if (lines.isEmpty()) { drawStatus(g, tr, W, H); return; }

        long t = LyricsState.now() + LyricsState.offsetMs;
        int cur = LyricsState.indexAt(lines, t);
        if (cur < 0) return;

        if (LyricsState.mode == 1) drawBottom(g, tr, lines, cur, t, W, H);
        else draw3d(g, mc, tr, lines, cur, t, W, H, tc.getTickProgress(true));
    }

    private static void drawStatus(DrawContext g, TextRenderer tr, int W, int H) {
        String s = LyricsState.status;
        long now = System.currentTimeMillis();
        if (!s.equals(lastStatus)) { lastStatus = s; statusSince = now; }
        if (now - statusSince > 8000) return;
        String text = tr.trimToWidth(s, W - 40);
        int tw = tr.getWidth(text);
        int x = (W - tw) / 2;
        int y = H - 68;
        LyricsScreen.rrect(g, x - 10, y - 6, tw + 20, 18, 6, 0xAA0B0D12);
        softText(g, tr, text, x, y, 1f, 0xFFB79CFF, 0x60A06CFF, true);
    }

    private static void drawBottom(DrawContext g, TextRenderer tr, List<LyricsState.LrcLine> lines,
                                   int cur, long t, int W, int H) {
        LyricsState.Theme th = LyricsState.theme();
        String prev = cur > 0 ? lines.get(cur - 1).text() : "";
        String now = lines.get(cur).text();
        String next = cur + 1 < lines.size() ? lines.get(cur + 1).text() : "";

        long start = lines.get(cur).ms();
        long end = cur + 1 < lines.size() ? lines.get(cur + 1).ms() : start + 4000;
        float progress = end > start ? Math.max(0f, Math.min(1f, (t - start) / (float) (end - start))) : 0f;
        float appear = easeOutCubic(Math.min(1f, (t - start) / 280f));

        float k = Math.max(0.55f, Math.min(2.4f, LyricsState.size));
        int cardH = (int) (92 * Math.min(1.35f, k));
        int baseY = H - (int) (28 + cardH * 0.55f);

        int cardW = Math.min(W - 48, (int) (420 * Math.min(1.4f, k)));
        int cardX = (W - cardW) / 2;
        int cardY = baseY - (int) (38 * k);
        LyricsScreen.rrect(g, cardX, cardY, cardW, cardH, 10, 0xCC0B0D12);
        int ac = 0xFF000000 | th.glow();
        LyricsScreen.rrect(g, cardX + 14, cardY + 6, Math.max(24, (int) ((cardW - 28) * (0.15f + 0.7f * progress))), 3, 2, ac);

        centeredSoft(g, tr, prev, W / 2f, baseY - 22 * k, 0.95f * k, argb(140, th.past()), argb(40, th.glow()));
        centeredSoft(g, tr, now,  W / 2f, baseY,          1.55f * k * (0.92f + 0.08f * appear),
            argb((int) (255 * appear), th.front()), argb((int) (90 * appear), th.glow()));
        centeredSoft(g, tr, next, W / 2f, baseY + 24 * k, 0.95f * k, argb(120, th.past()), argb(30, th.glow()));
    }

    private static void centeredSoft(DrawContext g, TextRenderer tr, String s, float cx, float y,
                                     float scale, int color, int glow) {
        if (s == null || s.isBlank()) return;
        float w = tr.getWidth(s);
        softText(g, tr, s, cx - w * scale * 0.5f, y, scale, color, glow, true);
    }

    private static void draw3d(DrawContext g, MinecraftClient mc, TextRenderer tr,
                               List<LyricsState.LrcLine> lines, int cur, long t, int W, int H, float tickProgress) {
        PHRASES.keySet().removeIf(k -> k < cur - 2 || k > cur);

        Camera cam = mc.gameRenderer.getCamera();
        Vec3d camPos = mc.player.getCameraPosVec(tickProgress);
        Quaternionf q = cam.getRotation();
        Quaternionf inv = new Quaternionf(q).conjugate();

        float fov = mc.options.getFov().getValue();
        float tanHalf = (float) Math.tan(Math.toRadians(fov) / 2.0);
        float aspect = (float) W / (float) H;

        for (int k = Math.max(0, cur - 2); k <= cur; k++) {
            Phrase p = PHRASES.get(k);
            if (p == null) {
                if (k != cur) continue;
                p = spawn(k, lines, camPos, q, tr);
                if (p == null) continue;
                PHRASES.put(k, p);
            }
            drawPhrase(g, tr, p, t, camPos, inv, tanHalf, aspect, W, H);
        }
    }

    private static Phrase spawn(int k, List<LyricsState.LrcLine> lines, Vec3d camPos, Quaternionf q, TextRenderer tr) {
        LyricsState.LrcLine ln = lines.get(k);
        String raw = ln.text() == null ? "" : ln.text().trim();
        if (raw.isEmpty()) return null;

        List<String> words = splitWords(raw);
        if (words.isEmpty()) return null;

        List<Row> rows = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        int first = 0;
        float rowW = 0;
        Random rnd = new Random(ln.ms() * 31L + k * 17L);

        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            float ww = tr.getWidth(w);
            float next = rowW + (buf.isEmpty() ? 0 : 4f) + ww;
            if (!buf.isEmpty() && (next > MAX_CHARS * 6f || buf.size() >= 6)) {
                rows.add(makeRow(tr, new ArrayList<>(buf), first, rows.size(), rnd));
                first += buf.size();
                buf.clear();
                rowW = 0;
            }
            buf.add(w);
            rowW += (buf.size() > 1 ? 4f : 0f) + ww;
        }
        if (!buf.isEmpty()) rows.add(makeRow(tr, new ArrayList<>(buf), first, rows.size(), rnd));
        if (rows.isEmpty()) return null;

        long nextMs = k + 1 < lines.size() ? lines.get(k + 1).ms() : ln.ms() + 4500;
        long dur = Math.max(1800, Math.min(9000, nextMs - ln.ms() + 1200));
        float step = Math.max(55f, Math.min(220f, dur / (float) Math.max(1, words.size() + 2)));

        Vector3f fwd = q.transform(new Vector3f(0f, 0f, -1f));
        Vector3f right = q.transform(new Vector3f(1f, 0f, 0f));
        Vector3f up = q.transform(new Vector3f(0f, 1f, 0f));
        double dist = 7.5 + rnd.nextDouble() * 2.8;
        double side = (k % 2 == 0 ? -1 : 1) * (0.08 + rnd.nextDouble() * 0.28);
        double lift = -0.01 + rnd.nextDouble() * 0.22;
        Vec3d pos = camPos.add(
            fwd.x * dist + right.x * side * dist,
            fwd.y * dist + right.y * side * dist + lift * dist,
            fwd.z * dist + right.z * side * dist);

        return new Phrase(pos, right, up, fwd, rows, ln.ms(), dur, step);
    }

    private static List<String> splitWords(String raw) {
        List<String> out = new ArrayList<>();
        for (String p : raw.split("\\s+")) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static Row makeRow(TextRenderer tr, List<String> words, int first, int r, Random rnd) {
        float[] wd = new float[words.size()];
        float total = 0;
        for (int i = 0; i < wd.length; i++) {
            wd[i] = tr.getWidth(words.get(i));
            total += wd[i] + (i > 0 ? 4f : 0f);
        }
        float xOff = (r % 2 == 0 ? -1 : 1) * (2f + rnd.nextFloat() * 16f);
        float roll = (float) Math.toRadians((rnd.nextFloat() - 0.5f) * 8f);
        return new Row(words, wd, total, first, xOff, roll);
    }

    private static void drawPhrase(DrawContext g, TextRenderer tr, Phrase p, long t, Vec3d camPos,
                                   Quaternionf inv, float tanHalf, float aspect, int W, int H) {
        if (t < p.start()) return;
        long end = p.start() + p.dur();

        float fade = 1f;
        long fadeStart = end + 700;
        if (t > fadeStart) fade = 1f - (t - fadeStart) / 1200f;
        if (fade <= 0.02f) return;

        LyricsState.Theme th = LyricsState.theme();
        int main = t > end ? th.past() : th.front();
        float unit = UNIT * LyricsState.size;
        float totalH = p.rows().size() * ROW_H;

        for (int r = 0; r < p.rows().size(); r++) {
            Row row = p.rows().get(r);
            float rowY = r * ROW_H - totalH / 2f;
            float cosR = (float) Math.cos(row.roll()), sinR = (float) Math.sin(row.roll());
            float x = -row.width() / 2f;

            for (int w = 0; w < row.words().size(); w++) {
                float ww = row.widths()[w];
                int gi = row.firstWord() + w;
                float prog = (t - (p.start() + gi * p.step())) / 240f;
                float dx = x + ww / 2f;
                x += ww + 4f;
                if (prog <= 0f) continue;

                float e = easeOutCubic(Math.min(1f, prog));
                int alpha = (int) (255 * e * fade);
                if (alpha < 8) continue;

                float cx = row.xOff() + dx * cosR;
                float cy = rowY + 4f + dx * sinR + (1f - e) * 5f
                    + (float) Math.sin((t + gi * 230L) / 680.0) * 0.55f;

                double wx = p.anchor().x + (p.right().x * cx - p.up().x * cy) * unit;
                double wy = p.anchor().y + (p.right().y * cx - p.up().y * cy) * unit;
                double wz = p.anchor().z + (p.right().z * cx - p.up().z * cy) * unit;

                Vector3f v = new Vector3f((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z));
                float len = v.length();
                inv.transform(v);
                float depth = -v.z;
                if (depth < 0.6f || depth > 70f) continue;
                float sx = W * 0.5f * (1f + v.x / (depth * tanHalf * aspect));
                float sy = H * 0.5f * (1f - v.y / (depth * tanHalf));
                if (sx < -250 || sx > W + 250 || sy < -120 || sy > H + 120) continue;

                float s = (H * 0.5f) / (tanHalf * depth) * unit;
                if (s < 0.28f) continue;
                float pop = 0.78f + 0.22f * e;
                float dot = Math.abs(((float) (wx - camPos.x) * p.normal().x
                    + (float) (wy - camPos.y) * p.normal().y
                    + (float) (wz - camPos.z) * p.normal().z) / Math.max(0.001f, len));
                float squish = Math.max(0.55f, Math.min(1f, 0.55f + 0.45f * dot));

                drawWord(g, tr, row.words().get(w), ww, sx, sy, s * squish * pop, s * pop, row.roll(),
                    alpha, main, th);
            }
        }
    }

    private static void drawWord(DrawContext g, TextRenderer tr, String word, float ww,
                                 float sx, float sy, float scaleX, float scaleY, float roll,
                                 int alpha, int main, LyricsState.Theme th) {
        Matrix3x2fStack m = g.getMatrices();
        m.pushMatrix();
        m.translate(sx, sy);
        m.rotate(roll);
        m.scale(scaleX, scaleY);
        m.translate(-ww / 2f, -4f);

        int ga = Math.max(0, alpha / 6);
        if (ga >= 6) {
            float[][] rings = {
                {0f, -1.6f}, {0f, 1.6f}, {-1.6f, 0f}, {1.6f, 0f},
                {-1.1f, -1.1f}, {1.1f, -1.1f}, {-1.1f, 1.1f}, {1.1f, 1.1f},
                {0f, -2.4f}, {0f, 2.4f}, {-2.4f, 0f}, {2.4f, 0f}
            };
            int outer = argb(Math.max(4, ga / 2), th.glow());
            int mid = argb(ga, th.glow());
            for (int i = 0; i < rings.length; i++) {
                put(g, tr, word, rings[i][0], rings[i][1], i < 8 ? mid : outer);
            }
        }

        for (int i = 3; i >= 0; i--) {
            float o = (i + 1) * 0.42f;
            put(g, tr, word, o, o, argb((int) (alpha * 0.88f), th.depth(i)));
        }

        put(g, tr, word, 0f, 0f, argb(alpha, main));
        if (Math.max(scaleX, scaleY) > 1.35f && alpha > 40) {
            int soft = argb(Math.min(255, alpha / 5), main);
            put(g, tr, word, 0.35f, 0f, soft);
            put(g, tr, word, -0.25f, 0.2f, soft);
        }

        m.popMatrix();
    }

    private static void softText(DrawContext g, TextRenderer tr, String s, float x, float y,
                                 float scale, int color, int glow, boolean shadow) {
        Matrix3x2fStack m = g.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);

        int ga = (glow >>> 24) & 0xFF;
        if (ga >= 8) {
            float[][] offs = {{0, -1.2f}, {0, 1.2f}, {-1.2f, 0}, {1.2f, 0},
                {-0.9f, -0.9f}, {0.9f, -0.9f}, {-0.9f, 0.9f}, {0.9f, 0.9f}};
            for (float[] o : offs) {
                m.pushMatrix();
                m.translate(o[0], o[1]);
                g.drawText(tr, s, 0, 0, glow, false);
                m.popMatrix();
            }
        }
        g.drawText(tr, s, 0, 0, color, shadow);
        if (scale > 1.25f) {
            int a = Math.min(255, ((color >>> 24) & 0xFF) / 5);
            int soft = (a << 24) | (color & 0x00FFFFFF);
            m.pushMatrix();
            m.translate(0.3f, 0f);
            g.drawText(tr, s, 0, 0, soft, false);
            m.popMatrix();
        }
        m.popMatrix();
    }

    private static void put(DrawContext g, TextRenderer tr, String word, float ox, float oy, int color) {
        Matrix3x2fStack m = g.getMatrices();
        m.pushMatrix();
        m.translate(ox, oy);
        g.drawText(tr, word, 0, 0, color, false);
        m.popMatrix();
    }

    private static int argb(int a, int rgb) {
        return (Math.min(255, Math.max(0, a)) << 24) | (rgb & 0x00FFFFFF);
    }

    private static float easeOutCubic(float x) {
        float t = 1f - x;
        return 1f - t * t * t;
    }

    private LyricsRenderer() {}
}
