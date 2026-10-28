import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;

/**
 * Cuts and hashes the SCTD blocks sctd-render-takeover checks, by running the feature's own cut and hash in the game's
 * Kahlua, writes each block here and prints the values for Takeover.BLOCKS. Not shipped. The reviewed copies of the
 * blocks the legacy migration added (CopyState, ApplyAuthoritativeTint, EmptyContainer, IsInvisVariant, RemoveWorn,
 * WearNoSync) are in the task 10-27-sctd-legacy-migration's research/sctd-blocks/.
 *
 * <pre>
 * cd <the game folder>  (Kahlua reads stdlib.lua from the working folder)
 * javac -cp projectzomboid.jar -d build BlockHashes.java
 * java -cp projectzomboid.jar;build BlockHashes &lt;Dont39AllInOne_SctdRenderTakeoverShared.lua&gt; &lt;SCTD's common folder&gt; &lt;this folder&gt;
 * </pre>
 */
public class BlockHashes {
  private static final String SCRIPT = String.join("\n",
    "for _, block in ipairs(Takeover.BLOCKS) do",
    "  local path = Takeover.FILES[block.file]",
    "  local text, first, last = Takeover.cut(readLines(path), block.header, path)",
    "  local a, b = Takeover.hash(text)",
    "  writeBlock(block.file .. '__' .. block.name .. '.lua', text)",
    "  local same = string.len(text) == block.length and a == block.hash[1] and b == block.hash[2]",
    "  print(block.name .. ' ' .. path .. ':' .. Takeover.digits(first) .. '-' .. Takeover.digits(last)",
    "    .. ' length = ' .. Takeover.digits(string.len(text)) .. ', hash = { ' .. Takeover.digits(a) .. ', '",
    "    .. Takeover.digits(b) .. ' }' .. (same and '' or '  CHANGED'))",
    "end",
    "local parts = {}",
    "for part in string.gmatch('a.b.c', '[^%.]+') do parts[#parts + 1] = part end",
    "print('kahlua: tostring(150000) = ' .. tostring(150000) .. ', digits(2147483646) = ' .. Takeover.digits(2147483646)",
    "  .. ', _G is ' .. type(_G) .. ', gmatch parts = ' .. table.concat(parts, ','))");

  public static void main(String[] args) throws Exception {
    for (long p : new long[] {2147483647L, 2147483629L}) {
      if (!BigInteger.valueOf(p).isProbablePrime(64)) throw new IllegalStateException(p + " is not prime");
    }
    Path module = Path.of(args[0]);
    Path sctd = Path.of(args[1]);
    Path out = Path.of(args[2]);
    J2SEPlatform platform = new J2SEPlatform();
    KahluaTable env = platform.newEnvironment();
    env.rawset("require", (JavaFunction) (frame, n) -> frame.push(platform.newTable()));
    // as getModFileReader reads: UTF-8, BufferedReader lines
    env.rawset("readLines", (JavaFunction) (frame, n) -> {
      KahluaTable lines = platform.newTable();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(
          new FileInputStream(sctd.resolve((String) frame.get(0)).toFile()), StandardCharsets.UTF_8))) {
        int i = 0;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          lines.rawset((double) ++i, line);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return frame.push(lines);
    });
    env.rawset("writeBlock", (JavaFunction) (frame, n) -> {
      try {
        Files.writeString(out.resolve((String) frame.get(0)), (String) frame.get(1), StandardCharsets.UTF_8);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return 0;
    });
    KahluaThread thread = new KahluaThread(System.out, platform, env);
    // the game's Kahlua expects the thread that runs it
    thread.debugOwnerThread = Thread.currentThread();
    LuaClosure moduleFn;
    try (Reader reader = new InputStreamReader(new FileInputStream(module.toFile()), StandardCharsets.UTF_8)) {
      moduleFn = LuaCompiler.loadis(reader, module.getFileName().toString(), env);
    }
    Object[] loaded = thread.pcall(moduleFn, new Object[0]);
    if (loaded[0] != Boolean.TRUE) throw new IllegalStateException("module: " + java.util.Arrays.toString(loaded));
    env.rawset("Takeover", loaded[1]);
    Object[] result = thread.pcall(LuaCompiler.loadstring(SCRIPT, "BlockHashes", env), new Object[0]);
    if (result[0] != Boolean.TRUE) throw new IllegalStateException("script: " + java.util.Arrays.toString(result));
  }
}
