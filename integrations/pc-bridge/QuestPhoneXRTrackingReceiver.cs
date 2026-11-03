using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using UnityEngine;

/// <summary>
/// Receives QuestPhoneXR head orientation and MediaPipe hand landmarks over local UDP.
/// This exposes tracking data to a Unity integration; it is not a SteamVR driver by itself.
/// </summary>
public sealed class QuestPhoneXRTrackingReceiver : MonoBehaviour
{
    public const int DefaultPort = 39471;
    private const int MaxPacketBytes = 8192;

    private static readonly object StateLock = new object();
    private static QuestPhoneXRTrackingPacket latest;
    private static long lastPacketUtcTicks;

    [SerializeField] private int listenPort = DefaultPort;
    private UdpClient client;
    private Thread receiveThread;
    private volatile bool running;
    private string pendingJson;

    public static QuestPhoneXRTrackingPacket Latest
    {
        get { lock (StateLock) return latest; }
    }

    private void Awake()
    {
        DontDestroyOnLoad(gameObject);
        try
        {
            client = new UdpClient(listenPort);
            client.Client.ReceiveBufferSize = 256 * 1024;
            running = true;
            receiveThread = new Thread(ReceiveLoop)
            {
                IsBackground = true,
                Name = "QuestPhoneXR Tracking UDP"
            };
            receiveThread.Start();
            Debug.Log("QuestPhoneXR: listening for tracking on UDP " + listenPort);
        }
        catch (Exception exception)
        {
            Debug.LogError("QuestPhoneXR tracking receiver could not start: " + exception.Message);
            running = false;
            if (client != null) client.Close();
            client = null;
        }
    }

    private void Update()
    {
        string json = Interlocked.Exchange(ref pendingJson, null);
        if (string.IsNullOrEmpty(json)) return;

        try
        {
            QuestPhoneXRTrackingPacket packet =
                JsonUtility.FromJson<QuestPhoneXRTrackingPacket>(json);
            if (!IsValid(packet)) return;

            lock (StateLock)
            {
                if (latest != null &&
                    string.Equals(packet.sessionId, latest.sessionId, StringComparison.Ordinal) &&
                    packet.sequence <= latest.sequence)
                    return;
                latest = packet;
                lastPacketUtcTicks = DateTime.UtcNow.Ticks;
            }
        }
        catch (Exception exception)
        {
            Debug.LogWarning("QuestPhoneXR tracking packet ignored: " + exception.Message);
        }
    }

    private static bool IsValid(QuestPhoneXRTrackingPacket packet)
    {
        if (packet == null ||
            packet.protocol != "QuestPhoneXR" ||
            packet.type != "tracking" ||
            packet.schemaVersion != 1 ||
            string.IsNullOrEmpty(packet.sessionId) ||
            packet.sequence < 0 ||
            packet.hands == null ||
            packet.hands.Length > 2)
            return false;

        if (packet.head != null && packet.head.tracked)
        {
            if (packet.head.rotationMatrix == null ||
                packet.head.rotationMatrix.Length != 9)
                return false;

            foreach (float value in packet.head.rotationMatrix)
                if (float.IsNaN(value) || float.IsInfinity(value)) return false;
        }

        foreach (QuestPhoneXRTrackedHand hand in packet.hands)
        {
            if (hand == null || hand.imageLandmarks == null ||
                hand.imageLandmarks.Length > 21 ||
                hand.worldLandmarks == null ||
                hand.worldLandmarks.Length > 21 ||
                float.IsNaN(hand.pinchStrength) ||
                float.IsInfinity(hand.pinchStrength))
                return false;
        }
        return true;
    }

    private void ReceiveLoop()
    {
        while (running && client != null)
        {
            try
            {
                IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                byte[] bytes = client.Receive(ref remote);
                if (bytes == null || bytes.Length == 0 || bytes.Length > MaxPacketBytes)
                    continue;

                string json = Encoding.UTF8.GetString(bytes);
                Interlocked.Exchange(ref pendingJson, json);
            }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { if (running) continue; }
            catch (Exception exception)
            {
                if (running) Debug.LogWarning("QuestPhoneXR UDP receive error: " + exception.Message);
            }
        }
    }

    public static bool TryGetFreshPacket(
        out QuestPhoneXRTrackingPacket packet,
        float timeoutSeconds = 0.25f)
    {
        lock (StateLock)
        {
            packet = latest;
            long ageTicks = DateTime.UtcNow.Ticks - lastPacketUtcTicks;
            return packet != null &&
                ageTicks >= 0 &&
                ageTicks <= timeoutSeconds * TimeSpan.TicksPerSecond;
        }
    }

    private void OnDestroy()
    {
        running = false;
        if (client != null) client.Close();
        if (receiveThread != null && receiveThread.IsAlive) receiveThread.Join(300);

        lock (StateLock)
        {
            latest = null;
            lastPacketUtcTicks = 0L;
        }
    }
}

[Serializable]
public sealed class QuestPhoneXRTrackingPacket
{
    public string protocol;
    public string type;
    public int schemaVersion;
    public string sessionId;
    public long sequence;
    public long timestampMs;
    public QuestPhoneXRHeadPose head;
    public QuestPhoneXRTrackedHand[] hands;
}

[Serializable]
public sealed class QuestPhoneXRHeadPose
{
    public bool tracked;
    public float[] rotationMatrix;
}

[Serializable]
public sealed class QuestPhoneXRTrackedHand
{
    public string side;
    public bool tracked;
    public float pinchStrength;
    public bool pinchPressed;
    public QuestPhoneXRVector3[] imageLandmarks;
    public QuestPhoneXRVector3[] worldLandmarks;
}

[Serializable]
public sealed class QuestPhoneXRVector3
{
    public float x;
    public float y;
    public float z;
}
