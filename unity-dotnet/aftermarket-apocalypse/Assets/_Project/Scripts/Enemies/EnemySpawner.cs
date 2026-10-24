using System.Collections;
using UnityEngine;
using UnityEngine.AI;
using TMPro;

/// <summary>
/// The round director (spec §5.2).
///
/// Three defects from spec §13 are fixed here:
///
///  #1 / §16 — THE CONCURRENT CAP. The old spawner fired round*5 zombies on a
///     flat metronome regardless of how many were alive: round 25 spawned 125
///     zombies over 250 seconds with no ceiling. Spawning now blocks while
///     RoundRules.MaxConcurrentZombies are already alive.
///
///  #23 (severe) — A ROUND COULD HARD-LOCK FOREVER. After 12 failed attempts the
///     old PickSpawnPosition returned a spawn point WITH NO REACHABILITY CHECK
///     AT ALL. Since zombiesRemaining is seeded with the whole round count, one
///     zombie stranded on an unreachable navmesh island meant the round never
///     ended and the run was dead. §5.2 described the late attempts as a safety
///     net; they were the opposite. The fallback is now validated too, and a
///     failed pick simply retries next tick instead of stranding a zombie.
///     EnemyAI carries the other half: a zombie with no route for 25 s despawns.
///
///  §15.1 — the round-survival drop pays SOUL CRYSTALS. "Ash Shards" is retired
///     entirely: the name, the PlayerPrefs key and the announcement string.
///
/// Pacing numbers come from RoundRules, not from inspector fields, so the scene
/// cannot silently override them the way Map2.unity overrode timeBetweenSpawns
/// from 2 to 5 (spec Part I "READ FIRST").
/// </summary>
public class EnemySpawner : MonoBehaviour
{
    [Header("Zombies")]
    public GameObject enemyPrefab;

    [Tooltip("Extra zombie looks rolled alongside enemyPrefab. ZombieM_02 ships " +
             "three, named from their albedo textures — ICE (skin 1), BONE (skin 2, " +
             "enemyPrefab) and TOXIC (skin 3) — and CoreLoopBuilder fills this with " +
             "whichever of the others are imported. enemyPrefab is skin 2 because the " +
             "scene already references Enemy.prefab, not for any art reason.")]
    public GameObject[] variantPrefabs;

    public Transform[] spawnPoints;

    [Header("Timing")]
    public float initialGameDelay = 5f;
    public float roundEndPulseDuration = 3f;
    public float breakBetweenRounds = 3f;

    [Header("Spawn distance")]
    public float preferMinDistance = 18f;
    public float preferMaxDistance = 70f;

    [Header("UI")]
    public TextMeshProUGUI roundText;
    public Vector2 textCenterPos = Vector2.zero;
    public Vector2 textCornerPos = new Vector2(-400f, 400f);
    public float centerFontSize = 300f;
    public float cornerFontSize = 100f;

    // --- state ---
    public int round { get; private set; }
    /// <summary>How many still have to DIE before the round ends.</summary>
    public int zombiesRemaining { get; private set; }
    /// <summary>How many are physically alive right now. Drives the cap.</summary>
    public int concurrentAlive { get; private set; }

    private int leftToSpawn;
    private bool isRoundInProgress;

    void Start()
    {
        if (roundText != null)
        {
            roundText.color = new Color(0f, 0f, 0f, 0f);
            roundText.rectTransform.anchoredPosition = textCornerPos;
            roundText.fontSize = cornerFontSize;
        }

        if (spawnPoints == null || spawnPoints.Length == 0)
            Debug.LogError("EnemySpawner on '" + name + "': spawnPoints is empty. No zombies will spawn. " +
                           "(Spec §13.18: SampleScene shipped exactly like this.)");

        StartCoroutine(GameLoop());
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        if (isRoundInProgress && zombiesRemaining <= 0 && leftToSpawn <= 0)
        {
            isRoundInProgress = false;
            StartCoroutine(RoundEndSequence());
        }
    }

    IEnumerator GameLoop()
    {
        yield return new WaitForSeconds(initialGameDelay);
        StartNextRound();
    }

