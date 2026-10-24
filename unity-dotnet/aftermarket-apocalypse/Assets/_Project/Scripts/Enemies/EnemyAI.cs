using System.Collections;
using UnityEngine;
using UnityEngine.AI;

/// <summary>
/// The zombie brain (spec §5.1).
///
/// LAW 15 / trap #1: Start() ADDS the NavMeshAgent if the prefab lacks one. The
/// old prefab never actually had an agent, so every zombie silently ran a legacy
/// LookAt+Translate path — which is why they walked through walls, always faced
/// the player and spun in place, and why months of "agent tuning" changed
/// nothing. There is no legacy branch any more: zombies pathfind, or they wait
/// and warp. They never ghost.
///
/// ===== FIX for spec §13.1 defect #21 (the worst bug in the old build) =====
/// StaggerRoutine wrote an ABSOLUTE Vector3.one * 0.94f and restored to
/// Vector3.one. But that model transform is scaled 1.5 in Enemy.prefab, and
/// Target staggers 25% of non-killing hits — so most of the horde dropped to
/// two-thirds size within the first magazine AND NEVER RECOVERED. The ragdoll
/// bones are children of it, so the Head collider shrank and dropped with them:
/// headshots stopped landing where you aimed.
/// The original scale is now cached on Start and every stagger MULTIPLIES
/// against it. Vector3.one is never written.
///
/// Also fixed here:
///  * Update() early-returns on PauseGate.IsPaused — the old one did not, so
///    zombies kept chasing and swinging through the pause menu (spec §2.3).
///  * A zombie with no route to the player for UnreachableTimeout seconds
///    despawns itself, so one stranded zombie can no longer hard-lock a round
///    forever (spec §13.1 defect #23; the spawner side is fixed too).
/// </summary>
public class EnemyAI : MonoBehaviour
{
    [Header("References")]
    public Transform player;
    public Animator anim;

    [Header("Combat")]
    public float attackRange = 2.5f;
    public int damage = 10;

    [Header("Attack timing")]
    public float damageDelay = 0.5f;
    public float animationTotalTime = 2.0f;

    [Header("Animation variants")]
    public int attackCount = 5;
    public int walkCount = 2;

    [Tooltip("Seconds with no route to the player before this zombie gives up " +
             "and despawns, so it cannot hard-lock the round.")]
    public float unreachableTimeout = 25f;

    [Header("Barriers (Phase 5)")]
    [Tooltip("How close a zombie must be to a boarded window before it starts " +
             "tearing at it.")]
    public float barrierReach = 2.2f;

    [Tooltip("Seconds between boards torn off. Six boards therefore costs a " +
             "zombie about 7 s, which is the round-one breathing room.")]
    public float barrierTearInterval = 1.2f;

    // --- state ---
    private float moveSpeed = 1.5f;
    private bool isRunning, isSprinting, isAttacking, isDead, isSpawning, isCrawler;
    private float nextAttackTime;
    private float nextBarrierHit;

    private Rigidbody[] ragdollRBs;
    private Collider[] ragdollColliders;

    private NavMeshAgent agent;
    private bool useNavMesh;
    private float nextRepathTime;
    private float nextWarpTime;
    private float unreachableSince = -1f;
    private Vector3 lastDestination = new Vector3(99999f, 0f, 99999f);
    private ZombieVoice voice;

    // THE FIX for #21: the model's real scale, captured before anything shrinks it.
    private Transform modelTransform;
    private Vector3 modelBaseScale = Vector3.one;

    public bool IsDead { get { return isDead; } }
    public bool IsCrawler { get { return isCrawler; } }
    public float StandUpTime { get { return RoundRules.ZombieStandUpTime; } }

