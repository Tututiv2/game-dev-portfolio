using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// TRUE first person: the player IS the selected character. Look down and you
/// see your own chest, arms, legs and outfit, because there is only one body and
/// the camera sits inside its head.
///
/// <para>This is also the first thing in the project that makes the character
/// roster mean anything in play. Seven characters were imported, rendered and
/// put on a select screen, and nothing outside that screen ever read the
/// choice — the map spawned the same invisible capsule whatever you picked.</para>
///
/// <para>WHY THERE IS NO ANIMATOR CONTROLLER HERE.</para>
///
/// <para>The pose is written directly through <c>HumanPoseHandler</c> every
/// LateUpdate. That needs no clips, and clips are the expensive part: an idle
/// for empty hands, plus one per weapon, times seven characters, is a content
/// bill this project cannot pay. Muscle values are rig-independent, so one pose
/// in <see cref="FirstPersonPoses"/> covers every character.</para>
///
/// <para>LateUpdate specifically, and this is load-bearing: an Animator writes
/// its own result during the normal update, so a pose applied any earlier is
/// overwritten before it is ever drawn, silently. Adding a controller later does
/// not change that — this still has to run after it.</para>
/// </summary>
[DisallowMultipleComponent]
public class FirstPersonBody : MonoBehaviour
{
    [Header("Who")]
    [Tooltip("Resolves the id CharacterSelect wrote to PlayerPrefs. Without it " +
             "the body falls back to the first character in the catalog.")]
    public CharacterCatalog catalog;

    [Tooltip("Live-tunable hand numbers. Edit them with " +
             "Tools > Aftermarket > First-Person Hand Editor. Null is fine — the " +
             "constants in FirstPersonPoses are used instead.")]
    public HandPoseSettings handPose;

    [Header("Where the eyes go")]
    [Tooltip("The transform the camera hangs off — FPSController pitches it.")]
    public Transform cameraHolder;

    /// <summary>
    /// The camera is NOT parented to the head bone. It would inherit every head
    /// movement, and a camera driven by an animated neck is the classic way to
    /// make people motion sick. The head follows the camera instead.
    /// </summary>
    [Tooltip("Metres above the character's own feet that the eyes sit at.")]
    public float eyeHeight = 1.62f;

    /// <summary>
    /// How far FORWARD of the head bone the eyes really are.
    ///
    /// <para>The head bone sits at the centre of the skull. Seating the camera
    /// on it puts the viewpoint inside the head, so looking down brings the
    /// chest straight through the near plane and the body "phases". Real eyes
    /// are about a hand's width in front of the skull's centre, and moving the
    /// body back by that much is what clears the torso when you look down.</para>
    /// </summary>
    public float eyeForward = 0.06f;

    [Tooltip("Shrink the head bone so the inside of the skull is not drawn over " +
             "the camera. The head is still there for shadows.")]
    public bool hideHead = true;

    /// <summary>
    /// Spawn a SECOND copy of the character parented to the camera, and use it
    /// for the arms.
    ///
    /// <para>This is the hybrid every true-first-person shooter ends up at, and
    /// it exists because one body cannot do both jobs. Hands locked to the
    /// screen and shoulders locked to the body means every degree of pitch has
    /// to be absorbed by the elbow — measured at 0.379 m of elbow travel over
    /// +/-40 degrees, which leaning the whole torso only cut to 0.331.</para>
    ///
    /// <para>With the arms copy parented to the camera its shoulders move WITH
    /// the view, so the arm never has to change shape at all. The body copy
    /// keeps the chest, legs and outfit you see when you look down; its arms are
    /// scaled away so there are not two sets.</para>
    ///
    /// <para>No new art: it is the same character mesh twice, so gloves, themes
    /// and every future character work with no extra work. The arms copy's torso
    /// and head sit behind the camera and are never drawn, which is exactly what
    /// a view-model arms mesh is.</para>
    /// </summary>
    public bool armsOnCamera = true;

    [Header("Idle motion")]
    public float breathAmplitude = 0.012f;
    public float breathSpeed = 1.4f;
    public float swayAmount = 0.9f;

