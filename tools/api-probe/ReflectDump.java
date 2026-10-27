import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Dumps real IntelliJ Platform API signatures by reflection, so the plugin is written
 * against the actual 241 API. JBR ships no javap, so this stands in for it.
 *
 * Usage: java ReflectDump 'class#nameRegex' 'class2#regex2' ...
 *        java ReflectDump 'class'            (all public methods)
 */
public final class ReflectDump {

    public static void main(String[] args) {
        ClassLoader cl = ReflectDump.class.getClassLoader();
        for (String arg : args) {
            String className = arg;
            String filter = null;
            int hash = arg.indexOf('#');
            if (hash >= 0) {
                className = arg.substring(0, hash);
                filter = arg.substring(hash + 1);
            }
            System.out.println("=== " + className + (filter == null ? "" : "  /" + filter + "/") + " ===");
            Class<?> c;
            try {
                c = Class.forName(className, false, cl);
            } catch (Throwable t) {
                System.out.println("  !! cannot load: " + t);
                System.out.println();
                continue;
            }
            try {
                for (Constructor<?> k : c.getConstructors()) {
                    System.out.println("  <init>" + params(k.getParameterTypes()));
                }
            } catch (Throwable t) {
                System.out.println("  !! ctors: " + t);
            }
            try {
                for (Method m : c.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    if (filter != null && !m.getName().matches(filter)) continue;
                    String line;
                    try {
                        line = Modifier.toString(m.getModifiers() & Modifier.methodModifiers())
                                + " " + m.getReturnType().getSimpleName()
                                + " " + m.getName() + params(m.getParameterTypes());
                    } catch (Throwable t) {
                        line = m.getName() + "(?)  [" + t + "]";
                    }
                    System.out.println("  " + line);
                }
            } catch (Throwable t) {
                System.out.println("  !! methods: " + t);
            }
            System.out.println();
        }
    }

    private static String params(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            try {
                sb.append(ps[i].getSimpleName());
            } catch (Throwable t) {
                sb.append('?');
            }
        }
        return sb.append(')').toString();
    }
}