    void Start()
    {
        GameObject playerObj = GameObject.Find("Player");
        if (playerObj != null) player = playerObj.transform;
        if (player == null)
        {
            FPSController fps = FindFirstObjectByType<FPSController>();
            if (fps != null) player = fps.transform;
        }

        if (anim == null) anim = GetComponentInChildren<Animator>();

        // Spec §15.11.3 / §15.12.4: the zombie theme goes on FIRST, so the type
        // signals below (the sprinter tint in ApplyRoundStats) land on top of it
        // rather than being replaced by it. It writes _HueShift/_SaturationMul/
        // _ValueMul; the tint writes _BaseColor. Different properties, same
        // MaterialPropertyBlock, so they compose.
        //
        // Null theme is the normal case and is a no-op, so this costs one static
        // read per zombie when no theme is set.
        if (ThemeSystem.ZombieTheme != null)
            ThemeSystem.Apply(gameObject, ThemeSystem.ZombieTheme);

        ragdollRBs = GetComponentsInChildren<Rigidbody>();
        ragdollColliders = GetComponentsInChildren<Collider>();
        if (ragdollRBs.Length <= 1)
            Debug.LogError("EnemyAI on '" + name + "': no ragdoll bones found. Run the Ragdoll Wizard on the child model.");

        SetRagdollState(false);

        // LAW 15: add the agent if the prefab does not carry one.
        agent = GetComponent<NavMeshAgent>();
        if (agent == null) agent = gameObject.AddComponent<NavMeshAgent>();
        agent.height = 2f;

        // A dynamic root Rigidbody fights the agent every frame — that is walk stutter.
        Rigidbody rootRb = GetComponent<Rigidbody>();
        if (rootRb != null) rootRb.isKinematic = true;

        voice = GetComponent<ZombieVoice>();
        if (voice == null) voice = gameObject.AddComponent<ZombieVoice>();

        // --- variety, rolled at spawn ---
        transform.localScale = transform.localScale * Random.Range(0.93f, 1.07f);

        isCrawler = Random.value < RoundRules.CrawlerChance;
        if (isCrawler)
        {
            if (voice != null) voice.pitchScale = 0.7f;
            if (anim != null)
            {
                anim.transform.localRotation = anim.transform.localRotation * Quaternion.Euler(60f, 0f, 0f);
                anim.transform.localPosition += new Vector3(0f, -0.55f, 0.2f);
            }
        }

        // #21: capture the model's ACTUAL local scale, after the crawler pose and
        // before any stagger can touch it. Enemy.prefab has this at 1.5.
        modelTransform = (anim != null) ? anim.transform : transform;
        modelBaseScale = modelTransform.localScale;

        // --- agent tuning (spec §5.1) ---
        agent.stoppingDistance = attackRange * 0.9f;      // 2.25
        agent.angularSpeed = 480f;
        agent.acceleration = 30f;
        agent.autoBraking = false;                        // always CHASING, never easing in
        agent.radius = 0.35f;                             // slim, so hordes fit doorways
        agent.obstacleAvoidanceType = ObstacleAvoidanceType.MedQualityObstacleAvoidance;
        // Equal-priority agents endlessly negotiate who dodges whom and wobble.
        // Random priorities mean one always yields cleanly.
        agent.avoidancePriority = Random.Range(20, 80);
        agent.updateRotation = true;

        useNavMesh = agent.isOnNavMesh;
        if (!useNavMesh)
        {
            NavMeshHit meshHit;
            if (NavMesh.SamplePosition(transform.position, out meshHit, 6f, NavMesh.AllAreas))
            {
                agent.Warp(meshHit.position);
                useNavMesh = agent.isOnNavMesh;
            }
            if (!useNavMesh)
                Debug.LogWarning("EnemyAI on '" + name + "': could not reach the NavMesh even after warping — it will wait and retry.");
        }

        if (anim != null) anim.SetInteger("WalkIndex", Random.Range(0, walkCount));

        ApplyRoundStats(ScoreManager.Round);

        StartCoroutine(SpawnRoutine());
    }

    void SetRagdollState(bool ragdollActive)
    {
        foreach (Rigidbody rb in ragdollRBs)
            if (rb != null && rb.gameObject != gameObject) rb.isKinematic = !ragdollActive;

        if (ragdollActive)
        {
            // Joint projection hard-limits how far bones can pull apart — the fix
            // for heads STRETCHING away from bodies when the killing shot's
            // impulse lands on a freshly-dynamic head.
            foreach (CharacterJoint joint in GetComponentsInChildren<CharacterJoint>())
            {
                joint.enableProjection = true;
                joint.projectionDistance = 0.05f;
                joint.projectionAngle = 60f;
            }
        }

        // Child colliders stay ENABLED at all times, alive or dead. Disabling them
        // also disabled the Head hitbox, so headshots stopped registering.
        foreach (Collider col in ragdollColliders)
            if (col != null && col.gameObject != gameObject) col.enabled = true;
    }

