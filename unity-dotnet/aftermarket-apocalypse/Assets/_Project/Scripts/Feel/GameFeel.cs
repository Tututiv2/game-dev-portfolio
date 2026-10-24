using UnityEngine;
using UnityEngine.UI;
using System.Collections;

/// <summary>
/// THE GAME FEEL LAYER (spec §8.1) — muzzle light, FOV punch, sprint FOV,
/// camera shake, landing dips, red damage vignette, low-health heartbeat and the
/// headshot-kill hit-stop. Self-building, zero art dependencies, all audio
/// synthesized. Spec §19 says reuse this verbatim; it is carried over with three
/// corrections.
///
/// FIX 1 — spec §13.1 defect #22 (severe). ExtractionSystem un-parents the
/// camera for the ending, after which localPosition IS world position; the old
/// GameFeel kept writing cam.transform.localPosition every LateUpdate and yanked
/// it back to the head offset, so the game's only ending filmed from the map
/// origin for all 6.5 seconds. Cinematics now take a lease via
/// <see cref="AcquireCamera"/> and GameFeel writes nothing while it is held.
/// This is spec trap #6 ("never write camera local position from two scripts")
/// made enforceable instead of merely documented.
///
/// FIX 2 — the camera baseline is re-captured when the camera is re-parented or
/// swapped, instead of being latched on the first frame forever.
///
/// FIX 3 — baseFov is settable, so a settings-menu FOV change is not overwritten
/// every frame (noted under spec §8.1).
/// </summary>
public class GameFeel : MonoBehaviour
{
    public static GameFeel instance;

    private Camera cam;
    private Transform camParent;          // watched so a re-parent re-captures the baseline
    private float baseFov = -1f;
    private float fovPunch;
    private float sprintFov;
    private float shakeAmount;
    private float dipAmount;
    private Vector3 camBasePos;
    private bool basePosCaptured;

    private Image vignette;
    private float vignettePulse;

    private FPSController player;
    private CharacterController controller;
    private PlayerHealth health;
    private bool wasGrounded = true;

    private AudioSource src;
    private AudioClip whump, thud, heartbeat;
    private float nextHeartbeatTime;

    private Light muzzleLight;

    // ---- camera lease (FIX 1) ----
    private static Object cameraOwner;

    /// <summary>True while some cinematic owns the camera and GameFeel must keep its hands off.</summary>
    public static bool CameraIsLeased { get { return cameraOwner != null; } }

    /// <summary>
    /// Take exclusive control of the camera transform. Call before un-parenting
    /// or driving Camera.main from a cutscene. Returns false if someone else
    /// already holds the lease.
    /// </summary>
    public static bool AcquireCamera(Object owner)
    {
        if (owner == null) return false;
        if (cameraOwner != null && cameraOwner != owner) return false;
        cameraOwner = owner;
        return true;
    }

    /// <summary>Hand the camera back. Safe to call even if the lease was never taken.</summary>
    public static void ReleaseCamera(Object owner)
    {
        if (cameraOwner != owner) return;
        cameraOwner = null;
        if (instance != null) instance.basePosCaptured = false;   // re-capture wherever it ended up
    }

    // ------------- public API (all self-creating) -------------

    public static void EnsureExists()
    {
        if (instance != null) return;
        instance = new GameObject("GameFeel").AddComponent<GameFeel>();
    }

    public static void ShotPunch()
    {
        EnsureExists();
        instance.fovPunch = Mathf.Min(instance.fovPunch + 2f, 5f);
    }

    public static void MuzzleLight(Vector3 position)
    {
        EnsureExists();
        instance.StartCoroutine(instance.DoMuzzleLight(position));
    }

    /// <summary>45 ms real-time world freeze on headshot kills only.</summary>
    public static void HitStop()
    {
        EnsureExists();
        instance.StartCoroutine(instance.DoHitStop());
    }

    public static void OnPlayerHit()
    {
        EnsureExists();
        instance.vignettePulse = 0.6f;
        instance.shakeAmount = 0.09f;
        if (instance.src != null && instance.whump != null)
        {
            instance.src.pitch = Random.Range(0.9f, 1.05f);
            instance.src.PlayOneShot(instance.whump, 0.5f);
        }
    }

