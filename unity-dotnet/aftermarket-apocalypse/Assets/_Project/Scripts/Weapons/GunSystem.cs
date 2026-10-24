using System.Collections;
using UnityEngine;
using TMPro;

/// <summary>
/// One instance per weapon object (spec §4.1). Perfectly accurate hitscan — no
/// spread, bloom or cone of fire, deliberately.
///
/// Fixes carried in from spec §13:
///
///  #25 (severe-ish, yellow) — the old whatIsEnemy mask was Default|Enemy, which
///      did two bad things at once: DoorDebris (layer 9) was NOT in it, so shots
///      passed clean through a 750-Essence barricade; and Default WAS in it, so
///      the world-impact else-branch could never run and BloodFX.WallImpact was
///      unreachable in every shipped scene. There is now ONE ray against a mask
///      that includes the world and the debris layer, and the branch is decided
///      by what was actually hit.
///
///  #26 — reserve ammo is clamped to maxReserve on Awake, so RefillAmmo() can
///      never destroy rounds. In the shipped scene one gun had reserveAmmo 300
///      against maxReserve 180, so paying 1000 Essence at the crate BURNED 120
///      rounds.
///
///  trap #4 — the magazine is initialised in Awake, not Start: OnEnable runs
///      first on a weapon swap and would otherwise see an empty mag.
///
///  trap #5 — only the active weapon redraws the ammo HUD.
///
///  trap #3 — wasAlive is sampled BEFORE damage is applied; corpses stay
///      collider-hittable for seconds and would otherwise be farmable.
/// </summary>
public class GunSystem : MonoBehaviour
{
    [Header("Identity")]
    [Tooltip("Keys this weapon's camo progression in PlayerPrefs. Must be stable.")]
    public string weaponName = "Glock";

    [Header("Damage")]
    public float damage = 10f;
    public float headshotMultiplier = 5f;
    public float range = 100f;
    public float impactForce = 2f;

    [Header("Fire")]
    [Tooltip("SECONDS BETWEEN SHOTS. 0.1 = 600 RPM.")]
    public float fireRate = 0.1f;
    public bool allowButtonHold = true;
    public float sprintToFireDelay = 0.3f;

    [Header("Recoil")]
    public float recoilUp = 2f;
    public float recoilSide = 1f;

    [Header("Ammo")]
    public int magazineSize = 30;
    public int currentAmmo;
    public int reserveAmmo = 180;
    public int maxReserve = 180;
    public float reloadTime = 1.5f;

    [Header("Layers")]
    [Tooltip("Enemy layer(s). Used to decide whether a hit is a body shot.")]
    public LayerMask whatIsEnemy;
    [Tooltip("Solid world the bullet must STOP on: Default plus DoorDebris. " +
             "If DoorDebris is missing here, bullets pass through unopened doors.")]
    public LayerMask whatIsWorld;

    [Header("References")]
    public Camera fpsCam;
    public Transform muzzlePoint;
    public Transform casingEjectPoint;
    public TextMeshProUGUI ammoText;
    public AudioClip shootSound;
    public AudioClip reloadSound;

    [Header("Tracer")]
    public GameObject tracerPrefab;
    public float tracerWidth = 0.05f;

    /// <summary>0 -> 1 -> 0 across the reload, published for WeaponAiming's flourish (spec §4.2).</summary>
    public float reloadTilt { get; private set; }

    public bool IsReloading { get; private set; }
    public bool IsPackAPunched { get; private set; }

    private float nextFireTime;
    private float lastSprintTime;
    private AudioSource audioSource;
    private FPSController player;
    private CamoSystem camo;

    private static Material casingMaterial;

    void Awake()
    {
        // trap #4: Awake, not Start. OnEnable fires first on a weapon swap.
        currentAmmo = magazineSize;

        // #26: enforce the invariant the old build violated in the scene file.
        if (reserveAmmo > maxReserve)
        {
            Debug.LogWarning("GunSystem on '" + name + "': reserveAmmo (" + reserveAmmo +
                             ") exceeded maxReserve (" + maxReserve + "). Clamping — otherwise the " +
                             "first Ammo Crate purchase would DESTROY " + (reserveAmmo - maxReserve) + " rounds.");
            reserveAmmo = maxReserve;
        }

        audioSource = GetComponent<AudioSource>();
        if (audioSource == null) audioSource = gameObject.AddComponent<AudioSource>();
        audioSource.playOnAwake = false;

        if (fpsCam == null) fpsCam = Camera.main;
        camo = GetComponent<CamoSystem>();
    }

