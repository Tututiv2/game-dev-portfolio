using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Player health, regen and death (spec §3.2).
///
/// Spec §13.11 fix: death is no longer a bare scene reload that loses
/// everything. The run is BANKED first — Embers earned, best round recorded —
/// and a summary is published for the UI (Phase 2 draws it; the data exists now
/// so nothing has to be reconstructed later).
/// </summary>
public class PlayerHealth : MonoBehaviour
{
    [Header("Health")]
    public float maxHealth = 100f;
    public float currentHealth = 100f;

    [Header("Regen")]
    public float regenDelay = 4f;      // seconds since last damage
    public float regenRate = 10f;      // HP per second

    [Tooltip("Set true by the extraction cinematic.")]
    public bool isInvincible = false;

    private float lastDamageTime = -999f;
    private bool isDead = false;

    /// <summary>Populated the moment the player dies, for the summary screen.</summary>
    public struct RunSummary
    {
        public int roundsSurvived;
        public int kills;
        public int embersEarned;
    }
    public static RunSummary LastRun;
    public static bool HasLastRun;

    void Awake()
    {
        currentHealth = maxHealth;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;
        if (isDead) return;

        if (currentHealth < maxHealth && Time.time >= lastDamageTime + regenDelay)
        {
            currentHealth = Mathf.Min(maxHealth, currentHealth + regenRate * Time.deltaTime);
        }
    }

    public void TakeDamage(int amount)
    {
        if (isInvincible || isDead) return;
        if (amount <= 0) return;

        currentHealth -= amount;
        lastDamageTime = Time.time;
        GameFeel.OnPlayerHit();

        if (currentHealth <= 0f) Die();
    }

    /// <summary>Pushed once at purchase (spec §3.3). Heals to the new maximum.</summary>
    public void ApplyJuggernog(float newMax)
    {
        maxHealth = newMax;
        currentHealth = newMax;
    }

    void Die()
    {
        if (isDead) return;

        // Quick Revive is checked FIRST and consumes the perk (spec §3.2).
        if (PlayerPerks.instance != null && PlayerPerks.instance.UseQuickRevive())
        {
            currentHealth = maxHealth * 0.5f;
            lastDamageTime = Time.time;
            if (PowerUpManager.instance != null) PowerUpManager.instance.Announce("QUICK REVIVE");
            return;
        }

        isDead = true;

        // Spec §13.11: bank the run BEFORE tearing the scene down. The old build
        // recorded nothing at all, which is fatal for an economy whose whole
        // point is that playing accrues persistent currency.
        int earned = MatchStats.BankRun();
        LastRun = new RunSummary
        {
            roundsSurvived = MatchStats.HighestRound,
            kills = MatchStats.TotalKills,
            embersEarned = earned
        };
        HasLastRun = true;

        if (PowerUpManager.instance != null)
            PowerUpManager.instance.Announce("YOU DIED  —  +" + earned + " EMBERS BANKED");

        // Phase 1 still reloads the scene; Phase 2 routes this through a real
        // death screen that reads PlayerHealth.LastRun.
        PauseGate.Set(false);
        SceneManager.LoadScene(SceneManager.GetActiveScene().name);
    }
}