    /// <summary>FIX 3: let a settings menu change FOV without it being stomped every frame.</summary>
    public static void SetBaseFov(float fov)
    {
        EnsureExists();
        instance.baseFov = fov;
    }

    void Awake()
    {
        if (instance != null && instance != this) { Destroy(gameObject); return; }
        instance = this;
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    // ------------- internals -------------

    IEnumerator DoMuzzleLight(Vector3 position)
    {
        if (muzzleLight == null)
        {
            muzzleLight = new GameObject("MuzzleLight").AddComponent<Light>();
            muzzleLight.type = LightType.Point;
            muzzleLight.color = new Color(1f, 0.7f, 0.3f);
            muzzleLight.range = 8f;
            muzzleLight.intensity = 2.8f;
            muzzleLight.shadows = LightShadows.None;
        }
        muzzleLight.transform.position = position;
        muzzleLight.enabled = true;
        yield return null;
        yield return null;
        if (muzzleLight != null) muzzleLight.enabled = false;
    }

    IEnumerator DoHitStop()
    {
        if (PauseGate.IsPaused || Time.timeScale < 0.9f) yield break;
        Time.timeScale = 0.05f;
        yield return new WaitForSecondsRealtime(0.045f);
        // Never restore timeScale over the top of a pause that started mid-freeze.
        if (!PauseGate.IsPaused) Time.timeScale = 1f;
    }

    void Start()
    {
        BuildSounds();
        BuildVignette();
    }

    void LateUpdate()
    {
        if (cam == null)
        {
            cam = Camera.main;
            basePosCaptured = false;
            if (cam != null && baseFov < 0) baseFov = cam.fieldOfView;
        }
        if (player == null)
        {
            player = FindFirstObjectByType<FPSController>();
            if (player != null)
            {
                controller = player.GetComponent<CharacterController>();
                health = player.GetComponent<PlayerHealth>();
            }
        }
        if (cam == null) return;

        // FIX 1: a cinematic owns the camera — do not touch the transform at all.
        // The vignette and heartbeat are still fine to run, so only the transform
        // and FOV writes are gated.
        bool mayDriveCamera = !CameraIsLeased;

        // FIX 2: if the camera was re-parented or replaced, the cached local
        // baseline is meaningless. Re-capture rather than dragging it to a stale
        // offset.
        if (cam.transform.parent != camParent)
        {
            camParent = cam.transform.parent;
            basePosCaptured = false;
        }
        if (!basePosCaptured)
        {
            camBasePos = cam.transform.localPosition;
            basePosCaptured = true;
        }

        // Sprint FOV + shot punch
        float targetSprintFov = (player != null && player.isSprinting) ? 6f : 0f;
        sprintFov = Mathf.Lerp(sprintFov, targetSprintFov, Time.deltaTime * 6f);
        fovPunch = Mathf.Lerp(fovPunch, 0f, Time.deltaTime * 14f);
        if (mayDriveCamera && baseFov > 0) cam.fieldOfView = baseFov + sprintFov + fovPunch;

        // Landing dip
        bool grounded = controller == null || controller.isGrounded;
        if (grounded && !wasGrounded)
        {
            dipAmount = 0.06f;
            if (src != null && thud != null) src.PlayOneShot(thud, 0.35f);
        }
        wasGrounded = grounded;
        dipAmount = Mathf.Lerp(dipAmount, 0f, Time.deltaTime * 8f);

        // Damage shake (perlin, not random — smooth wobble, no teleporting)
        shakeAmount = Mathf.Lerp(shakeAmount, 0f, Time.deltaTime * 6f);
        if (mayDriveCamera)
        {
            Vector3 shake = new Vector3(
                Mathf.PerlinNoise(Time.time * 30f, 0f) - 0.5f,
                Mathf.PerlinNoise(0f, Time.time * 30f) - 0.5f, 0f) * 2f * shakeAmount;
            cam.transform.localPosition = camBasePos + Vector3.down * dipAmount + shake;
        }

        // Red vignette: pulses on hits, glows steadily when nearly dead
        vignettePulse = Mathf.Lerp(vignettePulse, 0f, Time.deltaTime * 3.5f);
        float lowHealthGlow = 0f;
        float healthFrac = 1f;
        if (health != null && health.maxHealth > 0)
        {
            healthFrac = health.currentHealth / health.maxHealth;
            if (healthFrac < 0.35f) lowHealthGlow = (0.35f - healthFrac) * 1.3f;
        }
        if (vignette != null)
            vignette.color = new Color(0.45f, 0f, 0f, Mathf.Clamp01(vignettePulse + lowHealthGlow * 0.4f) * 0.5f);

        // Heartbeat below 35% health, faster the closer to death
        if (health != null && health.currentHealth > 0 && healthFrac < 0.35f && Time.time >= nextHeartbeatTime)
        {
            nextHeartbeatTime = Time.time + Mathf.Lerp(0.55f, 1.15f, healthFrac / 0.35f);
            if (src != null && heartbeat != null) src.PlayOneShot(heartbeat, 0.5f);
            vignettePulse = Mathf.Max(vignettePulse, 0.14f);
        }
    }

    void BuildVignette()
    {
        GameObject overlay = new GameObject("DamageVignette");
        overlay.transform.SetParent(transform);
        Canvas canvas = overlay.AddComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        canvas.sortingOrder = 500;
        GameObject imgGO = new GameObject("Red");
        imgGO.transform.SetParent(overlay.transform, false);
        vignette = imgGO.AddComponent<Image>();
        vignette.color = new Color(0.45f, 0f, 0f, 0f);
        vignette.raycastTarget = false;
        vignette.rectTransform.anchorMin = Vector2.zero;
        vignette.rectTransform.anchorMax = Vector2.one;
        vignette.rectTransform.offsetMin = Vector2.zero;
        vignette.rectTransform.offsetMax = Vector2.zero;
    }

    void BuildSounds()
    {
        src = gameObject.AddComponent<AudioSource>();
        src.spatialBlend = 0f;
        src.playOnAwake = false;
        int rate = 22050;

        // WHUMP (getting hit): deep 70 Hz thud + noise slap, 0.35 s
        whump = MakeThump(rate, 0.35f, 70f, 14f, 0.3f, 111);
        // THUD (landing): softer, shorter, 58 Hz, 0.22 s
        thud = MakeThump(rate, 0.22f, 58f, 22f, 0.12f, 222);
        // HEARTBEAT: two 58 Hz thumps 170 ms apart, second at 80%, decay 20
        int hbLength = (int)(rate * 0.5f);
        float[] hb = new float[hbLength];
        for (int i = 0; i < hbLength; i++)
        {
            float t = (float)i / rate;
            float a = Mathf.Sin(2f * Mathf.PI * 58f * t) * Mathf.Exp(-t * 20f);
            float t2 = t - 0.17f;
            float b = t2 > 0 ? Mathf.Sin(2f * Mathf.PI * 58f * t2) * Mathf.Exp(-t2 * 20f) * 0.8f : 0f;
            hb[i] = (a + b) * 0.8f;
        }
        heartbeat = AudioClip.Create("Heartbeat", hbLength, 1, rate, false);
        heartbeat.SetData(hb, 0);
    }

    AudioClip MakeThump(int rate, float duration, float freq, float decay, float noiseAmount, int seed)
    {
        int length = (int)(rate * duration);
        float[] data = new float[length];
        System.Random rng = new System.Random(seed);
        for (int i = 0; i < length; i++)
        {
            float t = (float)i / rate;
            float envelope = Mathf.Exp(-t * decay);
            data[i] = (Mathf.Sin(2f * Mathf.PI * freq * t) * 0.8f
                     + (float)(rng.NextDouble() * 2.0 - 1.0) * noiseAmount * Mathf.Exp(-t * 40f)) * envelope;
        }
        AudioClip clip = AudioClip.Create("Thump" + seed, length, 1, rate, false);
        clip.SetData(data, 0);
        return clip;
    }
}
