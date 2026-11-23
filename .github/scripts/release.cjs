// Release refs are published only after every platform has tested the same candidate commit.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");

function check(condition, message) {
  if (!condition) throw new Error(message);
}
function cleanupTemporaryDirectory(directory, remove = fs.rmSync, report = console.error) {
  try {
    remove(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  } catch (error) {
    report(`ERROR: Could not clean temporary directory: ${directory}`, error);
  }
}
function version(text) {
  const lines = text.match(/^[ \t]*projectVersion(?:[ \t]*[=:]|[ \t]+).*$/gm) || [];
  check(lines.length === 1, "Expected exactly one projectVersion property.");
  const match = /^projectVersion=(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-SNAPSHOT)?\r?$/.exec(
    lines[0],
  );
  check(match, "Expected projectVersion=A.B.C[-SNAPSHOT].");
  const [major, minor, patch] = match.slice(1, 4).map(Number);
  check(
    [major, minor, patch].every((n) => n < 65535),
    "Version components must be below 65535 (native package limit).",
  );
  const number = `${major}.${minor}.${patch}`;
  return {
    major,
    minor,
    patch,
    snapshot: Boolean(match[4]),
    number,
    value: number + (match[4] || ""),
  };
}
function replaceVersion(text, value) {
  version(text);
  version(`projectVersion=${value}`);
  return text.replace(/^projectVersion=.*$/m, `projectVersion=${value}`);
}
function releaseVersion(branch, text) {
  const v = version(text);
  check(
    branch === `release/${v.major}.${v.minor}.x` && v.snapshot,
    "Release requires release/A.B.x and its matching A.B.C-SNAPSHOT version.",
  );
  return v;
}
function digest(file) {
  const hash = crypto.createHash("sha256");
  const fd = fs.openSync(file, "r"),
    buffer = Buffer.alloc(1024 * 1024);
  try {
    let count;
    while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)))
      hash.update(buffer.subarray(0, count));
    return hash.digest("hex");
  } finally {
    fs.closeSync(fd);
  }
}
function assetNames(v) {
  version(`projectVersion=${v}`);
  return ["windows-x64", "linux-x64"]
    .flatMap((platform) =>
      ["VoxelBrew", "VoxelBrewCLI"].map((app) => `${app}-${v}-${platform}.zip`),
    )
    .concat(
      `VoxelBrewCLI-${v}-macos-x64.zip`,
      `VoxelBrew-${v}-windows-x64-Setup.exe`,
      ...(v === "1.0.0" ? [] : [`VoxelBrew-${v}-linux-x64.deb`, `VoxelBrew-${v}-macos-x64.dmg`]),
    )
    .sort();
}
function platformAsset(name, platform) {
  return name.includes(`-${platform}.`) || name.includes(`-${platform}-Setup.`);
}
function manifest(directory, m) {
  const expected = assetNames(m.version);
  const actual = fs
    .readdirSync(directory)
    .filter((name) => /\.(zip|exe|dmg|deb)$/.test(name))
    .sort();
  check(
    JSON.stringify(actual) === JSON.stringify(expected),
    "Missing or unexpected platform archives.",
  );
  const reports = ["windows-x64", "linux-x64", "macos-x64"].map(
    (platform) => `platform-${platform}.json`,
  );
  for (const name of reports) {
    const report = JSON.parse(fs.readFileSync(path.join(directory, name)));
    const platform = name.slice("platform-".length, -".json".length);
    const names = expected.filter((asset) => platformAsset(asset, platform));
    check(
      report.release === m.release &&
        report.platform === platform &&
        JSON.stringify(Object.keys(report.archives).sort()) === JSON.stringify(names),
      "Platform provenance does not match candidate.",
    );
    for (const file of names)
      check(
        digest(path.join(directory, file)) === report.archives[file],
        `Archive checksum mismatch: ${file}`,
      );
  }
  fs.writeFileSync(path.join(directory, "release.json"), JSON.stringify(m, null, 2) + "\n");
  const files = [...expected, ...reports, "release.json"];
  fs.writeFileSync(
    path.join(directory, "SHA256SUMS"),
    files.map((f) => `${digest(path.join(directory, f))}  ${f}\n`).join(""),
  );
  return [...files, "SHA256SUMS"];
}
function collect(release, plan, directory) {
  const m = JSON.parse(fs.readFileSync(path.join(plan, "release.json")));
  check(release.git("rev-parse", "HEAD") === m.release, "Archive checkout differs from candidate.");
  release.clean();
  check(process.arch === "x64", "Only x64 release targets are qualified.");
  const platform = { win32: "windows-x64", linux: "linux-x64", darwin: "macos-x64" }[
    process.platform
  ];
  check(platform, "Unqualified release platform.");
  const archives = {};
  fs.mkdirSync(directory, { recursive: true });
  for (const name of assetNames(m.version).filter((name) => platformAsset(name, platform))) {
    const module = name.startsWith("VoxelBrewCLI-") ? "cli" : "gui";
    fs.copyFileSync(
      path.join(release.cwd, module, "build/distributions", name),
      path.join(directory, name),
    );
    archives[name] = digest(path.join(directory, name));
  }
  fs.writeFileSync(
    path.join(directory, `platform-${platform}.json`),
    JSON.stringify({ platform, release: m.release, archives }, null, 2) + "\n",
  );
}
class Release {
  constructor(cwd = process.cwd(), repo = process.env.GITHUB_REPOSITORY) {
    this.cwd = cwd;
    this.repo = repo;
  }
  run(command, args, options = {}) {
    return (
      execFileSync(command, args, {
        cwd: this.cwd,
        encoding: "utf8",
        stdio: ["pipe", "pipe", "pipe"],
        ...options,
      }) || ""
    ).trim();
  }
  git(...args) {
    return this.run("git", ["-c", `safe.directory=${this.cwd.replaceAll("\\", "/")}`, ...args]);
  }
  read() {
    return fs.readFileSync(path.join(this.cwd, "gradle.properties"), "utf8");
  }
  api(endpoint, body) {
    return JSON.parse(
      this.run(
        "gh",
        ["api", `repos/${this.repo}/${endpoint}`, ...(body ? ["--input", "-"] : [])],
        body ? { input: JSON.stringify(body) } : {},
      ) || "null",
    );
  }
  releases() {
    return JSON.parse(
      this.run("gh", ["api", "--paginate", "--slurp", `repos/${this.repo}/releases?per_page=100`]),
    ).flat();
  }
  clean() {
    check(
      !this.git("status", "--porcelain", "--untracked-files=no"),
      "Tracked working tree must be clean.",
    );
  }
  configure() {
    this.git("config", "user.name", "github-actions[bot]");
    this.git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com");
  }
  remote(ref) {
    return this.git("ls-remote", "--refs", "origin", ref).split(/\s/)[0];
  }
  fetch(branch) {
    this.git("fetch", "origin", `+refs/heads/${branch}:refs/remotes/origin/${branch}`);
    return this.git("rev-parse", `refs/remotes/origin/${branch}`);
  }
  commit(value, message) {
    fs.writeFileSync(path.join(this.cwd, "gradle.properties"), replaceVersion(this.read(), value));
    this.git("add", "gradle.properties");
    this.git("commit", "-m", message);
    return this.git("rev-parse", "HEAD");
  }
  push(updates) {
    for (const u of updates) {
      check(this.remote(u.ref) === u.expected, `Concurrent change to ${u.ref}; nothing published.`);
      if (u.expected && u.ref.startsWith("refs/heads/"))
        this.git("merge-base", "--is-ancestor", u.expected, u.commit);
    }
    this.git(
      "push",
      "--atomic",
      ...updates.map((u) => `--force-with-lease=${u.ref}:${u.expected}`),
      "origin",
      ...updates.map((u) => `${u.commit}:${u.ref}`),
    );
  }
  prepare(ref, selected) {
    this.clean();
    check(ref === "refs/heads/master", "Prepare must be dispatched on master.");
    const base = this.fetch("master");
    check(
      base === selected,
      "Master advanced after dispatch; run prepare again on the new revision.",
    );
    this.git("checkout", "--detach", base);
    const v = version(this.read());
    check(v.snapshot, "Master must have a SNAPSHOT version.");
    const branch = `release/${v.major}.${v.minor}.x`;
    check(
      !this.remote(`refs/heads/${branch}`),
      "Release branch already exists; use it for patch releases.",
    );
    const nextVersion = `${v.major}.${v.minor + 1}.0-SNAPSHOT`;
    const next = this.commit(nextVersion, `Begin ${nextVersion} development`);
    this.push([
      { ref: `refs/heads/${branch}`, expected: "", commit: base },
      { ref: "refs/heads/master", expected: base, commit: next },
    ]);
    return { branch, base, next };
  }
  candidate(branch, selected, directory, rehearsal = false) {
    this.clean();
    check(/^[a-f0-9]{40}$/.test(selected), "Expected a full source SHA.");
    check(this.fetch(branch) === selected, "Selected branch advanced; dispatch the release again.");
    this.git("checkout", "--detach", selected);
    const source = version(this.read());
    const releaseBranch =
      rehearsal && branch === "master" ? `release/${source.major}.${source.minor}.x` : branch;
    const v = releaseVersion(releaseBranch, this.read()),
      tag = `v${v.number}`;
    check(
      !this.remote(`refs/tags/${tag}`),
      "Release tag already exists; never overwrite a release.",
    );
    check(
      !this.releases().some((r) => r.tag_name === tag),
      "Release or draft already exists; follow the recovery instructions.",
    );
    const release = this.commit(v.number, `Release ${v.number}`);
    this.git("tag", "-a", tag, "-m", `Release ${v.number}`, release);
    const nextVersion = `${v.major}.${v.minor}.${v.patch + 1}-SNAPSHOT`;
    const next = this.commit(nextVersion, `Begin ${nextVersion} development`);
    this.git("branch", "release-candidate-next", next);
    const m = {
      schema: 1,
      repository: this.repo,
      branch: releaseBranch,
      sourceBranch: branch,
      rehearsal,
      base: selected,
      release,
      next,
      tagObject: this.git("rev-parse", `refs/tags/${tag}`),
      tag,
      version: v.number,
      nextVersion,
    };
    fs.mkdirSync(directory, { recursive: true });
    this.git(
      "bundle",
      "create",
      path.join(directory, "release.bundle"),
      `^${selected}`,
      "refs/heads/release-candidate-next",
      `refs/tags/${tag}`,
    );
    fs.writeFileSync(path.join(directory, "release.json"), JSON.stringify(m, null, 2) + "\n");
    return m;
  }
  load(directory, selected, sourceBranch) {
    this.clean();
    const m = JSON.parse(fs.readFileSync(path.join(directory, "release.json")));
    check(
      m.schema === 1 &&
        m.repository === this.repo &&
        m.base === selected &&
        m.sourceBranch === sourceBranch,
      "Candidate does not belong to this repository, source branch and workflow SHA.",
    );
    for (const key of ["base", "release", "next", "tagObject"])
      check(/^[a-f0-9]{40}$/.test(m[key]), `Invalid ${key} SHA.`);
    const v = releaseVersion(m.branch, this.git("show", `${m.base}:gradle.properties`));
    check(
      m.tag === `v${v.number}` &&
        m.version === v.number &&
        m.nextVersion === `${v.major}.${v.minor}.${v.patch + 1}-SNAPSHOT`,
      "Invalid candidate version.",
    );
    check(
      m.sourceBranch === m.branch || (m.rehearsal === true && m.sourceBranch === "master"),
      "Invalid release source branch.",
    );
    this.git("bundle", "verify", path.join(directory, "release.bundle"));
    this.git(
      "fetch",
      path.join(directory, "release.bundle"),
      "refs/heads/release-candidate-next:refs/remotes/candidate/next",
      `refs/tags/${m.tag}:refs/tags/${m.tag}`,
    );
    check(
      this.git("rev-parse", "refs/remotes/candidate/next") === m.next &&
        this.git("rev-parse", `refs/tags/${m.tag}`) === m.tagObject &&
        this.git("rev-parse", `${m.tag}^{commit}`) === m.release,
      "Candidate refs differ from metadata.",
    );
    for (const [parent, child, value] of [
      [m.base, m.release, m.version],
      [m.release, m.next, m.nextVersion],
    ]) {
      check(
        this.git("rev-list", "--parents", "-n", "1", child) === `${child} ${parent}`,
        "Unexpected candidate history.",
      );
      check(
        this.git("diff", "--name-only", parent, child) === "gradle.properties",
        "Candidate changed more than the version.",
      );
      check(
        this.git("show", `${child}:gradle.properties`) ===
          replaceVersion(this.git("show", `${parent}:gradle.properties`), value),
        "Unexpected version-file change.",
      );
    }
    this.git("checkout", "--detach", m.release);
    return m;
  }
  publish(m, directory) {
    check(!m.rehearsal && m.sourceBranch === m.branch, "A rehearsal cannot publish.");
    check(
      this.git("rev-parse", "HEAD") === m.release,
      "Publish requires the tested candidate checkout.",
    );
    this.clean();
    check(
      this.remote(`refs/heads/${m.branch}`) === m.base,
      "Release branch advanced during the build; discard this candidate.",
    );
    check(
      !this.remote(`refs/tags/${m.tag}`) && !this.releases().some((r) => r.tag_name === m.tag),
      "Tag or release already exists; follow recovery instructions.",
    );
    const files = manifest(directory, m);
    const notes =
      `VoxelBrew ${m.version}\n\nSource: ${m.release} (base ${m.base}).\n\n` +
      "Extract the complete archive to use the bundled Java 21 runtime, OCCT/JNI, native launchers, PrusaSlicer 2.7.2 and OrcaSlicer 2.5.0. GUI and CLI packages target Windows x64 and Ubuntu 24.04 x64. The macOS 15 Intel package contains the CLI. See THIRD_PARTY_NOTICES.md at this tag for the slicer licenses and corresponding source.\n\n" +
      "Verify downloads against SHA256SUMS. CI evidence expires after three days; these release assets persist.\n\n" +
      this.git("log", "-20", "--format=- %s (%h)", m.base) +
      "\n";
    const notesFile = path.join(directory, "release-notes.md");
    fs.writeFileSync(notesFile, notes);
    this.run("gh", [
      "release",
      "create",
      m.tag,
      "--repo",
      this.repo,
      "--draft",
      "--target",
      m.base,
      "--title",
      `VoxelBrew ${m.version}`,
      "--notes-file",
      notesFile,
    ]);
    this.run("gh", [
      "release",
      "upload",
      m.tag,
      "--repo",
      this.repo,
      ...files.map((file) => path.join(directory, file)),
    ]);
    this.push([
      { ref: `refs/heads/${m.branch}`, expected: m.base, commit: m.next },
      { ref: `refs/tags/${m.tag}`, expected: "", commit: m.tagObject },
    ]);
    this.run("gh", [
      "release",
      "edit",
      m.tag,
      "--repo",
      this.repo,
      "--draft=false",
      "--latest=false",
    ]);
  }
  mergeBack(branch, source) {
    check(/^release\/(0|[1-9]\d*)\.(0|[1-9]\d*)\.x$/.test(branch), "Expected release/A.B.x.");
    check(/^[a-f0-9]{40}$/.test(source), "Expected full release commit SHA.");
    this.clean();
    const releaseHead = this.fetch(branch),
      target = this.fetch("master");
    this.git("merge-base", "--is-ancestor", source, releaseHead);
    try {
      this.git("merge-base", "--is-ancestor", source, target);
      return false;
    } catch {
      /* New release commits. */
    }
    // Rebase-merging a PR preserves its message but drops the original release ancestry.
    const marker = "VoxelBrew-release-source: ";
    const history = this.git("log", "--format=%B", "--fixed-strings", "--grep", marker, target);
    let mergeTarget = target;
    for (const match of history.matchAll(/^VoxelBrew-release-source: ([a-f0-9]{40})$/gm)) {
      try {
        this.git("merge-base", "--is-ancestor", match[1], source);
      } catch {
        continue;
      }
      mergeTarget = this.git(
        "commit-tree",
        `${target}^{tree}`,
        "-p",
        target,
        "-p",
        match[1],
        "-m",
        "Restore release merge base",
      );
      break;
    }
    if (this.git("merge-base", mergeTarget, source) === source) return false;
    this.git("checkout", "--detach", mergeTarget);
    const current = version(this.read()).value;
    const base = this.git("merge-base", mergeTarget, source);
    // Normalize only projectVersion before a real three-way merge of the property file.
    const directory = fs.mkdtempSync(path.join(require("node:os").tmpdir(), "voxelbrew-merge-"));
    let properties;
    try {
      for (const [name, ref] of [
        ["ours", target],
        ["base", base],
        ["theirs", source],
      ])
        fs.writeFileSync(
          path.join(directory, name),
          replaceVersion(this.git("show", `${ref}:gradle.properties`), current) + "\n",
        );
      properties =
        this.git(
          "merge-file",
          "-p",
          ...["ours", "base", "theirs"].map((name) => path.join(directory, name)),
        ) + "\n";
    } finally {
      cleanupTemporaryDirectory(directory);
    }
    try {
      try {
        this.git("merge", "--no-commit", "--no-ff", source);
      } catch {
        /* Inspect unresolved conflicts below. */
      }
      this.git("rev-parse", "--verify", "MERGE_HEAD");
      fs.writeFileSync(path.join(this.cwd, "gradle.properties"), properties);
      this.git("add", "gradle.properties");
      check(
        !this.git("diff", "--name-only", "--diff-filter=U"),
        "Merge conflicts require manual resolution before opening a merge-back PR.",
      );
      const tree = this.git("write-tree");
      this.git("merge", "--abort");
      if (tree === this.git("rev-parse", `${target}^{tree}`)) return false;
      const head = `merge-back/${branch.slice("release/".length)}/${source}`;
      const ref = `refs/heads/${head}`;
      const existing = this.remote(ref);
      if (!existing) {
        const commit = this.git(
          "commit-tree",
          tree,
          "-p",
          target,
          "-m",
          `fix: merge ${branch} back into master\n\n${marker}${source}`,
        );
        this.push([{ ref, expected: "", commit }]);
      }
      return head;
    } catch (error) {
      try {
        this.git("merge", "--abort");
      } catch {
        /* No merge is active if preparing the PR branch failed after cleanup. */
      }
      throw error;
    }
  }
}
function main() {
  const release = new Release();
  release.configure();
  const [mode, directory] = process.argv.slice(2);
  const branch = process.env.RELEASE_BRANCH || process.env.GITHUB_REF_NAME;
  const selected = process.env.GITHUB_SHA;
  if (mode === "prepare") {
    const m = release.prepare(process.env.GITHUB_REF, selected);
    for (const ref of ["master", m.branch])
      release.api("actions/workflows/check.yml/dispatches", { ref });
  } else if (mode === "candidate") {
    const m = release.candidate(
      branch,
      selected,
      path.resolve(directory),
      process.env.RELEASE_DRY_RUN === "true",
    );
    if (process.env.GITHUB_OUTPUT)
      fs.appendFileSync(process.env.GITHUB_OUTPUT, `version=${m.version}\nrelease=${m.release}\n`);
  } else if (mode === "checkout") {
    const m = release.load(path.resolve(directory), selected, branch);
    if (process.env.GITHUB_OUTPUT)
      fs.appendFileSync(
        process.env.GITHUB_OUTPUT,
        `linux_gui_installer=${assetNames(m.version).some((name) => name.endsWith(".deb"))}\n` +
          `mac_gui_installer=${assetNames(m.version).some((name) => name.endsWith(".dmg"))}\n`,
      );
  } else if (mode === "collect") {
    collect(release, path.resolve(directory), path.resolve(process.argv[4]));
  } else if (mode === "manifest") {
    manifest(
      path.resolve(process.argv[4]),
      JSON.parse(fs.readFileSync(path.join(directory, "release.json"))),
    );
  } else if (mode === "publish") {
    const m = release.load(path.resolve(directory), selected, branch);
    release.publish(m, path.resolve(process.argv[4]));
  } else if (mode === "merge-back") {
    const source = process.env.RELEASE_SOURCE || release.fetch(branch);
    const head = release.mergeBack(branch, source);
    if (head && process.env.GITHUB_OUTPUT)
      fs.appendFileSync(process.env.GITHUB_OUTPUT, `head=${head}\n`);
    if (!head) console.log(`Master already contains the changes from ${branch} at ${source}.`);
  } else if (mode === "dispatch-merge") {
    const m = JSON.parse(fs.readFileSync(path.join(directory, "release.json")));
    check(
      m.repository === release.repo &&
        m.sourceBranch === branch &&
        m.base === selected &&
        !m.rehearsal,
      "Invalid merge-back candidate.",
    );
    if (release.remote(`refs/tags/${m.tag}`) === m.tagObject)
      release.api("actions/workflows/release-merge-back.yml/dispatches", {
        ref: m.branch,
        inputs: { source: m.next },
      });
  } else
    throw new Error(
      "Expected prepare, candidate, checkout, publish, dispatch-merge or merge-back.",
    );
}
module.exports = {
  cleanupTemporaryDirectory,
  Release,
  version,
  replaceVersion,
  releaseVersion,
  assetNames,
  manifest,
  digest,
  collect,
};
if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(error.message, String(error.stderr || ""));
    process.exitCode = 1;
  }
}
