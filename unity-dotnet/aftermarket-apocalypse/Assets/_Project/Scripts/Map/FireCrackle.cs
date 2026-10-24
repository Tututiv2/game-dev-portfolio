using UnityEngine;

/// <summary>
/// Burning barrels. Plays the shared AmbientAudio crackle clip at a random pitch
/// AND a random start offset, so no two barrels ever sync (spec §9).
///
/// AmbientAudio builds its clips in Awake specifically so this can consume one
/// in Start — do not move either.
/// </summary>
[RequireComponent(typeof(AudioSource))]
public class FireCrackle : MonoBehaviour
{
    public float volume = 0.4f;
    public float minDistance = 2f;
    public float maxDistance = 18f;

    void Start()
    {
        AudioSource src = GetComponent<AudioSource>();
        if (AmbientAudio.crackleClip == null)
        {
            Debug.LogWarning("FireCrackle on '" + name + "': no AmbientAudio in the scene, so there is no crackle clip.");
            enabled = false;
            return;
        }

        src.clip = AmbientAudio.crackleClip;
        src.loop = true;
        src.volume = volume;
        src.spatialBlend = 1f;
        src.rolloffMode = AudioRolloffMode.Linear;
        src.minDistance = minDistance;
        src.maxDistance = maxDistance;
        src.pitch = Random.Range(0.85f, 1.15f);
        src.time = Random.Range(0f, src.clip.length);   // the de-sync
        src.Play();
    }
}