    IEnumerator RoundEndSequence()
    {
        // Null-guard: a missing roundText reference used to throw here and STOP
        // ROUNDS FOREVER (spec §5.2).
        if (roundText != null)
        {
            float timer = 0f;
            Color normalRed = Color.red;
            Color darkRed = new Color(0.3f, 0f, 0f, 1f);
            while (timer < roundEndPulseDuration)
            {
                timer += Time.deltaTime;
                roundText.color = Color.Lerp(normalRed, darkRed, Mathf.PingPong(Time.time * 2f, 1f));
                yield return null;
            }
            roundText.color = normalRed;
        }
        else
        {
            yield return new WaitForSeconds(roundEndPulseDuration);
        }

        yield return new WaitForSeconds(breakBetweenRounds);
        StartNextRound();
    }

    void StartNextRound()
    {
        round++;

        if (ScoreManager.instance != null) ScoreManager.instance.roundNumber = round;
        MatchStats.RecordRound(round);
        PlayerWallet.RecordRoundReached(round);

        AwardRoundSurvivalCurrency();

        int count = RoundRules.ZombiesForRound(round);
        zombiesRemaining = count;
        leftToSpawn = count;

        if (roundText != null) StartCoroutine(AnimateRoundStart());
        AmbientAudio.PlayRoundSting();

        StartCoroutine(SpawnWave());
        isRoundInProgress = true;
    }

    /// <summary>
    /// Surviving deep rounds pays SOUL CRYSTALS — the premium currency's earned
    /// path (spec §15.1). Serials only ever come from Sacreds; deep rounds pay
    /// you the key to them.
    /// </summary>
    void AwardRoundSurvivalCurrency()
    {
        if (round <= 1) return;
        int finishedRound = round - 1;
        if (finishedRound < 10) return;

        if (Random.value >= RoundRules.SoulCrystalChance(finishedRound)) return;

        int amount = RoundRules.SoulCrystalAmount(finishedRound, Random.Range(1, 3));
        PlayerWallet.AddSoulCrystals(amount);

        if (PowerUpManager.instance != null)
            PowerUpManager.instance.Announce("+" + amount + " SOUL CRYSTAL" + (amount > 1 ? "S" : "") +
                                             "  —  spend them on Sacreds");
    }

    IEnumerator SpawnWave()
    {
        while (leftToSpawn > 0)
        {
            // THE CAP. Wait rather than piling more onto the map.
            while (concurrentAlive >= RoundRules.MaxConcurrentZombies)
            {
                yield return new WaitForSeconds(0.25f);
                if (!Application.isPlaying) yield break;
            }

            if (TrySpawnEnemy()) leftToSpawn--;
            // If the spawn failed (nothing reachable right now — e.g. every open
            // spawn point is momentarily blocked) we simply try again next tick.
            // The old code force-spawned instead, which is defect #23.

            yield return new WaitForSeconds(RoundRules.TimeBetweenSpawns);
        }
    }

    bool TrySpawnEnemy()
    {
        if (enemyPrefab == null) return false;

        Vector3 spawnPos;
        if (!TryPickSpawnPosition(out spawnPos)) return false;

        // Ground-snap.
        RaycastHit hit;
        if (Physics.Raycast(spawnPos + Vector3.up * 2f, Vector3.down, out hit, 50f))
            spawnPos.y = hit.point.y;

        Instantiate(PickPrefab(), spawnPos, Quaternion.identity);
        concurrentAlive++;
        return true;
    }

    /// <summary>
    /// Which zombie look this spawn gets.
    ///
    /// <para>Rolled uniformly across <see cref="enemyPrefab"/> and every non-null
    /// entry of <see cref="variantPrefabs"/>. Nulls are skipped rather than
    /// counted: an unfilled inspector slot would otherwise be a silent
    /// one-in-three chance of <c>Instantiate(null)</c>, which throws and eats the
    /// spawn — the round would just be short a zombie with only a console line to
    /// say so, and the round cannot end until the count is met.</para>
    ///
    /// <para><see cref="TrySpawnEnemy"/> has already returned false if
    /// <c>enemyPrefab</c> itself is null, so this can always fall back to it.</para>
    /// </summary>
    GameObject PickPrefab()
    {
        if (variantPrefabs == null || variantPrefabs.Length == 0) return enemyPrefab;

        int usable = 1;                                  // enemyPrefab itself
        for (int i = 0; i < variantPrefabs.Length; i++)
            if (variantPrefabs[i] != null) usable++;

        int roll = Random.Range(0, usable);
        if (roll == 0) return enemyPrefab;

        int seen = 0;
        for (int i = 0; i < variantPrefabs.Length; i++)
        {
            if (variantPrefabs[i] == null) continue;
            if (++seen == roll) return variantPrefabs[i];
        }
        return enemyPrefab;
    }

