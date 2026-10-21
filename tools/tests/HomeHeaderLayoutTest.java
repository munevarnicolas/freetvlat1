package io.freemytv;

public final class HomeHeaderLayoutTest {
    private static final float EPSILON = 0.001f;

    public static void main(String[] args) {
        float[] bounds = new float[4];
        for (float scale : new float[] {0.75f, 1f, 1.5f}) {
            for (float titleRight : new float[] {380, 520, 740}) {
                float buttonLeft = 1596 * scale;
                HomeHeaderLayout.apply(bounds, buttonLeft, titleRight * scale, scale);
                equal(Math.max(titleRight * scale + HomeHeaderLayout.TITLE_GAP * scale,
                        buttonLeft - (HomeHeaderLayout.BUTTON_GAP + HomeHeaderLayout.MAX_WIDTH) * scale), bounds[0]);
                equal(buttonLeft - HomeHeaderLayout.BUTTON_GAP * scale, bounds[2]);
                equal(69 * scale, bounds[1]);
                equal(137 * scale, bounds[3]);
                equal(103 * scale, (bounds[1] + bounds[3]) / 2);
                if (bounds[2] - bounds[0] > HomeHeaderLayout.MAX_WIDTH * scale + EPSILON) {
                    throw new AssertionError("header exceeds its design width");
                }
                if (Math.abs(bounds[3] - bounds[1] - HomeHeaderLayout.HEIGHT * scale) > EPSILON) {
                    throw new AssertionError("unexpected header height");
                }
            }
        }
        System.out.println("HomeHeaderLayoutTest passed");
    }

    private static void equal(float expected, float actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}