    [Header("Hands")]
    /// <summary>
    /// Where the RIGHT hand sits, in CAMERA space. The left mirrors it.
    ///
    /// <para>This is the fix for two separate problems that share one cause.</para>
    ///
    /// <para>1. Muscle values are normalised to EACH AVATAR'S OWN joint limits,
    /// so the same numbers land in different places on differently proportioned
    /// rigs. Measured: the shared pose put six characters' hands 0.21-0.30 m
    /// apart and Miyoko's <b>0.047 m</b> apart — her hands mashed together in
    /// front of her face. No single muscle pose can fix that, because the units
    /// are per-rig by definition.</para>
    ///
    /// <para>2. In true first person the arms hang off the shoulders, so LOOKING
    /// DOWN leaves them behind — anatomically right and completely wrong for a
    /// shooter, where the hands are expected to follow the view.</para>
    ///
    /// <para>Solving to a CAMERA-SPACE target answers both: the placement is in
    /// metres so every rig lands in the same spot, and it is relative to the
    /// camera so the hands come with you when you look around.</para>
    /// </summary>
    public Vector3 rightHandLocal = new Vector3(0.17f, -0.07f, 0.40f);

    [Tooltip("How far out to push the elbows. Without it the solver is free to " +
             "bend them through the ribcage — a valid solution the arm cannot make.")]
    public float elbowOut = 0.45f;

    [Tooltip("Degrees each hand is turned INWARD. Hands aimed straight down the " +
             "camera axis read as two planks; every shooter cants them toward " +
             "each other.")]
    public float handInward = 22f;

    [Range(0f, 1f)]
    /// <summary>
    /// Share of the camera pitch the spine takes. Defaults to the measured best.
    ///
    /// <para>Elbow travel over +/-40 degrees of pitch, right arm, camera-relative:
    /// 0.379 m at 0, 0.352 at 0.5, 0.331 at 1.0. It helps and it does NOT solve
    /// the problem — the hands are locked to the camera and the shoulders are
    /// locked to the body, so every degree of pitch has to go somewhere, and the
    /// elbow is the only joint left to take it. Leaning the whole torso only
    /// carries the shoulders part of the way.</para>
    /// </summary>
    public float spineFollow = 1f;

    public GameObject Body { get; private set; }
    public Animator Animator { get; private set; }
    public CharacterDefinition Character { get; private set; }

    /// <summary>
    /// Whichever rig actually owns the visible arms. With armsOnCamera it is the
    /// camera-parented copy, and the body copy's arms are collapsed — so anything
    /// measuring hands must ask HERE. Reading Animator instead measures two
    /// zero-scaled stumps and reports the hands as missing.
    /// </summary>
    public Animator ArmsAnimator => m_armsAnimator != null ? m_armsAnimator : Animator;

    const string k_CharacterKey = "SelectedCharacter";   // written by CharacterSelect

    HumanPoseHandler m_handler;
    HumanPose m_pose;
    Transform m_head;
    Vector3 m_headScale = Vector3.one;
    float[] m_target;
    Vector3 m_bodyPosition;
    Quaternion m_bodyRotation;
    Transform m_upperL, m_lowerL, m_handL, m_upperR, m_lowerR, m_handR;
    Transform m_tipL, m_tipR;
    Transform m_idxL, m_idxR, m_litL, m_litR;
    Transform m_eye;
    GameObject m_arms;
    Animator m_armsAnimator;
    HumanPoseHandler m_armsHandler;
    HumanPose m_armsPose;
    Vector3 m_armsBodyPos;
    Quaternion m_armsBodyRot;
    float m_bendSign = 1f;
    float m_spineSign = 1f;
    float m_forcedLean = float.NaN;
    bool m_ready;

    void Start()
    {
        if (!Spawn()) return;
        ApplySettings();
        CalibrateBend();
        CalibrateSpine();
        m_ready = true;
    }

