package io.github.stonym.minecartwithfurnacefix.train;

/**
 * One tick of a rolling train's speed.
 *
 * Only the coasting case lives here. A lit furnace does not use this at all:
 * vanilla gives a furnace minecart full speed as soon as it is stoked, and
 * {@link TrainMover} reproduces that by pinning the speed to
 * {@code max-speed}. That also removes the state in which a train could ever
 * lose ground to a slope.
 *
 * Kept separate from {@link TrainMover} so the numbers can be checked without
 * a running server: how long a dead train coasts, and whether it can start
 * rolling again from a standstill on a grade.
 *
 * The sign of {@code speed} is relative to the cached path: positive means the
 * train moves towards increasing arc length. {@code tangentY} is expressed in
 * the same frame, so an ascending rail (positive {@code tangentY}) always pulls
 * the train back down the slope it is on, whichever way it happens to be
 * rolling.
 */
public final class SpeedModel {

    private SpeedModel() {
    }

    /**
     * @param speed      signed speed at the start of the tick, blocks/tick
     * @param friction   fraction of speed kept per tick, 0..1
     * @param resistance constant deceleration while coasting
     * @param gravity    slope pull, blocks/tick^2
     * @param tangentY   vertical part of the rail direction, -1..1
     */
    public static double coast(double speed, double friction, double resistance,
            double gravity, double tangentY, double maxSpeed, double stopSpeed) {
        double next = speed * friction;

        if (next != 0.0) {
            double brake = Math.min(Math.abs(next), resistance);
            next -= Math.signum(next) * brake;
        }

        // Kill the floating point tail before the grade gets a say, but only
        // when the grade itself is too weak to matter — otherwise a cart left
        // standing on a hill would be frozen there instead of rolling down.
        double pull = tangentY * gravity;
        if (Math.abs(next) < stopSpeed && Math.abs(pull) < stopSpeed) {
            next = 0.0;
        }

        next -= pull;

        if (next > maxSpeed) {
            next = maxSpeed;
        } else if (next < -maxSpeed) {
            next = -maxSpeed;
        }
        return next;
    }
}
