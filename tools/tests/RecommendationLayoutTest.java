package io.freemytv;

public final class RecommendationLayoutTest {
    private static final float EPSILON = 0.001f;

    public static void main(String[] args) {
        for (int expanded = 0; expanded < 3; expanded++) assertLayout(expanded);
        System.out.println("RecommendationLayoutTest passed");
    }

    private static void assertLayout(int expanded) {
        float[] rects = new float[12];
        RecommendationLayout.apply(rects, 80, 262, 442, 1760, 40, 320, expanded);
        float x = 80;
        for (int i = 0; i < 3; i++) {
            float width = i == expanded ? 1040 : 320;
            int at = i * 4;
            equal(x, rects[at]);
            equal(262, rects[at + 1]);
            equal(x + width, rects[at + 2]);
            equal(704, rects[at + 3]);
            x += width + 40;
        }
        equal(1840, x - 40);
    }

    private static void equal(float expected, float actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}
