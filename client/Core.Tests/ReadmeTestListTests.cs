using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;
using NUnit.Framework;
using NUnit.Framework.Interfaces;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// README.md's table of the tests held to the code (DOC-31), as the modules' READMEs are by the
    /// server's build (ReadmeTestListsTest, DOC-29): every class with tests has a row, named by its
    /// file, giving its [Test] methods and its parameterised ones; and no row names a file that is
    /// gone. The README is copied beside the tests by the project file.
    /// </summary>
    public class ReadmeTestListTests
    {
        /// <summary>A row of "The tests, by file": the file, its [Test] count, the parameterised column.</summary>
        private static readonly Regex Row = new Regex(@"^\| `(\w+)\.cs` \| (\d+) \| ([^|]*)\|", RegexOptions.Compiled);

        [Test]
        public void TheReadmeListsEveryTestFileWithItsCounts()
        {
            string path = Path.Combine(TestContext.CurrentContext.TestDirectory, "README.md");
            var listed = new Dictionary<string, (int tests, int parameterised)>();
            foreach (string line in File.ReadAllLines(path))
            {
                Match row = Row.Match(line);
                if (!row.Success) continue;
                string third = row.Groups[3].Value.Trim();
                Match leading = Regex.Match(third, @"^\d+");
                Assert.That(third.Length == 0 || leading.Success, Is.True,
                    row.Groups[1].Value + ".cs: the parameterised column starts with how many methods, or is empty");
                listed[row.Groups[1].Value] = (int.Parse(row.Groups[2].Value), third.Length == 0 ? 0 : int.Parse(leading.Value));
            }

            var actual = new Dictionary<string, (int tests, int parameterised)>();
            foreach (Type type in typeof(ReadmeTestListTests).Assembly.GetTypes())
            {
                MethodInfo[] methods = type.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);
                // As NUnit sorts them: [Test] builds one test from the method; [TestCase], [TestCaseSource] and
                // their kind build one a row, however many such attributes the method carries.
                int tests = methods.Count(m => m.GetCustomAttributes(true).OfType<ISimpleTestBuilder>().Any());
                int parameterised = methods.Count(m => m.GetCustomAttributes(true).OfType<ITestBuilder>().Any());
                if (tests + parameterised > 0) actual[type.Name] = (tests, parameterised);
            }

            Assert.That(listed.Keys, Is.EquivalentTo(actual.Keys), "a row for each file with tests, and a file for each row");
            foreach (var file in actual)
                Assert.That(listed[file.Key], Is.EqualTo(file.Value), file.Key + ".cs: ([Test] methods, parameterised methods)");
        }
    }
}
