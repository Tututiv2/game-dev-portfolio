using UnityEngine;

/// <summary>
/// Health and damage routing for anything shootable (spec §5.3).
///
/// Awards NO Essence — GunSystem owns scoring. Both of them paying was the
/// original double-scoring bug: a "+90" popup with +180 actually credited.
/// The points argument here exists only to drive the popup.
/// </summary>
public class Target : MonoBehaviour
{
    public float health = 50f;

    [Range(0f, 1f)]
    [Tooltip("Chance a non-killing hit staggers the zombie (spec §5.1).")]
    public float staggerChance = 0.25f;

    private EnemyAI ai;
    private bool dead;

    void Awake()
    {
        ai = GetComponent<EnemyAI>();
    }

    /// <param name="pointsToAdd">Used ONLY for the popup. 0 suppresses it —
    /// that is the Nuke case, which calls TakeDamage(health + 1, 0, ...) on
    /// every zombie and would otherwise carpet the map in "+0".</param>
    public void TakeDamage(float amount, int pointsToAdd, bool isHeadshot, Vector3 hitPoint)
    {
        if (dead) return;

        health -= amount;

        if (pointsToAdd != 0) GameFeedback.ShowPoints(hitPoint, pointsToAdd);

        if (health <= 0f)
        {
            dead = true;
            Die();
            return;
        }

        if (ai != null && Random.value < staggerChance) ai.Stagger();
    }

    void Die()
    {
        if (ai != null)
        {
            ai.Die();          // ragdoll path — handles spawner bookkeeping and drops
            return;
        }

        // Fallback for anything shootable that is not a full zombie.
        EnemySpawner spawner = FindFirstObjectByType<EnemySpawner>();
        if (spawner != null) spawner.EnemyKilled();
        Destroy(gameObject);
    }
}
