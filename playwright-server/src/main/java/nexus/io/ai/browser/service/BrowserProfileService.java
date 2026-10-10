package nexus.io.ai.browser.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.StandardOpenOption;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import nexus.io.ai.browser.model.BrowserProfile;
import nexus.io.ai.browser.model.CloneProfileRequest;
import nexus.io.ai.browser.model.CloneProfileResult;
import nexus.io.ai.browser.model.ProfileSelection;
import nexus.io.ai.browser.validation.ProfileValidator;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.validator.ParameterValidator;

/** Profile discovery and offline copy. No operation starts a browser. */
public class BrowserProfileService {
  public Path namedRoot() {
    String configured = ChromeBrowser.config("browser.profiles.dir");
    return (configured == null ? Path.of(EnvUtils.get("user.home", "."), ".config", "browseruse", "profiles", "named")
        : Path.of(configured)).toAbsolutePath().normalize();
  }

  public List<BrowserProfile> listProfiles() {
    List<BrowserProfile> result = new ArrayList<>();
    Path root = namedRoot();
    if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      try (Stream<Path> paths = Files.list(root)) {
        paths.sorted().filter(path -> path.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            .forEach(path -> result.add(describe(path.getFileName().toString(), path, "Default", "managed")));
      } catch (IOException e) {
        throw new IllegalStateException("Cannot list managed profiles", e);
      }
    }
    Path chrome = ChromeBrowser.userDataDir();
    if (chrome != null && Files.isDirectory(chrome)) {
      try (Stream<Path> paths = Files.list(chrome)) {
        paths.sorted().filter(path -> path.getFileName().toString().matches("Default|Profile [0-9]+"))
            .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            .forEach(path -> result.add(describe(path.getFileName().toString(), chrome, path.getFileName().toString(), "chrome")));
      } catch (IOException e) {
        throw new IllegalStateException("Cannot list Chrome profiles", e);
      }
    }
    Path configured = ChromeBrowser.cdpManagedProfileDir().toAbsolutePath().normalize();
    if (Files.isDirectory(configured) && result.stream().noneMatch(p -> p.userDataDir().equals(configured.toString()))) {
      result.add(describe(null, configured, "Default", "managed"));
    }
    return result;
  }

  private BrowserProfile describe(String name, Path root, String directory, String source) {
    String reason = null;
    try {
      requireDirectory(root);
      requireDirectory(root.resolve(directory));
      requireIdle(root);
    } catch (RuntimeException e) {
      reason = e.getMessage();
    }
    return new BrowserProfile(name, root.toAbsolutePath().normalize().toString(), directory, source, reason == null,
        reason == null && source.equals("chrome") ? "Clone recommended: Chrome may reject remote debugging of its default user data directory" : reason,
        displayName(root.resolve(directory), name == null ? root.getFileName().toString() : name));
  }

  public BrowserProfile resolve(ProfileSelection input) {
    if (input == null || !input.explicit()) {
      return null;
    }
    ParameterValidator.require(input.profile() == null || (input.userDataDir() == null && input.profileDirectory() == null),
        "Use profile OR userDataDir/profileDirectory, not both");
    Path root;
    String name;
    String directory;
    String source;
    if (input.profile() != null) {
      name = ProfileValidator.name(input.profile());
      root = namedRoot().resolve(name);
      directory = "Default";
      source = "managed";
    } else {
      name = null;
      root = Path.of(ProfileValidator.path(input.userDataDir(), "userDataDir")).toAbsolutePath().normalize();
      directory = ProfileValidator.directory(input.profileDirectory());
      source = "chrome";
    }
    ParameterValidator.require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Selected profile does not exist: " + root);
    ParameterValidator.require(Files.isDirectory(root.resolve(directory), LinkOption.NOFOLLOW_LINKS),
        "Selected profileDirectory does not exist: " + directory);
    requireDirectory(root);
    requireDirectory(root.resolve(directory));
    return new BrowserProfile(name, root.toString(), directory, source, true, null, name);
  }