    void Start()
    {
        player = FindFirstObjectByType<FPSController>();
        RefreshAmmoUI();
    }

    void OnEnable()
    {
        RefreshAmmoUI();
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;
        if (IsReloading) return;

        if (player != null && player.isSprinting) lastSprintTime = Time.time;
        bool sprintLockout = Time.time < lastSprintTime + sprintToFireDelay;

        bool firing = allowButtonHold ? GameInput.Firing : GameInput.FirePressedThisFrame;
        if (firing && !sprintLockout && Time.time >= nextFireTime && currentAmmo > 0)
        {
            // Double Tap shortens the interval by 25% (+33% RPM), spec §3.3.
            nextFireTime = Time.time + fireRate * PlayerPerks.FireIntervalMultiplier();
            Shoot();
        }

        if (GameInput.ReloadPressedThisFrame && currentAmmo < magazineSize && reserveAmmo > 0)
            StartCoroutine(Reload());

        if (currentAmmo <= 0 && reserveAmmo > 0 && GameInput.FirePressedThisFrame)
            StartCoroutine(Reload());
    }

    void Shoot()
    {
        currentAmmo--;
        RefreshAmmoUI();

        if (audioSource != null && shootSound != null)
        {
            audioSource.pitch = Random.Range(0.96f, 1.04f);
            audioSource.PlayOneShot(shootSound);
        }

        GameFeel.ShotPunch();
        if (muzzlePoint != null) GameFeel.MuzzleLight(muzzlePoint.position);
        if (player != null) player.AddRecoil(recoilUp, recoilSide);
        EjectCasing();

        Vector3 origin = fpsCam.transform.position + fpsCam.transform.forward * 0.1f;
        Vector3 direction = fpsCam.transform.forward;

        // ONE ray against enemies AND solid world. Whichever is nearer wins, so
        // an unopened door stops the bullet (#25) and the world-impact branch is
        // reachable again.
        int mask = whatIsEnemy | whatIsWorld;
        RaycastHit hit;
        if (!Physics.Raycast(origin, direction, out hit, range, mask, QueryTriggerInteraction.Ignore))
        {
            DrawTracer(origin, origin + direction * range);
            return;
        }

        DrawTracer(muzzlePoint != null ? muzzlePoint.position : origin, hit.point);

        Target target = hit.collider.GetComponentInParent<Target>();
        if (target != null)
        {
            ApplyHit(target, hit);
        }
        else
        {
            // World geometry — dust, chips, and the bullet stops here.
            BloodFX.WallImpact(hit.point, hit.normal);
            if (hit.rigidbody != null && !hit.rigidbody.isKinematic)
                hit.rigidbody.AddForce(-hit.normal * impactForce, ForceMode.Impulse);
        }
    }

    void ApplyHit(Target target, RaycastHit hit)
    {
        // TAG WARNING (spec §1.2): without the "Head" tag on the head collider,
        // headshots never register — no 130-Essence kills, no camo progression,
        // no ding.
        bool headshot = hit.collider.CompareTag("Head");

        // trap #3: sample BEFORE damage. Corpses stay hittable for seconds.
        bool wasAlive = target.health > 0f;

        float final = DamageMath.FinalDamage(
            damage, headshot, headshotMultiplier,
            PlayerPerks.HasDoubleTap(), PlayerPerks.DoubleTapDamage(),
            PowerUpManager.InstaKillActive);

        bool killed = wasAlive && final >= target.health;
        int points = DamageMath.PointsForHit(wasAlive, killed, headshot);

        target.TakeDamage(final, points, headshot, hit.point);

        // Spec §5.3: GunSystem owns scoring, Target awards nothing. Both paying
        // was the original double-scoring bug — a "+90" popup with +180 actually
        // credited.
        ScoreManager.Award(points);

        if (!wasAlive) return;      // no feedback, no payout, no camo credit for corpses

        if (headshot)
        {
            HitSounds.Headshot();
            if (killed)
            {
                BloodFX.HeadPop(hit.point);
                GameFeel.HitStop();          // 45 ms, headshot kills only
            }
            else
            {
                BloodFX.Spawn(hit.point, hit.normal, true);
            }
        }
        else
        {
            HitSounds.Hit();
            BloodFX.Spawn(hit.point, hit.normal, false);
        }

        if (killed)
        {
            BloodFX.Gibs(hit.point);
            MatchStats.RecordKill();
            if (camo != null) camo.RegisterKill(headshot);
        }
    }

