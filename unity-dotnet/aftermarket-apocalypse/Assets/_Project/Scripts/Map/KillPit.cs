using UnityEngine;

/// <summary>
/// Fall out of the world and you take 50 and get put back (spec §9). Zombies
/// that fall in die for ZERO Essence — otherwise the pit is a farm.
/// </summary>
[RequireComponent(typeof(Collider))]
public class KillPit : MonoBehaviour
{
    public int fallDamage = 50;
    public Transform respawnPoint;

    void Reset()
    {
        GetComponent<Collider>().isTrigger = true;
    }

    void OnTriggerEnter(Collider other)
    {
        Target zombie = other.GetComponentInParent<Target>();
        if (zombie != null)
        {
            // 0 points — no popup, no payout.
            zombie.TakeDamage(zombie.health + 1f, 0, false, zombie.transform.position);
            return;
        }

        FPSController player = other.GetComponentInParent<FPSController>();
        if (player == null) return;

        PlayerHealth health = player.GetComponent<PlayerHealth>();
        if (health != null) health.TakeDamage(fallDamage);

        if (respawnPoint == null) return;

        // The CharacterController must be off for the frame or it overwrites the
        // teleport with its own collision resolution.
        CharacterController cc = player.GetComponent<CharacterController>();
        if (cc != null) cc.enabled = false;
        player.transform.position = respawnPoint.position;
        if (cc != null) cc.enabled = true;
    }
}