    /// <summary>Instantiate the chosen character and take hold of its rig.</summary>
    public bool Spawn()
    {
        if (catalog == null)
        {
            Debug.LogError("[FirstPersonBody] no CharacterCatalog assigned, so the " +
                           "player has no body. Run Tools > Aftermarket > Build First-Person Body.");
            return false;
        }

        string id = PlayerPrefs.GetString(k_CharacterKey, "");
        Character = catalog.Find(id);
        if (Character == null)
        {
            // A saved id that no longer exists is not an error worth refusing
            // over — a character can be renamed or dropped between builds — but
            // it must be LOUD, because the symptom is "the game keeps giving me
            // someone else" and nothing else would say why.
            foreach (var c in catalog.characters)
                if (c != null) { Character = c; break; }
            if (Character == null)
            {
                Debug.LogError("[FirstPersonBody] the catalog holds no characters.");
                return false;
            }
            if (!string.IsNullOrEmpty(id))
                Debug.LogWarning($"[FirstPersonBody] saved character '{id}' is not in the " +
                                 $"catalog; falling back to '{Character.characterId}'.");
        }

        if (Character.prefab == null)
        {
            Debug.LogError($"[FirstPersonBody] '{Character.characterId}' has no prefab assigned.");
            return false;
        }

        Body = Instantiate(Character.prefab, transform);
        Body.name = "Body_" + Character.characterId;
        Body.transform.localPosition = Vector3.zero;
        Body.transform.localRotation = Quaternion.identity;

        Animator = Body.GetComponentInChildren<Animator>();
        if (Animator == null)
        {
            Debug.LogError($"[FirstPersonBody] '{Character.characterId}' has no Animator, so " +
                           "its rig cannot be posed.");
            return false;
        }

        // The three failure modes below all render a perfectly good character
        // standing in a T-pose, so each says which one it is and what fixes it.
        // NOTE: three of the characters' .fbx.meta files carry a stale
        // `rigImportErrors: "Avatar creation failed: Transform 'nyx_eye_l'..."`
        // string, and CLAUDE.md repeated it as "F_0x and Miyoko need a humanoid
        // remap before they can be animated". It is NOT true of the current
        // import — all seven avatars come back valid, humanoid and fully
        // fingered at runtime, and all seven pose. The meta string was never
        // re-cleared after the rig was fixed. Trust this check, not the meta.
        if (Animator.avatar == null || !Animator.avatar.isValid)
        {
            Debug.LogError($"[FirstPersonBody] '{Character.characterId}' has an INVALID avatar, " +
                           "so its rig cannot be posed. Re-import it as Humanoid.");
            return false;
        }
        if (!Animator.isHuman)
        {
            Debug.LogError($"[FirstPersonBody] '{Character.characterId}' is not a Humanoid rig, " +
                           "so muscle poses do not apply to it. Set Animation Type to Humanoid.");
            return false;
        }

        m_handler = new HumanPoseHandler(Animator.avatar, Animator.transform);
        m_handler.GetHumanPose(ref m_pose);
        m_target = new float[m_pose.muscles.Length];
        m_pose.muscles.CopyTo(m_target, 0);

        // A HumanPose carries the ROOT's position and rotation as well as the
        // muscles, and SetHumanPose writes all three. Reading it back and
        // re-applying it every frame therefore feeds the root's own drift into
        // itself: the body creeps and turns, a little per frame, forever.
        //
        // Measured, not theorised — it is why the calibration sweep contradicted
        // itself. Two rows that set the same six muscles to zero reported the
        // hand at y -0.770 and y -2.247, because by the second row the body had
        // wandered. Anything solved against those numbers would have been tuned
        // to an artefact of the instrument.
        m_bodyPosition = m_pose.bodyPosition;
        m_bodyRotation = m_pose.bodyRotation;

        // A SkinnedMeshRenderer culls against bounds derived from the BIND pose,
        // not from where the bones actually are. Posing the arms forward moves
        // the hands outside those bounds, and the camera sits inside the body,
        // so the renderer gets culled and the player sees NOTHING — while every
        // geometric check still reports the hands correctly in front of the
        // camera, because they are. Measured: the eye view came back as one
        // uniform colour while the same body photographed fine from outside.
        //
        // This is the standard cost of true first person and it is not optional
        // here: your own arms disappearing when you look up is the whole feature
        // failing, intermittently, with nothing logged.
        foreach (var smr in Body.GetComponentsInChildren<SkinnedMeshRenderer>(true))
            smr.updateWhenOffscreen = true;

        // THE EYE, not the holder. The camera is a CHILD of cameraHolder, raised
        // by eyeHeight — so treating the holder as the eye put every hand target
        // about 1.6 m too low. Measured: hands 0.09 m from the camera and six
        // screen-heights below the frame, while the separation between them was
        // perfect. A wrong ORIGIN and a correct OFFSET look like this.
        ResolveEye();

        m_upperL = Animator.GetBoneTransform(HumanBodyBones.LeftUpperArm);
        m_lowerL = Animator.GetBoneTransform(HumanBodyBones.LeftLowerArm);
        m_handL = Animator.GetBoneTransform(HumanBodyBones.LeftHand);
        m_upperR = Animator.GetBoneTransform(HumanBodyBones.RightUpperArm);
        m_lowerR = Animator.GetBoneTransform(HumanBodyBones.RightLowerArm);
        m_handR = Animator.GetBoneTransform(HumanBodyBones.RightHand);
        m_tipL = Animator.GetBoneTransform(HumanBodyBones.LeftMiddleDistal);
        m_tipR = Animator.GetBoneTransform(HumanBodyBones.RightMiddleDistal);
        m_idxL = Animator.GetBoneTransform(HumanBodyBones.LeftIndexProximal);
        m_idxR = Animator.GetBoneTransform(HumanBodyBones.RightIndexProximal);
        m_litL = Animator.GetBoneTransform(HumanBodyBones.LeftLittleProximal);
        m_litR = Animator.GetBoneTransform(HumanBodyBones.RightLittleProximal);

        m_head = Animator.GetBoneTransform(HumanBodyBones.Head);
        if (m_head != null) m_headScale = m_head.localScale;

        SeatEyes();
        if (armsOnCamera) SpawnArms();
        return true;
    }

