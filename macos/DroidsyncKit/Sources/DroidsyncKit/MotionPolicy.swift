import Foundation

/// When the Mac app's looping animations run. Only Reduce Motion and visibility turn them off;
/// the state a mark shows (connected, offline, ...) never does.
public enum MotionPolicy {
    /// Animate when Reduce Motion is off and the hosting window is actually visible.
    public static func shouldAnimate(reduceMotion: Bool, onScreen: Bool) -> Bool {
        !reduceMotion && onScreen
    }

    /// Seconds between frames. Small marks and the slow steady states (connected, offline)
    /// are smooth at 30 fps; only large, fast marks get 60.
    public static func frameInterval(size: Double, steady: Bool) -> Double {
        steady || size <= 28 ? 1.0 / 30 : 1.0 / 60
    }
}
