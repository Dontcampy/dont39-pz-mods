# Design: keep the case of `x_extends` paths

## Boundaries

- New feature module `anim-extends-case`, package `com.dont39.pz.animextendscase`, built like `missing-anim-folders`
  (`pz-feature`, no target mods, no Lua). Registered in `settings.gradle.kts` and the All-in-One feature list;
  `init()` called from `AllInOneMain`.
- Facade `AnimExtendsCase : Feature("anim-extends-case", "Dont39AllInOne")`, `problem() = null`, like
  `MissingAnimFolders`.
- One hook object on `zombie.ZomboidFileSystem`.

## Mechanism

`@ModifyReturnValue(method = ["resolveRelativePath"])` on `ZomboidFileSystem`, handler
`(original: String, @Self fs: ZomboidFileSystem, parent: String, name: String): String`:

1. If `fs.resolveFileOrGUID(original) != original`, the path is in `activeFileMap`: return `original`.
2. If `original` names an existing file: return `original`.
3. Join as the game does, `Paths.get(parent).parent.resolve(name)`; if that names an existing file, return it as a
  string.
4. Otherwise return `original`, so the game logs the missing file as before.

Paths are checked as given, relative to the working folder, the same way `FileInputStream` opens them.

Why `resolveRelativePath` and not `parseXml`: it is the single place the case is lost, it is public (no widening), and
its only callers are `PZXmlUtil.parseXml` (`x_extends`) and `PZXmlUtil.includeAnotherFile`, both of which want the real
file. A `parent` whose parent folder is null (bare file name) cannot occur from `parseXml`; if it does, the handler lets
the exception surface rather than guessing.

## Compatibility

- Windows and the usual client layout: step 1 or 2 always returns `original`; behavior unchanged.
- Nested `x_extends` chains: the fixed path is the next `parent`, so later links keep the case too.
- If a game update renames or changes `resolveRelativePath`, the hook processor or Zabric's gate fails and the feature
  reports `disabled` with the reason (error-handling spec); no fallback.

## Rollback

Remove the module from the feature list and `AllInOneMain`; nothing is persisted.