    /// <summary>
    /// Drop the body so the character's OWN eyes land at the camera. Measured
    /// from the rig rather than assumed: characters are not all the same height,
    /// and a typed offset puts a short character's camera above their head.
    /// </summary>
    void SeatEyes()
    {
        var head = Animator.GetBoneTransform(HumanBodyBones.Head);
        if (head == null) return;
        // The eye height is MEASURED off the rig when the rig can answer.
        // MapSeeder puts the holder at 1.60 and this field defaults to 1.62; two
        // centimetres is invisible and permanent, and the whole point of seating
        // the body is that the camera ends up behind the character's own eyes.
        if (m_eye != null)
        {
            float rigEye = transform.InverseTransformPoint(m_eye.position).y;
            if (rigEye > 0.5f) eyeHeight = rigEye;
        }

        // Seat the body by the EYES, not the head bone.
        //
        // "Head" is a joint, and on most rigs it sits at the BASE of the skull.
        // Aligning it with the camera therefore drops the viewpoint to neck
        // level, inside the upper chest -- so looking down showed shoulders and
        // legs with a hole where the torso should be. Backfaces are culled, so
        // being inside your own chest renders as nothing at all, which is
        // exactly what "my body phases when I look down" describes.
        //
        // The humanoid rig knows where the eyes are when the vendor mapped them,
        // so that is used in preference and the head bone is only a fallback.
        Transform eyeL = Animator.GetBoneTransform(HumanBodyBones.LeftEye);
        Transform eyeR = Animator.GetBoneTransform(HumanBodyBones.RightEye);
        Vector3 eyeWorld;
        if (eyeL != null && eyeR != null) eyeWorld = (eyeL.position + eyeR.position) * 0.5f;
        else if (eyeL != null) eyeWorld = eyeL.position;
        else if (eyeR != null) eyeWorld = eyeR.position;
        else eyeWorld = head.position + Body.transform.up * 0.085f;

        Vector3 headLocal = Body.transform.InverseTransformPoint(eyeWorld);
        if (headLocal.y <= 0.01f) return;
        // Back, not just up: the camera looks from where the EYES are, and the
        // head bone is the middle of the skull.
        Body.transform.localPosition = new Vector3(0f, eyeHeight - headLocal.y, -eyeForward);
    }

    /// <summary>Set the muscles this pose names, and warn about any it gets wrong.</summary>
    public void ApplyPose(FirstPersonPoses.Muscle[] pose)
    {
        if (m_target == null) return;
        var index = MuscleIndex();
        var unknown = new List<string>();
        foreach (var muscle in pose)
        {
            int i;
            if (!index.TryGetValue(muscle.name, out i)) { unknown.Add(muscle.name); continue; }
            m_target[i] = Mathf.Clamp(muscle.value, -1f, 1f);
        }

        // A misspelled muscle name is silently ignored by Unity — the pose just
        // does not include that joint, and the result is a hand that is nearly
        // right. This project has paid for that exact class of bug repeatedly
        // (interactKey, whatIsEnemy, viewModelPrefab), so it is named here.
        if (unknown.Count > 0)
            Debug.LogError("[FirstPersonBody] these muscle names do not exist and were " +
                           "IGNORED, so part of the pose did nothing: " + string.Join(", ", unknown));
    }

    void LateUpdate()
    {
        if (!m_ready) return;
        PushPose(Mathf.Sin(Time.time * breathSpeed) * breathAmplitude);
    }