    IEnumerator SpawnRoutine()
    {
        isSpawning = true;
        if (useNavMesh && agent != null && agent.isOnNavMesh) agent.isStopped = true;
        yield return new WaitForSeconds(RoundRules.ZombieStandUpTime);
        if (useNavMesh && agent != null && agent.isOnNavMesh) agent.isStopped = false;
        isSpawning = false;
    }

    void ApplyRoundStats(int round)
    {
        moveSpeed = RoundRules.SpeedForRound(round);
        RoundRules.Gait gait = RoundRules.GaitForRound(round);
        isRunning = gait == RoundRules.Gait.Run;
        isSprinting = gait == RoundRules.Gait.Sprint;

        if (isCrawler)
        {
            moveSpeed *= RoundRules.CrawlerSpeedMultiplier;
            isRunning = false;
            isSprinting = false;
        }
        else if (isSprinting)
        {
            // The sprinter tint is a GAMEPLAY SIGNAL, not decoration (spec §15.11.3).
            // Any future zombie Theme must multiply UNDER this, never replace it.
            TintRenderers(RoundRules.SprinterTint);
        }

        if (agent != null) agent.speed = moveSpeed;

        Target myHealth = GetComponent<Target>();
        if (myHealth != null) myHealth.health *= RoundRules.HealthMultiplierForRound(round);
    }

    void Update()
    {
        // Spec §2.3. The old EnemyAI had no pause guard at all: zombies kept
        // chasing and swinging while the pause menu was open.
        if (PauseGate.IsPaused) return;
        if (isDead || isSpawning || player == null) return;

        // Spec §13.3: In Plain Sight is supposed to make zombies IGNORE you. The
        // old build implemented it as walkSpeed += 5 and zombies tracked you the
        // whole time. This is the actual behaviour.
        if (GobblegumManager.StealthActive)
        {
            if (agent != null && agent.isOnNavMesh) agent.isStopped = true;
            StopWalkAnimations();
            unreachableSince = -1f;     // do not count stealth time toward the despawn timeout
            return;
        }

        // PHASE 5 — barriers. Checked before the chase, and a no-op in any map
        // with no WindowBarrier in it, so this cannot change behaviour on a map
        // that has none.
        //
        // A zombie only stops for a barrier when it cannot reach the player any
        // other way. Attacking one it could simply walk around would strand the
        // horde outside a window while an open door stood ten metres away, which
        // reads as broken AI rather than as pressure on the window.
        if (WindowBarrier.All.Count > 0 && !isAttacking)
        {
            var barrier = WindowBarrier.NearestBoarded(transform.position, barrierReach);
            if (barrier != null && !CanReachPlayerDirectly())
            {
                if (agent != null && agent.isOnNavMesh) agent.isStopped = true;
                StopWalkAnimations();

                Vector3 toBarrier = new Vector3(barrier.transform.position.x, transform.position.y,
                                                barrier.transform.position.z) - transform.position;
                if (toBarrier.sqrMagnitude > 0.01f)
                    transform.rotation = Quaternion.RotateTowards(
                        transform.rotation, Quaternion.LookRotation(toBarrier), 240f * Time.deltaTime);

                if (Time.time >= nextBarrierHit)
                {
                    nextBarrierHit = Time.time + barrierTearInterval;
                    barrier.TearBoard();
                }
                return;
            }
        }

        float distance = Vector3.Distance(transform.position, player.position);

        if (distance > attackRange && !isAttacking)
        {
            if (agent != null && agent.isOnNavMesh)
            {
                agent.isStopped = false;

                // Repath only when the player has actually MOVED > 1 m, and at
                // most ~2.9x/s. Per-frame repathing changes the corner point and
                // jitters the walk.
                if (Time.time >= nextRepathTime && (lastDestination - player.position).sqrMagnitude > 1f)
                {
                    nextRepathTime = Time.time + 0.35f;
                    lastDestination = player.position;
                    agent.SetDestination(player.position);
                }

                TrackReachability();
            }
            else
            {
                // Off the mesh — warp back. There is no walk-through-walls mode.
                if (Time.time >= nextWarpTime)
                {
                    nextWarpTime = Time.time + 1f;
                    NavMeshHit meshHit;
                    if (NavMesh.SamplePosition(transform.position, out meshHit, 6f, NavMesh.AllAreas))
                        agent.Warp(meshHit.position);
                }
                MarkUnreachable();
            }

            UpdateMovementAnimation();
        }
        else if (distance <= attackRange)
        {
            unreachableSince = -1f;

            if (agent != null && agent.isOnNavMesh) agent.isStopped = true;

            // Turn PHYSICALLY, over time, at 240 deg/s. Instant LookAt made
            // zombies snap-face you when you sprinted past.
            Vector3 toPlayer = new Vector3(player.position.x, transform.position.y, player.position.z) - transform.position;
            if (toPlayer.sqrMagnitude > 0.01f)
            {
                Quaternion facing = Quaternion.LookRotation(toPlayer);
                transform.rotation = Quaternion.RotateTowards(transform.rotation, facing, 240f * Time.deltaTime);
            }

            StopWalkAnimations();

            if (Time.time >= nextAttackTime && !isAttacking)
            {
                StartCoroutine(AttackLogic());
                nextAttackTime = Time.time + animationTotalTime + 0.1f;   // 2.1 s cadence
            }
        }
    }

