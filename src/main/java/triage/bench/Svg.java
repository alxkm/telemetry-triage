package triage.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal SVG line charts, so the figures need nothing beyond the JDK. */
public final class Svg {
    public record Series(String label, double[] x, double[] y, String color, boolean dashed) {
    }

    public record Marker(double x, String color, String label) {
    }

    public record Panel(String title, List<Series> series, List<Marker> markers, double[] xRange, double[] yRange, String xLabel, String yLabel, boolean legendBottom) {
    }

    private static final int W = 760;
    private static final int PANEL_H = 230;
    private static final int LEFT = 70;
    private static final int RIGHT = 20;
    private static final int TOP = 30;
    private static final int BOTTOM = 45;

    private Svg() {
    }

    public static void write(Path file, String title, List<Panel> panels) throws IOException {
        int h = 40 + panels.size() * (PANEL_H + TOP + BOTTOM);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d' font-family='Helvetica,Arial,sans-serif' font-size='11'>%n", W, h));
        sb.append(String.format(Locale.ROOT, "<rect width='%d' height='%d' fill='white'/>%n", W, h));
        sb.append(String.format(Locale.ROOT, "<text x='%d' y='22' font-size='14' font-weight='bold'>%s</text>%n", LEFT, esc(title)));
        int y0 = 40;
        for (Panel p : panels) {
            panel(sb, p, y0);
            y0 += PANEL_H + TOP + BOTTOM;
        }
        sb.append("</svg>\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void panel(StringBuilder sb, Panel p, int y0) {
        int plotW = W - LEFT - RIGHT;
        int top = y0 + TOP;
        double[] xr = p.xRange() != null ? p.xRange() : range(p.series(), true);
        double[] yr = p.yRange() != null ? p.yRange() : pad(range(p.series(), false));
        sb.append(String.format(Locale.ROOT, "<text x='%d' y='%d' font-size='12' font-weight='bold'>%s</text>%n", LEFT, y0 + 18, esc(p.title())));
        sb.append(String.format(Locale.ROOT, "<rect x='%d' y='%d' width='%d' height='%d' fill='none' stroke='#999'/>%n", LEFT, top, plotW, PANEL_H));
        for (int i = 0; i <= 4; i++) {
            double v = yr[0] + (yr[1] - yr[0]) * i / 4.0;
            double py = top + PANEL_H - (v - yr[0]) / (yr[1] - yr[0]) * PANEL_H;
            sb.append(String.format(Locale.ROOT, "<line x1='%d' x2='%d' y1='%.1f' y2='%.1f' stroke='#eee'/>%n", LEFT, LEFT + plotW, py, py));
            sb.append(String.format(Locale.ROOT, "<text x='%d' y='%.1f' text-anchor='end'>%s</text>%n", LEFT - 5, py + 4, num(v)));
            double xv = xr[0] + (xr[1] - xr[0]) * i / 4.0;
            double px = LEFT + (xv - xr[0]) / (xr[1] - xr[0]) * plotW;
            sb.append(String.format(Locale.ROOT, "<text x='%.1f' y='%d' text-anchor='middle'>%s</text>%n", px, top + PANEL_H + 15, num(xv)));
        }
        sb.append(String.format(Locale.ROOT, "<text x='%d' y='%d' text-anchor='middle'>%s</text>%n", LEFT + plotW / 2, top + PANEL_H + 32, esc(p.xLabel())));
        sb.append(String.format(Locale.ROOT, "<text x='14' y='%d' transform='rotate(-90 14 %d)' text-anchor='middle'>%s</text>%n", top + PANEL_H / 2, top + PANEL_H / 2, esc(p.yLabel())));
        int labelRow = 0;
        for (Marker m : p.markers()) {
            double px = LEFT + (m.x() - xr[0]) / (xr[1] - xr[0]) * plotW;
            if (px < LEFT || px > LEFT + plotW) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "<line x1='%.1f' x2='%.1f' y1='%d' y2='%d' stroke='%s' stroke-dasharray='4,3'/>%n", px, px, top, top + PANEL_H, m.color()));
            sb.append(String.format(Locale.ROOT, "<text x='%.1f' y='%d' fill='%s'>%s</text>%n", px + 3, top + 12 + 12 * (labelRow++ % 4), m.color(), esc(m.label())));
        }
        int legendX = LEFT + plotW - 10;
        int legendY = p.legendBottom() ? top + PANEL_H - 8 - 14 * (p.series().size() - 1) : top + 14;
        for (Series s : p.series()) {
            StringBuilder path = new StringBuilder();
            boolean pen = false;
            for (int i = 0; i < s.x().length; i++) {
                double xv = s.x()[i];
                double yv = s.y()[i];
                if (!Double.isFinite(yv) || xv < xr[0] || xv > xr[1]) {
                    pen = false;
                    continue;
                }
                double cy = Math.max(yr[0], Math.min(yr[1], yv));
                double px = LEFT + (xv - xr[0]) / (xr[1] - xr[0]) * plotW;
                double py = top + PANEL_H - (cy - yr[0]) / (yr[1] - yr[0]) * PANEL_H;
                path.append(pen ? " L" : " M").append(String.format(Locale.ROOT, "%.1f %.1f", px, py));
                pen = true;
            }
            sb.append(String.format(Locale.ROOT, "<path d='%s' fill='none' stroke='%s' stroke-width='1.2'%s/>%n", path.toString().trim(), s.color(), s.dashed() ? " stroke-dasharray='5,3'" : ""));
            if (s.x().length <= 12) {
                for (int i = 0; i < s.x().length; i++) {
                    if (!Double.isFinite(s.y()[i])) {
                        continue;
                    }
                    double px = LEFT + (s.x()[i] - xr[0]) / (xr[1] - xr[0]) * plotW;
                    double py = top + PANEL_H - (s.y()[i] - yr[0]) / (yr[1] - yr[0]) * PANEL_H;
                    sb.append(String.format(Locale.ROOT, "<circle cx='%.1f' cy='%.1f' r='2.5' fill='%s'/>%n", px, py, s.color()));
                }
            }
            sb.append(String.format(Locale.ROOT, "<line x1='%d' x2='%d' y1='%d' y2='%d' stroke='%s' stroke-width='2'%s/>%n", legendX - 200, legendX - 180, legendY - 4, legendY - 4, s.color(), s.dashed() ? " stroke-dasharray='5,3'" : ""));
            sb.append(String.format(Locale.ROOT, "<text x='%d' y='%d'>%s</text>%n", legendX - 175, legendY, esc(s.label())));
            legendY += 14;
        }
    }

    private static double[] range(List<Series> series, boolean x) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            for (double v : x ? s.x() : s.y()) {
                if (Double.isFinite(v)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
        }
        if (lo == hi) {
            hi = lo + 1;
        }
        return new double[] {lo, hi};
    }

    private static double[] pad(double[] r) {
        double d = (r[1] - r[0]) * 0.05;
        return new double[] {r[0] - d, r[1] + d};
    }

    private static String num(double v) {
        if (Math.abs(v) >= 1000 || v == Math.rint(v)) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return String.format(Locale.ROOT, Math.abs(v) >= 10 ? "%.1f" : "%.2f", v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static double[] indices(int from, int to) {
        double[] r = new double[to - from];
        for (int i = 0; i < r.length; i++) {
            r[i] = from + i;
        }
        return r;
    }

    static List<Series> list(Series... s) {
        return new ArrayList<>(List.of(s));
    }
}