    /// <summary>
    /// Write the current pose onto the rig. Public and callable outside play
    /// mode, because the muscle signs were established by RENDERING the result
    /// (Editor/FirstPersonShot.cs) and an editor tool cannot wait for LateUpdate.
    /// </summary>
    public void PushPose(float breath = 0f)
    {
        if (m_handler == null || m_target == null) return;

        m_handler.GetHumanPose(ref m_pose);
        m_target.CopyTo(m_pose.muscles, 0);
        // Pin the root back to where it started. See Spawn() for what happens
        // when this is left to round-trip.
        m_pose.bodyPosition = m_bodyPosition;
        m_pose.bodyRotation = m_bodyRotation;

        // Breathing, so empty hands do not read as a freeze-frame. Applied to
        // the pose rather than to the transform: moving the whole body would
        // move the camera with it.
        Add("Left Arm Down-Up", breath * swayAmount);
        Add("Right Arm Down-Up", breath * swayAmount);
        Add("Chest Front-Back", breath);

        // Lean the upper body with the view. THIS is what keeps the elbows
        // still: the hand targets are camera-space so they swing with the view,
        // while the shoulders were bolted to an upright torso — so the
        // shoulder-to-hand distance changed with every pitch and the elbows
        // opened and closed to absorb it. Pitching the spine carries the
        // shoulders along, so the arm barely has to change shape at all.
        //
        // It also makes the body visibly look where you look from outside,
        // which is the other half of true first person.
        if (m_eye != null && spineFollow > 0f)
        {
            float pitch = Mathf.Asin(Mathf.Clamp(
                transform.InverseTransformDirection(m_eye.forward).y, -1f, 1f)) * Mathf.Rad2Deg;
            float lean = (float.IsNaN(m_forcedLean)
                          ? Mathf.Clamp(-pitch / 60f, -1f, 1f)
                          : m_forcedLean) * spineFollow * m_spineSign;
            Add("Spine Front-Back", lean * 0.45f);
            Add("Chest Front-Back", lean * 0.55f);
        }

        m_handler.SetHumanPose(ref m_pose);

        if (m_armsHandler != null)
        {
            m_armsHandler.GetHumanPose(ref m_armsPose);
            m_target.CopyTo(m_armsPose.muscles, 0);
            m_armsPose.bodyPosition = m_armsBodyPos;
            m_armsPose.bodyRotation = m_armsBodyRot;
            m_armsHandler.SetHumanPose(ref m_armsPose);
            Shrink(m_armsAnimator, HumanBodyBones.Head);
            Shrink(m_armsAnimator, HumanBodyBones.LeftUpperLeg);
            Shrink(m_armsAnimator, HumanBodyBones.RightUpperLeg);
            Shrink(Animator, HumanBodyBones.LeftUpperArm);
            Shrink(Animator, HumanBodyBones.RightUpperArm);
        }

        // AFTER SetHumanPose, never before: the pose writes every bone, so IK
        // applied first is overwritten and thrown away without a word.
        ScaleFingers();
        SolveHands();

        if (hideHead && m_head != null)
            m_head.localScale = Vector3.one * 0.0001f;
    }

    /// <summary>Pull every tunable off the asset and re-pose. Safe to call at
    /// any time, which is what lets the editor window preview live.</summary>
    public void ApplySettings()
    {
        if (handPose != null)
        {
            rightHandLocal = handPose.rightHandLocal;
            handInward = handPose.handInward;
            elbowOut = handPose.elbowOut;
            spineFollow = handPose.spineFollow;
            eyeForward = handPose.eyeForward;
        }
        ApplyPose(FirstPersonPoses.From(handPose));
        ScaleFingers();
        PushPose();
    }

