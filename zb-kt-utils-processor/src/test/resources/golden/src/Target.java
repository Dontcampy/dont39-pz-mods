package t;

import java.util.List;

public class Target {
    public final String name;

    public Target(String name) {
        this.name = name;
    }

    public int compute(int x) {
        return x;
    }

    public String label(String suffix) {
        return name + suffix;
    }

    public void run(String s, long gap, int n) {
    }

    public static boolean check(String[] parts) {
        return parts.length > 0;
    }

    public static class Inner {
        public List<String> items() {
            return List.of();
        }
    }
}
