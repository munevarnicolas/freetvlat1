package io.freemytv;

final class HomeHeaderLayout {
    static final float MAX_WIDTH = 448, BUTTON_GAP = 24, TITLE_GAP = 48;
    static final float TOP = 69, HEIGHT = 68;

    private HomeHeaderLayout() {}

    static void apply(float[] out, float buttonLeft, float titleRight, float scale) {
        if (out.length < 4) throw new IllegalArgumentException("header bounds output is too small");
        if (scale <= 0) throw new IllegalArgumentException("scale must be positive");
        float right = buttonLeft - BUTTON_GAP * scale;
        float left = Math.max(titleRight + TITLE_GAP * scale, right - MAX_WIDTH * scale);
        out[0] = left;
        out[1] = TOP * scale;
        out[2] = right;
        out[3] = (TOP + HEIGHT) * scale;
    }
}
