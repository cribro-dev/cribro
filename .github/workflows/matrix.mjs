// Generates the CI matrix for ci.yml: a few jobs per run, drawn so that pairs of axis values
// vary between pull requests. See https://github.com/vlsi/github-actions-random-matrix
//
// Preview the rows and the pair coverage locally:
//   npm ci --prefix .github/workflows
//   MATRIX_JOBS=5 node .github/workflows/matrix.mjs
//   MATRIX_JOBS=5 node .github/workflows/matrix.mjs --coverage
// Replay the rows of a CI run with the seed its step summary prints:
//   RNG_SEED=pr_12 node .github/workflows/matrix.mjs
import { createGitHubMatrixBuilder, setGitHubOutput } from '@vlsi/github-actions-random-matrix/github';

const { matrix } = createGitHubMatrixBuilder();

matrix.failOnUnsatisfiableFilters(true);

// The JDK that runs the tests. TestKit starts the Gradle builds under test on the same JDK, and PIT
// loads the plugin's classes into it, so 17 is the floor.
matrix.addAxis({
  name: 'java_version',
  title: x => 'Java ' + x,
  values: ['17', '21', '25'],
});

// The vendor reaches the tests through -PtestJavaVendor, which matches this substring of the
// java.vendor property of the JDK.
const javaVendors = {
  temurin: 'adoptium',
  zulu: 'azul',
  liberica: 'bellsoft',
  microsoft: 'microsoft',
  corretto: 'amazon',
};

matrix.addAxis({
  name: 'java_distribution',
  values: Object.keys(javaVendors),
});

matrix.addAxis({
  name: 'os',
  title: x => x.value.replace('-latest', ''),
  values: [
    // cribro runs git and reads paths and line endings, so every OS runs at least once; the
    // weights lean the other rows toward the cheapest runner.
    {value: 'ubuntu-latest', weight: 4},
    {value: 'windows-latest', weight: 1},
    {value: 'macos-latest', weight: 1},
  ],
});

// Makes every identity hash code the same, which exposes code that depends on the iteration order
// of a hash-based collection keyed by identity. The flag reaches the test JVM only, not the Gradle
// daemons that TestKit starts.
matrix.addAxis({
  name: 'hash',
  values: [
    {value: 'regular', title: '', weight: 42},
    {value: 'same', title: 'same hashcode', weight: 1},
  ],
});

// tr_TR exposes a case conversion that omits the locale, because I lowercases to a dotless ı there.
matrix.addAxis({
  name: 'locale',
  title: x => x.language + '_' + x.country,
  values: [
    {language: 'en', country: 'US'},
    {language: 'de', country: 'DE'},
    {language: 'ru', country: 'RU'},
    {language: 'tr', country: 'TR'},
  ],
});

matrix.setNamePattern(['java_version', 'java_distribution', 'hash', 'os', 'locale']);

const javaValues = matrix.axisByName.java_version.values;

const include = matrix.generateRows(Number(process.env.MATRIX_JOBS || 5), {
  require: [
    ...matrix.allAxisValues('os'),
    {java_version: javaValues[0]},
    {java_version: javaValues[javaValues.length - 1]},
    {hash: {value: 'same'}},
    {locale: {language: 'tr', country: 'TR'}},
  ],
});
if (include.length === 0) {
  throw new Error('Matrix list is empty');
}

include.sort((a, b) => a.name.localeCompare(b.name, undefined, {numeric: true}));

include.forEach(v => {
  const jvmArgs = [];
  if (v.hash.value === 'same') {
    jvmArgs.push('-XX:+UnlockExperimentalVMOptions', '-XX:hashCode=2');
  }
  // The Gradle daemons that TestKit starts inherit the locale of the test JVM, so the plugin code
  // in those builds runs under it too.
  jvmArgs.push(`-Duser.language=${v.locale.language}`, `-Duser.country=${v.locale.country}`);
  v.test_extra_jvm_args = jvmArgs.join(' ');
  v.java_vendor = javaVendors[v.java_distribution];
  v.os = v.os.value;
  delete v.hash;
  delete v.locale;
});

if (process.argv.includes('--coverage')) {
  const coverage = matrix.pairCoverageReport();
  console.log(`Pair coverage: ${coverage.covered}/${coverage.total} (${coverage.percentage}%), weighted ${coverage.weightPercentage}%`);
} else {
  console.log(include);
  setGitHubOutput('matrix', {include});
}
