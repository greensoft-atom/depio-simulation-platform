using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// The least screen to play from (docs 08 §8): sign in as the device's guest, then a seat in the public arena or a
    /// duel's queue, its state, and a match found to accept. IMGUI, so it needs no prefab and no scene set up; the
    /// app's real screens are its designers' to make, on the same calls.
    /// </summary>
    public sealed class LobbyScreen : MonoBehaviour
    {
        [SerializeField] private BackendClient client;
        private bool _confirmingDeletion;

        private void OnGUI()
        {
            if (client.Match != null) return;
            float scale = Screen.height / 720f;
            GUI.matrix = Matrix4x4.Scale(new Vector3(scale, scale, 1f));
            GUILayout.BeginArea(new Rect(20f, 20f, 360f, 680f));
            if (client.Problem != null) GUILayout.Label(client.Problem);
            if (client.LastEnd != null) GUILayout.Label("The match ended: " + client.LastEnd);
            if (client.Session == null)
            {
                if (client.SigningIn) GUILayout.Label("Signing in...");
                else if (GUILayout.Button("Play")) client.SignIn();
            }
            else if (client.Lobby == null || client.Lobby.State != Backend.Client.Core.LobbyState.Ready)
            {
                GUILayout.Label("Lobby: " + (client.Lobby == null ? "none" : client.Lobby.State.ToString()));
            }
            else
            {
                var lobby = client.Lobby;
                if (lobby.Ready != null)
                {
                    GUILayout.Label("A match is found");
                    if (GUILayout.Button("Accept")) lobby.AcceptMatch();
                    if (GUILayout.Button("Decline")) lobby.DeclineMatch();
                }
                else if (lobby.QueueState == "none")
                {
                    if (GUILayout.Button("Play now")) client.PlayNow();
                    if (GUILayout.Button("A duel")) client.Queue("duel");
                }
                else
                {
                    GUILayout.Label("Queued: " + lobby.QueueState);
                    if (GUILayout.Button("Leave the queue")) lobby.LeaveQueue();
                }
                if (lobby.GrantRefusal != null) GUILayout.Label(lobby.GrantRefusal);
                if (lobby.QueueRefusal != null) GUILayout.Label(lobby.QueueRefusal);
            }
            // The player's right to have it erased (D-84), asked twice: there is no undo. And what becomes of a guest
            // nobody plays (D-87): this screen signs in as the device's guest alone.
            if (client.Session != null)
            {
                GUILayout.Label("A guest nobody plays for 180 days is erased, its progress with it.");
                if (client.Deleting) GUILayout.Label("Deleting the account...");
                else if (!_confirmingDeletion)
                {
                    if (GUILayout.Button("Delete my account")) _confirmingDeletion = true;
                }
                else
                {
                    GUILayout.Label("Delete this account for good? Its progress and what it bought cannot be brought back.");
                    if (GUILayout.Button("Delete it")) { client.DeleteAccount(); _confirmingDeletion = false; }
                    if (GUILayout.Button("Keep it")) _confirmingDeletion = false;
                }
            }
            GUILayout.EndArea();
        }
    }
}