    /// <summary>Build the camera-parented arms copy and hide the duplicates.</summary>
    void SpawnArms()
    {
        if (m_eye == null) return;

        m_arms = Instantiate(Character.prefab, m_eye);
        m_arms.name = "Arms_" + Character.characterId;
        m_armsAnimator = m_arms.GetComponentInChildren<Animator>();
        if (m_armsAnimator == null || m_armsAnimator.avatar == null || !m_armsAnimator.isHuman)
        {
            Debug.LogError("[FirstPersonBody] the arms copy has no usable humanoid rig.");
            Destroy(m_arms);
            m_arms = null;
            return;
        }

        foreach (var smr in m_arms.GetComponentsInChildren<SkinnedMeshRenderer>(true))
            smr.updateWhenOffscreen = true;

        m_armsHandler = new HumanPoseHandler(m_armsAnimator.avatar, m_armsAnimator.transform);
        m_armsHandler.GetHumanPose(ref m_armsPose);
        m_armsBodyPos = m_armsPose.bodyPosition;
        m_armsBodyRot = m_armsPose.bodyRotation;

        // Seat it so its own eyes sit at the camera, exactly as the body copy is
        // seated — then the shoulders land where a person's shoulders are
        // relative to their eyes, and the arms reach into frame on their own.
        var head = m_armsAnimator.GetBoneTransform(HumanBodyBones.Head);
        if (head != null)
        {
            Vector3 local = m_arms.transform.InverseTransformPoint(head.position);
            m_arms.transform.localPosition = new Vector3(0f, -local.y, -eyeForward);
            m_arms.transform.localRotation = Quaternion.identity;
        }

        // Nothing but arms. Head and legs on the arms copy would swing into
        // frame when you look down; the torso stays because it sits behind the
        // camera and is never drawn.
        Shrink(m_armsAnimator, HumanBodyBones.Head);
        Shrink(m_armsAnimator, HumanBodyBones.LeftUpperLeg);
        Shrink(m_armsAnimator, HumanBodyBones.RightUpperLeg);

        // ...and no second pair of arms on the body copy. UpperArm is the root of
        // the whole chain (clavicle -> upperarm -> lowerarm -> hand -> fingers),
        // so collapsing it takes the arm and nothing else.
        Shrink(Animator, HumanBodyBones.LeftUpperArm);
        Shrink(Animator, HumanBodyBones.RightUpperArm);

        // The IK drives the ARMS copy from here on.
        m_upperL = m_armsAnimator.GetBoneTransform(HumanBodyBones.LeftUpperArm);
        m_lowerL = m_armsAnimator.GetBoneTransform(HumanBodyBones.LeftLowerArm);
        m_handL = m_armsAnimator.GetBoneTransform(HumanBodyBones.LeftHand);
        m_upperR = m_armsAnimator.GetBoneTransform(HumanBodyBones.RightUpperArm);
        m_lowerR = m_armsAnimator.GetBoneTransform(HumanBodyBones.RightLowerArm);
        m_handR = m_armsAnimator.GetBoneTransform(HumanBodyBones.RightHand);
        m_tipL = m_armsAnimator.GetBoneTransform(HumanBodyBones.LeftMiddleDistal);
        m_tipR = m_armsAnimator.GetBoneTransform(HumanBodyBones.RightMiddleDistal);
        m_idxL = m_armsAnimator.GetBoneTransform(HumanBodyBones.LeftIndexProximal);
        m_idxR = m_armsAnimator.GetBoneTransform(HumanBodyBones.RightIndexProximal);
        m_litL = m_armsAnimator.GetBoneTransform(HumanBodyBones.LeftLittleProximal);
        m_litR = m_armsAnimator.GetBoneTransform(HumanBodyBones.RightLittleProximal);
    }

    /// <summary>
    /// Resize the finger chains. Scale is applied to the PROXIMAL bone only —
    /// a child's local position is scaled by its parent, so that shortens the
    /// whole chain once. Setting all three would compound to the cube.
    /// </summary>
    void ScaleFingers()
    {
        if (handPose == null) return;
        var a = ArmsAnimator;
        if (a == null) return;

        var thumbs = new[] { HumanBodyBones.LeftThumbProximal, HumanBodyBones.RightThumbProximal };
        foreach (var b in thumbs)
        {
            var t = a.GetBoneTransform(b);
            if (t != null) t.localScale = Vector3.one * handPose.thumbLength;
        }

        var fingers = new[]
        {
            HumanBodyBones.LeftIndexProximal, HumanBodyBones.LeftMiddleProximal,
            HumanBodyBones.LeftRingProximal, HumanBodyBones.LeftLittleProximal,
            HumanBodyBones.RightIndexProximal, HumanBodyBones.RightMiddleProximal,
            HumanBodyBones.RightRingProximal, HumanBodyBones.RightLittleProximal,
        };
        foreach (var b in fingers)
        {
            var t = a.GetBoneTransform(b);
            if (t != null) t.localScale = Vector3.one * handPose.fingerLength;
        }
    }

    static void Shrink(Animator a, HumanBodyBones bone)
    {
        var t = a.GetBoneTransform(bone);
        if (t != null) t.localScale = Vector3.one * 0.0001f;
    }

    /// <summary>
    /// Find the transform the player actually looks THROUGH.
    ///
    /// <para>Public because the camera does not always exist when the body is
    /// spawned — the screenshot tool builds the rig in the other order — and the
    /// hand targets are camera-space, so solving against the wrong transform
    /// silently puts every hand at the player's feet.</para>
    /// </summary>
    public void ResolveEye()
    {
        m_eye = null;
        if (cameraHolder == null) return;
        var c = cameraHolder.GetComponentInChildren<Camera>(true);
        m_eye = c != null ? c.transform : cameraHolder;
    }

