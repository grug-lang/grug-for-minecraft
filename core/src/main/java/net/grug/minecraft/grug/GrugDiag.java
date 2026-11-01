package net.grug.minecraft.grug;

import java.awt.image.BufferedImage;
import net.grug.minecraft.core.GrugCore;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Throwaway diagnostics for #961: never merged. */
public final class GrugDiag {
    private static BufferedImage first;
    private static BufferedImage prev;
    private static int n;
    private static int written;

    private GrugDiag() {}

    public static synchronized void onCapture(BufferedImage img) {
        if (img.getWidth() != 1280 || img.getHeight() != 720) {
            return;
        }
        try {
            File dir = new File(GrugCore.getAdapter().getGameDirectory(), "grug-screenshot-artifacts/diag");
            dir.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("capture ").append(n).append(" ").append(img.getWidth()).append("x").append(img.getHeight());
            if (first != null && first.getWidth() == img.getWidth() && first.getHeight() == img.getHeight()) {
                sb.append(" vsPrev ").append(stats(prev, img, dir, "prev"));
                sb.append(" vsFirst ").append(stats(first, img, dir, "first"));
            } else {
                first = img;
                try { ImageIO.write(img, "png", new File(dir, "first.png")); } catch (IOException e) { }
            }
            prev = img;
            n++;
            try (FileWriter w = new FileWriter(new File(dir, "stats.txt"), true)) {
                w.write(sb.append("\n").toString());
            }
            System.out.println("[GRUG DIAG] " + sb);
        } catch (Exception e) {
            System.out.println("[GRUG DIAG] failed " + e);
        }
    }

    private static String stats(BufferedImage a, BufferedImage b, File dir, String tag) {
        int w = a.getWidth(), h = a.getHeight();
        int count = 0, max = 0, small = 0;
        int[] srows = new int[9];
        int[] scols = new int[16];
        int[] rows = new int[9];
        int[] cols = new int[16];
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)),
                        Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                if (d > 0) {
                    count++;
                    if (d <= 8) {
                        small++;
                        srows[y * 9 / h]++;
                        scols[x * 16 / w]++;
                    }
                    max = Math.max(max, d);
                    rows[y * 9 / h]++;
                    cols[x * 16 / w]++;
                    int v = Math.min(255, 60 + d * 40);
                    diff.setRGB(x, y, (v << 16) | (v << 8));
                } else {
                    diff.setRGB(x, y, (q & 0xFEFEFE) >> 1);
                }
            }
        }
        if (count > 0 && written < 30) {
            written++;
            try { ImageIO.write(diff, "png", new File(dir, "diff-" + n + "-" + tag + ".png")); } catch (IOException e) { }
            try { ImageIO.write(b, "png", new File(dir, "cap-" + n + ".png")); } catch (IOException e) { }
        }
        return count + " px small " + small + " srows " + java.util.Arrays.toString(srows) + " scols " + java.util.Arrays.toString(scols) + " max " + max + " rows " + java.util.Arrays.toString(rows) + " cols " + java.util.Arrays.toString(cols);
    }
}