    void DrawTracer(Vector3 from, Vector3 to)
    {
        if (tracerPrefab == null) return;
        GameObject tracer = Instantiate(tracerPrefab, from, Quaternion.identity);
        LineRenderer lr = tracer.GetComponent<LineRenderer>();
        if (lr != null)
        {
            lr.startWidth = tracerWidth;
            lr.endWidth = tracerWidth;
            lr.SetPosition(0, from);
            lr.SetPosition(1, to);
        }
        Destroy(tracer, 0.05f);
    }

    void EjectCasing()
    {
        if (casingEjectPoint == null) return;

        if (casingMaterial == null)
        {
            casingMaterial = new Material(Shader.Find("Universal Render Pipeline/Lit"));
            casingMaterial.color = new Color(0.78f, 0.6f, 0.22f);
            casingMaterial.SetFloat("_Smoothness", 0.7f);
        }

        GameObject casing = GameObject.CreatePrimitive(PrimitiveType.Cube);
        casing.name = "Casing";
        casing.transform.position = casingEjectPoint.position;
        casing.transform.localScale = new Vector3(0.018f, 0.018f, 0.045f);
        casing.transform.rotation = Random.rotation;
        casing.GetComponent<Renderer>().sharedMaterial = casingMaterial;
        Destroy(casing.GetComponent<Collider>());

        Rigidbody rb = casing.AddComponent<Rigidbody>();
        rb.linearVelocity = casingEjectPoint.right * Random.Range(1.2f, 2.2f)
                          + casingEjectPoint.up * Random.Range(0.8f, 1.6f);
        rb.angularVelocity = Random.insideUnitSphere * 20f;
        Destroy(casing, 1f);
    }

    IEnumerator Reload()
    {
        if (IsReloading) yield break;
        IsReloading = true;

        if (audioSource != null && reloadSound != null) audioSource.PlayOneShot(reloadSound);

        // Speed Cola halves the duration (spec §3.3).
        float duration = reloadTime * PlayerPerks.ReloadMultiplier();

        float elapsed = 0f;
        while (elapsed < duration)
        {
            elapsed += Time.deltaTime;
            reloadTilt = Mathf.Sin(Mathf.PI * Mathf.Clamp01(elapsed / duration));
            yield return null;
        }
        reloadTilt = 0f;

        int needed = magazineSize - currentAmmo;
        int taken = Mathf.Min(needed, reserveAmmo);
        currentAmmo += taken;
        reserveAmmo -= taken;

        IsReloading = false;
        RefreshAmmoUI();
    }

    /// <summary>Top the reserve back up. Never reduces it — see #26 above.</summary>
    public void RefillAmmo()
    {
        reserveAmmo = Mathf.Max(reserveAmmo, maxReserve);
        RefreshAmmoUI();
    }

    /// <summary>Spec §6/§4.1. Idempotent: a second Pack-a-Punch does nothing.</summary>
    public bool PackAPunch()
    {
        if (IsPackAPunched) return false;
        IsPackAPunched = true;
        DamageMath.ApplyPackAPunch(ref damage, ref magazineSize, ref maxReserve);
        RefillAmmo();
        currentAmmo = magazineSize;
        RefreshAmmoUI();
        return true;
    }

    void RefreshAmmoUI()
    {
        // trap #5: a holstered weapon must not draw over the active one's HUD.
        if (ammoText == null) return;
        if (!gameObject.activeInHierarchy) return;
        ammoText.text = currentAmmo + " / " + reserveAmmo;
    }
}
