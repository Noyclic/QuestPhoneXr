using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using UnityEngine;

/// <summary>
/// Receives QuestPhoneXR controller snapshots over local Wi-Fi.
/// Add this component to one persistent GameObject in the Quest game.
/// </summary>
public sealed class QuestPhoneXRInputReceiver : MonoBehaviour
{
    public const int DefaultPort = 39470;
    public static QuestPhoneXRPacket Latest { get; private set; }
    public static float LastPacketRealtime { get; private set; }

    [SerializeField] private int listenPort = DefaultPort;
    private UdpClient client;
    private Thread receiveThread;
    private volatile bool running;

    private void Awake()
    {
        DontDestroyOnLoad(gameObject);
        try
        {
            client = new UdpClient(listenPort);
            running = true;
            receiveThread = new Thread(ReceiveLoop) { IsBackground = true, Name = "QuestPhoneXR UDP" };
            receiveThread.Start();
            Debug.Log("QuestPhoneXR: listening on UDP " + listenPort);
        }
        catch (Exception exception)
        {
            Debug.LogError("QuestPhoneXR UDP receiver could not start: " + exception.Message);
        }
    }

    private void ReceiveLoop()
    {
        while (running && client != null)
        {
            try
            {
                IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                byte[] bytes = client.Receive(ref remote);
                string json = Encoding.UTF8.GetString(bytes);
                QuestPhoneXRPacket packet = JsonUtility.FromJson<QuestPhoneXRPacket>(json);
                if (packet != null && packet.schemaVersion == 1)
                {
                    lock (typeof(QuestPhoneXRInputReceiver))
                    {
                        Latest = packet;
                        LastPacketRealtime = Time.realtimeSinceStartup;
                    }
                }
            }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { if (running) continue; }
            catch (Exception exception)
            {
                Debug.LogWarning("QuestPhoneXR packet ignored: " + exception.Message);
            }
        }
    }

    public static bool TryGetFreshPacket(out QuestPhoneXRPacket packet, float timeoutSeconds = 0.25f)
    {
        lock (typeof(QuestPhoneXRInputReceiver))
        {
            packet = Latest;
            return packet != null && Time.realtimeSinceStartup - LastPacketRealtime <= timeoutSeconds;
        }
    }

    private void OnDestroy()
    {
        running = false;
        if (client != null) client.Close();
        if (receiveThread != null && receiveThread.IsAlive) receiveThread.Join(300);
        if (Latest != null)
        {
            lock (typeof(QuestPhoneXRInputReceiver))
            {
                Latest = null;
                LastPacketRealtime = 0f;
            }
        }
    }
}

[Serializable]
public sealed class QuestPhoneXRPacket
{
    public int schemaVersion;
    public long sequence;
    public long timestampMs;
    public QuestPhoneXRControllerState left;
    public QuestPhoneXRControllerState right;
}

[Serializable]
public sealed class QuestPhoneXRControllerState
{
    public bool tracked;
    public float trigger;
    public float grip;
    public bool pinchPressed;
    public float stickX;
    public float stickY;
    public int[] keys;
    public QuestPhoneXRHandPose pose;
}

[Serializable]
public sealed class QuestPhoneXRHandPose
{
    public float imageX;
    public float imageY;
    public float relativeDepth;
    public QuestPhoneXRVector3 palmForward;
    public QuestPhoneXRVector3 palmNormal;
}

[Serializable]
public sealed class QuestPhoneXRVector3
{
    public float x;
    public float y;
    public float z;
}
