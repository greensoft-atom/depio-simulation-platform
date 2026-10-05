using System;
using System.Runtime.InteropServices;
using Backend.Client.Core;
using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// Where the device keeps the account's key (docs 08 §8): the keychain on iOS, the keystore on Android, and
    /// PlayerPrefs in the editor and a desktop build, which is not secure and says so.
    /// </summary>
    public static class SecureStores
    {
        public static ISecureStore ForThisDevice()
        {
#if UNITY_EDITOR
            return new PlayerPrefsStore();
#elif UNITY_IOS
            return new KeychainStore();
#elif UNITY_ANDROID
            return new KeystoreStore();
#else
            return new PlayerPrefsStore();
#endif
        }
    }

    /// <summary>PlayerPrefs: readable by anyone with the device's files. The editor's and a desktop build's, never a phone's.</summary>
    public sealed class PlayerPrefsStore : ISecureStore
    {
        public string Get(string key) => PlayerPrefs.HasKey(key) ? PlayerPrefs.GetString(key) : null;

        public void Set(string key, string value)
        {
            PlayerPrefs.SetString(key, value);
            PlayerPrefs.Save();
        }

        public void Delete(string key)
        {
            PlayerPrefs.DeleteKey(key);
            PlayerPrefs.Save();
        }
    }

#if UNITY_IOS
    /// <summary>The iOS keychain, by Plugins/iOS/BackendKeychain.mm: kept for this app, on this device, after its first unlock.</summary>
    public sealed class KeychainStore : ISecureStore
    {
        [DllImport("__Internal")] private static extern IntPtr BackendKeychainGet(string key);
        [DllImport("__Internal")] private static extern void BackendKeychainSet(string key, string value);
        [DllImport("__Internal")] private static extern void BackendKeychainDelete(string key);
        [DllImport("__Internal")] private static extern void BackendKeychainFree(IntPtr value);

        public string Get(string key)
        {
            IntPtr value = BackendKeychainGet(key);
            if (value == IntPtr.Zero) return null;
            try
            {
                return Marshal.PtrToStringUTF8(value);
            }
            finally
            {
                BackendKeychainFree(value);
            }
        }

        public void Set(string key, string value) => BackendKeychainSet(key, value);
        public void Delete(string key) => BackendKeychainDelete(key);
    }
#endif

#if UNITY_ANDROID
    /// <summary>The Android keystore, by Plugins/Android/SecureStore.java: each value sealed by a key that never leaves it.</summary>
    public sealed class KeystoreStore : ISecureStore
    {
        private readonly AndroidJavaObject _store;

        public KeystoreStore()
        {
            using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
            {
                _store = new AndroidJavaObject("com.backend.client.SecureStore", activity);
            }
        }

        /// <summary>Throws (AndroidJavaException) when the keystore cannot be read now: a sign-in fails and the key is kept.</summary>
        public string Get(string key) => _store.Call<string>("get", key);
        public void Set(string key, string value) => _store.Call("set", key, value);
        public void Delete(string key) => _store.Call("delete", key);
    }
#endif
}
