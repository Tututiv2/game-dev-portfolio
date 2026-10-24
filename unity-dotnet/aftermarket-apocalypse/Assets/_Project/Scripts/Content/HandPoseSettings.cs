using UnityEngine;

/// <summary>
/// Every number that decides how the first-person hands look, in one asset you
/// can drag a slider on.
///
/// <para>These lived in <see cref="FirstPersonPoses"/> as constants, which is
/// this project's convention and is right for tuning nobody looks at. It is
/// wrong here. "The hands are too closed" is a judgement only the owner can
/// make, and the loop was costing a code edit, a batch-mode Unity run and a
/// screenshot per guess — with me deciding what "more open" meant.</para>
///
/// <para>An asset keeps the one property the convention was protecting: it is a
/// real file, so it diffs, it is reviewable, and a test can read it. What it
/// gives up is that the value is no longer visible in the source that uses it —
/// so <see cref="FirstPersonPoses"/> keeps the same numbers as its DEFAULTS and
/// this overrides them. If the asset is missing, nothing breaks and nothing
/// silently changes.</para>
/// </summary>
[CreateAssetMenu(fileName = "HandPose", menuName = "Aftermarket/Hand Pose")]
public class HandPoseSettings : ScriptableObject
{
    [Header("Fingers")]
    [Range(0f, 1f)]
    [Tooltip("0 = flat open hand, 1 = closed fist.")]
    public float fingerCurl = 0.08f;

    [Range(0f, 1f)]
    [Tooltip("The thumb opposes the other fingers, so it needs its own amount — " +
             "a shared value bends it ACROSS the palm instead of into it.")]
    public float thumbCurl = 0.30f;

    [Range(-1f, 1f)]
    [Tooltip("Negative tucks the thumb in against the hand; positive splays it out.")]
    public float thumbSpread = -0.30f;

    [Range(0f, 2f)]
    [Tooltip("How much the fingers fan apart. A flat hand fans; a fist does not.")]
    public float fan = 1f;

    [Header("Proportions")]
    /// <summary>
    /// Scales the whole thumb chain. This is a BONE LENGTH knob, not a pose one,
    /// and it exists because no amount of posing could fix what was wrong.
    ///
    /// <para>Measured on the vendor rigs: Miyoko's thumb chain is 6.3 cm against
    /// a 6.3 cm index, and the Assassin's is 7.8 cm against 7.2 cm. A real thumb
    /// is roughly 60-70% of the index. Both are stylised long, and the thumb root
    /// also sits ~5 cm closer to the wrist than the index root, so the tip
    /// reaches nearly as far as the fingertips.</para>
    ///
    /// <para>Scaling the proximal bone shortens the entire chain, because a
    /// child's local position is scaled by its parent. Setting all three would
    /// compound to the cube.</para>
    /// </summary>
    [Range(0.4f, 1.3f)]
    public float thumbLength = 0.75f;

    [Range(0.4f, 1.3f)]
    [Tooltip("The same for the other four fingers. 1 leaves them exactly as the " +
             "artist made them.")]
    public float fingerLength = 1f;

    [Header("Placement")]
    [Tooltip("Where the RIGHT hand sits in CAMERA space, in metres. The left " +
             "mirrors it, so x is half the gap between the hands.")]
    public Vector3 rightHandLocal = new Vector3(0.17f, -0.07f, 0.40f);

    [Range(0f, 60f)]
    [Tooltip("Degrees each hand is canted inward. Straight down the camera axis " +
             "they read as two planks.")]
    public float handInward = 22f;

    [Range(0f, 1.5f)]
    [Tooltip("How far out the elbows are pushed. Too low and the solver bends " +
             "them through the ribcage.")]
    public float elbowOut = 0.45f;

    [Header("Body")]
    [Range(0f, 1f)]
    [Tooltip("Share of the camera pitch the spine takes. Only affects how the " +
             "body looks from OUTSIDE now that the arms ride the camera.")]
    public float spineFollow = 1f;

    [Range(0f, 0.3f)]
    [Tooltip("How far forward of the head bone the eyes sit.")]
    public float eyeForward = 0.06f;
}
