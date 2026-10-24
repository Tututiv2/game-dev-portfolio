using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// Hand and arm poses, authored as HUMANOID MUSCLE VALUES.
///
/// <para>WHY MUSCLES AND NOT BONE ROTATIONS OR ANIMATION CLIPS.</para>
///
/// <para>The naive version of "every character holds every weapon, and inspects
/// it" is 7 characters x N weapons x M animations of hand-authored clips. On a
/// zero budget that is why this feature normally does not get built.</para>
///
/// <para>A muscle value is Unity's own normalised, rig-INDEPENDENT description of
/// a joint: -1..1 between the avatar's configured limits. The same number means
/// the same pose on every humanoid avatar, which is the entire point of humanoid
/// retargeting. So one pose written here works on all seven characters and on
/// every character added later, with no per-character art.</para>
///
/// <para>Raw bone rotations would NOT do this. Each rig has its own bone local
/// axes, so a quaternion that opens one character's hand twists another's wrist.
/// These seven happen to come from two vendors, which is exactly enough
/// similarity to make a rotation-based pose look correct on the four it was
/// tuned against and wrong on the rest.</para>
///
/// <para>Numbers live here rather than on an inspector field, matching the
/// RoundRules/DamageMath convention: a pose baked into a scene component is a
/// value no test can see and no diff can review.</para>
/// </summary>
public static class FirstPersonPoses
{
    /// <summary>A single joint axis and where to put it, -1..1.</summary>
    public struct Muscle
    {
        public readonly string name;
        public readonly float value;
        public Muscle(string name, float value) { this.name = name; this.value = value; }
    }

    // Declared BEFORE the poses that use them. C# runs static field initializers
    // in declaration order, so with these at the bottom of the class Build() ran
    // against two null arrays and the whole type failed to initialise — which
    // surfaces as a TypeInitializationException from whatever touched a pose
    // first, naming a file that is not where the mistake is.
    static readonly string[] Sides = { "Left", "Right" };
    static readonly string[] Fingers = { "Thumb", "Index", "Middle", "Ring", "Little" };

    /// <summary>
    /// Hands empty, held forward and apart — what you see when you are carrying
    /// nothing.
    ///
    /// <para>RELAXED, not flat. At curl 0 every joint is fully extended and the
    /// hands read as two boards with a long straight thumb sticking off each
    /// one. A real empty hand keeps a little tension in every joint, which is
    /// what an FPS hand pose looks like, and it is also what gives a glove some
    /// form to catch light on.</para>
    /// </summary>
    public static readonly Muscle[] EmptyOpen = Build(0.16f);

    /// <summary>The same pose driven by a tunable asset. Falls back to the
    /// constants above when none is supplied, so a missing asset changes
    /// nothing rather than resetting the hands to a default nobody chose.</summary>
    public static Muscle[] From(HandPoseSettings s)
    {
        return s == null ? EmptyOpen
                         : Build(s.fingerCurl, s.thumbCurl, s.thumbSpread, s.fan);
    }

    /// <summary>
    /// The same pose with the fingers curled, as the starting point for holding
    /// something. Not wired to a weapon yet — grips need a socket on each weapon
    /// — but it proves the finger channels are driveable, which is the part the
    /// glove and grip work both depend on.
    /// </summary>
    public static readonly Muscle[] Curled = Build(0.75f);

    /// <summary>
    /// <paramref name="curl"/> 0 = flat open hand, 1 = closed fist. Everything
    /// else is shared, so the two poses cannot drift apart in the arms while
    /// only the fingers are meant to differ.
    /// </summary>
    static Muscle[] Build(float curl)
    {
        return Build(curl, Mathf.Min(1f, curl * 1.35f), -0.30f, 1f);
    }

    static Muscle[] Build(float curl, float thumbCurl, float thumbSpread, float fan)
    {
        var m = new List<Muscle>();

        // -- arms ------------------------------------------------------------
        // SOLVED, not chosen. Unity's muscle axis names name the JOINT and not
        // the direction of travel — "Arm Front-Back" does not say which sign is
        // front — so the first version was written from the names and put every
        // character's hands 63% of a screen BELOW the frame with the fingertips
        // behind the camera. Identically on all seven, which reads as a systemic
        // bug rather than as a wrong guess.
        //
        // Tools > Aftermarket > Solve First-Person Arm Pose grid-searches these
        // three axes and reports where the hand lands. These are its answer for
        // the right hand at camera-local (0.17, -0.20, 0.42) m: measured
        // (0.190, -0.203, 0.376), a 0.381 m gap between the hands.
        //
        // The solver's FIRST objective was viewport coordinates, and it produced
        // a pose that scored perfectly while being obviously wrong — both hands
        // on screen, 0.135 m apart, meeting in the middle with the fingers
        // pointing at each other. Screen position cannot express separation.
        // Re-run it if a rig or the eye height changes.
        //
        // The shoulders are deliberately at rest. The solve held them there, and
        // carrying over the hand-guessed 0.10/-0.15 would move the arms away from
        // the solution while every number still looked deliberate.
        foreach (string side in Sides)
        {
            m.Add(new Muscle(side + " Shoulder Down-Up", 0f));
            m.Add(new Muscle(side + " Shoulder Front-Back", 0f));

            m.Add(new Muscle(side + " Arm Down-Up", 0.250f));
            m.Add(new Muscle(side + " Arm Front-Back", -0.375f));
            m.Add(new Muscle(side + " Arm Twist In-Out", 0f));

            m.Add(new Muscle(side + " Forearm Stretch", 0.250f));
            m.Add(new Muscle(side + " Forearm Twist In-Out", 0.250f));

            // The wrist is solved SEPARATELY from the arm, in a second pass,
            // because the twist axes barely move the hand — solved together, a
            // tiny position gain buys a badly turned wrist. Objective: point the
            // middle finger along the camera's forward axis. Measured result is
            // forward 1.00, right 0.01, up -0.03.
            m.Add(new Muscle(side + " Hand Down-Up", 0.250f));
            m.Add(new Muscle(side + " Hand In-Out", 1.0f));
        }

        // -- fingers -----------------------------------------------------------
        // Every finger joint is driven explicitly. Leaving a joint out does not
        // leave it neutral — it leaves it at whatever the previous pose put
        // there, which is how a hand ends up open at the knuckles and clenched
        // at the tips.
        foreach (string side in Sides)
        {
            foreach (string finger in Fingers)
            {
                // The thumb opposes the others, so a shared curl value bends it
                // into the palm rather than across it.
                float f = finger == "Thumb" ? thumbCurl : curl;
                m.Add(new Muscle(side + " " + finger + " 1 Stretched", -f));
                m.Add(new Muscle(side + " " + finger + " 2 Stretched", -f));
                m.Add(new Muscle(side + " " + finger + " 3 Stretched", -f));
                m.Add(new Muscle(side + " " + finger + " Spread",
                                 finger == "Thumb" ? thumbSpread
                                                   : Spread(finger) * fan * (1f - curl)));
            }
        }

        return m.ToArray();
    }

    /// <summary>A flat hand fans slightly; a fist does not. Splaying every
    /// finger by the same amount reads as a cartoon "jazz hands".</summary>
    static float Spread(string finger)
    {
        switch (finger)
        {
            case "Index": return 0.12f;
            case "Middle": return 0.0f;
            case "Ring": return -0.10f;
            case "Little": return -0.22f;
            default: return 0f;
        }
    }
}
