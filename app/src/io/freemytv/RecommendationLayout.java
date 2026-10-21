package io.freemytv;

final class RecommendationLayout {
    private static final int CARD_COUNT = 3, VALUES_PER_CARD = 4;

    private RecommendationLayout() {}

    static void apply(float[] out, float left, float top, float height, float totalWidth, float gap,
                      float narrowWidth, int expanded) {
        if (out.length < CARD_COUNT * VALUES_PER_CARD) throw new IllegalArgumentException("layout output is too small");
        if (expanded < 0 || expanded >= CARD_COUNT) throw new IllegalArgumentException("invalid expanded card");
        float wideWidth = totalWidth - 2 * narrowWidth - 2 * gap;
        float x = left;
        for (int i = 0; i < CARD_COUNT; i++) {
            float width = i == expanded ? wideWidth : narrowWidth;
            int at = i * VALUES_PER_CARD;
            out[at] = x;
            out[at + 1] = top;
            out[at + 2] = x + width;
            out[at + 3] = top + height;
            x += width + gap;
        }
    }
}
