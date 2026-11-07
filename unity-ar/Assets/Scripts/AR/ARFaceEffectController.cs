using UnityEngine;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.ARSubsystems;

/// <summary>
/// Minimal AR Foundation face-effect prototype.
/// Assign a prefab whose local origin is calibrated to the face pose.
/// This controller intentionally tracks one face; multi-face support can be
/// added once the product requirements and device performance are established.
/// </summary>
[RequireComponent(typeof(ARFaceManager))]
public sealed class ARFaceEffectController : MonoBehaviour
{
    [Header("Tracking")]
    [SerializeField] private ARFaceManager faceManager;

    [Header("Effect")]
    [SerializeField] private GameObject effectPrefab;
    [SerializeField] private Vector3 localPositionOffset;
    [SerializeField] private Vector3 localEulerOffset;

    private ARFace trackedFace;
    private GameObject activeEffect;

    private void Reset()
    {
        faceManager = GetComponent<ARFaceManager>();
    }

    private void Awake()
    {
        if (faceManager == null)
            faceManager = GetComponent<ARFaceManager>();
    }

    private void OnEnable()
    {
        if (faceManager != null)
            faceManager.facesChanged += OnFacesChanged;
    }

    private void OnDisable()
    {
        if (faceManager != null)
            faceManager.facesChanged -= OnFacesChanged;

        ClearEffect();
        trackedFace = null;
    }

    private void OnFacesChanged(ARFacesChangedEventArgs args)
    {
        if (trackedFace == null)
        {
            foreach (ARFace face in args.added)
            {
                if (face != null && face.trackingState == TrackingState.Tracking)
                {
                    AttachTo(face);
                    break;
                }
            }
        }

        if (trackedFace != null)
        {
            foreach (ARFace face in args.updated)
            {
                if (face == trackedFace)
                {
                    if (face.trackingState != TrackingState.Tracking)
                        ClearEffect();
                    return;
                }
            }

            foreach (ARFace face in args.removed)
            {
                if (face == trackedFace)
                {
                    ClearEffect();
                    trackedFace = null;
                    break;
                }
            }
        }

        if (trackedFace == null)
        {
            foreach (ARFace face in args.updated)
            {
                if (face != null && face.trackingState == TrackingState.Tracking)
                {
                    AttachTo(face);
                    break;
                }
            }
        }
    }

    private void AttachTo(ARFace face)
    {
        if (face == null || effectPrefab == null)
            return;

        ClearEffect();
        trackedFace = face;
        activeEffect = Instantiate(effectPrefab, face.transform);
        activeEffect.transform.localPosition = localPositionOffset;
        activeEffect.transform.localRotation = Quaternion.Euler(localEulerOffset);
    }

    private void ClearEffect()
    {
        if (activeEffect != null)
            Destroy(activeEffect);

        activeEffect = null;
    }

    /// <summary>Change the active effect prefab at runtime.</summary>
    public void SetEffect(GameObject prefab, Vector3 positionOffset, Vector3 eulerOffset)
    {
        effectPrefab = prefab;
        localPositionOffset = positionOffset;
        localEulerOffset = eulerOffset;

        if (trackedFace != null)
            AttachTo(trackedFace);
    }
}