    /// <summary>
    /// Spec §13.1 defect #23: zombiesAlive is seeded with the whole round count,
    /// so ONE zombie stranded on an unreachable navmesh island means the round
    /// never ends and the run is dead. A zombie that cannot route to the player
    /// for a sustained period removes itself.
    /// </summary>
    void TrackReachability()
    {
        if (agent.pathPending) return;

        bool reachable = agent.pathStatus == NavMeshPathStatus.PathComplete;
        if (reachable)
        {
            unreachableSince = -1f;
            return;
        }
        MarkUnreachable();
    }

    void MarkUnreachable()
    {
        if (unreachableSince < 0f)
        {
            unreachableSince = Time.time;
            return;
        }
        if (Time.time - unreachableSince >= unreachableTimeout)
        {
            Debug.LogWarning("EnemyAI on '" + name + "': no route to the player for " +
                             unreachableTimeout + "s — despawning so the round can end.");
            Despawn();
        }
    }

    /// <summary>Remove without payout, drop or ragdoll, but DO decrement the
    /// spawner's alive count so the round can complete.</summary>
    public void Despawn()
    {
        if (isDead) return;
        isDead = true;
        EnemySpawner spawner = FindFirstObjectByType<EnemySpawner>();
        if (spawner != null) spawner.EnemyKilled();
        Destroy(gameObject);
    }

    void UpdateMovementAnimation()
    {
        if (anim == null) return;
        anim.SetBool("IsWalking", !isRunning && !isSprinting);
        anim.SetBool("IsRunning", isRunning);
        anim.SetBool("IsSprinting", isSprinting);
    }

    void StopWalkAnimations()
    {
        if (anim == null) return;
        anim.SetBool("IsWalking", false);
        anim.SetBool("IsRunning", false);
        anim.SetBool("IsSprinting", false);
    }

    IEnumerator AttackLogic()
    {
        isAttacking = true;

        if (voice != null) voice.AttackGrowl();

        if (anim != null)
        {
            anim.SetInteger("AttackIndex", Random.Range(0, attackCount));
            anim.SetTrigger("Attack");
        }

        yield return new WaitForSeconds(damageDelay);

        if (isDead) yield break;      // died mid-swing

        if (player != null && Vector3.Distance(transform.position, player.position) <= attackRange + 0.5f)
        {
            PlayerHealth playerHealth = player.GetComponent<PlayerHealth>();
            if (playerHealth != null) playerHealth.TakeDamage(damage);
        }

        float remaining = animationTotalTime - damageDelay;
        if (remaining > 0f) yield return new WaitForSeconds(remaining);

        isAttacking = false;
    }

    /// <summary>Called by Target on 25% of non-killing hits — shots get FELT.</summary>
    public void Stagger()
    {
        if (isDead || isAttacking) return;
        StartCoroutine(StaggerRoutine());
    }

    /// <summary>
    /// THE FIX for #21, isolated so it can be tested. The flinch is a FRACTION of
    /// whatever the model's real scale is (1.5 on Enemy.prefab), never an
    /// absolute Vector3. Restoring uses the same cached base, so N staggers in a
    /// row leave the zombie exactly the size it started.
    /// </summary>
    public const float StaggerScaleFactor = 0.94f;

