using UnityEngine;

/// <summary>
/// Rises the zombie model out of the ground at spawn (spec §5.5). Sinks to
/// spawnHeight - 1.0 immediately, then eases up at 1.5 m/s — a snap caused
/// visible spawn stutter.
///
/// Spec §5.5's known mismatch is fixed: the rise duration and EnemyAI's stand-up
/// freeze both read RoundRules.ZombieStandUpTime, so the AI can no longer unfreeze
/// half a second before the model finishes rising.
///
/// LateUpdate early-outs when the zombie is dead — clamping a corpse's height
/// every frame made dead zombies twitch forever.
/// </summary>
public class ZombieStabilizer : MonoBehaviour
{
    public float sinkDepth = 1.0f;
    public float riseSpeed = 1.5f;

    private EnemyAI ai;
    private float targetY;
    private float currentOffset;
    private bool done;

    void Start()
    {
        ai = GetComponent<EnemyAI>();
        targetY = transform.position.y;
        currentOffset = -sinkDepth;
        Apply();
    }

    void LateUpdate()
    {
        if (done) return;
        // Never fight the ragdoll.
        if (ai != null && ai.IsDead) { done = true; return; }
        if (PauseGate.IsPaused) return;

        currentOffset = Mathf.Min(0f, currentOffset + riseSpeed * Time.deltaTime);
        Apply();

        if (currentOffset >= 0f) done = true;
    }

    void Apply()
    {
        Vector3 p = transform.position;
        p.y = targetY + currentOffset;
        transform.position = p;
    }
}