    /// <summary>
    /// Up to 12 attempts. Attempts 0-7 also require the 18-70 m distance band;
    /// 8-11 accept any distance so rounds are not starved.
    ///
    /// EVERY attempt, including the last, must produce a spawn the player can
    /// actually be reached from. Returning false is a valid, safe outcome — the
    /// caller retries. That is the whole of fix #23 on this side.
    /// </summary>
    public bool TryPickSpawnPosition(out Vector3 result)
    {
        result = transform.position;
        if (spawnPoints == null || spawnPoints.Length == 0) return false;

        FPSController player = FindFirstObjectByType<FPSController>();
        if (player == null) return false;

        NavMeshPath path = new NavMeshPath();

        for (int attempt = 0; attempt < 12; attempt++)
        {
            Transform point = spawnPoints[Random.Range(0, spawnPoints.Length)];
            if (point == null) continue;
            Vector3 candidate = point.position;

            float distance = Vector3.Distance(candidate, player.transform.position);
            if (attempt < 8 && (distance < preferMinDistance || distance > preferMaxDistance)) continue;

            NavMeshHit startHit, endHit;
            bool foundStart = NavMesh.SamplePosition(candidate, out startHit, 2.5f, NavMesh.AllAreas);
            bool foundEnd = NavMesh.SamplePosition(player.transform.position, out endHit, 3f, NavMesh.AllAreas);

            // No navmesh baked at all (a greybox scene mid-Phase-3): accept, so
            // the game is still testable, but say so once.
            if (!foundStart || !foundEnd)
            {
                Debug.LogWarning("EnemySpawner: no NavMesh near the spawn point or the player. " +
                                 "Spawning unvalidated — bake a NavMeshSurface (spec §14 law 13: 0.12 m voxels).");
                result = candidate;
                return true;
            }

            // SKY-WALKER GUARD: navmesh sitting well above the point means the
            // mesh is on a prop roof and the zombie would spawn walking on air.
            if (startHit.position.y > candidate.y + 1.2f) continue;

            if (NavMesh.CalculatePath(startHit.position, endHit.position, NavMesh.AllAreas, path)
                && path.status == NavMeshPathStatus.PathComplete)
            {
                result = candidate;
                return true;
            }
        }

        return false;   // NOT a force-spawn. The caller retries next tick.
    }

    /// <summary>Called by EnemyAI on death AND on unreachable-despawn.</summary>
    public void EnemyKilled()
    {
        if (zombiesRemaining > 0) zombiesRemaining--;
        if (concurrentAlive > 0) concurrentAlive--;
    }

    IEnumerator AnimateRoundStart()
    {
        string display = RoundRules.RoundName(round);
        roundText.text = round > 25 ? "Round " + display : display;

        roundText.rectTransform.anchoredPosition = textCenterPos;
        roundText.fontSize = centerFontSize;

        Color target = Color.red;
        Color invisible = new Color(1f, 0f, 0f, 0f);
        roundText.color = invisible;

        float t = 0f;
        while (t < 1f)
        {
            t += Time.deltaTime;
            roundText.color = Color.Lerp(invisible, target, t);
            yield return null;
        }
        roundText.color = target;

        yield return new WaitForSeconds(1.5f);

        float m = 0f;
        while (m < 1f)
        {
            m += Time.deltaTime;
            roundText.rectTransform.anchoredPosition = Vector2.Lerp(textCenterPos, textCornerPos, m);
            roundText.fontSize = Mathf.Lerp(centerFontSize, cornerFontSize, m);
            yield return null;
        }
    }
}