    public static Vector3 StaggerScale(Vector3 baseScale)
    {
        return baseScale * StaggerScaleFactor;
    }

    IEnumerator StaggerRoutine()
    {
        if (agent != null && agent.isOnNavMesh) agent.isStopped = true;

        // #21: MULTIPLY against the cached base scale. Never write Vector3.one.
        if (modelTransform != null) modelTransform.localScale = StaggerScale(modelBaseScale);

        yield return new WaitForSeconds(0.22f);

        if (modelTransform != null) modelTransform.localScale = modelBaseScale;

        if (!isDead && !isAttacking && agent != null && agent.isOnNavMesh) agent.isStopped = false;
    }

    void TintRenderers(float multiplier)
    {
        MaterialPropertyBlock block = new MaterialPropertyBlock();
        foreach (Renderer r in GetComponentsInChildren<Renderer>())
        {
            if (r is ParticleSystemRenderer) continue;
            Color baseColor = Color.white;
            if (r.sharedMaterial != null && r.sharedMaterial.HasProperty("_BaseColor"))
                baseColor = r.sharedMaterial.GetColor("_BaseColor");
            r.GetPropertyBlock(block);
            block.SetColor("_BaseColor", baseColor * multiplier);
            r.SetPropertyBlock(block);
        }
    }

    public void Die()
    {
        if (isDead) return;
        isDead = true;

        Collider mainCol = GetComponent<Collider>();
        if (mainCol != null) mainCol.enabled = false;
        if (agent != null) agent.enabled = false;
        if (anim != null) anim.enabled = false;

        SetRagdollState(true);
        StartCoroutine(FreezeRagdoll());

        EnemySpawner spawner = FindFirstObjectByType<EnemySpawner>();
        if (spawner != null) spawner.EnemyKilled();

        if (PowerUpManager.instance != null) PowerUpManager.instance.TryDrop(transform.position);

        Destroy(gameObject, 25f);      // failsafe only; FreezeRagdoll destroys it far sooner
    }

    /// <summary>
    /// Settle the ragdoll GENTLY: crank damping so it stops bouncing, wait until
    /// it is actually still, THEN freeze. Freezing on a fixed timer snapped
    /// corpses mid-bounce. Corpse lifetime works out at 7.1 - 11.1 s.
    /// </summary>
    IEnumerator FreezeRagdoll()
    {
        yield return new WaitForSeconds(1.1f);

        foreach (Rigidbody rb in ragdollRBs)
        {
            if (rb != null && rb.gameObject != gameObject)
            {
                rb.linearDamping = 4f;
                rb.angularDamping = 6f;
            }
        }

        float deadline = Time.time + 4f;
        while (Time.time < deadline)
        {
            bool settled = true;
            foreach (Rigidbody rb in ragdollRBs)
            {
                if (rb != null && !rb.isKinematic && rb.gameObject != gameObject
                    && rb.linearVelocity.sqrMagnitude > 0.01f)
                {
                    settled = false;
                    break;
                }
            }
            if (settled) break;
            yield return new WaitForSeconds(0.25f);
        }

        foreach (Rigidbody rb in ragdollRBs)
            if (rb != null && rb.gameObject != gameObject) rb.isKinematic = true;

        yield return new WaitForSeconds(3.5f);

        float sinkTimer = 0f;
        while (sinkTimer < 2.5f)
        {
            sinkTimer += Time.deltaTime;
            transform.position += Vector3.down * 0.55f * Time.deltaTime;
            yield return null;
        }
        Destroy(gameObject);
    }

    /// <summary>
    /// Is there a complete navmesh route to the player right now?
    ///
    /// This is what stops a zombie chewing a window it does not need. Barriers
    /// open a NavMeshLink only when their last board comes off, so while a window
    /// is boarded the only complete paths are the ones through doors and openings
    /// that are genuinely available — which is exactly the question being asked.
    /// </summary>
    bool CanReachPlayerDirectly()
    {
        if (agent == null || !agent.isOnNavMesh) return false;

        var path = new NavMeshPath();
        if (!NavMesh.CalculatePath(transform.position, player.position, NavMesh.AllAreas, path))
            return false;
        return path.status == NavMeshPathStatus.PathComplete;
    }
}
