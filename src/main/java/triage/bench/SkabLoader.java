package triage.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the Skoltech Anomaly Benchmark (SKAB, GPL-3.0; https://github.com/waico/SKAB). Files are semicolon
 * separated: datetime, eight sensor channels, then {@code anomaly} and {@code changepoint} labels (absent in the
 * anomaly-free file). Rows are one second apart.
 */
public final class SkabLoader {
    public record SkabFile(String name, String[] channels, double[][] values, boolean[] anomaly) {
        public int rows() {
            return anomaly.length;
        }

        public int firstAnomaly() {
            for (int i = 0; i < anomaly.length; i++) {
                if (anomaly[i]) {
                    return i;
                }
            }
            return -1;
        }

        public int lastAnomaly() {
            for (int i = anomaly.length - 1; i >= 0; i--) {
                if (anomaly[i]) {
                    return i;
                }
            }
            return -1;
        }

        public double[] channel(int c) {
            double[] r = new double[values.length];
            for (int i = 0; i < r.length; i++) {
                r[i] = values[i][c];
            }
            return r;
        }
    }

    private SkabLoader() {
    }

    public static SkabFile read(Path file, Path root) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] header = lines.get(0).split(";");
        int nChannels = 8;
        String[] channels = new String[nChannels];
        System.arraycopy(header, 1, channels, 0, nChannels);
        boolean labelled = header.length > 1 + nChannels;
        double[][] values = new double[lines.size() - 1][nChannels];
        boolean[] anomaly = new boolean[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(";");
            for (int c = 0; c < nChannels; c++) {
                values[i - 1][c] = Double.parseDouble(f[1 + c]);
            }
            anomaly[i - 1] = labelled && Double.parseDouble(f[1 + nChannels]) > 0.5;
        }
        String name = root.relativize(file).toString().replace('\\', '/');
        return new SkabFile(name, channels, values, anomaly);
    }

    /** Every CSV under {@code root}, sorted by relative path. */
    public static List<SkabFile> readAll(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
        }
        List<SkabFile> out = new ArrayList<>();
        for (Path p : files) {
            out.add(read(p, root));
        }
        return out;
    }
}
