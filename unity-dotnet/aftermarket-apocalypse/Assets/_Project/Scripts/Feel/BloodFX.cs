using UnityEngine;

/// <summary>
/// Blood, gibs, wall impacts and floor splats. Pure static — never attached to
/// anything (spec §2.1, §8.2). One shared world-space particle system built on
/// first use; GunSystem just calls BloodFX.Spawn(hitPoint, hitNormal).
///
/// Spec §19 says reuse verbatim. Carried over with the statics reset on play so
/// a scene reload cannot leave the pool pointing at destroyed objects.
///
/// PARTICLE LAW (spec §14 law 11, trap #8): every particle material gets a
/// texture AND full transparent surface setup. Untextured particles render as
/// literal white squares — that shipped once already.
/// </summary>
public class BloodFX : MonoBehaviour
{
    private static ParticleSystem ps;

    // Pooled splat decals — 60 max, oldest recycled (spec §18.6)
    private static GameObject[] decals;
    private static int decalIndex;
    private static Material splatBlood, gibMat;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    private static void ResetStatics()
    {
        ps = null;
        decals = null;
        decalIndex = 0;
        splatBlood = null;
        gibMat = null;
    }

    /// <summary>Big burst at the head on headshot KILLS.</summary>
    public static void HeadPop(Vector3 position)
    {
        if (ps == null) Build();
        if (ps == null) return;
        var emit = new ParticleSystem.EmitParams();
        for (int i = 0; i < 30; i++)
        {
            emit.position = position;
            emit.velocity = Random.insideUnitSphere.normalized * Random.Range(2f, 5.5f) + Vector3.up * 1.2f;
            emit.startSize = Random.Range(0.06f, 0.16f);
            ps.Emit(emit, 1);
        }
        var mist = new ParticleSystem.EmitParams();
        for (int i = 0; i < 6; i++)
        {
            mist.position = position;
            mist.velocity = Random.insideUnitSphere * 0.8f;
            mist.startSize = Random.Range(0.35f, 0.6f);
            mist.startColor = new Color(0.4f, 0.02f, 0.02f, 0.4f);
            mist.startLifetime = 0.35f;
            ps.Emit(mist, 1);
        }
        SpawnDecal(position, true);
    }

    /// <summary>2-3 dark rigidbody chunks flung on kills.</summary>
    public static void Gibs(Vector3 position)
    {
        if (gibMat == null)
        {
            gibMat = new Material(Shader.Find("Universal Render Pipeline/Lit"));
            gibMat.color = new Color(0.32f, 0.04f, 0.04f);
        }
        int count = Random.Range(2, 4);
        for (int i = 0; i < count; i++)
        {
            GameObject gib = GameObject.CreatePrimitive(PrimitiveType.Cube);
            gib.name = "Gib";
            gib.transform.position = position + Random.insideUnitSphere * 0.2f;
            gib.transform.localScale = Vector3.one * Random.Range(0.05f, 0.1f);
            gib.transform.rotation = Random.rotation;
            gib.GetComponent<Renderer>().sharedMaterial = gibMat;
            Object.Destroy(gib.GetComponent<Collider>());
            Rigidbody rb = gib.AddComponent<Rigidbody>();
            rb.linearVelocity = Random.insideUnitSphere.normalized * Random.Range(1.5f, 3.5f) + Vector3.up * 2f;
            rb.angularVelocity = Random.insideUnitSphere * 15f;
            Object.Destroy(gib, Random.Range(1.4f, 2.2f));
        }
    }