    /// <summary>Put both hands on their camera-space targets.</summary>
    void SolveHands()
    {
        Transform cam = m_eye;
        if (cam == null) return;

        Vector3 r = rightHandLocal;
        SolveArm(m_upperR, m_lowerR, m_handR, cam.TransformPoint(r), cam, +1f);
        SolveArm(m_upperL, m_lowerL, m_handL,
                 cam.TransformPoint(new Vector3(-r.x, r.y, r.z)), cam, -1f);

        // Two-bone IK places the wrist and says nothing about which way it
        // FACES, so the fingers ended up pointing straight down (measured: up
        // -0.99, forward -0.02) while the hand sat exactly on target.
        //
        // Aimed by MEASURING the finger direction on the actual rig and rotating
        // to close the gap, rather than by assigning a world rotation: each rig's
        // hand bone has its own local axes, so a fixed rotation is correct on the
        // rig it was tuned against and a broken wrist everywhere else.
        Vector3 baseWant = (cam.forward + cam.up * -0.15f).normalized;
        // Palms turned slightly inward and up — the "open, waiting" read, and
        // what puts the back of a glove toward the camera.
        Vector3 wantPalm = (cam.up + cam.forward * 0.25f).normalized;

        // Each hand is canted TOWARD the other. Aimed straight down the camera
        // axis they read as two planks pointing at the horizon; every shooter
        // angles them inward.
        Vector3 wantR = Quaternion.AngleAxis(-handInward, cam.up) * baseWant;
        Vector3 wantL = Quaternion.AngleAxis(+handInward, cam.up) * baseWant;
        AimHand(m_handR, m_tipR, m_idxR, m_litR, wantR, wantPalm, +1f);
        AimHand(m_handL, m_tipL, m_idxL, m_litL, wantL, wantPalm, -1f);
    }

    /// <summary>
    /// Point the fingers, THEN settle the roll.
    ///
    /// <para>Aligning the finger axis alone leaves rotation ABOUT that axis
    /// wherever the IK happened to leave it, so the hands came out as flat
    /// flippers turned side-on — with the report cheerfully saying the fingers
    /// pointed forward 0.99, because they did.</para>
    ///
    /// <para>Both steps are derived from where the bones ACTUALLY are on this
    /// rig, never from a fixed rotation: every rig gives its hand bone different
    /// local axes, so an assigned world rotation is correct on the one character
    /// it was tuned against and a snapped wrist on the rest.</para>
    /// </summary>
    static void AimHand(Transform hand, Transform tip, Transform index, Transform little,
                        Vector3 want, Vector3 wantPalm, float side)
    {
        if (hand == null || tip == null) return;

        Vector3 have = tip.position - hand.position;
        if (have.sqrMagnitude < 1e-8f) return;
        hand.rotation = Quaternion.FromToRotation(have.normalized, want) * hand.rotation;

        if (index == null || little == null) return;
        Vector3 across = little.position - index.position;
        if (across.sqrMagnitude < 1e-8f) return;

        Vector3 palm = side * Vector3.Cross(want, across.normalized);
        if (palm.sqrMagnitude < 1e-8f) return;
        hand.rotation = Quaternion.AngleAxis(
            Vector3.SignedAngle(palm.normalized, wantPalm, want), want) * hand.rotation;
    }

    void SolveArm(Transform upper, Transform lower, Transform hand,
                  Vector3 target, Transform cam, float side)
    {
        if (upper == null || lower == null || hand == null) return;

        float a = Vector3.Distance(upper.position, lower.position);
        float b = Vector3.Distance(lower.position, hand.position);
        Vector3 toTarget = target - upper.position;
        float len = toTarget.magnitude;
        if (a < 1e-5f || b < 1e-5f || len < 1e-5f) return;

        // Clamped INSIDE the reachable range. Asking for a distance the arm
        // cannot span makes the law of cosines return NaN, and a NaN rotation
        // deletes the whole limb from the screen with no error logged.
        float c = Mathf.Clamp(len, Mathf.Abs(a - b) + 1e-3f, a + b - 1e-3f);

        // 1. aim the whole chain at the target.
        upper.rotation = Quaternion.FromToRotation(hand.position - upper.position, toTarget)
                         * upper.rotation;

        // 2. bend the elbow to close the triangle.
        float angU = Mathf.Acos(Mathf.Clamp((a * a + c * c - b * b) / (2f * a * c), -1f, 1f))
                     * Mathf.Rad2Deg;
        float angL = Mathf.Acos(Mathf.Clamp((a * a + b * b - c * c) / (2f * a * b), -1f, 1f))
                     * Mathf.Rad2Deg;

        // The pole decides WHICH WAY the elbow folds. Both directions reach the
        // target, and the wrong one bends the arm through the ribcage.
        Vector3 pole = upper.position
                     + cam.rotation * new Vector3(side * elbowOut, -0.25f, -0.30f);
        Vector3 axis = Vector3.Cross(toTarget, pole - upper.position);
        if (axis.sqrMagnitude < 1e-8f) axis = Vector3.Cross(toTarget, cam.up);
        axis.Normalize();

        float s = m_bendSign;
        upper.rotation = Quaternion.AngleAxis(s * -angU, axis) * upper.rotation;
        lower.rotation = Quaternion.AngleAxis(s * (180f - angL), axis) * lower.rotation;
    }

