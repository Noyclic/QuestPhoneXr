using UnityEngine;

/// <summary>
/// First Baldi Classic VR input shim. It exposes the remote movement/turn/run state without
/// pretending camera-relative hand landmarks are already calibrated world-space controller poses.
/// Add it beside QuestPhoneXRInputReceiver and wire PlayerScript's legacy input reads to this shim.
/// </summary>
public sealed class BaldiQuestPhoneXRAdapter : MonoBehaviour
{
    [Header("Button mapping (Android key codes from the connected gamepad)")]
    [SerializeField] private int runKeyCode = -1;
    [SerializeField] private int useItemKeyCode = -1;
    [SerializeField] private float triggerRunThreshold = 0.85f;

    public static float Forward { get; private set; }
    public static float Strafe { get; private set; }
    public static float Turn { get; private set; }
    public static bool Run { get; private set; }
    public static bool UseItemPressed { get; private set; }
    public static bool Connected { get; private set; }

    private bool previousUseItem;

    private void Update()
    {
        if (!QuestPhoneXRInputReceiver.TryGetFreshPacket(out QuestPhoneXRPacket packet))
        {
            previousUseItem = false;
            ResetInput();
            return;
        }

        Connected = true;
        Forward = -packet.left.stickY;
        Strafe = packet.left.stickX;
        Turn = packet.right.stickX;
        Run = IsPressed(packet.left, runKeyCode) || packet.left.trigger >= triggerRunThreshold;
        bool useItem = IsPressed(packet.right, useItemKeyCode) || packet.right.trigger >= 0.75f;
        UseItemPressed = useItem && !previousUseItem;
        previousUseItem = useItem;
    }

    private static bool IsPressed(QuestPhoneXRControllerState state, int keyCode)
    {
        if (state == null || state.keys == null || keyCode < 0) return false;
        for (int i = 0; i < state.keys.Length; i++)
            if (state.keys[i] == keyCode) return true;
        return false;
    }

    private void OnDisable()
    {
        previousUseItem = false;
        ResetInput();
    }

    private static void ResetInput()
    {
        Forward = 0f;
        Strafe = 0f;
        Turn = 0f;
        Run = false;
        UseItemPressed = false;
        Connected = false;
    }
}