    /// <summary>Bullets hitting the WORLD: 8 grey chips + 1 dust puff.
    /// Spec §13.1 defect #25: this branch was unreachable in every shipped scene
    /// because GunSystem's damage mask included Default. GunSystem now uses two
    /// separate masks so world impacts actually fire.</summary>
    public static void WallImpact(Vector3 position, Vector3 normal)
    {
        if (ps == null) Build();
        if (ps == null) return;
        var emit = new ParticleSystem.EmitParams();
        for (int i = 0; i < 8; i++)
        {
            emit.position = position;
            Vector3 dir = (normal + Random.insideUnitSphere * 0.7f).normalized;
            emit.velocity = dir * Random.Range(0.8f, 2.2f);
            emit.startSize = Random.Range(0.04f, 0.1f);
            emit.startColor = new Color(0.55f, 0.52f, 0.48f, 0.85f);
            emit.startLifetime = Random.Range(0.2f, 0.45f);
            ps.Emit(emit, 1);
        }
        var puff = new ParticleSystem.EmitParams();
        puff.position = position;
        puff.velocity = normal * 0.5f;
        puff.startSize = 0.3f;
        puff.startColor = new Color(0.5f, 0.48f, 0.45f, 0.35f);
        puff.startLifetime = 0.3f;
        ps.Emit(puff, 1);
    }

    /// <summary>
    /// Drop a splat on the FLOOR below a wound. Every rejection below is a bug
    /// that already shipped (spec §8.2):
    ///   * a Target in the parents  -> the zombie's own hitboxes, giving a
    ///     "floating red rectangle" stuck to a limb
    ///   * a BuyableDoor            -> debris sinks 4 m on purchase, orphaning the splat
    ///   * a non-kinematic rigidbody-> movable surfaces carry the splat away
    ///   * normal.y < 0.65          -> floors only, no wall carpets
    /// Hover is capped AND wrapped: the original i * 0.0004 reached a visible
    /// 4 cm by splat #60 (spec trap #10).
    /// </summary>
    public static void SpawnDecal(Vector3 fromPosition, bool big = false)
    {
        RaycastHit[] hits = Physics.RaycastAll(fromPosition + Vector3.up * 0.3f, Vector3.down, 4f,
            Physics.DefaultRaycastLayers, QueryTriggerInteraction.Ignore);
        System.Array.Sort(hits, (a, b) => a.distance.CompareTo(b.distance));
        bool found = false;
        RaycastHit ground = default(RaycastHit);
        foreach (RaycastHit h in hits)
        {
            if (h.collider.GetComponentInParent<Target>() != null) continue;
            if (h.collider.GetComponentInParent<BuyableDoor>() != null) continue;
            if (h.rigidbody != null && !h.rigidbody.isKinematic) continue;
            ground = h;
            found = true;
            break;
        }
        if (!found) return;
        if (ground.normal.y < 0.65f) return;

        if (decals == null) decals = new GameObject[60];
        int i = decalIndex;
        decalIndex = (decalIndex + 1) % decals.Length;

        if (splatBlood == null)
        {
            splatBlood = new Material(Shader.Find("Universal Render Pipeline/Lit"));
            splatBlood.color = new Color(0.17f, 0.012f, 0.012f);
        }

        if (decals[i] == null)
        {
            // An irregular 3-blob cluster: one main blob + two satellites, each
            // with its own rotation — reads as splatter, not a carpet tile.
            decals[i] = new GameObject("BloodSplat");
            for (int b = 0; b < 3; b++)
            {
                GameObject blob = GameObject.CreatePrimitive(PrimitiveType.Cube);
                blob.transform.SetParent(decals[i].transform, false);
                Object.Destroy(blob.GetComponent<Collider>());
                blob.GetComponent<Renderer>().sharedMaterial = splatBlood;
                float s = (b == 0) ? 1f : Random.Range(0.3f, 0.5f);
                blob.transform.localScale = new Vector3(s, 0.008f + b * 0.003f, s * Random.Range(0.55f, 0.9f));
                blob.transform.localPosition = (b == 0) ? Vector3.zero
                    : new Vector3(Random.Range(-0.55f, 0.55f), 0f, Random.Range(-0.55f, 0.55f));
                blob.transform.localRotation = Quaternion.Euler(0, Random.Range(0f, 180f), 0);
            }
        }
        float size = big ? Random.Range(0.45f, 0.7f) : Random.Range(0.24f, 0.44f);
        decals[i].transform.localScale = new Vector3(size, 1f, size);
        decals[i].transform.position = ground.point + ground.normal * DecalHover(i);
        decals[i].transform.rotation = Quaternion.FromToRotation(Vector3.up, ground.normal)
                                     * Quaternion.Euler(0, Random.Range(0f, 180f), 0);
    }