    /// <summary>
    /// Choose the spine sign that keeps the ARM SHAPE steadiest as you look
    /// around — measured directly, by pitching the view and watching how much
    /// the shoulder-to-target distance moves.
    ///
    /// <para>The first version inferred it instead: apply a lean, see which way
    /// the shoulder moved vertically, call the lower one "forward". It chose
    /// wrong, and the result was measurable and backwards — elbow travel over
    /// +/-40 degrees of pitch went 0.379 m at spineFollow 0, 0.410 at 0.5 and
    /// 0.445 at 1.0. Getting monotonically worse as the correction gets stronger
    /// is what an inverted sign looks like, and no amount of staring at the
    /// muscle names would have said so.</para>
    ///
    /// <para>So this optimises the symptom rather than a proxy for it. The hands
    /// are camera-locked and the shoulders are not, so every degree of pitch has
    /// to be absorbed by the elbow; the right lean is whichever one leaves it
    /// least to absorb.</para>
    /// </summary>
    public void CalibrateSpine()
    {
        if (m_upperR == null || m_eye == null || cameraHolder == null) return;

        Quaternion rest = cameraHolder.localRotation;
        float best = float.MaxValue;
        float bestSign = 1f;

        foreach (float sign in new[] { 1f, -1f })
        {
            m_spineSign = sign;
            float lo = float.MaxValue, hi = float.MinValue;
            foreach (float pitch in new[] { -35f, 0f, 35f })
            {
                cameraHolder.localRotation = Quaternion.Euler(pitch, 0f, 0f);
                PushPose();
                float d = Vector3.Distance(m_upperR.position,
                                           m_eye.TransformPoint(rightHandLocal));
                lo = Mathf.Min(lo, d);
                hi = Mathf.Max(hi, d);
            }
            if (hi - lo < best) { best = hi - lo; bestSign = sign; }
        }

        m_spineSign = bestSign;
        cameraHolder.localRotation = rest;
        PushPose();
    }

    /// <summary>
    /// Work out which way the elbow folds by TRYING BOTH and keeping whichever
    /// lands the hand nearer its target.
    ///
    /// <para>The rotation direction depends on each rig's bone axes, which no
    /// amount of reading the humanoid spec will tell you. Hard-coding a sign is
    /// how a solver works on the four characters it was tested against and puts
    /// the other three elbows through their own chest.</para>
    /// </summary>
    public void CalibrateBend()
    {
        if (m_handR == null || cameraHolder == null) return;
        Vector3 target = cameraHolder.TransformPoint(rightHandLocal);

        float best = float.MaxValue;
        float bestSign = 1f;
        foreach (float sign in new[] { 1f, -1f })
        {
            m_bendSign = sign;
            PushPose();
            float err = Vector3.Distance(m_handR.position, target);
            if (err < best) { best = err; bestSign = sign; }
        }
        m_bendSign = bestSign;
        PushPose();
    }

    void Add(string muscleName, float delta)
    {
        int i;
        if (MuscleIndex().TryGetValue(muscleName, out i))
            m_pose.muscles[i] = Mathf.Clamp(m_pose.muscles[i] + delta, -1f, 1f);
    }

    /// <summary>Restore the head, for the character-select screen and for shots.</summary>
    public void ShowHead()
    {
        if (m_head != null) m_head.localScale = m_headScale;
    }

    // HumanTrait.MuscleName is a fixed table for every humanoid avatar, so this
    // is built once for the whole process rather than per body.
    static Dictionary<string, int> s_index;

    public static Dictionary<string, int> MuscleIndex()
    {
        if (s_index != null) return s_index;
        s_index = new Dictionary<string, int>();
        string[] names = HumanTrait.MuscleName;
        for (int i = 0; i < names.Length; i++) s_index[names[i]] = i;
        return s_index;
    }
}
