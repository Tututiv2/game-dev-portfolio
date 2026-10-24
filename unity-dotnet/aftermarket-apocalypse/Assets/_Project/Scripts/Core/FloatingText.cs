using UnityEngine;
using TMPro;

/// <summary>
/// A world-space popup that rises, fades and billboards to the camera.
/// Spec §13.8 lists FloatingText.offset as a dead inspector field — it is not
/// carried over.
/// </summary>
public class FloatingText : MonoBehaviour
{
    public float riseSpeed = 1.2f;
    public float lifetime = 0.9f;

    private TextMeshPro tmp;
    private float age;
    private Color baseColor = Color.white;

    public void Init(string text, Color color)
    {
        tmp = GetComponent<TextMeshPro>();
        if (tmp == null) tmp = GetComponentInChildren<TextMeshPro>();
        baseColor = color;
        if (tmp != null)
        {
            tmp.text = text;
            tmp.color = color;
        }
        age = 0f;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        age += Time.deltaTime;
        transform.position += Vector3.up * riseSpeed * Time.deltaTime;

        Camera cam = Camera.main;
        if (cam != null)
        {
            // LAW 2 (spec §14): TMP faces -Z, so a billboard must look AWAY from
            // the camera, not at it.
            transform.rotation = Quaternion.LookRotation(transform.position - cam.transform.position);
        }

        if (tmp != null)
        {
            float alpha = Mathf.Clamp01(1f - age / lifetime);
            tmp.color = new Color(baseColor.r, baseColor.g, baseColor.b, alpha);
        }

        if (age >= lifetime) Destroy(gameObject);
    }
}