    /// <summary>Millimetres, capped and wrapped. Exposed so a test can assert it
    /// never exceeds a millimetre-scale bound (spec trap #10).</summary>
    public static float DecalHover(int index)
    {
        return 0.008f + (index % 12) * 0.0006f;   // 8.0 mm .. 14.6 mm, forever
    }

    public static void Spawn(Vector3 position, Vector3 normal, bool big = false)
    {
        if (ps == null) Build();
        if (ps == null) return;

        var emit = new ParticleSystem.EmitParams();
        int count = big ? 26 : 15;
        for (int i = 0; i < count; i++)
        {
            emit.position = position;
            Vector3 dir = (normal + Random.insideUnitSphere * 0.85f).normalized;
            emit.velocity = dir * Random.Range(1.6f, 4.2f) + Vector3.up * 0.6f;
            emit.startSize = Random.Range(0.06f, 0.14f);
            ps.Emit(emit, 1);
        }

        // A brief red MIST puff at the wound — what makes a hit readable from
        // across the street.
        var mist = new ParticleSystem.EmitParams();
        for (int i = 0; i < (big ? 4 : 3); i++)
        {
            mist.position = position;
            mist.velocity = normal * 0.6f + Random.insideUnitSphere * 0.4f;
            mist.startSize = Random.Range(0.28f, 0.5f);
            mist.startColor = new Color(0.4f, 0.02f, 0.02f, 0.4f);
            mist.startLifetime = 0.3f;
            ps.Emit(mist, 1);
        }

        if (Random.value < 0.5f) SpawnDecal(position, big);
    }

    static void Build()
    {
        GameObject go = new GameObject("BloodFX");
        ps = go.AddComponent<ParticleSystem>();

        var main = ps.main;
        main.simulationSpace = ParticleSystemSimulationSpace.World;
        main.startLifetime = new ParticleSystem.MinMaxCurve(0.25f, 0.5f);
        main.startSpeed = 0f;                     // we hand each particle its velocity
        main.startSize = new ParticleSystem.MinMaxCurve(0.03f, 0.08f);
        main.startColor = new Color(0.45f, 0.03f, 0.03f);
        main.gravityModifier = 1.5f;
        main.maxParticles = 800;

        var emission = ps.emission;
        emission.rateOverTime = 0f;               // burst-only, via Emit()

        var shape = ps.shape;
        shape.enabled = false;

        // PARTICLE LAW: texture + full transparent setup, or white squares.
        Material mat = new Material(Shader.Find("Universal Render Pipeline/Particles/Unlit"));
        mat.color = new Color(0.5f, 0.04f, 0.04f, 0.95f);
        mat.SetTexture("_BaseMap", MakeSoftDot());
        mat.SetFloat("_Surface", 1f);
        mat.SetOverrideTag("RenderType", "Transparent");
        mat.SetInt("_SrcBlend", (int)UnityEngine.Rendering.BlendMode.SrcAlpha);
        mat.SetInt("_DstBlend", (int)UnityEngine.Rendering.BlendMode.OneMinusSrcAlpha);
        mat.SetInt("_ZWrite", 0);
        mat.EnableKeyword("_SURFACE_TYPE_TRANSPARENT");
        mat.renderQueue = (int)UnityEngine.Rendering.RenderQueue.Transparent;
        go.GetComponent<ParticleSystemRenderer>().sharedMaterial = mat;
    }

    /// <summary>Generated 64x64 RGBA radial-alpha sprite: alpha = clamp01(1 - dist)^2.</summary>
    static Texture2D MakeSoftDot()
    {
        int size = 64;
        Texture2D tex = new Texture2D(size, size, TextureFormat.RGBA32, false);
        Color32[] pixels = new Color32[size * size];
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                float dx = (x - size / 2f) / (size / 2f);
                float dy = (y - size / 2f) / (size / 2f);
                float alpha = Mathf.Clamp01(1f - Mathf.Sqrt(dx * dx + dy * dy));
                alpha *= alpha;
                pixels[y * size + x] = new Color32(255, 255, 255, (byte)(alpha * 255f));
            }
        }
        tex.SetPixels32(pixels);
        tex.Apply();
        return tex;
    }
}
