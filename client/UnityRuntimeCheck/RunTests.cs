namespace Backend.Client.UnityRuntimeCheck
{
    /// <summary>
    /// The core's tests on Unity 2019.4's own Mono (D-109): Core.Tests compiled with this by Unity's compiler, at C#
    /// 7.3 against .NET Framework 4.7.1's references, and run here by NUnitLite, in the process: Mono outside the
    /// editor never sees a child's output end, so no runner of its own processes. The files the tests read beside it.
    /// </summary>
    internal static class RunTests
    {
        private static int Main(string[] args) =>
            new NUnitLite.AutoRun(typeof(Backend.Client.Core.Tests.TrustRootTests).Assembly).Execute(args);
    }
}