  static void requireDirectory(Path path) {
    rejectLinks(path);
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Profile directory does not exist: " + path);
    }
  }

  static void rejectLinks(Path path) {
    for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
      if (Files.isSymbolicLink(current)) {
        throw new IllegalStateException("Symbolic links are not allowed in profile paths: " + current);
      }
      try {
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
            && Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
          throw new IllegalStateException("Special files and junctions are not allowed: " + current);
        }
      } catch (IOException e) {
        throw new IllegalStateException("Cannot inspect profile path: " + current, e);
      }
    }
  }

  static void requireIdle(Path root) {
    for (String lock : List.of("SingletonLock", "SingletonCookie", "SingletonSocket", "lockfile")) {
      if (Files.exists(root.resolve(lock), LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Profile has a lock marker; close Chrome and resolve stale locks first: " + root);
      }
    }
    if (ChromeBrowser.profileInUse(root)) {
      throw new IllegalStateException("Profile is in use; close its browser first: " + root);
    }
    Path defaultRoot = ChromeBrowser.userDataDir();
    if (EnvUtils.get("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      requireIdleWindows(root, defaultRoot);
      return;
    }
    if (defaultRoot != null && defaultRoot.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize())) {
      for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
        String command = process.info().command().orElse("").toLowerCase(Locale.ROOT);
        String args = process.info().commandLine().orElse("").toLowerCase(Locale.ROOT);
        if ((command.endsWith("chrome.exe") || command.endsWith("/chrome") || command.endsWith("/google chrome"))
            && !args.contains("--type=") && !args.contains("--user-data-dir=")) {
          throw new IllegalStateException("Chrome may be using the default profile; close Chrome before continuing");
        }
      }
    }
  }

  private static void requireIdleWindows(Path root, Path defaultRoot) {
    Process process = null;
    try {
      String script = "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; "
          + "ConvertTo-Json -Compress -InputObject @(Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | "
          + "Select-Object -ExpandProperty CommandLine)";
      process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
          Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      final Process query = process;
      CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
        try {
          return query.getInputStream().readNBytes(1024 * 1024);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IllegalStateException("Cannot verify Chrome process ownership; close Chrome before copying profiles");
      }
      String text = new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
      if (text.startsWith("\uFEFF")) {
        text = text.substring(1);
      }
      JSONArray commands = JSONArray.parseArray(text);
      if (commands == null) {
        throw new IllegalStateException("Cannot read Chrome process command lines");
      }
      boolean isDefault = defaultRoot != null && root.equals(defaultRoot.toAbsolutePath().normalize());
      for (int index = 0; index < commands.size(); index++) {
        if (commandUsesRoot(commands.getString(index), root, isDefault)) {
          throw new IllegalStateException("Profile is in use; close its Chrome window first: " + root);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Profile process check interrupted", e);
    } catch (IOException | ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Cannot verify Chrome process ownership", e);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  static boolean commandUsesRoot(String command, Path root, boolean defaultRoot) {
    if (command == null || command.isBlank()) {
      return true;
    }
    command = command.replaceAll("\"(--user-data-dir=)([^\"]+)\"", "$1\"$2\"");
    String lower = command.toLowerCase(Locale.ROOT);
    if (lower.contains("--type=")) {
      return false;
    }
    Matcher argument = Pattern.compile(
        "--user-data-dir(?:=| +)(?:\"([^\"]+)\"|([^ ]+))", Pattern.CASE_INSENSITIVE).matcher(command);
    if (!argument.find()) {
      return defaultRoot;
    }
    String directory = argument.group(1) == null ? argument.group(2) : argument.group(1);
    return Path.of(directory).toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize());
  }

  private static String displayName(Path directory, String fallback) {
    Path state = directory.getParent().resolve("Local State");
    try {
      if (Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) && Files.size(state) < 32 * 1024 * 1024) {
        JSONObject data = JSONObject.parseObject(Files.readString(state));
        JSONObject profile = data == null ? null : data.getJSONObject("profile");
        JSONObject cache = profile == null ? null : profile.getJSONObject("info_cache");
        JSONObject entry = cache == null ? null : cache.getJSONObject(directory.getFileName().toString());
        String name = entry == null ? null : entry.getString("name");
        if (name != null && !name.isBlank()) {
          return name;
        }
      }
    } catch (IOException | RuntimeException ignored) {
      // Local State is preferred, but Preferences can supply older profile names.
    }
    Path preferences = directory.resolve("Preferences");
    try {
      if (Files.isRegularFile(preferences, LinkOption.NOFOLLOW_LINKS) && Files.size(preferences) < 32 * 1024 * 1024) {
        JSONObject data = JSONObject.parseObject(Files.readString(preferences));
        JSONObject profile = data == null ? null : data.getJSONObject("profile");
        String name = profile == null ? null : profile.getString("name");
        if (name != null && !name.isBlank()) {
          return name;
        }
      }
    } catch (IOException | RuntimeException ignored) {
      // Display metadata is optional. Never return account details from preferences.
    }
    return fallback;
  }

  public CloneProfileResult cloneProfile(CloneProfileRequest input) {
    requireNotInterrupted();
    ParameterValidator.require(input != null, "Clone request is required");
    String name = ProfileValidator.name(input.name());
    Path source = Path.of(ProfileValidator.path(input.sourceUserDataDir(), "sourceUserDataDir")).toAbsolutePath().normalize();
    String directory = ProfileValidator.directory(input.sourceProfileDirectory());
    Path parent = namedRoot();
    Path destination = parent.resolve(name);
    requireDirectory(source);
    requireDirectory(source.resolve(directory));
    rejectLinks(parent);
    if (destination.startsWith(source) || source.startsWith(destination)) {
      throw new IllegalStateException("Source and destination must not overlap");
    }
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Destination already exists: " + destination);
    }
    requireIdle(source);
    long[] totals = new long[2];
    Path staging = null;
    try {
      Files.createDirectories(parent);
      staging = Files.createTempDirectory(parent, ".clone-");
      Path target = staging.resolve("Default");
      copyTree(source.resolve(directory), target, totals);
      Path state = source.resolve("Local State");
      if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
        copyFile(state, staging.resolve("Local State"), totals);
      }
      renameProfile(staging, name, directory);
      requireIdle(source);
      requireNotInterrupted();
      Files.move(staging, destination);
      staging = null;
      return new CloneProfileResult(new BrowserProfile(name, destination.toString(), "Default", "managed", true, null, name), totals[0], totals[1],
          "Offline copy only. Encrypted cookies and saved credentials may not work; sign in again if needed. Cache directories and transient LOCK files are excluded.");
    } catch (IOException e) {
      throw new IllegalStateException("Profile copy failed; destination was not replaced", e);
    } finally {
      if (staging != null) {
        cleanup(staging);
      }
    }
  }

  private static final Set<String> CACHE_DIRECTORIES = Set.of("Cache", "Code Cache", "GPUCache", "DawnCache",
      "DawnGraphiteCache", "DawnWebGPUCache", "ShaderCache", "GrShaderCache", "GraphiteDawnCache", "CacheStorage");

  private static void copyTree(Path source, Path target, long[] totals) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
        requireNotInterrupted();
        rejectLinks(dir);
        if (!dir.equals(source) && CACHE_DIRECTORIES.contains(dir.getFileName().toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Files.createDirectory(target.resolve(source.relativize(dir)));
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        requireNotInterrupted();
        rejectLinks(file);
        if (!file.getFileName().toString().equals("LOCK")) {
          copyFile(file, target.resolve(source.relativize(file)), totals);
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void copyFile(Path source, Path target, long[] totals) throws IOException {
    requireNotInterrupted();
    rejectLinks(source);
    BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()) {
      throw new IOException("Only regular profile files can be copied");
    }
    try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
        OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
      byte[] buffer = new byte[1024 * 1024];
      int count;
      while ((count = input.read(buffer)) != -1) {
        requireNotInterrupted();
        output.write(buffer, 0, count);
      }
    }
    requireNotInterrupted();
    BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
      throw new IOException("Source changed during copy; close its browser first");
    }
    totals[0]++;
    totals[1] += before.size();
  }

  private static void renameProfile(Path root, String name, String sourceDirectory) throws IOException {
    Path preferences = root.resolve("Default/Preferences");
    if (Files.isRegularFile(preferences)) {
      JSONObject json = JSONObject.parseObject(Files.readString(preferences));
      JSONObject profile = json.getJSONObject("profile");
      if (profile == null) {
        profile = new JSONObject();
        json.put("profile", profile);
      }
      profile.put("name", name);
      profile.put("is_using_default_name", false);
      Files.writeString(preferences, json.toJSONString());
    }
    Path state = root.resolve("Local State");
    if (Files.isRegularFile(state)) {
      JSONObject json = JSONObject.parseObject(Files.readString(state));
      JSONObject profile = json.getJSONObject("profile");
      if (profile == null) {
        profile = new JSONObject();
        json.put("profile", profile);
      }
      JSONObject cache = profile.getJSONObject("info_cache");
      JSONObject entry = cache == null ? null : cache.getJSONObject(sourceDirectory);
      if (entry == null) {
        entry = new JSONObject();
      }
      entry.put("name", name);
      entry.put("is_using_default_name", false);
      JSONObject newCache = new JSONObject();
      newCache.put("Default", entry);
      profile.put("info_cache", newCache);
      profile.put("last_used", "Default");
      profile.put("last_active_profiles", List.of("Default"));
      Files.writeString(state, json.toJSONString());
    }
  }

  private static void requireNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("Profile copy cancelled; no destination was published");
    }
  }

  private static void cleanup(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // A failed copy remains hidden and is never listed as a selectable profile.
    }
  }
}
