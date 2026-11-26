package ibuprofen.motor;

/**
 * Human motor persona parameters for aim, click rhythm, and WASD hysteresis.
 */
public final class HumanProfile {

    public enum ClickStyle {
        HOLD,
        JITTER,
        BUTTERFLY
    }

    private final String name;
    private final double aimSpeed;
    private final double fittsBaseTicks;
    private final double fittsSlopeTicks;
    private final double overshoot;
    private final double pursuitAlpha;
    private final double tremor;
    private final double sprintConeDeg;
    private final int minKeyHoldTicks;
    private final ClickStyle clickStyle;
    private final double cpsMean;
    private final double cpsStd;

    public HumanProfile(
            String name,
            double aimSpeed,
            double fittsBaseTicks,
            double fittsSlopeTicks,
            double overshoot,
            double pursuitAlpha,
            double tremor,
            double sprintConeDeg,
            int minKeyHoldTicks,
            ClickStyle clickStyle,
            double cpsMean,
            double cpsStd) {
        this.name = name;
        this.aimSpeed = aimSpeed;
        this.fittsBaseTicks = fittsBaseTicks;
        this.fittsSlopeTicks = fittsSlopeTicks;
        this.overshoot = overshoot;
        this.pursuitAlpha = pursuitAlpha;
        this.tremor = tremor;
        this.sprintConeDeg = sprintConeDeg;
        this.minKeyHoldTicks = minKeyHoldTicks;
        this.clickStyle = clickStyle;
        this.cpsMean = cpsMean;
        this.cpsStd = cpsStd;
    }

    public static HumanProfile casual() {
        return new HumanProfile("casual", 0.78, 3.0, 1.45, 0.14, 0.34, 0.14, 38.0, 2, ClickStyle.JITTER, 7.0, 1.4);
    }

    public static HumanProfile average() {
        return new HumanProfile("average", 1.00, 2.2, 1.15, 0.09, 0.48, 0.09, 44.0, 2, ClickStyle.JITTER, 9.5, 1.6);
    }

    public static HumanProfile sweaty() {
        return new HumanProfile("sweaty", 1.25, 1.5, 0.85, 0.05, 0.62, 0.05, 48.0, 1, ClickStyle.BUTTERFLY, 12.5, 1.8);
    }

    public static HumanProfile defaultProfile() {
        return average();
    }

    public String name() {
        return name;
    }

    public double aimSpeed() {
        return aimSpeed;
    }

    public double fittsBaseTicks() {
        return fittsBaseTicks;
    }

    public double fittsSlopeTicks() {
        return fittsSlopeTicks;
    }

    public double overshoot() {
        return overshoot;
    }

    public double pursuitAlpha() {
        return pursuitAlpha;
    }

    public double tremor() {
        return tremor;
    }

    public double sprintConeDeg() {
        return sprintConeDeg;
    }

    public int minKeyHoldTicks() {
        return minKeyHoldTicks;
    }

    public ClickStyle clickStyle() {
        return clickStyle;
    }

    public double cpsMean() {
        return cpsMean;
    }

    public double cpsStd() {
        return cpsStd;
    }

    /**
     * Box-Muller standard normal sample using deterministic ForkableRandom.
     */
    public static double nextGaussian(ForkableRandom rand) {
        double u1 = Math.max(1e-12, rand.nextDouble());
        double u2 = rand.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }
}
