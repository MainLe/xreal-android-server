package com.xreal.glassesdisplayplugevent.display;

import android.graphics.Point;
import android.hardware.display.DeviceProductInfo;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import java.util.Locale;

/** Binary-compatible replacement for the vendor helper's HDMI-name-only detector. */
public final class DisplayModel {
    private final int width, height, nrealDisplay;
    private final boolean isHdmi;
    public DisplayModel(Display display) {
        Point p = new Point();
        if (display != null) display.getRealSize(p);
        width = p.x; height = p.y;
        nrealDisplay = identify(display);
        isHdmi = nrealDisplay >= 0;
        Log.i("XrealServer.Display", "id=" + (display == null ? -1 : display.getDisplayId())
                + " name=" + (display == null ? "null" : display.getName())
                + " type=" + nrealDisplay + " size=" + width + "x" + height);
    }
    private static int identify(Display d) {
        if (d == null || !d.isValid() || d.getDisplayId() == Display.DEFAULT_DISPLAY) return -1;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                DeviceProductInfo info = d.getDeviceProductInfo();
                if (info != null) {
                    String vid = info.getManufacturerPnpId(), pid = info.getProductId();
                    if ("NRL".equals(vid) && "12594".equals(pid)) return 1;
                    if ("MRG".equals(vid)) {
                        if ("12593".equals(pid)) return 1;
                        if ("12594".equals(pid)) return 2;
                        if ("12596".equals(pid)) return 4;
                        if ("12597".equals(pid)) return 3;
                        if ("12598".equals(pid)) return 5;
                        if ("16640".equals(pid)) return 6;
                        if ("16641".equals(pid)) return 7;
                        if ("16642".equals(pid)) return 8;
                        return 0;
                    }
                }
            } catch (RuntimeException e) { Log.w("XrealServer.Display", "Product info unavailable", e); }
        }
        // Fallback is restricted to identified glasses names, never any arbitrary external display.
        String name = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
        if (name.contains("air 2 ultra")) return 5;
        if (name.contains("xreal") || name.contains("nreal")) return 0;
        return -1;
    }
    public boolean isHdmi() { return isHdmi; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getNrealDisplay() { return nrealDisplay; }
    @Override public String toString() {
        return "DisplayModel{isHdmi=" + isHdmi + ", width=" + width + ", height=" + height + ", nrealDisplay=" + nrealDisplay + '}';
    }
}
